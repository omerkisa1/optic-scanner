package com.omrreader.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.omrreader.data.db.entity.ExamEntity

@Dao
interface ExamDao {
    @Query("SELECT * FROM exams ORDER BY createdAt DESC")
    suspend fun getAllExams(): List<ExamEntity>

    @Query("SELECT * FROM exams WHERE id = :examId")
    suspend fun getExamById(examId: Long): ExamEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExam(exam: ExamEntity): Long

    @Update
    suspend fun updateExam(exam: ExamEntity)

    @Delete
    suspend fun deleteExam(exam: ExamEntity)
}
