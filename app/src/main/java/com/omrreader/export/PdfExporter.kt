package com.omrreader.export

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.AreaBreakType
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

            val sortedResults = results.sortedByDescending { it.totalScore }
            val scores = sortedResults.map { it.totalScore }
            val average = scores.average().takeIf { !it.isNaN() } ?: 0.0
            val max = scores.maxOrNull() ?: 0.0
            val min = scores.minOrNull() ?: 0.0
            val stdDev = kotlin.math.sqrt(scores.map { (it - average) * (it - average) }.average().takeIf { !it.isNaN() } ?: 0.0)
            val correctAnswers = parseCorrectAnswersFromQr(exam.qrData)
            val answerMaps = sortedResults.map { parseAnswerMap(it.answersJson) }
            val totalQuestions = resolveTotalQuestionCount(exam, correctAnswers, answerMaps)
            val optionCount = resolveOptionCount(exam.optionCount, correctAnswers, answerMaps)

            document.add(Paragraph("Sınav Raporu: ${exam.name}").setTextAlignment(TextAlignment.CENTER).setFontSize(20f))
            document.add(Paragraph("Tarih: ${java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("tr", "TR")).format(java.util.Date())}"))
            document.add(Paragraph("Toplam Öğrenci: ${sortedResults.size}"))
            document.add(Paragraph("Ortalama Puan: ${String.format("%.2f", average)}"))
            document.add(Paragraph("En Yüksek Puan: ${String.format("%.2f", max)}"))
            document.add(Paragraph("En Düşük Puan: ${String.format("%.2f", min)}"))
            document.add(Paragraph("Standart Sapma: ${String.format("%.2f", stdDev)}"))

            document.add(AreaBreak(AreaBreakType.NEXT_PAGE))

            val studentTable = Table(floatArrayOf(1f, 3f, 5f, 2f, 2f, 2f, 2f, 3f))
            studentTable.addHeaderCell("#")
            studentTable.addHeaderCell("Öğrenci No")
            studentTable.addHeaderCell("Ad Soyad")
            studentTable.addHeaderCell("Sınıf")
            studentTable.addHeaderCell("Doğru")
            studentTable.addHeaderCell("Yanlış")
            studentTable.addHeaderCell("Boş")
            studentTable.addHeaderCell("Puan")

            sortedResults.forEachIndexed { index, result ->
                studentTable.addCell((index + 1).toString())
                studentTable.addCell(result.studentNumber ?: "-")
                studentTable.addCell(result.studentName ?: "-")
                studentTable.addCell(result.className ?: "-")
                studentTable.addCell(result.correctCount.toString())
                studentTable.addCell(result.wrongCount.toString())
                studentTable.addCell(result.emptyCount.toString())
                studentTable.addCell(String.format("%.2f", result.totalScore))
            }

            document.add(Paragraph("Sınıf Listesi").setBold())
            document.add(studentTable)

            document.add(AreaBreak(AreaBreakType.NEXT_PAGE))
            document.add(Paragraph("Soru Analizi").setBold())

            val totalStudents = sortedResults.size.coerceAtLeast(1)

            val analysisWidths = FloatArray(optionCount + 4) { 1f }
            val analysisTable = Table(analysisWidths)
            analysisTable.addHeaderCell("Soru #")
            analysisTable.addHeaderCell("Doğru")
            analysisTable.addHeaderCell("Doğru %")
            for (option in 0 until optionCount) {
                analysisTable.addHeaderCell("${('A'.code + option).toChar()} %")
            }
            analysisTable.addHeaderCell("Boş %")

            for (question in 1..totalQuestions) {
                val marked = answerMaps.map { it[question] }
                val correct = correctAnswers.getOrNull(question - 1)
                val correctCount = if (correct == null) 0 else marked.count { it == correct }

                analysisTable.addCell(question.toString())
                analysisTable.addCell(if (correct == null) "-" else answerLabel(correct))
                analysisTable.addCell("${percent(correctCount, totalStudents)}")

                for (option in 0 until optionCount) {
                    val count = marked.count { it == option }
                    analysisTable.addCell("${percent(count, totalStudents)}")
                }

                val emptyCount = marked.count { it == null }
                analysisTable.addCell("${percent(emptyCount, totalStudents)}")
            }

            document.add(analysisTable)

            document.add(AreaBreak(AreaBreakType.NEXT_PAGE))
            document.add(Paragraph("Detay").setBold())

            val chunkSize = 20
            var chunkStart = 1
            while (chunkStart <= totalQuestions) {
                val chunkEnd = minOf(totalQuestions, chunkStart + chunkSize - 1)
                if (chunkStart > 1) {
                    document.add(AreaBreak(AreaBreakType.NEXT_PAGE))
                }

                document.add(Paragraph("Soru $chunkStart - $chunkEnd").setBold())

                val questionCount = chunkEnd - chunkStart + 1
                val detailWidths = FloatArray(questionCount + 4) { 1f }
                detailWidths[0] = 1.2f
                detailWidths[1] = 2.5f
                detailWidths[2] = 4.5f
                detailWidths[3] = 2f
                val detailTable = Table(detailWidths)

                detailTable.addHeaderCell("#")
                detailTable.addHeaderCell("Öğrenci No")
                detailTable.addHeaderCell("Ad Soyad")
                detailTable.addHeaderCell("Puan")
                for (question in chunkStart..chunkEnd) {
                    detailTable.addHeaderCell("S$question")
                }

                detailTable.addCell("")
                detailTable.addCell("")
                detailTable.addCell("Doğru Cevap")
                detailTable.addCell("")
                for (question in chunkStart..chunkEnd) {
                    val correct = correctAnswers.getOrNull(question - 1)
                    detailTable.addCell(correct?.let { answerLabel(it) } ?: "-")
                }

                sortedResults.forEachIndexed { index, result ->
                    val answerMap = answerMaps.getOrNull(index).orEmpty()

                    detailTable.addCell((index + 1).toString())
                    detailTable.addCell(result.studentNumber ?: "-")
                    detailTable.addCell(result.studentName ?: "-")
                    detailTable.addCell(String.format("%.2f", result.totalScore))

                    for (question in chunkStart..chunkEnd) {
                        val marked = answerMap[question]
                        val correct = correctAnswers.getOrNull(question - 1)
                        detailTable.addCell(createDetailStatusCell(marked, correct))
                    }
                }

                document.add(detailTable)
                chunkStart += chunkSize
            }

            document.close()

            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseAnswerMap(answersJson: String): Map<Int, Int?> {
        return try {
            val listType = object : TypeToken<List<Map<String, Any?>>>() {}.type
            val rows: List<Map<String, Any?>> = Gson().fromJson(answersJson, listType)
            rows.mapNotNull { row ->
                val question = (row["q"] as? Number)?.toInt() ?: 0
                val marked = (row["marked"] as? Number)?.toInt()
                if (question <= 0) null else question to marked
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun resolveTotalQuestionCount(
        exam: Exam,
        correctAnswers: List<Int>,
        answerMaps: List<Map<Int, Int?>>
    ): Int {
        val fromExam = exam.subjectCount * exam.questionsPerSubject
        val fromCorrectAnswers = correctAnswers.size
        val fromMarkedAnswers = answerMaps.maxOfOrNull { map ->
            map.keys.maxOrNull() ?: 0
        } ?: 0

        return maxOf(fromExam, fromCorrectAnswers, fromMarkedAnswers).coerceAtLeast(1)
    }

    private fun parseCorrectAnswersFromQr(qrData: String?): List<Int> {
        if (qrData.isNullOrBlank()) return emptyList()
        return try {
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            val root: Map<String, Any?> = Gson().fromJson(qrData, mapType)
            val subjects = root["subjects"] as? List<*> ?: return emptyList()
            subjects.flatMap { subject ->
                val subjectMap = subject as? Map<*, *> ?: return@flatMap emptyList()
                val answers = subjectMap["answers"] as? List<*> ?: return@flatMap emptyList()
                answers.mapNotNull { (it as? Number)?.toInt() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun resolveOptionCount(
        examOptionCount: Int,
        correctAnswers: List<Int>,
        answerMaps: List<Map<Int, Int?>>
    ): Int {
        val fromCorrectAnswers = (correctAnswers.maxOrNull() ?: -1) + 1
        val fromMarkedAnswers = answerMaps
            .flatMap { it.values }
            .mapNotNull { it }
            .maxOrNull()
            ?.plus(1)
            ?: 0

        val inferred = maxOf(fromCorrectAnswers, fromMarkedAnswers).coerceIn(0, 8)
        val normalizedExamCount = examOptionCount.coerceIn(2, 8)
        return maxOf(5, normalizedExamCount, inferred).coerceIn(2, 8)
    }

    private fun percent(value: Int, total: Int): String {
        val ratio = if (total == 0) 0.0 else (value.toDouble() / total.toDouble()) * 100.0
        return "${String.format("%.2f", ratio)}%"
    }

    private fun createDetailStatusCell(marked: Int?, correct: Int?): Cell {
        val cell = Cell().setTextAlignment(TextAlignment.CENTER)
        when {
            marked == null -> {
                cell.add(Paragraph("-"))
                cell.setBackgroundColor(DeviceRgb(230, 230, 230))
            }

            correct == null -> {
                cell.add(Paragraph(answerLabel(marked)))
                cell.setBackgroundColor(DeviceRgb(255, 243, 205))
            }

            marked == correct -> {
                cell.add(Paragraph(answerLabel(marked)))
                cell.setBackgroundColor(DeviceRgb(212, 237, 218))
            }

            else -> {
                cell.add(Paragraph(answerLabel(marked)))
                cell.setBackgroundColor(DeviceRgb(248, 215, 218))
            }
        }
        return cell
    }

    private fun answerLabel(answer: Int): String {
        return ('A'.code + answer).toChar().toString()
    }
}
