package com.omrreader.processing

import android.graphics.Bitmap
import com.omrreader.domain.model.BubbleState
import com.omrreader.domain.model.QuestionResult
import com.omrreader.domain.model.ThresholdConfig
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BubbleDetector @Inject constructor() {
    private val config = ThresholdConfig()

    fun detect(bitmap: Bitmap, grids: List<ResolvedGridRegion>): List<QuestionResult> {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        val gray = Mat()
        if (mat.channels() == 4) {
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
        } else {
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
        }

        val binary = Mat()
        Imgproc.adaptiveThreshold(
            gray,
            binary,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            31,
            8.0
        )

        val results = mutableListOf<QuestionResult>()
        var globalQuestionNumber = 1

        for (grid in grids) {
            val startX = grid.region.left
            val startY = grid.region.top
            val gridRight = grid.region.right
            val gridBottom = grid.region.bottom

            for (row in 0 until grid.rows) {
                val fillRatios = MutableList(grid.cols) { 0.0 }

                for (col in 0 until grid.cols) {
                    val cellLeft = startX + (col * grid.cellWidth)
                    val cellTop = startY + (row * grid.cellHeight)

                    val nextCellLeft = if (col == grid.cols - 1) gridRight else startX + ((col + 1) * grid.cellWidth)
                    val nextCellTop = if (row == grid.rows - 1) gridBottom else startY + ((row + 1) * grid.cellHeight)

                    val cellWidth = (nextCellLeft - cellLeft).coerceAtLeast(1)
                    val cellHeight = (nextCellTop - cellTop).coerceAtLeast(1)

                    val bubbleSize = (minOf(cellWidth, cellHeight) * 0.62).toInt().coerceAtLeast(6)
                    val bx = cellLeft + ((cellWidth - bubbleSize) / 2)
                    val by = cellTop + ((cellHeight - bubbleSize) / 2)
                    val bw = bubbleSize
                    val bh = bubbleSize

                    if (bx < 0 || by < 0 || bx + bw > binary.cols() || by + bh > binary.rows()) {
                        fillRatios[col] = 0.0
                        continue
                    }

                    val roi = Rect(bx, by, bw, bh)
                    val bubbleMat = Mat(binary, roi)
                    fillRatios[col] = computeFillRatio(bubbleMat)
                }

                val (selectedAnswer, isValid) = determineAnswer(fillRatios)
                val bubbleStates = buildBubbleStates(fillRatios, selectedAnswer, isValid)

                results.add(
                    QuestionResult(
                        questionNumber = globalQuestionNumber,
                        bubbleStates = bubbleStates,
                        selectedAnswer = selectedAnswer,
                        fillRatios = fillRatios.toList(),
                        isValid = isValid
                    )
                )

                globalQuestionNumber++
            }
        }

        return results
    }

    private fun computeFillRatio(binaryBubble: Mat): Double {
        val width = binaryBubble.cols()
        val height = binaryBubble.rows()
        if (width <= 0 || height <= 0) return 0.0

        val mask = createCircleMask(width, height)
        val maskedBubble = Mat()
        Core.bitwise_and(binaryBubble, binaryBubble, maskedBubble, mask)

        val contourInput = maskedBubble.clone()
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(contourInput, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val minContourArea = (width * height) * 0.01
        val contourAreaSum = contours.sumOf { contour ->
            val area = Imgproc.contourArea(contour)
            if (area >= minContourArea) area else 0.0
        }

        val fallbackFilled = Core.countNonZero(maskedBubble).toDouble()
        val filled = maxOf(contourAreaSum, fallbackFilled)
        val total = Core.countNonZero(mask).coerceAtLeast(1).toDouble()
        return (filled / total).coerceIn(0.0, 1.0)
    }

    private fun determineAnswer(fillRatios: List<Double>): Pair<Int?, Boolean> {
        if (fillRatios.isEmpty()) return null to true

        val bestIndex = fillRatios.indices.maxByOrNull { fillRatios[it] } ?: return null to true
        val sorted = fillRatios.sortedDescending()
        val best = sorted.getOrElse(0) { 0.0 }
        val second = sorted.getOrElse(1) { 0.0 }

        if (best < config.markedMin) return null to true
        if (best - second <= config.ambiguousRange) return null to false

        return bestIndex to true
    }

    private fun buildBubbleStates(
        fillRatios: List<Double>,
        selectedAnswer: Int?,
        isValid: Boolean
    ): List<BubbleState> {
        return fillRatios.mapIndexed { index, ratio ->
            when {
                selectedAnswer == index && isValid -> BubbleState.MARKED
                ratio >= config.filledMin -> BubbleState.FILLED
                ratio >= config.markedMin -> BubbleState.MARKED
                ratio >= (config.markedMin - config.ambiguousRange) -> BubbleState.AMBIGUOUS
                else -> BubbleState.EMPTY
            }
        }
    }

    private fun createCircleMask(width: Int, height: Int): Mat {
        val mask = Mat.zeros(height, width, CvType.CV_8UC1)
        val center = Point(width / 2.0, height / 2.0)
        val radius = (minOf(width, height) / 2.0 - 2.0).coerceAtLeast(1.0)
        Imgproc.circle(mask, center, radius.toInt(), Scalar(255.0), -1)
        return mask
    }
}
