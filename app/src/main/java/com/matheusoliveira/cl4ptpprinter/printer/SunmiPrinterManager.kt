package com.matheusoliveira.cl4ptpprinter.printer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.RemoteException
import android.util.Log
import com.sunmi.peripheral.printer.InnerPrinterCallback
import com.sunmi.peripheral.printer.InnerPrinterException
import com.sunmi.peripheral.printer.InnerPrinterManager
import com.sunmi.peripheral.printer.InnerResultCallback
import com.sunmi.peripheral.printer.SunmiPrinterService
import java.io.ByteArrayOutputStream

class SunmiPrinterManager(
    private val context: Context,
    private val statusListener: (String) -> Unit
) {

    private var printerService: SunmiPrinterService? = null
    private var isServiceBound = false
    private var readyForPrint = false

    private val printerConnection = object : InnerPrinterCallback() {
        override fun onConnected(service: SunmiPrinterService) {
            printerService = service
            Log.d(TAG, "SUNMI printer service connected")
            val status = resolvePrinterStatus(service)
            readyForPrint = status.canPrint
            notifyStatus(status.message)
        }

        override fun onDisconnected() {
            Log.w(TAG, "SUNMI printer service disconnected")
            printerService = null
            isServiceBound = false
            readyForPrint = false
            notifyStatus("Printer service disconnected.")
        }
    }

    fun initialize() {
        if (printerService != null) {
            notifyStatus("Printer already ready.")
            return
        }

        try {
            notifyStatus("Connecting to SUNMI printer service...")
            isServiceBound = InnerPrinterManager.getInstance().bindService(context, printerConnection)
            if (!isServiceBound) {
                notifyStatus("Could not bind to the SUNMI printer service.")
            }
        } catch (error: InnerPrinterException) {
            Log.e(TAG, "Printer service bind failed", error)
            isServiceBound = false
            notifyStatus("Printer initialization failed: ${error.message}")
        } catch (error: Exception) {
            Log.e(TAG, "Printer service bind failed", error)
            isServiceBound = false
            notifyStatus("Printer initialization failed: ${error.message}")
        }
    }

    fun isReady(): Boolean = printerService != null && readyForPrint

    fun printSeparator(separator: String): Boolean {
        return printText("$separator\n")
    }

    fun printText(text: String): Boolean {
        val service = printerService ?: run {
            notifyStatus("Printer is not connected.")
            return false
        }

        val status = resolvePrinterStatus(service)
        readyForPrint = status.canPrint
        if (!readyForPrint) {
            notifyStatus(status.message)
            return false
        }

        return try {
            service.setAlignment(ALIGN_LEFT, null)
            service.printText(text, buildLoggingCallback("text"))
            true
        } catch (error: RemoteException) {
            Log.e(TAG, "Failed to print text", error)
            notifyStatus("Printing failed: ${error.message}")
            false
        } catch (error: Exception) {
            Log.e(TAG, "Unexpected text print failure", error)
            notifyStatus("Unexpected text printing failure: ${error.message}")
            false
        }
    }

    fun printBitmapCentered(bitmap: Bitmap): Boolean {
        val service = printerService ?: run {
            notifyStatus("Printer is not connected.")
            return false
        }

        val status = resolvePrinterStatus(service)
        readyForPrint = status.canPrint
        if (!readyForPrint) {
            notifyStatus(status.message)
            return false
        }

        return try {
            service.setAlignment(ALIGN_CENTER, null)
            if (PrintLayoutConfig.LOGO_TOP_FEED_LINES > 0) {
                service.lineWrap(PrintLayoutConfig.LOGO_TOP_FEED_LINES, null)
            }
            service.sendRAWData(buildRasterBitmapCommand(bitmap), buildLoggingCallback("bitmap-raster"))
            if (PrintLayoutConfig.LOGO_BOTTOM_FEED_LINES > 0) {
                service.lineWrap(PrintLayoutConfig.LOGO_BOTTOM_FEED_LINES, null)
            }
            service.setAlignment(ALIGN_LEFT, null)
            true
        } catch (error: RemoteException) {
            Log.e(TAG, "Failed to print bitmap", error)
            notifyStatus("Bitmap printing failed: ${error.message}")
            false
        } catch (error: Exception) {
            Log.e(TAG, "Unexpected bitmap print failure", error)
            notifyStatus("Unexpected bitmap printing failure: ${error.message}")
            false
        }
    }

    private fun buildRasterBitmapCommand(bitmap: Bitmap): ByteArray {
        val targetWidth = PrintLayoutConfig.PRINTER_WIDTH_DOTS
        val widthBytes = targetWidth / 8
        val height = bitmap.height
        val leftPadding = ((targetWidth - bitmap.width) / 2).coerceAtLeast(0)
        val output = ByteArrayOutputStream()

        output.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))
        output.write(widthBytes and 0xFF)
        output.write((widthBytes shr 8) and 0xFF)
        output.write(height and 0xFF)
        output.write((height shr 8) and 0xFF)

        for (y in 0 until height) {
            for (byteIndex in 0 until widthBytes) {
                var packedByte = 0
                for (bit in 0 until 8) {
                    val printerX = byteIndex * 8 + bit
                    val sourceX = printerX - leftPadding
                    val shouldPrint = sourceX >= 0 && sourceX < bitmap.width && isBlack(bitmap.getPixel(sourceX, y))
                    if (shouldPrint) {
                        packedByte = packedByte or (0x80 shr bit)
                    }
                }
                output.write(packedByte)
            }
        }

        output.write(0x0A)
        return output.toByteArray()
    }

    private fun isBlack(pixel: Int): Boolean {
        val luminance = (
            0.299 * Color.red(pixel) +
            0.587 * Color.green(pixel) +
            0.114 * Color.blue(pixel)
        ).toInt()
        return luminance < PrintLayoutConfig.LOGO_BLACK_THRESHOLD
    }

    fun printTestReceipt() {
        val receipt = """
            ========================
            TEST SUNMI
            ========================
            Printer connected
            Android ${Build.VERSION.RELEASE}
            Initial test OK
            
        """.trimIndent()

        printText(receipt)
    }

    fun release() {
        if (!isServiceBound) {
            return
        }

        try {
            InnerPrinterManager.getInstance().unBindService(context, printerConnection)
        } catch (error: InnerPrinterException) {
            Log.w(TAG, "Failed to unbind printer service cleanly", error)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to unbind printer service cleanly", error)
        } finally {
            printerService = null
            isServiceBound = false
            readyForPrint = false
        }
    }

    private fun buildLoggingCallback(operationName: String): InnerResultCallback {
        return object : InnerResultCallback() {
            override fun onRunResult(isSuccess: Boolean) {
                val message = if (isSuccess) {
                    "Printer accepted $operationName command."
                } else {
                    "Printer rejected $operationName command."
                }
                Log.d(TAG, message)
                notifyStatus(message)
            }

            override fun onReturnString(result: String?) {
                val safeResult = result ?: "(empty result)"
                Log.d(TAG, "Printer returned for $operationName: $safeResult")
            }

            override fun onRaiseException(code: Int, msg: String?) {
                val safeMessage = msg ?: "Unknown printer exception"
                Log.e(TAG, "$operationName exception $code: $safeMessage")
                notifyStatus("Printer error $code during $operationName: $safeMessage")
            }

            override fun onPrintResult(code: Int, msg: String?) {
                val safeMessage = msg ?: "No extra details"
                Log.d(TAG, "$operationName print result $code: $safeMessage")
                notifyStatus("Printer $operationName result $code: $safeMessage")
            }
        }
    }

    private fun notifyStatus(message: String) {
        Log.d(TAG, message)
        statusListener(message)
    }

    private fun resolvePrinterStatus(service: SunmiPrinterService): PrinterStatus {
        return try {
            when (service.updatePrinterState()) {
                1 -> {
                    service.printerInit(null)
                    PrinterStatus(true, "Printer ready.")
                }
                2 -> PrinterStatus(false, "Printer found, still preparing.")
                3 -> PrinterStatus(false, "Printer communication error.")
                4 -> PrinterStatus(false, "Printer is out of paper.")
                5 -> PrinterStatus(false, "Printer is overheated.")
                6 -> PrinterStatus(false, "Printer cover is open.")
                7 -> PrinterStatus(false, "Printer cutter error.")
                8 -> PrinterStatus(true, "Printer cutter recovered.")
                9 -> PrinterStatus(false, "Black mark not detected.")
                505 -> PrinterStatus(false, "Printer not detected.")
                507 -> PrinterStatus(false, "Printer firmware update failed.")
                else -> PrinterStatus(false, "Printer connected, but status is unknown.")
            }
        } catch (error: RemoteException) {
            Log.e(TAG, "Failed to query printer status", error)
            PrinterStatus(false, "Printer connected, but status query failed: ${error.message}")
        }
    }

    companion object {
        private const val TAG = "SunmiPrinterManager"
        private const val ALIGN_LEFT = 0
        private const val ALIGN_CENTER = 1
    }

    private data class PrinterStatus(
        val canPrint: Boolean,
        val message: String
    )
}
