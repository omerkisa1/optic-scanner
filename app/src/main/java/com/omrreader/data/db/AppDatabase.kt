package com.omrreader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.omrreader.data.db.dao.AnswerKeyDao
import com.omrreader.data.db.dao.ExamDao
import com.omrreader.data.db.dao.StudentResultDao
import com.omrreader.data.db.entity.AnswerKeyEntity
import com.omrreader.data.db.entity.ExamEntity
import com.omrreader.data.db.entity.StudentResultEntity

@Database(
    entities = [
        ExamEntity::class,
        AnswerKeyEntity::class,
        StudentResultEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun examDao(): ExamDao
    abstract fun answerKeyDao(): AnswerKeyDao
    abstract fun studentResultDao(): StudentResultDao
}
