package com.omrreader.processing

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import com.omrreader.models.OMRResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OMRPipeline @Inject constructor(
    private val documentDetector: DocumentDetector,
    private val omrEngine: OMREngine,
    private val ocrProcessor: OCRProcessor
) {
    /**
     * Executes the strict 8-step OMR processing pipeline.
     */
    suspend fun processImage(bitmap: Bitmap): OMRResult {
        // Step 1: Image Capture is handled upstream (CameraX)

        // Step 2: Preprocessing
        val preprocessedMat = documentDetector.preprocess(bitmap)

        // Step 3: Document Detection
        val contour = documentDetector.findDocumentContour(preprocessedMat)
            ?: throw Exception("Document not found in image")

        // Step 4: Perspective Correction
        val warpedMat = documentDetector.correctPerspective(preprocessedMat, contour)

        // Step 5: Template Alignment (Fixed Templates)
        // Here we define fixed regions mapped to a normalized 1000x1000 coordinate system.
        // Step 6: Region Segmentation
        val nameRegionMat = omrEngine.cropRegion(warpedMat, "name")
        val studentNumberRegionMat = omrEngine.cropRegion(warpedMat, "studentNo")
        val bubbleGridRegionMat = omrEngine.cropRegion(warpedMat, "answers")

        // Step 7: OMR Engine (Bubble Reading)
        val answers = omrEngine.readAnswers(bubbleGridRegionMat)

    // Step 8: OCR Engine (Text Decoding) & Step 9: Merge
    val nameResult = ocrProcessor.recognize(matToBitmap(nameRegionMat), android.graphics.Rect(0, 0, nameRegionMat.cols(), nameRegionMat.rows()), "name")
    val studentNoResult = ocrProcessor.recognize(matToBitmap(studentNumberRegionMat), android.graphics.Rect(0, 0, studentNumberRegionMat.cols(), studentNumberRegionMat.rows()), "number")

        // Step 10: Validation / Merge Details
        if (nameResult.confidence < 0.6f && nameResult.processedText.isNotEmpty()) {
            throw Exception("Name OCR check failed due to low confidence")
        }
        if (studentNoResult.confidence < 0.6f && studentNoResult.processedText.isNotEmpty()) {
            throw Exception("Student number OCR check failed due to low confidence")
        }

        // Check if any answers have multiple marks or are empty
        answers.forEach { (question, answer) ->
            if (answer == "Multiple Marks" || answer == "Empty") {
                // Optionally throw or log, assuming JSON output includes it correctly.
            }
        }

        // Return output JSON-like format
        return OMRResult(
            name = nameResult.processedText,
            studentNo = studentNoResult.processedText,
            answers = answers
        )
    }

    private fun matToBitmap(mat: Mat): Bitmap {
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bitmap)
        return bitmap
    }
}
