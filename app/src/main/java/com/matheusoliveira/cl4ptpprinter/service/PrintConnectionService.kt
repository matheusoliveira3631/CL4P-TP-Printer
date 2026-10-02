package com.matheusoliveira.cl4ptpprinter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.matheusoliveira.cl4ptpprinter.MainActivity
import com.matheusoliveira.cl4ptpprinter.R
import com.matheusoliveira.cl4ptpprinter.model.PrintJob
import com.matheusoliveira.cl4ptpprinter.mqtt.MqttManager
import com.matheusoliveira.cl4ptpprinter.printer.PrintExecutor
import com.matheusoliveira.cl4ptpprinter.printer.PrintFormatter
import com.matheusoliveira.cl4ptpprinter.printer.SunmiPrinterManager

/**
 * Owns the printer/MQTT connection lifecycle independently of any Activity,
 * running as a foreground service so it keeps working with the app in the
 * background or the screen locked. MainActivity binds to this service for
 * status updates and manual actions (test print, reconnect) instead of
 * owning the connection itself.
 */
class PrintConnectionService : Service() {

    private val binder = LocalBinder()

    private lateinit var printerManager: SunmiPrinterManager
    private lateinit var printExecutor: PrintExecutor
    private lateinit var mqttManager: MqttManager

    private var printerStatus = "Printer status not started yet."
    private var mqttStatus = "MQTT status not started yet."
    private var printStatus = "No print jobs yet."

    private var statusListener: ((String, String, String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): PrintConnectionService = this@PrintConnectionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(buildNotification("Iniciando..."))

        printerManager = SunmiPrinterManager(applicationContext) { message ->
            updatePrinterStatus(message)
        }
        mqttManager = MqttManager(
            statusListener = { message -> updateMqttStatus(message) },
            printJobListener = { job -> printExecutor.enqueue(job) }
        )
        printExecutor = PrintExecutor(
            context = applicationContext,
            printerManager = printerManager,
            formatter = PrintFormatter(),
            statusListener = { message -> updatePrintStatus(message) },
            jobStatusListener = { job, status, message, error ->
                mqttManager.publishPrintStatus(job, status, message, error)
            }
        )

        updatePrinterStatus("Initializing printer manager...")
        printerManager.initialize()
        mqttManager.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if the system kills this service under memory
        // pressure, it is restarted (without the original intent) as soon
        // as resources allow, instead of staying dead until the Activity
        // is reopened.
        return START_STICKY
    }

    fun reconnectMqtt() {
        mqttManager.reconnect()
    }

    fun testPrint() {
        printExecutor.enqueue(
            PrintJob(
                jobId = "manual-test",
                text = "Teste manual do MVP MQTT.\n\nTexto com acentos: ação, impressão, São Paulo."
            )
        )
    }

    fun setStatusListener(listener: ((printer: String, mqtt: String, print: String) -> Unit)?) {
        statusListener = listener
        listener?.invoke(printerStatus, mqttStatus, printStatus)
    }

    private fun updatePrinterStatus(message: String) {
        printerStatus = "Printer: $message"
        emitStatus()
    }

    private fun updateMqttStatus(message: String) {
        mqttStatus = "MQTT: $message"
        emitStatus()
        updateNotification(message)
    }

    private fun updatePrintStatus(message: String) {
        printStatus = "Print: $message"
        emitStatus()
    }

    private fun emitStatus() {
        statusListener?.invoke(printerStatus, mqttStatus, printStatus)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(subtitle: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(subtitle))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CL4P-TP Printer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém a conexão MQTT da impressora ativa em segundo plano."
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(subtitle: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val contentIntent = PendingIntent.getActivity(this, 0, openAppIntent, pendingIntentFlags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("CL4P-TP Printer")
            .setContentText(subtitle)
            .setSmallIcon(R.drawable.clp_logo)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        mqttManager.release()
        printExecutor.release()
        printerManager.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "cl4ptp_printer_channel"
        private const val NOTIFICATION_ID = 1
    }
}
