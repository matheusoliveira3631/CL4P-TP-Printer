package com.matheusoliveira.cl4ptpprinter.mqtt

import android.util.Log
import com.matheusoliveira.cl4ptpprinter.model.PrintJob
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.UUID

class MqttManager(
    private val statusListener: (String) -> Unit,
    private val printJobListener: (PrintJob) -> Unit,
    private val payloadParser: MqttPayloadParser = MqttPayloadParser()
) {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var mqttClient: MqttClient? = null

    fun connect() {
        worker.execute {
            connectInternal()
        }
    }

    private fun connectInternal() {
        if (!MqttConfig.isConfigured()) {
            notifyStatus("MQTT disabled. Update MqttConfig before connecting to the broker.")
            return
        }

        val existingClient = mqttClient
        if (existingClient != null && existingClient.isConnected) {
            notifyStatus("MQTT already connected.")
            return
        }

        try {
            notifyStatus("Connecting to MQTT broker ${MqttConfig.brokerUri()}...")
            val client = MqttClient(
                MqttConfig.brokerUri(),
                MqttConfig.CLIENT_ID,
                MemoryPersistence()
            )
            client.setCallback(buildCallback(client))
            client.connect(buildConnectOptions())
            mqttClient = client
        } catch (error: MqttException) {
            Log.e(TAG, "MQTT connect failed", error)
            notifyStatus("MQTT connection failed: ${error.message}")
        } catch (error: Exception) {
            Log.e(TAG, "Unexpected MQTT connect failure", error)
            notifyStatus("Unexpected MQTT failure: ${error.message}")
        }
    }

    /**
     * Tears down the current client connection but keeps the worker thread
     * alive so connect()/reconnect() still work afterwards. Use release()
     * instead when the manager itself is being torn down for good.
     */
    fun disconnect() {
        worker.execute {
            disconnectInternal()
        }
    }

    /**
     * Forces a fresh connection attempt: tears down whatever client exists
     * (if any) and immediately connects again, instead of waiting on Paho's
     * own automatic-reconnect backoff.
     */
    fun reconnect() {
        worker.execute {
            notifyStatus("Reconectando manualmente...")
            disconnectInternal()
            connectInternal()
        }
    }

    fun isConnected(): Boolean = mqttClient?.isConnected == true

    /**
     * Final teardown: disconnects the client and shuts down the worker
     * thread. Call this only when the manager will not be used again
     * (e.g. the owning service is being destroyed).
     */
    fun release() {
        worker.execute {
            disconnectInternal()
        }
        worker.shutdown()
    }

    private fun disconnectInternal() {
        val client = mqttClient
        try {
            if (client != null) {
                if (client.isConnected) {
                    client.disconnect()
                }
                client.close()
            }
            notifyStatus("MQTT disconnected.")
        } catch (error: MqttException) {
            Log.w(TAG, "MQTT disconnect failed", error)
            notifyStatus("MQTT disconnect failed: ${error.message}")
        } finally {
            mqttClient = null
        }
    }

    private fun buildConnectOptions(): MqttConnectOptions {
        return MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 30
            if (MqttConfig.USERNAME.isNotBlank()) {
                userName = MqttConfig.USERNAME
            }
            if (MqttConfig.PASSWORD.isNotBlank()) {
                password = MqttConfig.PASSWORD.toCharArray()
            }
        }
    }

    private fun buildCallback(client: MqttClient): MqttCallbackExtended {
        return object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                val reconnectText = if (reconnect) "reconnected" else "connected"
                notifyStatus("MQTT $reconnectText to ${serverURI ?: MqttConfig.brokerUri()}.")
                subscribe(client)
            }

            override fun connectionLost(cause: Throwable?) {
                val reason = cause?.message ?: "unknown reason"
                Log.w(TAG, "MQTT connection lost: $reason", cause)
                notifyStatus("MQTT connection lost: $reason")
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                if (topic == null || message == null) {
                    notifyStatus("MQTT received an empty message envelope.")
                    return
                }

                val printJob = payloadParser.parse(topic, message.payload)
                if (printJob == null) {
                    notifyStatus("MQTT message ignored: invalid payload on topic $topic.")
                    return
                }

                notifyStatus("MQTT job received: ${printJob.jobId}")
                printJobListener(printJob)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                // Status publishing is fire-and-forget.
            }
        }
    }

    private fun subscribe(client: MqttClient) {
        try {
            client.subscribe(MqttConfig.PRINT_JOB_TOPIC, MqttConfig.PRINT_JOB_QOS)
            notifyStatus("MQTT subscribed to ${MqttConfig.PRINT_JOB_TOPIC}")
        } catch (error: MqttException) {
            Log.e(TAG, "MQTT subscribe failed", error)
            notifyStatus("MQTT subscribe failed: ${error.message}")
        }
    }

    fun publishPrintStatus(printJob: PrintJob, status: String, message: String, error: String? = null) {
        worker.execute {
            val client = mqttClient
            if (client == null || !client.isConnected) {
                notifyStatus("MQTT status not published for ${printJob.jobId}: client disconnected.")
                return@execute
            }

            try {
                val envelope = JSONObject().apply {
                    put("id", UUID.randomUUID().toString())
                    put("type", "sunmi.print.status")
                    put("source", MqttConfig.CLIENT_ID)
                    put("timestamp", javaIsoTimestamp())
                    put("correlationId", printJob.jobId)
                    put("version", 1)
                    put("data", JSONObject().apply {
                        put("jobId", printJob.jobId)
                        put("status", status)
                        put("message", message)
                        if (!error.isNullOrBlank()) {
                            put("error", error)
                        }
                    })
                }
                val mqttMessage = MqttMessage(envelope.toString().toByteArray(Charsets.UTF_8)).apply {
                    qos = MqttConfig.PRINT_STATUS_QOS
                    isRetained = false
                }
                client.publish(MqttConfig.PRINT_STATUS_TOPIC, mqttMessage)
            } catch (publishError: Exception) {
                Log.w(TAG, "MQTT status publish failed", publishError)
                notifyStatus("MQTT status publish failed: ${publishError.message}")
            }
        }
    }

    private fun javaIsoTimestamp(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())
    }

    private fun notifyStatus(message: String) {
        Log.d(TAG, message)
        statusListener(message)
    }

    companion object {
        private const val TAG = "MqttManager"
    }
}
