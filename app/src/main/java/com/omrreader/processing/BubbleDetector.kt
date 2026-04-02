package com.omrreader.processing

import android.graphics.Bitmap
import com.omrreader.domain.model.BubbleState
import com.omrreader.domain.model.QuestionResult
import com.omrreader.domain.model.ThresholdConfig
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
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

        // Convert to grayscale & binarize using adaptive threshold
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
                    val bx = startX + (col * subject.horizontalGap)
                    val by = startY + (row * subject.verticalGap)
                    val bw = subject.bubbleWidth
                    val bh = subject.bubbleHeight

                    // Prevent out of bounds
                    if (bx + bw > binary.cols() || by + bh > binary.rows()) {
                        fillRatios.add(0.0)
                        bubbleStates.add(BubbleState.EMPTY)
                        continue
                    }

                    val roi = Rect(bx, by, bw, bh)
                    val bubbleMat = Mat(binary, roi)

                    // Provide circle mask to ignore square bounding box corners
                    val mask = Mat.zeros(bubbleMat.size(), org.opencv.core.CvType.CV_8U)
                    Imgproc.circle(mask, Point(bw / 2.0, bh / 2.0), bw / 2, org.opencv.core.Scalar(255.0), -1)

                    val filledPixels = org.opencv.core.Core.countNonZero(
                        // Bitwise AND to keep only circular Region
                        bubbleMat
                    )
                    
                    val totalPixels = Math.PI * (bw / 2.0) * (bh / 2.0)
                    val ratio = filledPixels / totalPixels
                    fillRatios.add(ratio)

                    val state = when {
                        ratio > config.filledMin -> BubbleState.FILLED // crossed out or fully dark
                        ratio >= config.markedMin -> BubbleState.MARKED // valid mark
                        else -> BubbleState.EMPTY
                    }
                    bubbleStates.add(state)
                }

                // Decide final answer and validity
                val tamKarali = bubbleStates.mapIndexedNotNull { index, state -> if (state == BubbleState.FILLED) index else null }
                val isaretli = bubbleStates.mapIndexedNotNull { index, state -> if (state == BubbleState.MARKED) index else null }

                val selectedAnswer: Int?
                val isValid: Boolean

                if (isaretli.size == 1) {
                    selectedAnswer = isaretli[0]
                    isValid = true
                } else if (isaretli.isEmpty()) {
                    selectedAnswer = null
                    isValid = true
                } else {
                    selectedAnswer = null
                    isValid = false // Multi-mark
                }

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
}
