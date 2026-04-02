package com.omrreader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.omrreader.data.db.entity.AnswerKeyEntity

@Dao
interface AnswerKeyDao {
    @Query("SELECT * FROM answer_keys WHERE examId = :examId ORDER BY subjectIndex, questionNumber")
    suspend fun getAnswerKeysForExam(examId: Long): List<AnswerKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnswerKeys(answerKeys: List<AnswerKeyEntity>)

    @Update
    suspend fun updateAnswerKeys(answerKeys: List<AnswerKeyEntity>)
}
