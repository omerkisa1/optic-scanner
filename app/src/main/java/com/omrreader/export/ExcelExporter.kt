package com.omrreader.export

import android.content.Context
import com.omrreader.domain.model.Exam
import com.omrreader.domain.model.StudentResult
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExcelExporter @Inject constructor() {

    fun exportToExcel(context: Context, exam: Exam, results: List<StudentResult>): File? {
        return try {
            val workbook = XSSFWorkbook()
            val sheet = workbook.createSheet("Sonuçlar")
            
            // Header
            val headerRow = sheet.createRow(0)
            val headers = listOf("#", "Öğrenci No", "Ad Soyad", "Sınıf", "Doğru", "Yanlış", "Boş", "Puan")
            headers.forEachIndexed { i, header ->
                val cell = headerRow.createCell(i)
                cell.setCellValue(header)
            }
            
            // Data
            results.sortedByDescending { it.totalScore }.forEachIndexed { index, result ->
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

            // Summary row at the end
            val summaryRow = sheet.createRow(results.size + 2)
            summaryRow.createCell(1).setCellValue("Ortalama:")
            val average = results.map { it.totalScore }.average().takeIf { !it.isNaN() } ?: 0.0
            summaryRow.createCell(7).setCellValue(average)

            val dir = File(context.cacheDir, "exports")
            if (!dir.exists()) dir.mkdirs()
            
            val fileName = "${exam.name.replace(" ", "_").take(30)}_Sonuclar.xlsx"
            val file = File(dir, fileName)
            
            FileOutputStream(file).use { out ->
                workbook.write(out)
            }
            workbook.close()
            
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
