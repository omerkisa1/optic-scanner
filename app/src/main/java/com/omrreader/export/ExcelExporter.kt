package com.omrreader.export

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.omrreader.domain.model.Exam
import com.omrreader.domain.model.StudentResult
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExcelExporter @Inject constructor() {

    companion object {
        private const val TAG = "ExcelExporter"
    }

    fun exportToExcel(context: Context, exam: Exam, results: List<StudentResult>): File? {
        return try {
            val workbook = HSSFWorkbook()
            val resultSheet = workbook.createSheet("Sonuçlar")
            val analysisSheet = workbook.createSheet("Soru Analizi")

            writeResultsSheet(resultSheet, results)
            writeQuestionAnalysisSheet(analysisSheet, exam, results)

            // autoSizeColumn is not reliable on Android (AWT/font dependencies).
            applyColumnWidths(resultSheet, analysisSheet)

            val dir = File(context.cacheDir, "exports")
            if (!dir.exists()) dir.mkdirs()
            
            val fileName = "${exam.name.replace(" ", "_").take(30)}_Sonuclar.xls"
            val file = File(dir, fileName)
            
            FileOutputStream(file).use { out ->
                workbook.write(out)
            }
            workbook.close()
            
            file
        } catch (t: Throwable) {
            Log.e(TAG, "Excel export failed", t)
            null
        }
    }

    private fun applyColumnWidths(
        resultSheet: org.apache.poi.ss.usermodel.Sheet,
        analysisSheet: org.apache.poi.ss.usermodel.Sheet
    ) {
        val resultWidths = intArrayOf(1800, 4500, 7000, 3800, 2200, 2200, 2200, 2600)
        for (i in resultWidths.indices) {
            resultSheet.setColumnWidth(i, resultWidths[i])
        }

        analysisSheet.setColumnWidth(0, 2200)
        analysisSheet.setColumnWidth(1, 3200)
        analysisSheet.setColumnWidth(2, 2600)
        for (i in 3..10) {
            analysisSheet.setColumnWidth(i, 2200)
        }
    }

    private fun writeResultsSheet(
        sheet: org.apache.poi.ss.usermodel.Sheet,
        results: List<StudentResult>
    ) {
        val sorted = results.sortedByDescending { it.totalScore }

        val headerRow = sheet.createRow(0)
        val headers = listOf("#", "Öğrenci No", "Ad Soyad", "Sınıf", "Doğru", "Yanlış", "Boş", "Puan")
        headers.forEachIndexed { i, header ->
            headerRow.createCell(i).setCellValue(header)
        }

        sorted.forEachIndexed { index, result ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue((index + 1).toDouble())
            row.createCell(1).setCellValue(result.studentNumber ?: "-")
            row.createCell(2).setCellValue(result.studentName ?: "-")
            row.createCell(3).setCellValue(result.className ?: "-")
            row.createCell(4).setCellValue(result.correctCount.toDouble())
            row.createCell(5).setCellValue(result.wrongCount.toDouble())
            row.createCell(6).setCellValue(result.emptyCount.toDouble())
            row.createCell(7).setCellValue(result.totalScore)
        }

        val scores = sorted.map { it.totalScore }
        val average = scores.average().takeIf { !it.isNaN() } ?: 0.0
        val highest = scores.maxOrNull() ?: 0.0
        val lowest = scores.minOrNull() ?: 0.0
        val stdDev = kotlin.math.sqrt(scores.map { (it - average) * (it - average) }.average().takeIf { !it.isNaN() } ?: 0.0)

        val base = sorted.size + 3
        val rows = listOf(
            "Sınıf Ortalaması" to average,
            "En Yüksek" to highest,
            "En Düşük" to lowest,
            "Standart Sapma" to stdDev
        )

        rows.forEachIndexed { index, pair ->
            val row = sheet.createRow(base + index)
            row.createCell(0).setCellValue(pair.first)
            row.createCell(1).setCellValue((pair.second * 100).toInt() / 100.0)
        }
    }

    private fun writeQuestionAnalysisSheet(
        sheet: org.apache.poi.ss.usermodel.Sheet,
        exam: Exam,
        results: List<StudentResult>
    ) {
        val totalStudents = results.size.coerceAtLeast(1)
        val totalQuestions = (exam.subjectCount * exam.questionsPerSubject).coerceAtLeast(1)
        val correctAnswers = parseCorrectAnswersFromQr(exam.qrData)
        val answerMaps = results.map { parseAnswerMap(it.answersJson) }
        val optionCount = resolveOptionCount(exam.optionCount, correctAnswers, answerMaps)

        val headerRow = sheet.createRow(0)
        val headers = mutableListOf("Soru #", "Doğru Cevap", "Doğru %")
        for (option in 0 until optionCount) {
            headers.add("${('A'.code + option).toChar()} %")
        }
        headers.add("Boş %")

        headers.forEachIndexed { index, value ->
            headerRow.createCell(index).setCellValue(value)
        }

        for (question in 1..totalQuestions) {
            val row = sheet.createRow(question)
            val markedAnswers = answerMaps.map { map -> map[question] }
            val correctAnswer = correctAnswers.getOrNull(question - 1)

            row.createCell(0).setCellValue(question.toDouble())
            row.createCell(1).setCellValue(if (correctAnswer == null) "-" else answerLabel(correctAnswer))

            val correctCount = if (correctAnswer == null) 0 else markedAnswers.count { it == correctAnswer }
            row.createCell(2).setCellValue(toPercent(correctCount, totalStudents))

            for (option in 0 until optionCount) {
                val optionCountValue = markedAnswers.count { it == option }
                row.createCell(3 + option).setCellValue(toPercent(optionCountValue, totalStudents))
            }

            val emptyCount = markedAnswers.count { it == null }
            row.createCell(3 + optionCount).setCellValue(toPercent(emptyCount, totalStudents))
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

    private fun toPercent(value: Int, total: Int): Double {
        val ratio = if (total == 0) 0.0 else (value.toDouble() / total.toDouble()) * 100.0
        return (ratio * 100).toInt() / 100.0
    }

    private fun answerLabel(answer: Int): String {
        return ('A'.code + answer).toChar().toString()
    }
}
