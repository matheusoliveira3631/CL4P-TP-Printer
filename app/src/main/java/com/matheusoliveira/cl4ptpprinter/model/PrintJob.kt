package com.matheusoliveira.cl4ptpprinter.model

data class PrintJob(
    val jobId: String,
    val text: String,
    val imageBase64: String = "",
    val imageMime: String = "",
    val imageMode: String = ""
)
