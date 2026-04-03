package com.omrreader.domain.usecase

import android.graphics.Bitmap
import android.graphics.Rect
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
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.imgproc.Imgproc
import javax.inject.Inject

class ProcessOMRUseCase @Inject constructor(
    private val perspectiveCorrector: PerspectiveCorrector,
    private val bubbleDetector: BubbleDetector,
    private val qrProcessor: QRProcessor,
    private val ocrProcessor: OCRProcessor,
    private val scoringEngine: ScoringEngine,
    private val gson: Gson
) {
    suspend fun process(
        bitmap: Bitmap,
        expectedExam: Exam? = null,
        fallbackAnswerKey: OMRAnswerKeyResponse? = null
    ): ProcessResult = withContext(Dispatchers.Default) {
        try {
            val template = FormTemplate.DEFAULT
            val corrected = perspectiveCorrector.correct(bitmap, template)
                ?: return@withContext ProcessResult.Error("Kağıt algılanamadı. Lütfen düz bir yüzeyde, iyi aydınlatmada tekrar çekin.")

            val correctedWidth = corrected.width
            val correctedHeight = corrected.height

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

            val ocrRegions = detectOcrRegions(corrected, template)
            val nameResult = ocrProcessor.recognize(
                corrected,
                ocrRegions.nameRegion,
                "name"
            )
            val numberResult = ocrProcessor.recognize(
                corrected,
                ocrRegions.numberRegion,
                "number"
            )
            val classResult = ocrProcessor.recognize(
                corrected,
                ocrRegions.classRegion,
                "class"
            )

            val omrResults = bubbleDetector.detect(
                corrected,
                template.resolveGrids(correctedWidth, correctedHeight, subjectOverrides)
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

        return fallbackOptionCount?.coerceIn(2, 8) ?: 5
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

    private fun detectOcrRegions(bitmap: Bitmap, template: FormTemplate): OcrRegions {
        val fallback = OcrRegions(
            nameRegion = template.resolveNameRegion(bitmap.width, bitmap.height),
            numberRegion = template.resolveNumberRegion(bitmap.width, bitmap.height),
            classRegion = template.resolveClassRegion(bitmap.width, bitmap.height)
        )

        return try {
            val source = Mat()
            Utils.bitmapToMat(bitmap, source)

            val gray = Mat()
            if (source.channels() == 4) {
                Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
            } else {
                Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
            }

            val binary = Mat()
            Imgproc.adaptiveThreshold(
                gray,
                binary,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                15,
                4.0
            )

            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(binary.clone(), contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            val widthThreshold = source.cols() * 0.4
            val minHeight = (source.rows() * 0.02).toInt().coerceAtLeast(28)
            val maxHeight = (source.rows() * 0.16).toInt().coerceAtLeast(minHeight + 1)
            val yThreshold = source.rows() * 0.35

            val rawBoxes = contours.mapNotNull { contour ->
                val rect = Imgproc.boundingRect(contour)
                if (rect.width < widthThreshold) return@mapNotNull null
                if (rect.height !in minHeight..maxHeight) return@mapNotNull null
                if (rect.y >= yThreshold) return@mapNotNull null
                rect
            }

            val deduped = rawBoxes
                .sortedByDescending { it.width * it.height }
                .fold(mutableListOf<org.opencv.core.Rect>()) { acc, rect ->
                    val duplicate = acc.any {
                        kotlin.math.abs(it.y - rect.y) < (rect.height * 0.5) &&
                            kotlin.math.abs(it.x - rect.x) < (rect.width * 0.2)
                    }
                    if (!duplicate) acc.add(rect)
                    acc
                }
                .sortedBy { it.y }
                .take(3)

            if (deduped.size < 3) return fallback

            OcrRegions(
                nameRegion = cropBoxInterior(deduped[0], bitmap.width, bitmap.height),
                numberRegion = cropBoxInterior(deduped[1], bitmap.width, bitmap.height),
                classRegion = cropBoxInterior(deduped[2], bitmap.width, bitmap.height)
            )
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun cropBoxInterior(
        box: org.opencv.core.Rect,
        maxWidth: Int,
        maxHeight: Int,
        margin: Int = 8
    ): Rect {
        val marginX = maxOf(margin, (box.width * 0.03).toInt())
        val marginTop = maxOf(margin + 8, (box.height * 0.25).toInt())
        val marginBottom = maxOf(margin, (box.height * 0.10).toInt())

        val left = (box.x + marginX).coerceIn(0, maxWidth - 1)
        val top = (box.y + marginTop).coerceIn(0, maxHeight - 1)
        val right = (box.x + box.width - marginX).coerceIn(left + 1, maxWidth)
        val bottom = (box.y + box.height - marginBottom).coerceIn(top + 1, maxHeight)

        return Rect(left, top, right, bottom)
    }

    private data class OcrRegions(
        val nameRegion: Rect,
        val numberRegion: Rect,
        val classRegion: Rect
    )
}
