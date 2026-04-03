package com.omrreader.domain.usecase

import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.omrreader.domain.model.Exam
import com.omrreader.domain.model.OMRAnswerKeyResponse
import com.omrreader.domain.model.ProcessResult
import com.omrreader.domain.model.SubjectInfo
import com.omrreader.processing.BubbleDetector
import com.omrreader.processing.FormTemplate
import com.omrreader.processing.GridOverride
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
    companion object {
        private const val TAG = "OMR"
    }

    suspend fun process(
        bitmap: Bitmap,
        expectedExam: Exam? = null,
        fallbackAnswerKey: OMRAnswerKeyResponse? = null
    ): ProcessResult = withContext(Dispatchers.Default) {
        try {
            val template = FormTemplate.DEFAULT
            val correctedRaw = perspectiveCorrector.correct(bitmap, template)
                ?: return@withContext ProcessResult.Error("Kağıt algılanamadı. Lütfen düz bir yüzeyde, iyi aydınlatmada tekrar çekin.")

            val corrected = normalizePageSize(correctedRaw, template)
            val correctedWidth = corrected.width
            val correctedHeight = corrected.height

            Log.d(TAG, "=== DEBUG INFO ===")
            Log.d(TAG, "Warped image size: ${correctedRaw.width}x${correctedRaw.height}")
            Log.d(TAG, "Normalized size: ${correctedWidth}x${correctedHeight}")

            val qrAnswerKey = template.resolveQrRegion(correctedWidth, correctedHeight)?.let { qrRegion ->
                val qrData = qrProcessor.decode(corrected, qrRegion) ?: return@let null
                try {
                    gson.fromJson(qrData, OMRAnswerKeyResponse::class.java)
                } catch (_: Exception) {
                    null
                }
            }

            val answerKey = qrAnswerKey ?: fallbackAnswerKey
                ?: return@withContext ProcessResult.Error(
                    if (template.qrRegion == null) {
                        "Bu formda QR alanı yok ve manuel cevap anahtarı bulunamadı."
                    } else {
                        "QR kod okunamadı ve manuel cevap anahtarı bulunamadı."
                    }
                )

            if (answerKey.subjects.isEmpty()) {
                return@withContext ProcessResult.Error("Cevap anahtarında soru bulunamadı.")
            }

            if (answerKey.subjects.size > template.grids.size) {
                return@withContext ProcessResult.Error("Bu form şablonu en fazla ${template.grids.size} ders destekliyor.")
            }

            val subjectOverrides = answerKey.subjects.map { subject ->
                GridOverride(
                    rows = subject.answers.size.coerceAtLeast(1),
                    cols = deriveOptionCount(subject, expectedExam?.optionCount)
                )
            }

            val nameRegion = template.resolveNameRegion(correctedWidth, correctedHeight)
            val numberRegion = template.resolveNumberRegion(correctedWidth, correctedHeight)
            val classRegion = template.resolveClassRegion(correctedWidth, correctedHeight)

            val nameResult = ocrProcessor.recognize(
                corrected,
                nameRegion,
                "name"
            )
            val numberResult = ocrProcessor.recognize(
                corrected,
                numberRegion,
                "number"
            )
            val classResult = ocrProcessor.recognize(
                corrected,
                classRegion,
                "class"
            )

            val resolvedGrids = template.resolveGrids(correctedWidth, correctedHeight, subjectOverrides)
            val bubbleOutput = bubbleDetector.detectWithDebug(corrected, template, resolvedGrids)
            val omrResults = bubbleOutput.results

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
                ocrConfidence = mapOf(
                    "name" to nameResult.confidence,
                    "number" to numberResult.confidence,
                    "class" to classResult.confidence
                ),
                ocrCandidates = mapOf(
                    "name" to nameResult.allCandidates,
                    "number" to numberResult.allCandidates,
                    "class" to classResult.allCandidates
                ),
                omrResults = normalizedOmrResults,
                scoreResult = scoreResult,
                answerKey = answerKey,
                correctedImagePath = null,
                debugImagePath = bubbleOutput.debugImagePath
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

        return fallbackOptionCount?.coerceIn(2, 8) ?: 5
    }

    private fun normalizePageSize(bitmap: Bitmap, template: FormTemplate): Bitmap {
        if (bitmap.width == template.normalizedWidth && bitmap.height == template.normalizedHeight) {
            return bitmap
        }

        return Bitmap.createScaledBitmap(
            bitmap,
            template.normalizedWidth,
            template.normalizedHeight,
            true
        )
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
