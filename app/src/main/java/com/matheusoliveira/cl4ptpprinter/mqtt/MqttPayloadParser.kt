package com.matheusoliveira.cl4ptpprinter.mqtt

import android.util.Log
import com.matheusoliveira.cl4ptpprinter.model.PrintJob
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

class MqttPayloadParser {

    fun parse(topic: String, payload: ByteArray): PrintJob? {
        val rawPayload = payload.toString(Charsets.UTF_8).trim()
        if (rawPayload.isBlank()) {
            Log.w(TAG, "Ignoring empty MQTT payload on topic $topic")
            return null
        }

        return if (rawPayload.startsWith("{")) {
            parseJsonPayload(rawPayload)
        } else {
            PrintJob(
                jobId = fallbackJobId(),
                text = rawPayload
            )
        }
    }

    private fun parseJsonPayload(rawPayload: String): PrintJob? {
        return try {
            val jsonObject = JSONObject(rawPayload)
            val envelopeData = jsonObject.optJSONObject("data")
            val payloadObject = envelopeData ?: jsonObject
            val text = firstNonBlank(
                payloadObject.optString("content"),
                payloadObject.optString("text")
            )
            val imageObject = payloadObject.optJSONObject("image")
            val imageBase64 = imageObject?.optString("base64")?.trim() ?: ""

            if (text.isBlank() && imageBase64.isBlank()) {
                Log.w(TAG, "Ignoring JSON payload without printable text or image")
                null
            } else {
                val jobId = firstNonBlank(
                    payloadObject.optString("jobId"),
                    jsonObject.optString("id")
                ).ifBlank { fallbackJobId() }
                PrintJob(
                    jobId = jobId,
                    text = text,
                    imageBase64 = imageBase64,
                    imageMime = imageObject?.optString("mime")?.trim() ?: "",
                    imageMode = imageObject?.optString("mode")?.trim() ?: ""
                )
            }
        } catch (error: JSONException) {
            Log.e(TAG, "Failed to parse MQTT JSON payload", error)
            null
        }
    }

    private fun firstNonBlank(vararg values: String): String {
        return values.firstOrNull { it.trim().isNotBlank() }?.trim() ?: ""
    }

    private fun fallbackJobId(): String = "job-${UUID.randomUUID()}"

    companion object {
        private const val TAG = "MqttPayloadParser"
    }
}
