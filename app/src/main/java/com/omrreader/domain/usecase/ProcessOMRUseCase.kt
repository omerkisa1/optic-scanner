package com.omrreader.domain.usecase

import android.graphics.Bitmap
import com.google.gson.Gson
import com.omrreader.domain.model.Exam
import com.omrreader.domain.model.OMRAnswerKeyResponse
import com.omrreader.domain.model.ProcessResult
import com.omrreader.domain.model.SubjectInfo
import com.omrreader.processing.BubbleDetector
import com.omrreader.processing.FormTemplate
import com.omrreader.processing.OCRProcessor
import com.omrreader.processing.PerspectiveCorrector
import com.omrreader.processing.QRProcessor
import com.omrreader.processing.SubjectGridOverride
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
    suspend fun process(bitmap: Bitmap, expectedExam: Exam? = null): ProcessResult = withContext(Dispatchers.Default) {
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

            if (answerKey.subjects.isEmpty()) {
                return@withContext ProcessResult.Error("QR içinde cevap anahtarı bulunamadı.")
            }

            if (answerKey.subjects.size > template.subjects.size) {
                return@withContext ProcessResult.Error("Bu form şablonu en fazla ${template.subjects.size} ders destekliyor.")
            }

            val subjectOverrides = answerKey.subjects.mapIndexed { index, subject ->
                SubjectGridOverride(
                    name = subject.name.ifBlank { "DERS ${index + 1}" },
                    rows = subject.answers.size.coerceAtLeast(1),
                    cols = deriveOptionCount(subject, expectedExam?.optionCount)
                )
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
                template.resolveSubjects(correctedWidth, correctedHeight, subjectOverrides)
            )

            val correctAnswers = mutableListOf<Int>()
            val weights = mutableListOf<Double>()
            
            answerKey.subjects.forEach { subject ->
                correctAnswers.addAll(subject.answers)
                weights.addAll(subject.weights)
            }

            val expectedQuestionCount = correctAnswers.size
            if (expectedQuestionCount == 0) {
                return@withContext ProcessResult.Error("QR cevap anahtarında soru bulunamadı.")
            }

            if (omrResults.size < expectedQuestionCount) {
                return@withContext ProcessResult.Error("Optik formdaki tüm sorular okunamadı. Formu daha net ve hizalı çekin.")
            }

            val normalizedOmrResults = omrResults
                .take(expectedQuestionCount)
                .mapIndexed { index, result ->
                    if (result.questionNumber == index + 1) {
                        result
                    } else {
                        result.copy(questionNumber = index + 1)
                    }
                }

            val studentAnswers = normalizedOmrResults.map { it.selectedAnswer }
            val normalizedWeights = normalizeWeights(weights, expectedQuestionCount)

            val scoreResult = scoringEngine.calculateScore(
                studentAnswers = studentAnswers,
                correctAnswers = correctAnswers,
                weights = normalizedWeights
            )

            ProcessResult.Success(
                studentName = nameResult.processedText,
                studentNumber = numberResult.processedText,
                className = classResult.processedText,
                omrResults = normalizedOmrResults,
                scoreResult = scoreResult,
                answerKey = answerKey,
                correctedImagePath = null
            )
        } catch (e: Exception) {
            e.printStackTrace()
            ProcessResult.Error("İşlem sırasında beklenmedik bir hata oluştu: ${e.message}")
        }
    }

    private fun deriveOptionCount(subject: SubjectInfo, fallbackOptionCount: Int?): Int {
        val fromPayload = subject.optionCount?.takeIf { it in 2..8 }
        if (fromPayload != null) return fromPayload

        val inferred = (subject.answers.maxOrNull() ?: -1) + 1
        if (inferred in 2..8) return inferred

        return fallbackOptionCount?.coerceIn(2, 8) ?: 4
    }

    private fun normalizeWeights(weights: List<Double>, totalQuestions: Int): List<Double> {
        if (totalQuestions <= 0) return emptyList()

        val validWeights = weights.filter { it > 0.0 }
        if (validWeights.size == totalQuestions) {
            return validWeights
        }

        val defaultWeight = 100.0 / totalQuestions
        val normalized = MutableList(totalQuestions) { defaultWeight }
        for (index in 0 until minOf(totalQuestions, validWeights.size)) {
            normalized[index] = validWeights[index]
        }
        return normalized
    }
}
