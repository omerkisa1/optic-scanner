package com.omrreader.processing

import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import android.util.Log
import com.omrreader.domain.model.BubbleState
import com.omrreader.domain.model.QuestionResult
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect as CvRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.Locale

@Singleton
class BubbleDetector @Inject constructor() {
    companion object {
        private const val TAG = "BubbleDetector"
        private const val EMPTY_MAX = 0.20
        private const val MARKED_MIN = 0.25
        private const val HEAVY_MIN = 0.75
        private const val VALID_RATIO_MIN = 2.0
        private const val GRID_EXPAND_PX = 20
        private const val GRID_MAX_SHIFT_PX = 12
    }

    fun detect(bitmap: Bitmap, grids: List<ResolvedGridRegion>): List<QuestionResult> {
        if (grids.isEmpty()) return emptyList()

        val source = Mat()
        Utils.bitmapToMat(bitmap, source)

        val results = mutableListOf<QuestionResult>()
        var globalQuestionNumber = 1

        for ((gridIndex, grid) in grids.withIndex()) {
            if (grid.rows <= 0 || grid.cols <= 0) continue

            val expectedRegion = cvRectFromAndroidRect(grid.region)
            val tunedRegion = refineGridRegion(source, expectedRegion)
            val gridRoi = Mat(source, tunedRegion)
            val binary = preprocessGrid(gridRoi)

            val localGrid = CvRect(0, 0, binary.cols(), binary.rows())
            val positions = calculateGridPositions(localGrid, grid.rows, grid.cols)
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
                    "grid=$gridIndex soru=$globalQuestionNumber $ratioStr -> ${decision.status} selected=${decision.questionResult.selectedAnswer}"
                )

                globalQuestionNumber++
            }

            gridRoi.release()
            binary.release()
        }

        source.release()
        return results
    }

    private fun preprocessGrid(input: Mat): Mat {
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
        Imgproc.GaussianBlur(enhanced, blurred, org.opencv.core.Size(5.0, 5.0), 0.0)

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

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, org.opencv.core.Size(2.0, 2.0))
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)

        gray.release()
        enhanced.release()
        blurred.release()

        return binary
    }

    private fun calculateGridPositions(gridRegion: CvRect, rows: Int, cols: Int): List<BubblePosition> {
        if (rows <= 0 || cols <= 0) return emptyList()

        val positions = mutableListOf<BubblePosition>()
        val cellWidth = gridRegion.width.toDouble() / cols.toDouble().coerceAtLeast(1.0)
        val cellHeight = gridRegion.height.toDouble() / rows.toDouble().coerceAtLeast(1.0)
        val radius = (min(cellWidth, cellHeight) * 0.35).roundToInt().coerceAtLeast(3)

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val centerX = (gridRegion.x + (col * cellWidth) + (cellWidth / 2.0)).roundToInt()
                val centerY = (gridRegion.y + (row * cellHeight) + (cellHeight / 2.0)).roundToInt()
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

        val roiRect = CvRect(left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
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

        val dominanceRatio = if (secondMaxFill > 0.0) maxFill / secondMaxFill else 99.0
        if (dominanceRatio >= VALID_RATIO_MIN && maxFill > MARKED_MIN) {
            return DetectionDecision(
                questionResult = QuestionResult(
                    questionNumber = questionNumber,
                    bubbleStates = states,
                    selectedAnswer = maxIndex,
                    fillRatios = fillRatios,
                    isValid = true
                ),
                status = if (maxFill >= HEAVY_MIN) "HEAVY_MARK" else "VALID"
            )
        }

        val highFillCount = fillRatios.count { it > MARKED_MIN && it > maxFill * 0.5 }
        if (highFillCount > 1) {
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

    private fun refineGridRegion(source: Mat, expectedRegion: CvRect): CvRect {
        val expected = clampRect(expectedRegion, source.cols(), source.rows())
        val expanded = expandRect(expected, GRID_EXPAND_PX, source.cols(), source.rows())

        val expandedRoi = Mat(source, expanded)
        val binary = preprocessGrid(expandedRoi)

        val horizontal = Mat()
        val vertical = Mat()
        val intersections = Mat()

        val horizontalKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size((expected.width * 0.10).coerceAtLeast(24.0), 1.0)
        )
        val verticalKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(1.0, (expected.height * 0.10).coerceAtLeast(24.0))
        )

        Imgproc.morphologyEx(binary, horizontal, Imgproc.MORPH_OPEN, horizontalKernel)
        Imgproc.morphologyEx(binary, vertical, Imgproc.MORPH_OPEN, verticalKernel)
        Core.bitwise_and(horizontal, vertical, intersections)

        val points = Mat()
        Core.findNonZero(intersections, points)

        var candidateX = Int.MAX_VALUE
        var candidateY = Int.MAX_VALUE
        if (!points.empty()) {
            for (index in 0 until points.rows()) {
                val xy = points.get(index, 0) ?: continue
                if (xy.size < 2) continue
                val px = xy[0].toInt()
                val py = xy[1].toInt()
                if (px + py < candidateX + candidateY) {
                    candidateX = px
                    candidateY = py
                }
            }
        }

        expandedRoi.release()
        binary.release()
        horizontal.release()
        vertical.release()
        intersections.release()
        points.release()

        if (candidateX == Int.MAX_VALUE || candidateY == Int.MAX_VALUE) {
            return expected
        }

        val expectedLocalX = expected.x - expanded.x
        val expectedLocalY = expected.y - expanded.y
        val offsetX = (candidateX - expectedLocalX).coerceIn(-GRID_MAX_SHIFT_PX, GRID_MAX_SHIFT_PX)
        val offsetY = (candidateY - expectedLocalY).coerceIn(-GRID_MAX_SHIFT_PX, GRID_MAX_SHIFT_PX)

        val shifted = CvRect(expected.x + offsetX, expected.y + offsetY, expected.width, expected.height)
        return clampRect(shifted, source.cols(), source.rows())
    }

    private fun cvRectFromAndroidRect(rect: AndroidRect): CvRect {
        return CvRect(rect.left, rect.top, rect.width(), rect.height())
    }

    private fun clampRect(rect: CvRect, maxWidth: Int, maxHeight: Int): CvRect {
        val x = rect.x.coerceIn(0, maxWidth - 1)
        val y = rect.y.coerceIn(0, maxHeight - 1)
        val width = rect.width.coerceAtLeast(1).coerceAtMost(maxWidth - x)
        val height = rect.height.coerceAtLeast(1).coerceAtMost(maxHeight - y)
        return CvRect(x, y, width, height)
    }

    private fun expandRect(rect: CvRect, padding: Int, maxWidth: Int, maxHeight: Int): CvRect {
        val left = (rect.x - padding).coerceAtLeast(0)
        val top = (rect.y - padding).coerceAtLeast(0)
        val right = (rect.x + rect.width + padding).coerceAtMost(maxWidth)
        val bottom = (rect.y + rect.height + padding).coerceAtMost(maxHeight)
        return CvRect(left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
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
