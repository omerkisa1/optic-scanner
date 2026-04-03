package com.omrreader.processing

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
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
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

@Singleton
class BubbleDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "OMR"
        private const val MEAN_EMPTY_RATIO_MAX = 1.5
        private const val VALID_SECOND_RATIO_MIN = 1.5
        private const val VALID_MEAN_MULTIPLIER = 1.5
        private const val ABOVE_MEAN_MULTIPLIER = 1.4
        private const val HIGH_MEAN_MULTIPLIER = 1.8
        private const val MID_MEAN_MULTIPLIER = 1.3
    }

    fun detect(bitmap: Bitmap, grids: List<ResolvedGridRegion>): List<QuestionResult> {
        return detectWithDebug(bitmap, FormTemplate.DEFAULT, grids).results
    }

    fun detectWithDebug(
        bitmap: Bitmap,
        template: FormTemplate,
        grids: List<ResolvedGridRegion>
    ): BubbleDetectionOutput {
        if (grids.isEmpty()) {
            return BubbleDetectionOutput(emptyList(), null, null, emptyList())
        }

        val source = Mat()
        Utils.bitmapToMat(bitmap, source)
        val binary = preprocessPage(source)
        val debugMat = source.clone()
        val thresholdDebugPath = saveThresholdDebugImage(binary)

        val results = mutableListOf<QuestionResult>()
        val debugInfos = mutableListOf<QuestionDebugInfo>()
        val questionLogLines = mutableListOf<String>()
        var globalQuestionNumber = 1

        Log.d(TAG, "=== DEBUG INFO ===")
        Log.d(TAG, "Normalized size: ${source.cols()}x${source.rows()}")

        for ((gridIndex, grid) in grids.withIndex()) {
            if (grid.rows <= 0 || grid.cols <= 0) continue

            Log.d(
                TAG,
                "Grid region: left=${grid.region.left}, top=${grid.region.top}, right=${grid.region.right}, bottom=${grid.region.bottom}"
            )

            val positions = calculateBubblePositions(grid)
            positions.firstOrNull()?.let { first ->
                Log.d(TAG, "First bubble (Q${globalQuestionNumber}-A): x=${first.centerX}, y=${first.centerY}")
            }
            positions.lastOrNull()?.let { last ->
                val lastOption = ('A'.code + last.col).toChar()
                Log.d(TAG, "Last bubble (Q${globalQuestionNumber + grid.rows - 1}-$lastOption): x=${last.centerX}, y=${last.centerY}")
            }

            val ratioMatrix = Array(grid.rows) { DoubleArray(grid.cols) }

            for (position in positions) {
                ratioMatrix[position.row][position.col] = analyzeBubble(binary, position)
            }

            for (row in 0 until grid.rows) {
                val ratios = ratioMatrix[row].toList()
                val decision = determineAnswer(globalQuestionNumber, ratios)
                results.add(decision.questionResult)

                val rowPositions = positions
                    .filter { it.row == row }
                    .sortedBy { it.col }
                debugInfos += QuestionDebugInfo(
                    questionNumber = globalQuestionNumber,
                    status = decision.status,
                    ratios = ratios,
                    positions = rowPositions
                )

                val ratioStr = ratios.mapIndexed { index, ratio ->
                    "${('A'.code + index).toChar()}=${String.format(Locale.US, "%.2f", ratio)}"
                }.joinToString(" ")

                val maxFill = ratios.maxOrNull() ?: 0.0
                val maxIndex = ratios.indexOf(maxFill).coerceAtLeast(0)
                val maxOption = ('A'.code + maxIndex).toChar()
                val selectedLabel = decision.questionResult.selectedAnswer
                    ?.let { ('A'.code + it).toChar().toString() }
                    ?: "-"

                val uiLine = "Q$globalQuestionNumber: $ratioStr -> ${decision.status}($selectedLabel)"
                questionLogLines += uiLine

                Log.d(
                    TAG,
                    "Q$globalQuestionNumber grid=$gridIndex: $ratioStr -> max=${String.format(Locale.US, "%.3f", maxFill)} at $maxOption status=${decision.status}"
                )

                globalQuestionNumber++
            }
        }

        drawDebugOverlay(debugMat, template, debugInfos)

        val debugPath = saveDebugImage(debugMat)

        binary.release()
        source.release()
        debugMat.release()

        return BubbleDetectionOutput(
            results = results,
            debugImagePath = debugPath,
            thresholdDebugImagePath = thresholdDebugPath,
            questionLogs = questionLogLines
        )
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
            21,
            8.0
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

        val meanFill = fillRatios.average().coerceAtLeast(0.0001)
        val maxFill = fillRatios.maxOrNull() ?: 0.0
        val maxIndex = fillRatios.indexOf(maxFill).coerceAtLeast(0)
        val sortedRatios = fillRatios.sortedDescending()
        val secondMaxFill = sortedRatios.getOrElse(1) { 0.0 }
        val maxToMeanRatio = maxFill / meanFill
        val maxToSecondRatio = if (secondMaxFill > 0.01) maxFill / secondMaxFill else 99.0

        val states = fillRatios.map { value ->
            when {
                value > meanFill * HIGH_MEAN_MULTIPLIER -> BubbleState.FILLED
                value > meanFill * MID_MEAN_MULTIPLIER -> BubbleState.MARKED
                else -> BubbleState.EMPTY
            }
        }

        if (maxToMeanRatio < MEAN_EMPTY_RATIO_MAX) {
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

        if (
            maxToSecondRatio >= VALID_SECOND_RATIO_MIN &&
            maxFill > meanFill * VALID_MEAN_MULTIPLIER
        ) {
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

        val aboveMeanCount = fillRatios.count { it > meanFill * ABOVE_MEAN_MULTIPLIER }
        if (aboveMeanCount >= 2) {
            val highMarks = fillRatios.indices.filter { fillRatios[it] > meanFill * HIGH_MEAN_MULTIPLIER }
            val midMarks = fillRatios.indices.filter {
                fillRatios[it] > meanFill * MID_MEAN_MULTIPLIER &&
                    fillRatios[it] <= meanFill * HIGH_MEAN_MULTIPLIER
            }

            if (highMarks.size == 1 && midMarks.isEmpty()) {
                return DetectionDecision(
                    questionResult = QuestionResult(
                        questionNumber = questionNumber,
                        bubbleStates = states,
                        selectedAnswer = highMarks[0],
                        fillRatios = fillRatios,
                        isValid = true
                    ),
                    status = "VALID"
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

        if (maxFill > meanFill * ABOVE_MEAN_MULTIPLIER) {
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
                isValid = true
            ),
            status = "EMPTY"
        )
    }

    private fun drawDebugOverlay(
        debugMat: Mat,
        template: FormTemplate,
        questions: List<QuestionDebugInfo>
    ) {
        for (question in questions) {
            val color = statusColor(question.status)

            question.positions.forEach { pos ->
                val center = Point(pos.centerX.toDouble(), pos.centerY.toDouble())
                Imgproc.circle(debugMat, center, pos.radius, color, 2)

                val ratio = question.ratios.getOrElse(pos.col) { 0.0 }
                Imgproc.putText(
                    debugMat,
                    String.format(Locale.US, "%.2f", ratio),
                    Point(center.x - 14.0, center.y + pos.radius + 12.0),
                    Imgproc.FONT_HERSHEY_SIMPLEX,
                    0.30,
                    color,
                    1
                )
            }

            question.positions.minByOrNull { it.col }?.let { first ->
                Imgproc.putText(
                    debugMat,
                    "Q${question.questionNumber}",
                    Point((first.centerX - 40).toDouble(), (first.centerY + 5).toDouble()),
                    Imgproc.FONT_HERSHEY_SIMPLEX,
                    0.40,
                    Scalar(255.0, 255.0, 0.0),
                    1
                )
            }
        }

        drawOcrBox(debugMat, template.resolveNameRegion(debugMat.cols(), debugMat.rows()), "NAME")
        drawOcrBox(debugMat, template.resolveNumberRegion(debugMat.cols(), debugMat.rows()), "NUMBER")
        drawOcrBox(debugMat, template.resolveClassRegion(debugMat.cols(), debugMat.rows()), "CLASS")
    }

    private fun drawOcrBox(debugMat: Mat, rect: android.graphics.Rect, label: String) {
        Imgproc.rectangle(
            debugMat,
            Point(rect.left.toDouble(), rect.top.toDouble()),
            Point(rect.right.toDouble(), rect.bottom.toDouble()),
            Scalar(255.0, 0.0, 0.0),
            2
        )

        val textY = (rect.top - 6).coerceAtLeast(14)
        Imgproc.putText(
            debugMat,
            label,
            Point(rect.left.toDouble(), textY.toDouble()),
            Imgproc.FONT_HERSHEY_SIMPLEX,
            0.45,
            Scalar(255.0, 0.0, 0.0),
            1
        )
    }

    private fun statusColor(status: String): Scalar {
        return when (status) {
            "VALID", "HEAVY_MARK" -> Scalar(0.0, 255.0, 0.0)
            "EMPTY" -> Scalar(0.0, 0.0, 255.0)
            else -> Scalar(0.0, 255.0, 255.0)
        }
    }

    private fun saveDebugImage(debugMat: Mat): String? {
        return try {
            val debugBitmap = Bitmap.createBitmap(debugMat.cols(), debugMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(debugMat, debugBitmap)

            val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                ?: context.filesDir
            if (!dir.exists()) {
                dir.mkdirs()
            }

            val file = File(dir, "omr_debug_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                debugBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            if (!debugBitmap.isRecycled) {
                debugBitmap.recycle()
            }

            Log.d(TAG, "Debug image saved: ${file.absolutePath}")
            file.absolutePath
        } catch (t: Throwable) {
            Log.e(TAG, "Debug image save failed", t)
            null
        }
    }

    private fun saveThresholdDebugImage(binary: Mat): String? {
        return try {
            val rgba = Mat()
            Imgproc.cvtColor(binary, rgba, Imgproc.COLOR_GRAY2RGBA)

            val debugBitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, debugBitmap)

            val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                ?: context.filesDir
            if (!dir.exists()) {
                dir.mkdirs()
            }

            val file = File(dir, "omr_threshold_debug_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                debugBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            if (!debugBitmap.isRecycled) {
                debugBitmap.recycle()
            }

            rgba.release()
            Log.d(TAG, "Threshold debug saved: ${file.absolutePath}")
            file.absolutePath
        } catch (t: Throwable) {
            Log.e(TAG, "Threshold debug save failed", t)
            null
        }
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

    private data class QuestionDebugInfo(
        val questionNumber: Int,
        val status: String,
        val ratios: List<Double>,
        val positions: List<BubblePosition>
    )

    data class BubbleDetectionOutput(
        val results: List<QuestionResult>,
        val debugImagePath: String?,
        val thresholdDebugImagePath: String?,
        val questionLogs: List<String>
    )
}
