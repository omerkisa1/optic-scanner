package com.omrreader.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exams")
data class ExamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val subjectCount: Int,
    val questionsPerSubject: Int,
    val optionCount: Int,
    val totalPoints: Double = 100.0,
    val createdAt: Long = System.currentTimeMillis(),
    val qrData: String? = null,
    val formFormat: String = "CLASSIC_BORDERED"
)
