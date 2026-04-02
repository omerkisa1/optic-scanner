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
            val corrected = perspectiveCorrector.correct(bitmap)
                ?: return@withContext ProcessResult.Error("Kağıt algılanamadı. Lütfen düz bir yüzeyde, iyi aydınlatmada tekrar çekin.")

            val template = FormTemplate.DEFAULT
            val correctedWidth = corrected.width
            val correctedHeight = corrected.height

            val qrRegion = template.resolveQrRegion(correctedWidth, correctedHeight)
            val qrData = qrProcessor.decode(corrected, qrRegion)
                ?: return@withContext ProcessResult.Error("QR kod okunamadı. QR kodun görünür ve net olduğundan emin olun.")

            val answerKey = try {
                gson.fromJson(qrData, OMRAnswerKeyResponse::class.java)
            } catch (e: Exception) {
                return@withContext ProcessResult.Error("QR kod formatı geçersiz.")
            }

            val nameResult = ocrProcessor.recognize(
                corrected,
                template.resolveNameRegion(correctedWidth, correctedHeight),
                "name"
            )
            val numberResult = ocrProcessor.recognize(
                corrected,
                template.resolveNumberRegion(correctedWidth, correctedHeight),
                "number"
            )
            val classResult = ocrProcessor.recognize(
                corrected,
                template.resolveClassRegion(correctedWidth, correctedHeight),
                "class"
            )

            val omrResults = bubbleDetector.detect(
                corrected,
                template.resolveSubjects(correctedWidth, correctedHeight)
            )

            val correctAnswers = mutableListOf<Int>()
            val weights = mutableListOf<Double>()
            
            answerKey.subjects.forEach { subject ->
                correctAnswers.addAll(subject.answers)
                weights.addAll(subject.weights)
            }

            val studentAnswers = omrResults.map { it.selectedAnswer }

            val scoreResult = scoringEngine.calculateScore(
                studentAnswers = studentAnswers,
                correctAnswers = correctAnswers,
                weights = weights
            )

            ProcessResult.Success(
                studentName = nameResult.processedText,
                studentNumber = numberResult.processedText,
                className = classResult.processedText,
                omrResults = omrResults,
                scoreResult = scoreResult,
                answerKey = answerKey,
                correctedImagePath = null
            )
        } catch (e: Exception) {
            e.printStackTrace()
            ProcessResult.Error("İşlem sırasında beklenmedik bir hata oluştu: ${e.message}")
        }
    }
}
