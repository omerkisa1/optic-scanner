package com.omrreader.processing

import android.graphics.Rect

data class FormTemplate(
    val name: String,
    val pageWidth: Int,
    val pageHeight: Int,
    val markerSize: Int,
    val nameRegion: Rect,
    val numberRegion: Rect,
    val classRegion: Rect,
    val qrRegion: Rect,
    val subjects: List<SubjectGrid>
) {
    companion object {
        // A4 Paper definition (~ 2480x3508 for 300dpi, but we might scale to 1240x1754 for faster processing)
        val DEFAULT = FormTemplate(
            name = "standard_2x20",
            pageWidth = 1240,
            pageHeight = 1754,
            markerSize = 50, // 50px marker at 300dpi is ~10mm? We will calibrate
            nameRegion = Rect(150, 150, 800, 250),
            numberRegion = Rect(150, 260, 800, 360),
            classRegion = Rect(150, 370, 800, 470),
            qrRegion = Rect(900, 150, 1100, 350), // 30x30mm QR
            subjects = listOf(
                SubjectGrid(
                    name = "DERS 1",
                    gridRegion = Rect(150, 600, 600, 1600),
                    rows = 20,
                    cols = 4,
                    bubbleWidth = 35,
                    bubbleHeight = 35,
                    horizontalGap = 59, // 10mm gap approx
                    verticalGap = 47 // 8mm gap approx
                ),
                SubjectGrid(
                    name = "DERS 2",
                    gridRegion = Rect(650, 600, 1100, 1600),
                    rows = 20,
                    cols = 4,
                    bubbleWidth = 35,
                    bubbleHeight = 35,
                    horizontalGap = 59,
                    verticalGap = 47
                )
            )
        )
    }
}

data class SubjectGrid(
    val name: String,
    val gridRegion: Rect,
    val rows: Int,
    val cols: Int,
    val bubbleWidth: Int,
    val bubbleHeight: Int,
    val horizontalGap: Int,
    val verticalGap: Int
)
