package com.omrreader.processing

import android.graphics.Bitmap
import android.util.Log
import com.omrreader.domain.model.BubbleState
import com.omrreader.domain.model.QuestionResult
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

@Singleton
class BubbleDetector @Inject constructor() {
    companion object {
        private const val TAG = "BubbleDetector"
        private const val EMPTY_THRESHOLD = 0.25
        private const val MARKED_THRESHOLD = 0.25
        private const val FILLED_THRESHOLD = 0.75
        private const val RELATIVE_DOMINANCE_RATIO = 1.5
    }

    fun detect(bitmap: Bitmap, grids: List<ResolvedGridRegion>): List<QuestionResult> {
        if (grids.isEmpty()) return emptyList()

        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        val binary = preprocessForCamera(mat)

        val contourInput = binary.clone()
        val allContours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(contourInput, allContours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val results = mutableListOf<QuestionResult>()
        var globalQuestionNumber = 1

        for ((gridIndex, grid) in grids.withIndex()) {
            val gridCandidates = detectGridBubbleCandidates(allContours, grid)
            val rowBuckets = assignCandidatesToRows(gridCandidates, grid)

            Log.d(
                TAG,
                "grid=$gridIndex expectedRows=${grid.rows} expectedCols=${grid.cols} candidates=${gridCandidates.size}"
            )

            for (rowIndex in 0 until grid.rows) {
                val rowCandidates = rowBuckets[rowIndex].sortedBy { it.center.x }
                Log.d(
                    TAG,
                    "grid=$gridIndex row=$rowIndex bubbleCount=${rowCandidates.size} coords=${rowCandidates.joinToString { "(${it.center.x.toInt()},${it.center.y.toInt()})" }}"
                )

                val orderedBubbles = orderRowBubbles(rowCandidates, grid, rowIndex)
                if (orderedBubbles == null) {
                    results.add(ambiguousRow(globalQuestionNumber, grid.cols))
                    globalQuestionNumber++
                    continue
                }

                val fillRatios = orderedBubbles.map { computeFillRatio(binary, it.rect) }
                Log.d(
                    TAG,
                    "q=$globalQuestionNumber fillRatios=${fillRatios.joinToString(prefix = "[", postfix = "]") { String.format("%.3f", it) }}"
                )

                results.add(determineAnswer(globalQuestionNumber, fillRatios))
                globalQuestionNumber++
            }
        }

        return results
    }

    private fun preprocessForCamera(input: Mat): Mat {
        val gray = Mat()
        if (input.channels() == 4) {
            Imgproc.cvtColor(input, gray, Imgproc.COLOR_RGBA2GRAY)
        } else {
            Imgproc.cvtColor(input, gray, Imgproc.COLOR_BGR2GRAY)
        }

        val clahe = Imgproc.createCLAHE(2.0, org.opencv.core.Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(gray, enhanced)

        val denoised = Mat()
        Imgproc.bilateralFilter(enhanced, denoised, 9, 75.0, 75.0)

        val binary = Mat()
        Imgproc.adaptiveThreshold(
            denoised,
            binary,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            15,
            4.0
        )

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, org.opencv.core.Size(3.0, 3.0))
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
        return binary
    }

    private fun detectGridBubbleCandidates(
        contours: List<MatOfPoint>,
        grid: ResolvedGridRegion
    ): List<BubbleCandidate> {
        val expectedBubbleDiameter = (min(grid.cellWidth, grid.cellHeight) * 0.62).coerceAtLeast(8.0)
        val minBubbleSize = (expectedBubbleDiameter * 0.45).toInt().coerceAtLeast(6)
        val expectedBubbleArea = PI * (expectedBubbleDiameter / 2.0) * (expectedBubbleDiameter / 2.0)
        val minContourArea = expectedBubbleArea * 0.06
        val maxContourArea = expectedBubbleArea * 2.60

        val marginX = (grid.cellWidth * 0.30).toInt()
        val marginY = (grid.cellHeight * 0.30).toInt()

        val candidates = contours.mapNotNull { contour ->
            val area = Imgproc.contourArea(contour)
            if (area !in minContourArea..maxContourArea) return@mapNotNull null

            val rect = Imgproc.boundingRect(contour)
            if (rect.width < minBubbleSize || rect.height < minBubbleSize) return@mapNotNull null

            val aspectRatio = rect.width.toDouble() / rect.height.toDouble().coerceAtLeast(1.0)
            if (aspectRatio < 0.8 || aspectRatio > 1.2) return@mapNotNull null

            val center = Point(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0)
            if (center.x < grid.region.left - marginX || center.x > grid.region.right + marginX) return@mapNotNull null
            if (center.y < grid.region.top - marginY || center.y > grid.region.bottom + marginY) return@mapNotNull null

            BubbleCandidate(rect = rect, center = center, contourArea = area)
        }

        val dedupeDistance = (min(grid.cellWidth, grid.cellHeight) * 0.28).coerceAtLeast(5.0)
        return dedupeCandidates(candidates, dedupeDistance)
    }

    private fun dedupeCandidates(candidates: List<BubbleCandidate>, minDistance: Double): List<BubbleCandidate> {
        if (candidates.isEmpty()) return emptyList()

        val sorted = candidates.sortedByDescending { it.contourArea }
        val kept = mutableListOf<BubbleCandidate>()
        for (candidate in sorted) {
            val isDuplicate = kept.any { existing ->
                hypot(existing.center.x - candidate.center.x, existing.center.y - candidate.center.y) < minDistance
            }
            if (!isDuplicate) {
                kept.add(candidate)
            }
        }
        return kept
    }

    private fun assignCandidatesToRows(
        candidates: List<BubbleCandidate>,
        grid: ResolvedGridRegion
    ): Array<MutableList<BubbleCandidate>> {
        val rows = Array(grid.rows) { mutableListOf<BubbleCandidate>() }

        for (candidate in candidates) {
            val relativeY = candidate.center.y - grid.region.top.toDouble()
            val rawRow = (relativeY / grid.cellHeight.toDouble()).toInt()
            if (rawRow !in 0 until grid.rows) continue

            val expectedCenterY = grid.region.top + (rawRow + 0.5) * grid.cellHeight
            val maxDeltaY = grid.cellHeight * 0.65
            if (abs(candidate.center.y - expectedCenterY) > maxDeltaY) continue

            rows[rawRow].add(candidate)
        }

        return rows
    }

    private fun orderRowBubbles(
        rowCandidates: List<BubbleCandidate>,
        grid: ResolvedGridRegion,
        rowIndex: Int
    ): List<BubbleCandidate>? {
        if (rowCandidates.size != grid.cols) return null

        val sorted = rowCandidates.sortedBy { it.center.x }
        val colTolerance = grid.cellWidth * 0.60
        val rowCenterY = grid.region.top + (rowIndex + 0.5) * grid.cellHeight
        val rowTolerance = grid.cellHeight * 0.70

        for ((colIndex, candidate) in sorted.withIndex()) {
            val expectedCenterX = grid.region.left + (colIndex + 0.5) * grid.cellWidth
            if (abs(candidate.center.x - expectedCenterX) > colTolerance) return null
            if (abs(candidate.center.y - rowCenterY) > rowTolerance) return null
        }

        return sorted
    }

    private fun computeFillRatio(binary: Mat, rect: Rect): Double {
        if (binary.empty() || binary.cols() <= 1 || binary.rows() <= 1) return 0.0

        val left = rect.x.coerceIn(0, binary.cols() - 1)
        val top = rect.y.coerceIn(0, binary.rows() - 1)
        val right = (rect.x + rect.width).coerceIn(left + 1, binary.cols())
        val bottom = (rect.y + rect.height).coerceIn(top + 1, binary.rows())

        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        val safeRect = Rect(left, top, width, height)

        val bubbleRoi = Mat(binary, safeRect)
        val mask = Mat.zeros(height, width, CvType.CV_8UC1)
        val radius = ((min(width, height) * 0.42) - 1.0).coerceAtLeast(1.0)
        Imgproc.circle(mask, Point(width / 2.0, height / 2.0), radius.toInt(), Scalar(255.0), -1)

        val masked = Mat()
        Core.bitwise_and(bubbleRoi, bubbleRoi, masked, mask)

        val filledPixels = Core.countNonZero(masked).toDouble()
        val totalPixels = Core.countNonZero(mask).coerceAtLeast(1).toDouble()
        return (filledPixels / totalPixels).coerceIn(0.0, 1.0)
    }

    private fun determineAnswer(
        questionNumber: Int,
        fillRatios: List<Double>
    ): QuestionResult {
        if (fillRatios.isEmpty()) {
            return QuestionResult(
                questionNumber = questionNumber,
                bubbleStates = emptyList(),
                selectedAnswer = null,
                fillRatios = emptyList(),
                isValid = false
            )
        }

        val states = fillRatios.map { ratio ->
            when {
                ratio > FILLED_THRESHOLD -> BubbleState.FILLED
                ratio > MARKED_THRESHOLD -> BubbleState.MARKED
                else -> BubbleState.EMPTY
            }
        }

        val markedIndices = states.indices.filter { states[it] == BubbleState.MARKED }
        val filledIndices = states.indices.filter { states[it] == BubbleState.FILLED }
        val result: QuestionResult
        val status: String

        when {
            markedIndices.isEmpty() && filledIndices.isEmpty() -> {
                result = QuestionResult(
                    questionNumber = questionNumber,
                    bubbleStates = states,
                    selectedAnswer = null,
                    fillRatios = fillRatios,
                    isValid = true
                )
                status = "EMPTY"
            }

            filledIndices.size == states.size -> {
                result = QuestionResult(
                    questionNumber = questionNumber,
                    bubbleStates = states,
                    selectedAnswer = null,
                    fillRatios = fillRatios,
                    isValid = true
                )
                status = "ALL_CANCELLED"
            }

            filledIndices.isNotEmpty() && markedIndices.size == 1 -> {
                result = QuestionResult(
                    questionNumber = questionNumber,
                    bubbleStates = states,
                    selectedAnswer = markedIndices[0],
                    fillRatios = fillRatios,
                    isValid = true
                )
                status = "CORRECTED"
            }

            markedIndices.size == 1 -> {
                result = QuestionResult(
                    questionNumber = questionNumber,
                    bubbleStates = states,
                    selectedAnswer = markedIndices[0],
                    fillRatios = fillRatios,
                    isValid = true
                )
                status = "VALID"
            }

            else -> {
                val relative = determineAnswerRelative(fillRatios)
                if (relative != null) {
                    result = QuestionResult(
                        questionNumber = questionNumber,
                        bubbleStates = states,
                        selectedAnswer = relative,
                        fillRatios = fillRatios,
                        isValid = true
                    )
                    status = "VALID_RELATIVE"
                } else {
                    val sorted = fillRatios.sortedDescending()
                    val strongest = sorted.getOrElse(0) { 0.0 }
                    val second = sorted.getOrElse(1) { 0.0 }
                    val isMultiple = strongest > MARKED_THRESHOLD && second > MARKED_THRESHOLD

                    result = QuestionResult(
                        questionNumber = questionNumber,
                        bubbleStates = if (isMultiple) states else List(states.size) { BubbleState.EMPTY },
                        selectedAnswer = null,
                        fillRatios = fillRatios,
                        isValid = !isMultiple
                    )
                    status = if (isMultiple) "MULTIPLE" else "EMPTY"
                }
            }
        }

        Log.d(
            TAG,
            "q=$questionNumber status=$status selected=${result.selectedAnswer} valid=${result.isValid} states=${result.bubbleStates.joinToString()}"
        )

        return result
    }

    private fun determineAnswerRelative(fillRatios: List<Double>): Int? {
        if (fillRatios.isEmpty()) return null
        if (fillRatios.size == 1) {
            return if (fillRatios[0] > MARKED_THRESHOLD) 0 else null
        }

        val sorted = fillRatios.withIndex().sortedByDescending { it.value }
        val highest = sorted[0]
        val secondHighest = sorted[1]

        if (highest.value > MARKED_THRESHOLD && highest.value > secondHighest.value * RELATIVE_DOMINANCE_RATIO) {
            return highest.index
        }

        return null
    }

    private fun ambiguousRow(questionNumber: Int, optionCount: Int): QuestionResult {
        return QuestionResult(
            questionNumber = questionNumber,
            bubbleStates = List(optionCount) { BubbleState.AMBIGUOUS },
            selectedAnswer = null,
            fillRatios = List(optionCount) { 0.0 },
            isValid = false
        )
    }

    private data class BubbleCandidate(
        val rect: Rect,
        val center: Point,
        val contourArea: Double
    )
}
