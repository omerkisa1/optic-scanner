package com.omrreader.models

data class Region(
    val name: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

data class OMRResult(
    val name: String,
    val studentNo: String,
    val answers: Map<Int, String>
)
