package com.omrreader.ui.screens.classroom

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omrreader.data.db.dao.ClassExamResultView
import com.omrreader.data.db.dao.ClassroomDao
import com.omrreader.data.db.dao.ClassroomExamResultDao
import com.omrreader.data.db.dao.RosterStudentDao
import com.omrreader.data.db.entity.ClassroomEntity
import com.omrreader.data.db.entity.ClassroomExamResultEntity
import com.omrreader.data.db.entity.RosterStudentEntity
import com.omrreader.data.repository.ExamRepository
import com.omrreader.export.ExcelExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

sealed class ClassroomAssignState {
    object Idle : ClassroomAssignState()
    data class Matched(val studentName: String, val studentNumber: String) : ClassroomAssignState()
    object NotMatched : ClassroomAssignState()
    data class Saved(val message: String) : ClassroomAssignState()
}

@HiltViewModel
class ClassroomViewModel @Inject constructor(
    private val classroomDao: ClassroomDao,
    private val rosterStudentDao: RosterStudentDao,
    private val classroomExamResultDao: ClassroomExamResultDao,
    private val examRepository: ExamRepository,
    private val excelExporter: ExcelExporter
) : ViewModel() {

    val classrooms: Flow<List<ClassroomEntity>> = classroomDao.getAllClassrooms()

    private val _assignState = MutableStateFlow<ClassroomAssignState>(ClassroomAssignState.Idle)
    val assignState: StateFlow<ClassroomAssignState> = _assignState.asStateFlow()

    fun createClassroom(
        courseName: String,
        gradeLevel: String,
        section: String,
        educationType: String,
        onCreated: (Long) -> Unit
    ) {
        viewModelScope.launch {
            val id = classroomDao.insert(
                ClassroomEntity(
                    courseName = courseName,
                    gradeLevel = gradeLevel,
                    section = section,
                    educationType = educationType
                )
            )
            onCreated(id)
        }
    }

    fun getClassroom(classroomId: Long): Flow<ClassroomEntity?> {
        return kotlinx.coroutines.flow.flow {
            emit(classroomDao.getClassroom(classroomId))
        }
    }

    fun getStudents(classroomId: Long): Flow<List<RosterStudentEntity>> {
        return rosterStudentDao.getStudentsByClassroom(classroomId)
    }

    fun getClassroomResults(classroomId: Long): Flow<List<ClassExamResultView>> {
        return classroomExamResultDao.getResultsForClassroom(classroomId)
    }

    fun importStudentsFromText(classroomId: Long, text: String) {
        viewModelScope.launch {
            val students = parseStudentList(text)
            val entities = students.map { (number, name) ->
                RosterStudentEntity(
                    classroomId = classroomId,
                    studentName = name,
                    studentNumber = number
                )
            }
            rosterStudentDao.insertAll(entities)
        }
    }

    fun addStudent(classroomId: Long, number: String, name: String) {
        viewModelScope.launch {
            rosterStudentDao.insert(
                RosterStudentEntity(
                    classroomId = classroomId,
                    studentName = name.trim(),
                    studentNumber = number.trim()
                )
            )
        }
    }

    fun matchAndAssignResult(
        classroomId: Long,
        examId: Long,
        resultId: Long,
        ocrNumber: String,
        ocrName: String
    ) {
        viewModelScope.launch {
            var matched = rosterStudentDao.findByNumber(classroomId, ocrNumber)

            if (matched == null && ocrNumber.isNotBlank()) {
                val allStudents = rosterStudentDao.getStudentsByClassroomOnce(classroomId)
                matched = allStudents.minByOrNull {
                    levenshteinDistance(it.studentNumber, ocrNumber)
                }?.takeIf {
                    levenshteinDistance(it.studentNumber, ocrNumber) <= 2
                }
            }

            classroomExamResultDao.insert(
                ClassroomExamResultEntity(
                    classroomId = classroomId,
                    resultId = resultId,
                    rosterStudentId = matched?.id,
                    examId = examId
                )
            )

            _assignState.value = if (matched != null) {
                ClassroomAssignState.Matched(matched.studentName, matched.studentNumber)
            } else {
                ClassroomAssignState.NotMatched
            }
        }
    }

    fun exportClassroomExcel(
        context: Context,
        classroomId: Long,
        results: List<ClassExamResultView>,
        onComplete: (File?) -> Unit
    ) {
        viewModelScope.launch {
            val classroom = classroomDao.getClassroom(classroomId)
            if (classroom == null) {
                onComplete(null)
                return@launch
            }

            val examId = results.firstOrNull()?.examId
            val correctAnswers = if (examId != null) {
                val exam = examRepository.getExamById(examId)
                excelExporter.parseCorrectAnswersFromQr(exam?.qrData)
            } else {
                emptyList()
            }

            val file = excelExporter.exportClassroomReport(
                context = context,
                classroom = classroom,
                results = results,
                correctAnswers = correctAnswers
            )
            onComplete(file)
        }
    }

    fun resetAssignState() {
        _assignState.value = ClassroomAssignState.Idle
    }

    private fun parseStudentList(text: String): List<Pair<String, String>> {
        return text.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(Regex("[,\t;]+"), limit = 2)
                if (parts.size >= 2) {
                    val number = parts[0].trim().filter { it.isDigit() }
                    val name = parts[1].trim()
                    if (number.isNotBlank() && name.isNotBlank()) {
                        Pair(number, name)
                    } else null
                } else {
                    val words = line.trim().split(Regex("\\s+"), limit = 2)
                    if (words.size >= 2 && words[0].all { it.isDigit() }) {
                        Pair(words[0], words[1])
                    } else null
                }
            }
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + if (s1[i - 1] == s2[j - 1]) 0 else 1
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
