package com.omrreader.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.omrreader.data.db.entity.StudentResultEntity

@Dao
interface StudentResultDao {
    @Query("SELECT * FROM student_results WHERE examId = :examId ORDER BY totalScore DESC")
    suspend fun getResultsForExam(examId: Long): List<StudentResultEntity>

    @Query("SELECT * FROM student_results WHERE id = :resultId")
    suspend fun getResultById(resultId: Long): StudentResultEntity?

    @Query("SELECT AVG(totalScore) FROM student_results WHERE examId = :examId")
    suspend fun getAverageScoreForExam(examId: Long): Double?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResult(result: StudentResultEntity): Long

    @Update
    suspend fun updateResult(result: StudentResultEntity)

    @Delete
    suspend fun deleteResult(result: StudentResultEntity)
}
