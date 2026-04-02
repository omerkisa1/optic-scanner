package com.omrreader.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omrreader.data.repository.ExamRepository
import com.omrreader.data.repository.ResultRepository
import com.omrreader.domain.model.Exam
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeExamItem(
    val exam: Exam,
    val scannedStudentCount: Int
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val examRepository: ExamRepository,
    private val resultRepository: ResultRepository
) : ViewModel() {

    private val _exams = MutableStateFlow<List<HomeExamItem>>(emptyList())
    val exams: StateFlow<List<HomeExamItem>> = _exams.asStateFlow()

    init {
        loadExams()
    }

    fun loadExams() {
        viewModelScope.launch {
            val loadedExams = examRepository.getAllExams()
            _exams.value = loadedExams.map { exam ->
                val scannedCount = resultRepository.getResultsForExam(exam.id).size
                HomeExamItem(exam = exam, scannedStudentCount = scannedCount)
            }
        }
    }
    
    fun deleteExam(exam: Exam) {
        viewModelScope.launch {
            examRepository.deleteExam(exam)
            loadExams()
        }
    }
}
