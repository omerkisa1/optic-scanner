package com.omrreader.export

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
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

            document.add(Paragraph("Öğrenci Listesi").setBold())
            document.add(studentTable)

            document.add(AreaBreak(AreaBreakType.NEXT_PAGE))
            document.add(Paragraph("Soru Analizi").setBold())

            val totalStudents = sortedResults.size.coerceAtLeast(1)
            val totalQuestions = (exam.subjectCount * exam.questionsPerSubject).coerceAtLeast(1)
            val correctAnswers = parseCorrectAnswersFromQr(exam.qrData)
            val answerMaps = sortedResults.map { parseAnswerMap(it.answersJson) }
            val optionCount = resolveOptionCount(exam.optionCount, correctAnswers, answerMaps)

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
            rows.associate { row ->
                val question = (row["q"] as? Number)?.toInt() ?: 0
                val marked = (row["marked"] as? Number)?.toInt()
                question to marked
            }
        } catch (_: Exception) {
            emptyMap()
        }
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

    private fun answerLabel(answer: Int): String {
        return ('A'.code + answer).toChar().toString()
    }
}
