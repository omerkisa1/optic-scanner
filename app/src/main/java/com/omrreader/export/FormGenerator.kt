package com.omrreader.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.omrreader.processing.FormFormat
import com.omrreader.processing.FormTemplate
import com.omrreader.processing.GridOverride
import com.omrreader.processing.ResolvedGridRegion
import com.omrreader.qr.QRGenerator
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

data class FormSubjectLayout(
    val name: String,
    val questionCount: Int,
    val optionCount: Int
)

@Singleton
class FormGenerator @Inject constructor(
    private val qrGenerator: QRGenerator
) {

    fun generateTemplatePdf(
        context: Context,
        examName: String,
        subjects: List<FormSubjectLayout>,
        qrData: String? = null,
        template: FormTemplate = FormTemplate.DEFAULT,
        formFormat: FormFormat = FormFormat.CLASSIC_BORDERED
    ): File? {
        return try {
            val outDir = File(context.cacheDir, "exports")
            if (!outDir.exists()) outDir.mkdirs()

            val fileName = "${sanitizeFileName(examName).take(32)}_Optik_Form.pdf"
            val outputFile = File(outDir, fileName)

            val pageWidth = template.normalizedWidth
            val pageHeight = template.normalizedHeight

            val document = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = document.startPage(pageInfo)

            drawFormPage(
                canvas = page.canvas,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                examName = examName,
                subjects = subjects,
                qrData = qrData,
                template = template,
                formFormat = formFormat
            )

            document.finishPage(page)
            outputFile.outputStream().use { stream ->
                document.writeTo(stream)
            }
            document.close()
            outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun drawFormPage(
        canvas: Canvas,
        pageWidth: Int,
        pageHeight: Int,
        examName: String,
        subjects: List<FormSubjectLayout>,
        qrData: String?,
        template: FormTemplate,
        formFormat: FormFormat
    ) {
        canvas.drawColor(Color.WHITE)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 20)
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val sectionTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 45, 45)
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 30, 30)
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val optionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 30, 30)
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 35, 35)
            strokeWidth = 1.7f
            style = Paint.Style.STROKE
        }
        val sectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(245, 245, 245)
            style = Paint.Style.FILL
        }
        val fieldFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val fieldGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(205, 205, 205)
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 35, 35)
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(70, 70, 70)
            strokeWidth = 1.0f
            style = Paint.Style.STROKE
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 12f
        }

        val safeExamName = examName.ifBlank { "OMR SINAVI" }
        canvas.drawText(safeExamName.take(64), pageWidth / 2f, 42f, titlePaint)
        canvas.drawLine(80f, 58f, (pageWidth - 80).toFloat(), 58f, borderPaint)

        drawMarkers(canvas, template, markerPaint)

        val nameRect = template.resolveNameRegion(pageWidth, pageHeight)
        val numberRect = template.resolveNumberRegion(pageWidth, pageHeight)
        val classRect = template.resolveClassRegion(pageWidth, pageHeight)
        drawIdentitySection(
            canvas = canvas,
            nameRect = nameRect,
            numberRect = numberRect,
            classRect = classRect,
            sectionTitlePaint = sectionTitlePaint,
            fieldLabelPaint = labelPaint,
            sectionFillPaint = sectionFillPaint,
            borderPaint = borderPaint,
            fieldFillPaint = fieldFillPaint,
            fieldGuidePaint = fieldGuidePaint
        )

        val effectiveSubjects = subjects
            .ifEmpty { listOf(FormSubjectLayout("DERS 1", 20, 5)) }
            .take(template.grids.size)

        val overrides = effectiveSubjects.map { subject ->
            GridOverride(
                rows = subject.questionCount.coerceAtLeast(1),
                cols = subject.optionCount.coerceIn(2, 8)
            )
        }

        val resolvedGrids = template.resolveGrids(pageWidth, pageHeight, overrides)
        resolvedGrids.forEachIndexed { index, grid ->
            val subjectName = effectiveSubjects.getOrNull(index)?.name?.ifBlank { "DERS ${index + 1}" }
                ?: "DERS ${index + 1}"
            val questionOffset = resolvedGrids.take(index).sumOf { it.rows }
            when (formFormat) {
                FormFormat.COMPACT_ZIPGRADE -> drawGridCompact(
                    canvas = canvas,
                    grid = grid,
                    subjectName = subjectName,
                    questionOffset = questionOffset,
                    labelPaint = labelPaint,
                    optionPaint = optionPaint,
                    bubblePaint = bubblePaint
                )
                else -> drawGrid(
                    canvas = canvas,
                    grid = grid,
                    subjectName = subjectName,
                    labelPaint = labelPaint,
                    optionPaint = optionPaint,
                    linePaint = linePaint,
                    bubblePaint = bubblePaint
                )
            }
        }

        drawQrIfNeeded(canvas, template, pageWidth, pageHeight, qrData, borderPaint, labelPaint)

        val footerY = (template.normalizedHeight - template.markerMargin - template.markerSize - 10).toFloat()

        canvas.drawText(
            "Her soruda yalniz bir secenegi koyu sekilde isaretleyin.",
            40f,
            footerY,
            footerPaint
        )
    }

    private fun drawMarkers(canvas: Canvas, template: FormTemplate, markerPaint: Paint) {
        val markerSize = template.markerSize.toFloat()
        val margin = template.markerMargin.toFloat()
        val right = template.normalizedWidth.toFloat() - margin - markerSize
        val bottom = template.normalizedHeight.toFloat() - margin - markerSize

        canvas.drawRect(margin, margin, margin + markerSize, margin + markerSize, markerPaint)
        canvas.drawRect(right, margin, right + markerSize, margin + markerSize, markerPaint)
        canvas.drawRect(right, bottom, right + markerSize, bottom + markerSize, markerPaint)
        canvas.drawRect(margin, bottom, margin + markerSize, bottom + markerSize, markerPaint)
    }

    private fun drawIdentitySection(
        canvas: Canvas,
        nameRect: Rect,
        numberRect: Rect,
        classRect: Rect,
        sectionTitlePaint: Paint,
        fieldLabelPaint: Paint,
        sectionFillPaint: Paint,
        borderPaint: Paint,
        fieldFillPaint: Paint,
        fieldGuidePaint: Paint
    ) {
        val left = minOf(nameRect.left, numberRect.left, classRect.left)
        val top = minOf(nameRect.top, numberRect.top, classRect.top)
        val right = maxOf(nameRect.right, numberRect.right, classRect.right)
        val bottom = maxOf(nameRect.bottom, numberRect.bottom, classRect.bottom)

        val sectionRect = RectF(
            (left - 12).toFloat(),
            (top - 18).toFloat(),
            (right + 12).toFloat(),
            (bottom + 12).toFloat()
        )
        canvas.drawRoundRect(sectionRect, 10f, 10f, sectionFillPaint)
        canvas.drawRoundRect(sectionRect, 10f, 10f, borderPaint)
        canvas.drawText("Kimlik Bilgileri", sectionRect.left + 12f, sectionRect.top + 14f, sectionTitlePaint)

        drawField(canvas, nameRect, "Ad Soyad", fieldLabelPaint, borderPaint, fieldFillPaint, fieldGuidePaint)
        drawField(canvas, numberRect, "Ogrenci No", fieldLabelPaint, borderPaint, fieldFillPaint, fieldGuidePaint)
        drawField(canvas, classRect, "Sinif", fieldLabelPaint, borderPaint, fieldFillPaint, fieldGuidePaint)
    }

    private fun drawField(
        canvas: Canvas,
        rect: Rect,
        label: String,
        labelPaint: Paint,
        borderPaint: Paint,
        fieldFillPaint: Paint,
        fieldGuidePaint: Paint
    ) {
        val fieldRect = RectF(rect)
        canvas.drawRoundRect(fieldRect, 7f, 7f, fieldFillPaint)
        canvas.drawRoundRect(fieldRect, 7f, 7f, borderPaint)

        val labelY = fieldRect.top + 18f
        canvas.drawText(label, fieldRect.left + 10f, labelY, labelPaint)

        val guideY = (fieldRect.top + 24f).coerceAtMost(fieldRect.bottom - 8f)
        canvas.drawLine(fieldRect.left + 10f, guideY, fieldRect.right - 10f, guideY, fieldGuidePaint)
    }

    private fun drawGrid(
        canvas: Canvas,
        grid: ResolvedGridRegion,
        subjectName: String,
        labelPaint: Paint,
        optionPaint: Paint,
        linePaint: Paint,
        bubblePaint: Paint
    ) {
        val left = grid.region.left.toFloat()
        val top = grid.region.top.toFloat()
        val right = grid.region.right.toFloat()
        val bottom = grid.region.bottom.toFloat()

        canvas.drawRect(left, top, right, bottom, linePaint)

        val titleY = (top - 10f).coerceAtLeast(14f)
        canvas.drawText(subjectName.take(24), left, titleY, labelPaint)

        val optionLabelY = (top - 4f).coerceAtLeast(20f)
        for (col in 0 until grid.cols) {
            val x = left + col * grid.cellWidth
            if (col > 0) {
                canvas.drawLine(x, top, x, bottom, linePaint)
            }

            val label = ('A'.code + col).toChar().toString()
            val cx = x + (grid.cellWidth / 2f)
            canvas.drawText(label, cx, optionLabelY, optionPaint)
        }

        val rowNumberPaint = Paint(labelPaint).apply {
            textAlign = Paint.Align.RIGHT
            textSize = 12f
        }

        for (row in 0 until grid.rows) {
            val y = top + row * grid.cellHeight
            if (row > 0) {
                canvas.drawLine(left, y, right, y, linePaint)
            }

            val rowLabel = (row + 1).toString()
            val labelY = y + (grid.cellHeight / 2f) + 4f
            canvas.drawText(rowLabel, left - 6f, labelY, rowNumberPaint)

            for (col in 0 until grid.cols) {
                val cellLeft = left + col * grid.cellWidth
                val centerX = cellLeft + grid.cellWidth / 2f
                val centerY = y + grid.cellHeight / 2f
                val radius = (min(grid.cellWidth, grid.cellHeight) * 0.23f).coerceAtLeast(4f)
                canvas.drawCircle(centerX, centerY, radius, bubblePaint)
            }
        }
    }

    private fun drawGridCompact(
        canvas: Canvas,
        grid: ResolvedGridRegion,
        subjectName: String,
        questionOffset: Int,
        labelPaint: Paint,
        optionPaint: Paint,
        bubblePaint: Paint
    ) {
        val left = grid.region.left.toFloat()
        val top = grid.region.top.toFloat()

        val titleY = (top - 10f).coerceAtLeast(14f)
        canvas.drawText(subjectName.take(24), left, titleY, labelPaint)

        val optionLabelY = (top - 4f).coerceAtLeast(20f)
        for (col in 0 until grid.cols) {
            val x = left + col * grid.cellWidth
            val label = ('A'.code + col).toChar().toString()
            val cx = x + (grid.cellWidth / 2f)
            canvas.drawText(label, cx, optionLabelY, optionPaint)
        }

        val rowNumberPaint = Paint(labelPaint).apply {
            textAlign = Paint.Align.RIGHT
            textSize = 12f
        }

        for (row in 0 until grid.rows) {
            val y = top + row * grid.cellHeight

            val rowLabel = (questionOffset + row + 1).toString()
            val labelY = y + (grid.cellHeight / 2f) + 4f
            canvas.drawText(rowLabel, left - 6f, labelY, rowNumberPaint)

            for (col in 0 until grid.cols) {
                val cellLeft = left + col * grid.cellWidth
                val centerX = cellLeft + grid.cellWidth / 2f
                val centerY = y + grid.cellHeight / 2f
                val radius = (min(grid.cellWidth, grid.cellHeight) * 0.23f).coerceAtLeast(4f)
                canvas.drawCircle(centerX, centerY, radius, bubblePaint)
            }
        }
    }

    private fun drawQrIfNeeded(
        canvas: Canvas,
        template: FormTemplate,
        pageWidth: Int,
        pageHeight: Int,
        qrData: String?,
        borderPaint: Paint,
        labelPaint: Paint
    ) {
        val qrRegion = template.resolveQrRegion(pageWidth, pageHeight) ?: return
        if (qrData.isNullOrBlank()) return

        val side = min(qrRegion.width(), qrRegion.height()).coerceAtLeast(64)
        val qrBitmap = qrGenerator.generateQR(qrData, side) ?: return

        val drawRect = Rect(
            qrRegion.left,
            qrRegion.top,
            qrRegion.left + side,
            qrRegion.top + side
        )

        canvas.drawBitmap(qrBitmap, null, drawRect, null)
        canvas.drawRect(RectF(drawRect), borderPaint)
        val labelY = (drawRect.top - 6f).coerceAtLeast(14f)
        canvas.drawText("QR", drawRect.left.toFloat(), labelY, labelPaint)
    }

    private fun sanitizeFileName(raw: String): String {
        val trimmed = raw.trim().ifBlank { "Sinav" }
        val normalized = trimmed.replace(Regex("[^a-zA-Z0-9_-]+"), "_")
        return normalized.trim('_').ifBlank { "Sinav" }
    }
}
