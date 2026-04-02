package com.omrreader.processing

import android.graphics.Bitmap
import com.omrreader.domain.model.BubbleState
import com.omrreader.domain.model.QuestionResult
import com.omrreader.domain.model.ThresholdConfig
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BubbleDetector @Inject constructor() {
    private val config = ThresholdConfig()

    fun detect(bitmap: Bitmap, subjects: List<SubjectGrid>): List<QuestionResult> {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
        
        val binary = Mat()
        Imgproc.adaptiveThreshold(
            gray, binary, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 11, 2.0
        )

        val results = mutableListOf<QuestionResult>()

        for (subject in subjects) {
            val startX = subject.gridRegion.left
            val startY = subject.gridRegion.top

            for (row in 0 until subject.rows) {
                val bubbleStates = mutableListOf<BubbleState>()
                val fillRatios = mutableListOf<Double>()

                for (col in 0 until subject.cols) {
                    val bx = startX + (col * subject.horizontalGap) + ((subject.horizontalGap - subject.bubbleWidth) / 2)
                    val by = startY + (row * subject.verticalGap) + ((subject.verticalGap - subject.bubbleHeight) / 2)
                    val bw = subject.bubbleWidth
                    val bh = subject.bubbleHeight

                    if (bx < 0 || by < 0 || bx + bw > binary.cols() || by + bh > binary.rows()) {
                        fillRatios.add(0.0)
                        bubbleStates.add(BubbleState.EMPTY)
                        continue
                    }

                    val roi = Rect(bx, by, bw, bh)
                    val bubbleMat = Mat(binary, roi)

                    val mask = createCircleMask(bw, bh)
                    val maskedBubble = Mat()
                    Core.bitwise_and(bubbleMat, bubbleMat, maskedBubble, mask)

                    val filledPixels = Core.countNonZero(maskedBubble)
                    val totalPixels = Core.countNonZero(mask).coerceAtLeast(1)
                    val ratio = filledPixels.toDouble() / totalPixels.toDouble()
                    fillRatios.add(ratio)

                    val state = when {
                        ratio > config.filledMin -> BubbleState.FILLED
                        ratio > config.markedMin -> BubbleState.MARKED
                        ratio > (config.markedMin - config.ambiguousRange) -> BubbleState.AMBIGUOUS
                        else -> BubbleState.EMPTY
                    }
                    bubbleStates.add(state)
                }

                val (selectedAnswer, isValid) = determineAnswer(bubbleStates)

                results.add(
                    QuestionResult(
                        questionNumber = row + 1,
                        bubbleStates = bubbleStates,
                        selectedAnswer = selectedAnswer,
                        fillRatios = fillRatios,
                        isValid = isValid
                    )
                )
            }
        }

        return results
    }

    private fun determineAnswer(states: List<BubbleState>): Pair<Int?, Boolean> {
        val marked = states.indices.filter { states[it] == BubbleState.MARKED }
        val ambiguous = states.indices.filter { states[it] == BubbleState.AMBIGUOUS }

        return when {
            marked.size == 1 -> marked.first() to true
            marked.isEmpty() && ambiguous.size == 1 -> ambiguous.first() to true
            marked.isEmpty() -> null to true
            else -> null to false
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
