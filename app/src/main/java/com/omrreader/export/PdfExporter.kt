package com.omrreader.export

import android.content.Context
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.TextAlignment
import com.omrreader.domain.model.Exam
import com.omrreader.domain.model.StudentResult
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfExporter @Inject constructor() {

    fun exportToPdf(context: Context, exam: Exam, results: List<StudentResult>): File? {
        return try {
            val dir = File(context.cacheDir, "exports")
            if (!dir.exists()) dir.mkdirs()

            val fileName = "${exam.name.replace(" ", "_").take(30)}_Rapor.pdf"
            val file = File(dir, fileName)

            val writer = PdfWriter(file.absolutePath)
            val pdf = PdfDocument(writer)
            val document = Document(pdf)

            // Title
            document.add(Paragraph("Sınav Raporu: ${exam.name}").setTextAlignment(TextAlignment.CENTER).setFontSize(20f))
            
            val average = results.map { it.totalScore }.average().takeIf { !it.isNaN() } ?: 0.0
            val max = results.maxOfOrNull { it.totalScore } ?: 0.0
            val min = results.minOfOrNull { it.totalScore } ?: 0.0

            document.add(Paragraph("\nGenel İstatistikler").setBold())
            document.add(Paragraph("Toplam Öğrenci: ${results.size}"))
            document.add(Paragraph("Ortalama Puan: ${String.format("%.2f", average)}"))
            document.add(Paragraph("En Yüksek Puan: $max"))
            document.add(Paragraph("En Düşük Puan: $min\n\n"))

            // Table
            val widths = floatArrayOf(1f, 3f, 5f, 2f, 2f, 2f, 2f, 3f)
            val table = Table(widths)
            table.addHeaderCell("#")
            table.addHeaderCell("Öğrenci No")
            table.addHeaderCell("Ad Soyad")
            table.addHeaderCell("Sınıf")
            table.addHeaderCell("D")
            table.addHeaderCell("Y")
            table.addHeaderCell("B")
            table.addHeaderCell("Puan")

            results.sortedByDescending { it.totalScore }.forEachIndexed { index, result ->
                table.addCell((index + 1).toString())
                table.addCell(result.studentNumber ?: "-")
                table.addCell(result.studentName ?: "-")
                table.addCell(result.className ?: "-")
                table.addCell(result.correctCount.toString())
                table.addCell(result.wrongCount.toString())
                table.addCell(result.emptyCount.toString())
                table.addCell(result.totalScore.toString())
            }

            document.add(table)
            document.close()

            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
