package com.omrreader.processing

import android.graphics.Bitmap
import android.util.Log
import com.omrreader.domain.model.BubbleState
import com.omrreader.domain.model.QuestionResult
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

@Singleton
class BubbleDetector @Inject constructor() {
    companion object {
        private const val TAG = "OMR"
        private const val EMPTY_MAX = 0.22
        private const val MARKED_MIN = 0.25
        private const val HEAVY_MIN = 0.70
        private const val VALID_RELATIVE_MIN = 2.0
        private const val LIKELY_RELATIVE_MIN = 1.5
        private const val LIKELY_ABSOLUTE_MIN = 0.30
    }

    fun detect(bitmap: Bitmap, grids: List<ResolvedGridRegion>): List<QuestionResult> {
        if (grids.isEmpty()) return emptyList()

        val source = Mat()
        Utils.bitmapToMat(bitmap, source)
        val binary = preprocessPage(source)

        val results = mutableListOf<QuestionResult>()
        var globalQuestionNumber = 1

        for ((gridIndex, grid) in grids.withIndex()) {
            if (grid.rows <= 0 || grid.cols <= 0) continue

            val positions = calculateBubblePositions(grid)
            val ratioMatrix = Array(grid.rows) { DoubleArray(grid.cols) }

            for (position in positions) {
                ratioMatrix[position.row][position.col] = analyzeBubble(binary, position)
            }

            for (row in 0 until grid.rows) {
                val ratios = ratioMatrix[row].toList()
                val decision = determineAnswer(globalQuestionNumber, ratios)
                results.add(decision.questionResult)

                val ratioStr = ratios.mapIndexed { index, ratio ->
                    "${('A'.code + index).toChar()}=${String.format(Locale.US, "%.2f", ratio)}"
                }.joinToString(" | ")

                Log.d(
                    TAG,
                    "Q$globalQuestionNumber grid=$gridIndex $ratioStr -> ${decision.status}"
                )

                globalQuestionNumber++
            }
        }

        binary.release()
        source.release()
        return results
    }

    private fun preprocessPage(input: Mat): Mat {
        val gray = Mat()
        when (input.channels()) {
            4 -> Imgproc.cvtColor(input, gray, Imgproc.COLOR_RGBA2GRAY)
            3 -> Imgproc.cvtColor(input, gray, Imgproc.COLOR_BGR2GRAY)
            else -> input.copyTo(gray)
        }

        val clahe = Imgproc.createCLAHE(2.0, org.opencv.core.Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(gray, enhanced)

        val blurred = Mat()
        Imgproc.GaussianBlur(enhanced, blurred, org.opencv.core.Size(3.0, 3.0), 0.0)

        val binary = Mat()
        Imgproc.adaptiveThreshold(
            blurred,
            binary,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            15,
            4.0
        )

        gray.release()
        enhanced.release()
        blurred.release()

        return binary
    }

    private fun calculateBubblePositions(grid: ResolvedGridRegion): List<BubblePosition> {
        if (grid.rows <= 0 || grid.cols <= 0) return emptyList()

        val positions = mutableListOf<BubblePosition>()
        val gridWidth = (grid.region.right - grid.region.left).toDouble().coerceAtLeast(1.0)
        val gridHeight = (grid.region.bottom - grid.region.top).toDouble().coerceAtLeast(1.0)
        val cellWidth = gridWidth / grid.cols.toDouble()
        val cellHeight = gridHeight / grid.rows.toDouble()
        val radius = min(
            FormConstants.BUBBLE_RADIUS,
            (min(cellWidth, cellHeight) * 0.45).roundToInt().coerceAtLeast(6)
        )

        for (row in 0 until grid.rows) {
            for (col in 0 until grid.cols) {
                val centerX = (grid.region.left + (col * cellWidth) + (cellWidth / 2.0)).roundToInt()
                val centerY = (grid.region.top + (row * cellHeight) + (cellHeight / 2.0)).roundToInt()
                positions += BubblePosition(row, col, centerX, centerY, radius)
            }
        }

        return positions
    }

    private fun analyzeBubble(binary: Mat, bubble: BubblePosition): Double {
        if (binary.empty() || binary.cols() <= 1 || binary.rows() <= 1) return 0.0

        val radius = bubble.radius.coerceAtLeast(1)
        val left = (bubble.centerX - radius).coerceIn(0, binary.cols() - 1)
        val top = (bubble.centerY - radius).coerceIn(0, binary.rows() - 1)
        val right = (bubble.centerX + radius + 1).coerceIn(left + 1, binary.cols())
        val bottom = (bubble.centerY + radius + 1).coerceIn(top + 1, binary.rows())

        val roiRect = Rect(left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
        val roi = Mat(binary, roiRect)
        val mask = Mat.zeros(roiRect.height, roiRect.width, CvType.CV_8UC1)

        val localCenter = Point((bubble.centerX - left).toDouble(), (bubble.centerY - top).toDouble())
        val localRadius = radius.coerceAtMost((min(roiRect.width, roiRect.height) / 2).coerceAtLeast(1))
        Imgproc.circle(mask, localCenter, localRadius, Scalar(255.0), -1)

        val masked = Mat()
        Core.bitwise_and(roi, roi, masked, mask)

        val filledPixels = Core.countNonZero(masked).toDouble()
        val totalPixels = Core.countNonZero(mask).toDouble().coerceAtLeast(1.0)

        roi.release()
        mask.release()
        masked.release()

        return (filledPixels / totalPixels).coerceIn(0.0, 1.0)
    }

    private fun determineAnswer(
        questionNumber: Int,
        fillRatios: List<Double>
    ): DetectionDecision {
        if (fillRatios.isEmpty()) {
            return DetectionDecision(
                questionResult = QuestionResult(
                    questionNumber = questionNumber,
                    bubbleStates = emptyList(),
                    selectedAnswer = null,
                    fillRatios = emptyList(),
                    isValid = false
                ),
                status = "AMBIGUOUS"
            )
        }

        val states = fillRatios.map { value ->
            when {
                value >= HEAVY_MIN -> BubbleState.FILLED
                value >= MARKED_MIN -> BubbleState.MARKED
                else -> BubbleState.EMPTY
            }
        }

        val maxFill = fillRatios.maxOrNull() ?: 0.0
        val maxIndex = fillRatios.indexOf(maxFill).coerceAtLeast(0)
        val sortedRatios = fillRatios.sortedDescending()
        val secondMaxFill = sortedRatios.getOrElse(1) { 0.0 }

        if (maxFill < EMPTY_MAX) {
            return DetectionDecision(
                questionResult = QuestionResult(
                    questionNumber = questionNumber,
                    bubbleStates = states,
                    selectedAnswer = null,
                    fillRatios = fillRatios,
                    isValid = true
                ),
                status = "EMPTY"
            )
        }

        val dominanceRatio = if (secondMaxFill > 0.01) maxFill / secondMaxFill else 100.0
        if (dominanceRatio >= VALID_RELATIVE_MIN && maxFill >= MARKED_MIN) {
            return DetectionDecision(
                questionResult = QuestionResult(
                    questionNumber = questionNumber,
                    bubbleStates = states,
                    selectedAnswer = maxIndex,
                    fillRatios = fillRatios,
                    isValid = true
                ),
                status = "VALID"
            )
        }

        val highFillCount = fillRatios.count { it >= MARKED_MIN }
        if (highFillCount >= 2) {
            val heavyMarks = fillRatios.indices.filter { fillRatios[it] > HEAVY_MIN }
            val lightMarks = fillRatios.indices.filter { fillRatios[it] in MARKED_MIN..HEAVY_MIN }

            if (heavyMarks.isNotEmpty() && lightMarks.size == 1) {
                return DetectionDecision(
                    questionResult = QuestionResult(
                        questionNumber = questionNumber,
                        bubbleStates = states,
                        selectedAnswer = lightMarks[0],
                        fillRatios = fillRatios,
                        isValid = true
                    ),
                    status = "CORRECTED"
                )
            }

            return DetectionDecision(
                questionResult = QuestionResult(
                    questionNumber = questionNumber,
                    bubbleStates = states,
                    selectedAnswer = null,
                    fillRatios = fillRatios,
                    isValid = false
                ),
                status = "MULTIPLE"
            )
        }

        if (maxFill >= LIKELY_ABSOLUTE_MIN && dominanceRatio >= LIKELY_RELATIVE_MIN) {
            return DetectionDecision(
                questionResult = QuestionResult(
                    questionNumber = questionNumber,
                    bubbleStates = states,
                    selectedAnswer = maxIndex,
                    fillRatios = fillRatios,
                    isValid = true
                ),
                status = "LIKELY"
            )
        }

        return DetectionDecision(
            questionResult = QuestionResult(
                questionNumber = questionNumber,
                bubbleStates = states,
                selectedAnswer = null,
                fillRatios = fillRatios,
                isValid = false
            ),
            status = "AMBIGUOUS"
        )
    }

    private data class BubblePosition(
        val row: Int,
        val col: Int,
        val centerX: Int,
        val centerY: Int,
        val radius: Int
    )

    private data class DetectionDecision(
        val questionResult: QuestionResult,
        val status: String
    )
}
