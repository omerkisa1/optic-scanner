package com.omrreader.domain.usecase

import android.graphics.Bitmap
import com.google.gson.Gson
import com.omrreader.domain.model.OMRAnswerKeyResponse
import com.omrreader.domain.model.ProcessResult
import com.omrreader.processing.BubbleDetector
import com.omrreader.processing.FormTemplate
import com.omrreader.processing.OCRProcessor
import com.omrreader.processing.PerspectiveCorrector
import com.omrreader.processing.QRProcessor
import com.omrreader.scoring.ScoringEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ProcessOMRUseCase @Inject constructor(
    private val perspectiveCorrector: PerspectiveCorrector,
    private val bubbleDetector: BubbleDetector,
    private val qrProcessor: QRProcessor,
    private val ocrProcessor: OCRProcessor,
    private val scoringEngine: ScoringEngine,
    private val gson: Gson
) {
    suspend fun process(bitmap: Bitmap): ProcessResult = withContext(Dispatchers.Default) {
        try {
            // 1. Perspective Correction
            val corrected = perspectiveCorrector.correct(bitmap)
                ?: return@withContext ProcessResult.Error("Kağıt algılanamadı. Lütfen düz bir zeminde tekrar çekin.")

            val template = FormTemplate.DEFAULT

            // 2. Decode QR Code
            val qrData = qrProcessor.decode(corrected, template.qrRegion)
                ?: return@withContext ProcessResult.Error("QR kod okunamadı. Lütfen kamera açısını ayarlayıp tekrar deneyin.")

            val answerKey = try {
                gson.fromJson(qrData, OMRAnswerKeyResponse::class.java)
            } catch (e: Exception) {
                return@withContext ProcessResult.Error("QR kod formatı geçersiz.")
            }

            // 3. OCR (Name, Number, Class)
            val nameResult = ocrProcessor.recognize(corrected, template.nameRegion, "name")
            val numberResult = ocrProcessor.recognize(corrected, template.numberRegion, "number")
            val classResult = ocrProcessor.recognize(corrected, template.classRegion, "class")

            // 4. Optical Mark Recognition (Bubble Detect)
            val omrResults = bubbleDetector.detect(corrected, template.subjects)

            // Normalize Correct Answers and Weights for ScoringEngine
            val correctAnswers = mutableListOf<Int>()
            val weights = mutableListOf<Double>()
            
            // Assume subjects are ordered as in template
            answerKey.subjects.forEach { subject ->
                correctAnswers.addAll(subject.answers)
                weights.addAll(subject.weights)
            }

            val studentAnswers = omrResults.map { it.selectedAnswer }

            // 5. Calculate Score
            val scoreResult = scoringEngine.calculateScore(
                studentAnswers = studentAnswers,
                correctAnswers = correctAnswers,
                weights = weights
            )

            // 6. Return Data
            ProcessResult.Success(
                studentName = nameResult.processedText,
                studentNumber = numberResult.processedText,
                className = classResult.processedText,
                omrResults = omrResults,
                scoreResult = scoreResult,
                answerKey = answerKey,
                correctedImagePath = null // Can save to disk if needed
            )
        } catch (e: Exception) {
            e.printStackTrace()
            ProcessResult.Error("İşlem sırasında beklenmedik bir hata oluştu: ${e.message}")
        }
    }
}
