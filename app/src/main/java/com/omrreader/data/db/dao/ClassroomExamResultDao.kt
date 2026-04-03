package com.omrreader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.omrreader.data.db.entity.ClassroomExamResultEntity
import kotlinx.coroutines.flow.Flow

data class ClassExamResultView(
    val id: Long,
    val classroomId: Long,
    val resultId: Long,
    val rosterStudentId: Long?,
    val rosterName: String?,
    val rosterNumber: String?,
    val totalScore: Double,
    val correctCount: Int,
    val wrongCount: Int,
    val emptyCount: Int,
    val ocrName: String?,
    val ocrNumber: String?,
    val answersJson: String
)

@Dao
interface ClassroomExamResultDao {
    @Query("""
        SELECT cer.id, cer.classroomId, cer.resultId, cer.rosterStudentId,
               rs.studentName as rosterName, rs.studentNumber as rosterNumber,
               sr.totalScore, sr.correctCount, sr.wrongCount, sr.emptyCount,
               sr.studentName as ocrName, sr.studentNumber as ocrNumber, sr.answersJson
        FROM classroom_exam_results cer
        LEFT JOIN roster_students rs ON cer.rosterStudentId = rs.id
        LEFT JOIN student_results sr ON cer.resultId = sr.id
        WHERE cer.classroomId = :classroomId AND cer.examId = :examId
        ORDER BY rs.studentName
    """)
    fun getResultsForClassExam(classroomId: Long, examId: Long): Flow<List<ClassExamResultView>>

    @Query("""
        SELECT cer.id, cer.classroomId, cer.resultId, cer.rosterStudentId,
               rs.studentName as rosterName, rs.studentNumber as rosterNumber,
               sr.totalScore, sr.correctCount, sr.wrongCount, sr.emptyCount,
               sr.studentName as ocrName, sr.studentNumber as ocrNumber, sr.answersJson
        FROM classroom_exam_results cer
        LEFT JOIN roster_students rs ON cer.rosterStudentId = rs.id
        LEFT JOIN student_results sr ON cer.resultId = sr.id
        WHERE cer.classroomId = :classroomId
        ORDER BY rs.studentName
    """)
    fun getResultsForClassroom(classroomId: Long): Flow<List<ClassExamResultView>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: ClassroomExamResultEntity): Long
}
