package com.matheusoliveira.cl4ptpprinter.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttPayloadParserTest {

    private val parser = MqttPayloadParser()

    @Test
    fun `parses envelope with optional image`() {
        val payload = """
            {
              "id": "job-123",
              "type": "sunmi.print.request",
              "data": {
                "content": "Pedido com imagem",
                "image": {
                  "mime": "image/png",
                  "base64": "aW1hZ2U=",
                  "placement": "header",
                  "mode": "photo"
                }
              }
            }
        """.trimIndent()

        val job = parser.parse("cl4ptp/sunmi/print/request", payload.toByteArray(Charsets.UTF_8))

        assertEquals("job-123", job?.jobId)
        assertEquals("Pedido com imagem", job?.text)
        assertEquals("image/png", job?.imageMime)
        assertEquals("aW1hZ2U=", job?.imageBase64)
        assertEquals("photo", job?.imageMode)
    }

    @Test
    fun `keeps image fields empty when payload has no image`() {
        val payload = """{"jobId":"job-456","text":"Pedido sem imagem"}"""

        val job = parser.parse("cl4ptp/sunmi/print/request", payload.toByteArray(Charsets.UTF_8))

        assertEquals("job-456", job?.jobId)
        assertEquals("Pedido sem imagem", job?.text)
        assertTrue(job?.imageMime.orEmpty().isBlank())
        assertTrue(job?.imageBase64.orEmpty().isBlank())
        assertTrue(job?.imageMode.orEmpty().isBlank())
    }

    @Test
    fun `keeps image mode empty when image payload omits mode`() {
        val payload = """
            {
              "jobId": "job-789",
              "text": "Imagem sem modo",
              "image": {
                "mime": "image/jpeg",
                "base64": "aW1hZ2U="
              }
            }
        """.trimIndent()

        val job = parser.parse("cl4ptp/sunmi/print/request", payload.toByteArray(Charsets.UTF_8))

        assertEquals("job-789", job?.jobId)
        assertEquals("image/jpeg", job?.imageMime)
        assertEquals("aW1hZ2U=", job?.imageBase64)
        assertTrue(job?.imageMode.orEmpty().isBlank())
    }
}
