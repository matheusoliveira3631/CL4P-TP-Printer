package com.matheusoliveira.cl4ptpprinter.printer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import android.util.Log
import com.matheusoliveira.cl4ptpprinter.R
import com.matheusoliveira.cl4ptpprinter.model.PrintJob
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class PrintExecutor(
    private val context: Context,
    private val printerManager: SunmiPrinterManager,
    private val formatter: PrintFormatter,
    private val statusListener: (String) -> Unit,
    private val jobStatusListener: (PrintJob, String, String, String?) -> Unit = { _, _, _, _ -> }
) {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val pendingJobs = AtomicInteger(0)

    fun enqueue(printJob: PrintJob) {
        val queuedCount = pendingJobs.incrementAndGet()
        notifyStatus("Queued print job ${printJob.jobId}. Pending jobs: $queuedCount")
        notifyJobStatus(printJob, "queued", "Print job queued.", null)

        worker.execute {
            try {
                notifyStatus("Printing job ${printJob.jobId}...")
                notifyJobStatus(printJob, "printing", "Print job started.", null)
                execute(printJob)
                notifyStatus("Finished print job ${printJob.jobId}.")
                notifyJobStatus(printJob, "completed", "Print job completed.", null)
            } catch (error: Exception) {
                Log.e(TAG, "Failed to print job ${printJob.jobId}", error)
                notifyStatus("Failed to print job ${printJob.jobId}: ${error.message}")
                notifyJobStatus(printJob, "failed", "Print job failed.", error.message)
            } finally {
                pendingJobs.decrementAndGet()
            }
        }
    }

    fun release() {
        worker.shutdown()
    }

    private fun execute(printJob: PrintJob) {
        if (!printerManager.isReady()) {
            throw IllegalStateException("Printer is not ready.")
        }

        val logoBitmap = loadHeaderBitmap(printJob) ?: throw IllegalStateException("Header image could not be loaded.")
        val formattedBody = formatter.formatBody(printJob.text)

        if (!printerManager.printSeparator(PrintLayoutConfig.SEPARATOR)) {
            throw IllegalStateException("Failed to print top separator.")
        }
        if (!printerManager.printBitmapCentered(logoBitmap)) {
            throw IllegalStateException("Failed to print header logo.")
        }
        if (!printerManager.printText("$formattedBody\n")) {
            throw IllegalStateException("Failed to print message body.")
        }
        if (!printerManager.printText("${PrintLayoutConfig.SEPARATOR}\n${"\n".repeat(PrintLayoutConfig.RECEIPT_END_FEED_LINES)}")) {
            throw IllegalStateException("Failed to print bottom separator.")
        }
    }

    private fun loadHeaderBitmap(printJob: PrintJob): Bitmap? {
        val customBitmap = loadCustomBitmap(printJob)
        val originalBitmap = customBitmap ?: BitmapFactory.decodeResource(context.resources, R.drawable.clp_logo) ?: return null
        val imageMode = if (customBitmap != null) {
            normalizeImageMode(printJob.imageMode, DEFAULT_CUSTOM_IMAGE_MODE)
        } else {
            IMAGE_MODE_LOGO
        }
        val scaledBitmap = if (originalBitmap.width <= PrintLayoutConfig.LOGO_MAX_WIDTH_PX) {
            originalBitmap
        } else {
            val scaleFactor = PrintLayoutConfig.LOGO_MAX_WIDTH_PX.toFloat() / originalBitmap.width.toFloat()
            val scaledHeight = (originalBitmap.height * scaleFactor).roundToInt()
            Bitmap.createScaledBitmap(
                originalBitmap,
                PrintLayoutConfig.LOGO_MAX_WIDTH_PX,
                scaledHeight,
                true
            )
        }

        return prepareThermalBitmap(scaledBitmap, imageMode)
    }

    private fun loadCustomBitmap(printJob: PrintJob): Bitmap? {
        if (printJob.imageBase64.isBlank()) {
            return null
        }

        return try {
            val imageBytes = Base64.decode(printJob.imageBase64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: throw IllegalArgumentException("Custom image could not be decoded.")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to decode custom image for job ${printJob.jobId}", error)
            throw IllegalStateException("Custom image could not be decoded: ${error.message}")
        }
    }

    private fun normalizeImageMode(value: String, fallback: String): String {
        return when (value.trim().lowercase()) {
            IMAGE_MODE_LOGO -> IMAGE_MODE_LOGO
            IMAGE_MODE_PHOTO -> IMAGE_MODE_PHOTO
            else -> fallback
        }
    }

    private fun prepareThermalBitmap(source: Bitmap, imageMode: String): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)

        return if (imageMode == IMAGE_MODE_PHOTO) {
            preparePhotoBitmap(output)
        } else {
            prepareLogoBitmap(output)
        }
    }

    private fun prepareLogoBitmap(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until output.height) {
            for (x in 0 until output.width) {
                val luminance = luminance(source.getPixel(x, y))
                output.setPixel(x, y, if (luminance < PrintLayoutConfig.LOGO_BLACK_THRESHOLD) {
                    Color.BLACK
                } else {
                    Color.WHITE
                })
            }
        }

        return output
    }

    private fun preparePhotoBitmap(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val values = DoubleArray(width * height)
        val histogram = IntArray(256)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = luminance(source.getPixel(x, y)).coerceIn(0, 255)
                values[y * width + x] = value.toDouble()
                histogram[value] += 1
            }
        }

        val low = percentile(histogram, width * height, PrintLayoutConfig.PHOTO_LOW_PERCENTILE)
        val high = percentile(histogram, width * height, PrintLayoutConfig.PHOTO_HIGH_PERCENTILE)
        val range = max(1.0, (high - low).toDouble())

        for (index in values.indices) {
            val leveled = ((values[index] - low) / range).coerceIn(0.0, 1.0)
            val gammaAdjusted = leveled.pow(PrintLayoutConfig.PHOTO_GAMMA)
            val contrasted = ((gammaAdjusted - 0.5) * PrintLayoutConfig.PHOTO_CONTRAST + 0.5) * 255.0
            values[index] = (contrasted + PrintLayoutConfig.PHOTO_BRIGHTNESS).coerceIn(0.0, 255.0)
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val oldPixel = values[index]
                val newPixel = if (oldPixel < PrintLayoutConfig.PHOTO_DITHER_THRESHOLD) 0.0 else 255.0
                val error = oldPixel - newPixel
                output.setPixel(x, y, if (newPixel == 0.0) Color.BLACK else Color.WHITE)

                diffuseError(values, width, height, x + 1, y, error * 7.0 / 16.0)
                diffuseError(values, width, height, x - 1, y + 1, error * 3.0 / 16.0)
                diffuseError(values, width, height, x, y + 1, error * 5.0 / 16.0)
                diffuseError(values, width, height, x + 1, y + 1, error * 1.0 / 16.0)
            }
        }

        return output
    }

    private fun diffuseError(values: DoubleArray, width: Int, height: Int, x: Int, y: Int, error: Double) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return
        }

        val index = y * width + x
        values[index] = (values[index] + error).coerceIn(0.0, 255.0)
    }

    private fun percentile(histogram: IntArray, total: Int, percentile: Int): Int {
        val target = ((total * percentile) / 100).coerceAtLeast(1)
        var cumulative = 0
        for (value in histogram.indices) {
            cumulative += histogram[value]
            if (cumulative >= target) {
                return value
            }
        }
        return 255
    }

    private fun luminance(pixel: Int): Int {
        return (
            0.299 * Color.red(pixel) +
            0.587 * Color.green(pixel) +
            0.114 * Color.blue(pixel)
        ).toInt()
    }

    private fun notifyStatus(message: String) {
        Log.d(TAG, message)
        statusListener(message)
    }

    private fun notifyJobStatus(printJob: PrintJob, status: String, message: String, error: String?) {
        jobStatusListener(printJob, status, message, error)
    }

    companion object {
        private const val TAG = "PrintExecutor"
        private const val IMAGE_MODE_LOGO = "logo"
        private const val IMAGE_MODE_PHOTO = "photo"
        private const val DEFAULT_CUSTOM_IMAGE_MODE = IMAGE_MODE_PHOTO
    }
}
