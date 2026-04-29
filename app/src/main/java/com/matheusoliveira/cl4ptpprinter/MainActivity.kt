package com.matheusoliveira.cl4ptpprinter

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.matheusoliveira.cl4ptpprinter.model.PrintJob
import com.matheusoliveira.cl4ptpprinter.mqtt.MqttManager
import com.matheusoliveira.cl4ptpprinter.printer.PrintExecutor
import com.matheusoliveira.cl4ptpprinter.printer.PrintFormatter
import com.matheusoliveira.cl4ptpprinter.printer.SunmiPrinterManager

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var testPrinterButton: Button
    private lateinit var printerManager: SunmiPrinterManager
    private lateinit var printExecutor: PrintExecutor
    private lateinit var mqttManager: MqttManager

    private var printerStatus: String = "Printer status not started yet."
    private var mqttStatus: String = "MQTT status not started yet."
    private var printStatus: String = "No print jobs yet."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusTextView)
        testPrinterButton = findViewById(R.id.testPrinterButton)

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

        testPrinterButton.setOnClickListener {
            printExecutor.enqueue(
                PrintJob(
                    jobId = "manual-test",
                    text = "Teste manual do MVP MQTT.\n\nTexto com acentos: ação, impressão, São Paulo."
                )
            )
        }
    }

    override fun onDestroy() {
        mqttManager.disconnect()
        printExecutor.release()
        printerManager.release()
        super.onDestroy()
    }

    private fun updatePrinterStatus(message: String) {
        Log.d(TAG, message)
        printerStatus = "Printer: $message"
        renderStatus()
    }

    private fun updateMqttStatus(message: String) {
        Log.d(TAG, message)
        mqttStatus = "MQTT: $message"
        renderStatus()
    }

    private fun updatePrintStatus(message: String) {
        Log.d(TAG, message)
        printStatus = "Print: $message"
        renderStatus()
    }

    private fun renderStatus() {
        runOnUiThread {
            statusTextView.text = listOf(printerStatus, mqttStatus, printStatus).joinToString(separator = "\n\n")
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
