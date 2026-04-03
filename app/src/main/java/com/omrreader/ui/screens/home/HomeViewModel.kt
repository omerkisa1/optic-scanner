package com.omrreader.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omrreader.data.db.dao.ClassroomDao
import com.omrreader.data.db.entity.ClassroomEntity
import com.omrreader.data.repository.ExamRepository
import com.omrreader.data.repository.ResultRepository
import com.omrreader.domain.model.Exam
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeExamItem(
    val exam: Exam,
    val scannedStudentCount: Int
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val examRepository: ExamRepository,
    private val resultRepository: ResultRepository,
    classroomDao: ClassroomDao
) : ViewModel() {

    private val _exams = MutableStateFlow<List<HomeExamItem>>(emptyList())
    val exams: StateFlow<List<HomeExamItem>> = _exams.asStateFlow()

    val classrooms: StateFlow<List<ClassroomEntity>> = classroomDao.getAllClassrooms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
