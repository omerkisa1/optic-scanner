package com.omrreader.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import com.google.gson.Gson
import com.omrreader.domain.model.Exam
import com.omrreader.domain.model.OMRAnswerKeyResponse
import com.omrreader.domain.model.ProcessResult
import com.omrreader.domain.model.QuestionResult
import com.omrreader.domain.model.SubjectInfo
import com.omrreader.processing.BubbleDetector
import com.omrreader.processing.FormTemplate
import com.omrreader.processing.GridOverride
import com.omrreader.processing.ResolvedGridRegion
import com.omrreader.processing.OCRProcessor
import com.omrreader.processing.PerspectiveCorrector
import com.omrreader.processing.QRProcessor
import com.omrreader.scoring.ScoringEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min

class ProcessOMRUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
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

            val pageMat = Mat()
            Utils.bitmapToMat(corrected, pageMat)
            val adjustedGrids = resolvedGrids.map { grid ->
                val actualBounds = bubbleDetector.findGridBounds(pageMat, grid.region)
                val dx = actualBounds.left - grid.region.left
                val dy = actualBounds.top - grid.region.top
                if (abs(dx) > 3 || abs(dy) > 3) {
                    Log.d(TAG, "Grid offset applied: dx=$dx dy=$dy")
                    grid.withOffset(dx, dy)
                } else {
                    grid
                }
            }
            pageMat.release()

            val bubbleOutput = bubbleDetector.detectWithDebug(corrected, template, adjustedGrids)
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

            val correctedOverlayPath = createPaperEvaluationOverlay(
                bitmap = corrected,
                grids = adjustedGrids,
                omrResults = normalizedOmrResults,
                correctAnswers = correctAnswers
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
                correctedImagePath = correctedOverlayPath,
                debugImagePath = bubbleOutput.debugImagePath,
                thresholdDebugImagePath = bubbleOutput.thresholdDebugImagePath,
                omrDebugLines = bubbleOutput.questionLogs
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

    private fun createPaperEvaluationOverlay(
        bitmap: Bitmap,
        grids: List<ResolvedGridRegion>,
        omrResults: List<QuestionResult>,
        correctAnswers: List<Int>
    ): String? {
        return createPaperEvaluationOverlayWithInfo(bitmap, grids, omrResults, correctAnswers, null, null)
    }

    fun createGradedOverlay(
        bitmap: Bitmap,
        grids: List<ResolvedGridRegion>,
        omrResults: List<QuestionResult>,
        correctAnswers: List<Int>,
        studentName: String?,
        scoreInfo: String?
    ): String? {
        return createPaperEvaluationOverlayWithInfo(bitmap, grids, omrResults, correctAnswers, studentName, scoreInfo)
    }

    private fun createPaperEvaluationOverlayWithInfo(
        bitmap: Bitmap,
        grids: List<ResolvedGridRegion>,
        omrResults: List<QuestionResult>,
        correctAnswers: List<Int>,
        studentName: String?,
        scoreInfo: String?
    ): String? {
        return try {
            val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutable)

            val goodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(46, 125, 50)
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            val badPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(198, 40, 40)
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(249, 168, 37)
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 20f
                style = Paint.Style.FILL
            }

            if (!studentName.isNullOrBlank() || !scoreInfo.isNullOrBlank()) {
                val barHeight = 50f
                val barPaint = Paint().apply {
                    color = Color.argb(220, 0, 0, 0)
                    style = Paint.Style.FILL
                }
                canvas.drawRect(0f, 0f, mutable.width.toFloat(), barHeight, barPaint)

                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 22f
                    style = Paint.Style.FILL
                }
                val displayText = buildString {
                    if (!studentName.isNullOrBlank()) append(studentName)
                    if (!scoreInfo.isNullOrBlank()) {
                        if (isNotEmpty()) append(" | ")
                        append(scoreInfo)
                    }
                }
                canvas.drawText(displayText, 10f, 35f, textPaint)
            }

            var questionIndex = 0
            for (grid in grids) {
                val radius = (min(grid.cellWidth, grid.cellHeight) * 0.32f).coerceAtLeast(8f)
                for (row in 0 until grid.rows) {
                    if (questionIndex >= omrResults.size || questionIndex >= correctAnswers.size) break

                    val selected = omrResults[questionIndex].selectedAnswer
                    val correct = correctAnswers[questionIndex]
                    val centerY = grid.region.top + ((row + 0.5f) * grid.cellHeight)

                    if (selected != null && selected in 0 until grid.cols) {
                        val centerX = grid.region.left + ((selected + 0.5f) * grid.cellWidth)
                        val paint = if (selected == correct) goodPaint else badPaint
                        canvas.drawCircle(centerX, centerY, radius, paint)

                        if (selected != correct) {
                            canvas.drawLine(
                                centerX - radius,
                                centerY - radius,
                                centerX + radius,
                                centerY + radius,
                                badPaint
                            )
                            canvas.drawLine(
                                centerX + radius,
                                centerY - radius,
                                centerX - radius,
                                centerY + radius,
                                badPaint
                            )
                        }
                    }

                    if (correct in 0 until grid.cols) {
                        val correctCenterX = grid.region.left + ((correct + 0.5f) * grid.cellWidth)
                        val isWrongOrEmpty = selected == null || selected != correct
                        if (isWrongOrEmpty) {
                            canvas.drawCircle(correctCenterX, centerY, radius, goodPaint)
                        }
                    }

                    val symbol = when {
                        selected == null -> "○"
                        selected == correct -> "✓"
                        else -> "✗"
                    }
                    val symbolPaintUse = when (symbol) {
                        "✓" -> goodPaint
                        "✗" -> badPaint
                        else -> warnPaint
                    }

                    val leftMarkerX = (grid.region.left - 24).toFloat().coerceAtLeast(6f)
                    canvas.drawCircle(leftMarkerX, centerY, 8f, symbolPaintUse)
                    canvas.drawText(symbol, leftMarkerX + 12f, centerY + 7f, symbolPaint)

                    questionIndex++
                }
            }

            val dir = File(context.cacheDir, "exports")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, "omr_overlay_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                mutable.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (!mutable.isRecycled) {
                mutable.recycle()
            }
            file.absolutePath
        } catch (t: Throwable) {
            Log.e(TAG, "Paper overlay save failed", t)
            null
        }
    }
}
