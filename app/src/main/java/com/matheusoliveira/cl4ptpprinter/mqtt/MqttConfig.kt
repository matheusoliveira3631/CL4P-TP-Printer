package com.matheusoliveira.cl4ptpprinter.mqtt

object MqttConfig {

    const val BROKER_HOST = "192.168.15.73"
    const val BROKER_PORT = 1883
    const val USERNAME = ""
    const val PASSWORD = ""
    const val CLIENT_ID = "sunmi-p2-printer-mvp"

    const val PRINT_JOB_TOPIC = "cl4ptp/sunmi/print/request"
    const val PRINT_STATUS_TOPIC = "cl4ptp/sunmi/print/status"
    const val PRINT_JOB_QOS = 1
    const val PRINT_STATUS_QOS = 0

    fun isConfigured(): Boolean {
        return BROKER_HOST.isNotBlank() && BROKER_HOST != "TODO_BROKER_HOST"
    }

    fun brokerUri(): String = "tcp://$BROKER_HOST:$BROKER_PORT"
}
