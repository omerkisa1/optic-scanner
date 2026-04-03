package com.omrreader.processing

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OMREngine @Inject constructor() {

    // Step 5: Fixed Region Segmentation
    // Map normalized 1000x1000 coordinates to actual width/height
    fun cropRegion(warpedMat: Mat, regionName: String): Mat {
        val width = warpedMat.cols().toDouble()
        val height = warpedMat.rows().toDouble()

        val rect = when (regionName) {
            "name" -> Rect((0.1 * width).toInt(), (0.05 * height).toInt(), (0.4 * width).toInt(), (0.1 * height).toInt())
            "studentNo" -> Rect((0.6 * width).toInt(), (0.05 * height).toInt(), (0.3 * width).toInt(), (0.1 * height).toInt())
            "answers" -> Rect((0.1 * width).toInt(), (0.2 * height).toInt(), (0.8 * width).toInt(), (0.7 * height).toInt())
            else -> throw IllegalArgumentException("Unknown region name: $regionName")
        }

        return Mat(warpedMat, rect)
    }

    // Step 7: OMR Engine (Bubble Reading)
    fun readAnswers(bubbleGridRegionMat: Mat): Map<Int, String> {
        val answers = mutableMapOf<Int, String>()

        val thresholdMat = Mat()
        Imgproc.adaptiveThreshold(
            bubbleGridRegionMat,
            thresholdMat,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            11,
            2.0
        )

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            thresholdMat,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        val bubbles = mutableListOf<Rect>()

        // 7.1 Bubble Detection
        for (contour in contours) {
            val rect = Imgproc.boundingRect(contour)
            val aspectRatio = rect.width.toDouble() / rect.height.toDouble()

            // Circle filter: width ≈ height, size threshold
            if (aspectRatio >= 0.8 && aspectRatio <= 1.2 && rect.width > 20 && rect.height > 20) {
                bubbles.add(rect)
            }
        }

        // 7.2 Grouping by row (question) and sorting by X
        // Instead of strict mathematical division, group by Y coordinate allowing a vertical variance
        val sortedBubbles = bubbles.sortedBy { it.y }
        val questions = mutableMapOf<Int, MutableList<Rect>>()
        var currentRowY = -1
        var currentQuestion = 1
        
        for (bubble in sortedBubbles) {
            if (currentRowY == -1 || Math.abs(bubble.y - currentRowY) > bubble.height / 2) {
                // New row detected
                currentRowY = bubble.y
                if (questions.isNotEmpty()) {
                    currentQuestion++
                }
            }
            questions.getOrPut(currentQuestion) { mutableListOf() }.add(bubble)
        }
        
        // Ensure options within questions are sorted left to right
        questions.values.forEach { row ->
            row.sortBy { it.x }
        }

        // 7.3 Filled Detection
        val options = listOf("A", "B", "C", "D", "E")

        for ((qNum, rowBubbles) in questions) {
            if (rowBubbles.size != 5) continue // Expecting exactly 5 options per question (A, B, C, D, E)

            var selectedOptionsCount = 0
            var finalSelection = "Empty"

            for ((index, bubble) in rowBubbles.withIndex()) {
                val bubbleMat = Mat(thresholdMat, bubble)
                val pixelCount = Core.countNonZero(bubbleMat)
                val area = bubble.width * bubble.height
                val fillRatio = pixelCount.toDouble() / area.toDouble()
                
                // Threshold density logic
                if (fillRatio > 0.4) { // Assumes 40% filled is a valid mark
                    selectedOptionsCount++
                    finalSelection = options[index]
                }
            }

            // Step 10: Validation for multiple marks or empty
            answers[qNum] = when {
                selectedOptionsCount > 1 -> "Multiple Marks"
                selectedOptionsCount == 0 -> "Empty"
                else -> finalSelection
            }
        }

        return answers
    }
}
