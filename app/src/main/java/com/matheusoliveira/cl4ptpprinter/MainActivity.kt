package com.matheusoliveira.cl4ptpprinter

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.matheusoliveira.cl4ptpprinter.service.PrintConnectionService

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var testPrinterButton: Button
    private lateinit var reconnectButton: Button

    private var service: PrintConnectionService? = null
    private var bound = false

    private val statusListener: (String, String, String) -> Unit = { printer, mqtt, print ->
        runOnUiThread {
            statusTextView.text = listOf(printer, mqtt, print).joinToString(separator = "\n\n")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? PrintConnectionService.LocalBinder ?: return
            service = localBinder.getService()
            bound = true
            service?.setStatusListener(statusListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusTextView)
        testPrinterButton = findViewById(R.id.testPrinterButton)
        reconnectButton = findViewById(R.id.reconnectButton)

        testPrinterButton.setOnClickListener {
            service?.testPrint()
        }

        reconnectButton.setOnClickListener {
            service?.reconnectMqtt()
        }

        val serviceIntent = Intent(this, PrintConnectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (bound) {
            service?.setStatusListener(null)
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }
}
