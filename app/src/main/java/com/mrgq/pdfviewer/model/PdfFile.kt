package com.mrgq.pdfviewer.model

data class PdfFile(
    val name: String,
    val path: String,
    val lastModified: Long = 0L,
    val size: Long = 0L
)