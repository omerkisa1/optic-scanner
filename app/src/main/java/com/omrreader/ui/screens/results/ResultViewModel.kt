package com.omrreader.ui.screens.results

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omrreader.data.repository.ExamRepository
import com.omrreader.data.repository.ResultRepository
import com.omrreader.domain.model.Exam
import com.omrreader.domain.model.StudentResult
import com.omrreader.export.ExcelExporter
import com.omrreader.export.PdfExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.pow
import kotlin.math.sqrt

data class ExamStatistics(
    val studentCount: Int,
    val average: Double,
    val highest: Double,
    val lowest: Double,
    val stdDeviation: Double
)

sealed class ExportState {
    object Idle : ExportState()
    object Loading : ExportState()
    data class Success(val filePath: String) : ExportState()
    data class Error(val message: String) : ExportState()
}

@HiltViewModel
class ResultViewModel @Inject constructor(
    private val resultRepository: ResultRepository,
    private val examRepository: ExamRepository,
    private val excelExporter: ExcelExporter,
    private val pdfExporter: PdfExporter
) : ViewModel() {

    private val gson = Gson()

    private val _exam = MutableStateFlow<Exam?>(null)
    val exam: StateFlow<Exam?> = _exam.asStateFlow()

    private val _results = MutableStateFlow<List<StudentResult>>(emptyList())
    val results: StateFlow<List<StudentResult>> = _results.asStateFlow()

    private val _statistics = MutableStateFlow(
        ExamStatistics(
            studentCount = 0,
            average = 0.0,
            highest = 0.0,
            lowest = 0.0,
            stdDeviation = 0.0
        )
    )
    val statistics: StateFlow<ExamStatistics> = _statistics.asStateFlow()

    private val _selectedResult = MutableStateFlow<StudentResult?>(null)
    val selectedResult: StateFlow<StudentResult?> = _selectedResult.asStateFlow()

    private val _selectedCorrectAnswers = MutableStateFlow<List<Int>>(emptyList())
    val selectedCorrectAnswers: StateFlow<List<Int>> = _selectedCorrectAnswers.asStateFlow()

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    fun loadExamDetail(examId: Long) {
        viewModelScope.launch {
            val examData = examRepository.getExamById(examId)
            val resultList = resultRepository.getResultsForExam(examId).sortedByDescending { it.totalScore }
            _exam.value = examData
            _results.value = resultList
            _statistics.value = computeStatistics(resultList)
        }
    }

    fun loadResultDetail(resultId: Long) {
        viewModelScope.launch {
            val result = resultRepository.getResultById(resultId)
            _selectedResult.value = result

            if (result == null) {
                _selectedCorrectAnswers.value = emptyList()
                return@launch
            }

            val exam = examRepository.getExamById(result.examId)
            val fromQr = parseCorrectAnswersFromQr(exam?.qrData)
            if (fromQr.isNotEmpty()) {
                _selectedCorrectAnswers.value = fromQr
                return@launch
            }

            val fromDb = examRepository.getAnswerKeysForExam(result.examId)
                .sortedWith(compareBy({ it.subjectIndex }, { it.questionNumber }))
                .map { it.correctAnswer }
            _selectedCorrectAnswers.value = fromDb
        }
    }

    fun exportExcel(examId: Long, context: Context) {
        viewModelScope.launch {
            _exportState.value = ExportState.Loading
            val examData = examRepository.getExamById(examId)
            val resultList = resultRepository.getResultsForExam(examId)
            if (examData == null) {
                _exportState.value = ExportState.Error("Sınav bulunamadı.")
                return@launch
            }

            val file = excelExporter.exportToExcel(context, examData, resultList)
            _exportState.value = if (file != null) {
                ExportState.Success(file.absolutePath)
            } else {
                ExportState.Error("Excel dosyası oluşturulamadı.")
            }
        }
    }

    fun exportPdf(examId: Long, context: Context) {
        viewModelScope.launch {
            _exportState.value = ExportState.Loading
            val examData = examRepository.getExamById(examId)
            val resultList = resultRepository.getResultsForExam(examId)
            if (examData == null) {
                _exportState.value = ExportState.Error("Sınav bulunamadı.")
                return@launch
            }

            val file = pdfExporter.exportToPdf(context, examData, resultList)
            _exportState.value = if (file != null) {
                ExportState.Success(file.absolutePath)
            } else {
                ExportState.Error("PDF dosyası oluşturulamadı.")
            }
        }
    }

    fun resetExportState() {
        _exportState.value = ExportState.Idle
    }

    private fun computeStatistics(results: List<StudentResult>): ExamStatistics {
        if (results.isEmpty()) {
            return ExamStatistics(0, 0.0, 0.0, 0.0, 0.0)
        }

        val scores = results.map { it.totalScore }
        val average = scores.average()
        val highest = scores.maxOrNull() ?: 0.0
        val lowest = scores.minOrNull() ?: 0.0
        val variance = scores.map { (it - average).pow(2) }.average()
        val stdDeviation = sqrt(variance)

        return ExamStatistics(
            studentCount = results.size,
            average = average,
            highest = highest,
            lowest = lowest,
            stdDeviation = stdDeviation
        )
    }

    private fun parseCorrectAnswersFromQr(qrData: String?): List<Int> {
        if (qrData.isNullOrBlank()) return emptyList()
        return try {
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            val root: Map<String, Any?> = gson.fromJson(qrData, mapType)
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
}
