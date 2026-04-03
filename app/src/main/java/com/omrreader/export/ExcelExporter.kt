package com.omrreader.export

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.omrreader.data.db.dao.ClassExamResultView
import com.omrreader.data.db.entity.AnswerKeyEntity
import com.omrreader.data.db.entity.ClassroomEntity

import com.omrreader.domain.model.Exam
import com.omrreader.domain.model.StudentResult
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
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
            val resultSheet = workbook.createSheet("Sınıf Listesi")
            val analysisSheet = workbook.createSheet("Soru Analizi")
            val detailSheet = workbook.createSheet("Detay")

            val sortedResults = results.sortedByDescending { it.totalScore }
            val correctAnswers = parseCorrectAnswersFromQr(exam.qrData)
            val answerMaps = sortedResults.map { parseAnswerMap(it.answersJson) }
            val totalQuestions = resolveTotalQuestionCount(exam, correctAnswers, answerMaps)

            writeResultsSheet(resultSheet, sortedResults)
            writeQuestionAnalysisSheet(
                sheet = analysisSheet,
                exam = exam,
                sortedResults = sortedResults,
                totalQuestions = totalQuestions,
                correctAnswers = correctAnswers,
                answerMaps = answerMaps
            )
            writeDetailSheet(
                workbook = workbook,
                sheet = detailSheet,
                sortedResults = sortedResults,
                totalQuestions = totalQuestions,
                correctAnswers = correctAnswers,
                answerMaps = answerMaps
            )

            // autoSizeColumn is not reliable on Android (AWT/font dependencies).
            applyColumnWidths(resultSheet, analysisSheet, detailSheet, totalQuestions)

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
        analysisSheet: org.apache.poi.ss.usermodel.Sheet,
        detailSheet: org.apache.poi.ss.usermodel.Sheet,
        totalQuestions: Int
    ) {
        val resultWidths = intArrayOf(1800, 4500, 7000, 3800, 2200, 2200, 2200, 2600)
        for (i in resultWidths.indices) {
            resultSheet.setColumnWidth(i, resultWidths[i])
        }

        analysisSheet.setColumnWidth(0, 2200)
        analysisSheet.setColumnWidth(1, 3200)
        analysisSheet.setColumnWidth(2, 2600)
        val analysisLast = (analysisSheet.getRow(0)?.lastCellNum?.toInt() ?: 0).coerceAtLeast(4)
        for (i in 3 until analysisLast) {
            analysisSheet.setColumnWidth(i, 2200)
        }

        val detailWidths = intArrayOf(1800, 4200, 7000, 3200, 2600)
        for (i in detailWidths.indices) {
            detailSheet.setColumnWidth(i, detailWidths[i])
        }
        for (q in 0 until totalQuestions) {
            detailSheet.setColumnWidth(5 + q, 1150)
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
        sortedResults: List<StudentResult>,
        totalQuestions: Int,
        correctAnswers: List<Int>,
        answerMaps: List<Map<Int, Int?>>
    ) {
        val totalStudents = sortedResults.size.coerceAtLeast(1)
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

    private fun writeDetailSheet(
        workbook: HSSFWorkbook,
        sheet: org.apache.poi.ss.usermodel.Sheet,
        sortedResults: List<StudentResult>,
        totalQuestions: Int,
        correctAnswers: List<Int>,
        answerMaps: List<Map<Int, Int?>>
    ) {
        val correctStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.LIGHT_GREEN.index
            fillPattern = FillPatternType.SOLID_FOREGROUND.code
        }
        val wrongStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.ROSE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND.code
        }
        val emptyStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.LIGHT_YELLOW.index
            fillPattern = FillPatternType.SOLID_FOREGROUND.code
        }

        val headerRow = sheet.createRow(0)
        val fixedHeaders = listOf("#", "Öğrenci No", "Ad Soyad", "Sınıf", "Puan")
        fixedHeaders.forEachIndexed { index, header ->
            headerRow.createCell(index).setCellValue(header)
        }
        for (question in 1..totalQuestions) {
            headerRow.createCell(4 + question).setCellValue("S$question")
        }

        val answerKeyRow = sheet.createRow(1)
        answerKeyRow.createCell(3).setCellValue("Doğru Cevap")
        for (question in 1..totalQuestions) {
            val correct = correctAnswers.getOrNull(question - 1)
            answerKeyRow.createCell(4 + question)
                .setCellValue(correct?.let { answerLabel(it) } ?: "-")
        }

        sortedResults.forEachIndexed { index, result ->
            val row = sheet.createRow(index + 2)
            row.createCell(0).setCellValue((index + 1).toDouble())
            row.createCell(1).setCellValue(result.studentNumber ?: "-")
            row.createCell(2).setCellValue(result.studentName ?: "-")
            row.createCell(3).setCellValue(result.className ?: "-")
            row.createCell(4).setCellValue(result.totalScore)

            val answerMap = answerMaps.getOrNull(index).orEmpty()
            for (question in 1..totalQuestions) {
                val marked = answerMap[question]
                val correct = correctAnswers.getOrNull(question - 1)
                val cell = row.createCell(4 + question)

                when {
                    marked == null -> {
                        cell.setCellValue("-")
                        cell.cellStyle = emptyStyle
                    }

                    correct == null -> {
                        cell.setCellValue(answerLabel(marked))
                        cell.cellStyle = emptyStyle
                    }

                    marked == correct -> {
                        cell.setCellValue(answerLabel(marked))
                        cell.cellStyle = correctStyle
                    }

                    else -> {
                        cell.setCellValue(answerLabel(marked))
                        cell.cellStyle = wrongStyle
                    }
                }
            }
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

    fun parseCorrectAnswersFromQr(qrData: String?): List<Int> {
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

    fun exportClassroomReport(
        context: Context,
        classroom: ClassroomEntity,
        results: List<ClassExamResultView>,
        correctAnswers: List<Int>
    ): File? {
        return try {
            val workbook = HSSFWorkbook()
            val questionCount = correctAnswers.size.coerceAtLeast(
                results.maxOfOrNull { parseClassAnswerMap(it.answersJson).keys.maxOrNull() ?: 0 } ?: 0
            )

            val correctStyle = workbook.createCellStyle().apply {
                fillForegroundColor = IndexedColors.LIGHT_GREEN.index
                fillPattern = FillPatternType.SOLID_FOREGROUND.code
            }
            val wrongStyle = workbook.createCellStyle().apply {
                fillForegroundColor = IndexedColors.ROSE.index
                fillPattern = FillPatternType.SOLID_FOREGROUND.code
            }
            val emptyStyle = workbook.createCellStyle().apply {
                fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
                fillPattern = FillPatternType.SOLID_FOREGROUND.code
            }
            val boldFont = workbook.createFont().apply { bold = true }
            val boldStyle = workbook.createCellStyle().apply { setFont(boldFont) }

            val sheet = workbook.createSheet("Sınıf Raporu")
            var rowIdx = 0

            val infoLabels = listOf(
                "Ders Adı:" to classroom.courseName,
                "Öğrenim Yılı:" to classroom.gradeLevel,
                "Öğrenim Türü:" to classroom.educationType,
                "Şube:" to classroom.section
            )
            for ((label, value) in infoLabels) {
                val row = sheet.createRow(rowIdx++)
                row.createCell(0).apply { setCellValue(label); setCellStyle(boldStyle) }
                row.createCell(1).setCellValue(value)
            }
            rowIdx++

            val headerRow = sheet.createRow(rowIdx++)
            val headers = mutableListOf("#", "Öğrenci No", "Ad Soyad")
            for (q in 1..questionCount) headers.add("S$q")
            headers.addAll(listOf("Doğru", "Yanlış", "Boş", "Puan"))
            headers.forEachIndexed { i, title ->
                headerRow.createCell(i).apply { setCellValue(title); setCellStyle(boldStyle) }
            }

            val sorted = results.sortedByDescending { it.totalScore }
            val allScores = mutableListOf<Double>()
            val questionCorrect = IntArray(questionCount)
            val questionWrong = IntArray(questionCount)
            val questionEmpty = IntArray(questionCount)

            sorted.forEachIndexed { index, result ->
                val row = sheet.createRow(rowIdx++)
                row.createCell(0).setCellValue((index + 1).toDouble())
                row.createCell(1).setCellValue(result.rosterNumber ?: result.ocrNumber ?: "-")
                row.createCell(2).setCellValue(result.rosterName ?: result.ocrName ?: "-")

                val answerMap = parseClassAnswerMap(result.answersJson)
                for (q in 1..questionCount) {
                    val cell = row.createCell(q + 2)
                    val marked = answerMap[q]
                    val correct = correctAnswers.getOrNull(q - 1)
                    if (marked != null) {
                        val isCorrect = correct != null && marked == correct
                        cell.setCellValue(if (isCorrect) "D" else "Y")
                        cell.setCellStyle(if (isCorrect) correctStyle else wrongStyle)
                        if (isCorrect) questionCorrect[q - 1]++ else questionWrong[q - 1]++
                    } else {
                        cell.setCellValue("B")
                        cell.setCellStyle(emptyStyle)
                        questionEmpty[q - 1]++
                    }
                }

                val baseCol = questionCount + 3
                row.createCell(baseCol).setCellValue(result.correctCount.toDouble())
                row.createCell(baseCol + 1).setCellValue(result.wrongCount.toDouble())
                row.createCell(baseCol + 2).setCellValue(result.emptyCount.toDouble())
                row.createCell(baseCol + 3).setCellValue(result.totalScore)
                allScores.add(result.totalScore)
            }

            rowIdx += 2
            val totalStudents = sorted.size.coerceAtLeast(1).toDouble()

            val topCorrect = (0 until questionCount).sortedByDescending { questionCorrect[it] }.take(3)
            val topWrong = (0 until questionCount).sortedByDescending { questionWrong[it] }.take(3)
            val topEmpty = (0 until questionCount).sortedByDescending { questionEmpty[it] }.take(3)

            fun formatTopList(indices: List<Int>, counts: IntArray): String {
                return indices.joinToString(", ") { i ->
                    "S${i + 1} (%${String.format("%.0f", counts[i] * 100.0 / totalStudents)})"
                }
            }

            val analysisTitle = sheet.createRow(rowIdx++)
            analysisTitle.createCell(0).apply { setCellValue("ANALİZ"); setCellStyle(boldStyle) }

            listOf(
                "En Çok Doğru Yapılan:" to formatTopList(topCorrect, questionCorrect),
                "En Çok Yanlış Yapılan:" to formatTopList(topWrong, questionWrong),
                "En Çok Boş Bırakılan:" to formatTopList(topEmpty, questionEmpty)
            ).forEach { (label, value) ->
                val row = sheet.createRow(rowIdx++)
                row.createCell(0).apply { setCellValue(label); setCellStyle(boldStyle) }
                row.createCell(1).setCellValue(value)
            }

            rowIdx++
            val avg = if (allScores.isNotEmpty()) allScores.average() else 0.0
            val highest = allScores.maxOrNull() ?: 0.0
            val lowest = allScores.minOrNull() ?: 0.0
            val stdDev = if (allScores.size > 1) {
                val mean = avg
                kotlin.math.sqrt(allScores.map { (it - mean) * (it - mean) }.average())
            } else 0.0

            listOf(
                "Sınıf Ortalaması:" to String.format("%.2f", avg),
                "En Yüksek Puan:" to String.format("%.2f", highest),
                "En Düşük Puan:" to String.format("%.2f", lowest),
                "Standart Sapma:" to String.format("%.2f", stdDev),
                "Öğrenci Sayısı:" to "${sorted.size}"
            ).forEach { (label, value) ->
                val row = sheet.createRow(rowIdx++)
                row.createCell(0).apply { setCellValue(label); setCellStyle(boldStyle) }
                row.createCell(1).setCellValue(value)
            }

            sheet.setColumnWidth(0, 5000)
            sheet.setColumnWidth(1, 4500)
            sheet.setColumnWidth(2, 7000)
            for (q in 0 until questionCount) sheet.setColumnWidth(q + 3, 1200)
            val baseCol = questionCount + 3
            intArrayOf(2200, 2200, 2200, 2600).forEachIndexed { i, w ->
                sheet.setColumnWidth(baseCol + i, w)
            }

            val dir = File(context.cacheDir, "exports")
            if (!dir.exists()) dir.mkdirs()

            val safeName = classroom.displayName.replace(Regex("[^\\w\\s\\-()]"), "").take(40)
            val file = File(dir, "${safeName}_rapor.xls")

            FileOutputStream(file).use { workbook.write(it) }
            workbook.close()
            file
        } catch (t: Throwable) {
            Log.e(TAG, "Classroom Excel export failed", t)
            null
        }
    }

    private fun parseClassAnswerMap(answersJson: String): Map<Int, Int?> {
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
}
