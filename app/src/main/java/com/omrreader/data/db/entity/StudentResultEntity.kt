package com.omrreader.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "student_results",
    foreignKeys = [
        ForeignKey(
            entity = ExamEntity::class,
            parentColumns = ["id"],
            childColumns = ["examId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class StudentResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val examId: Long,
    val studentName: String?,
    val studentNumber: String?,
    val className: String?,
    val answersJson: String,
    val totalScore: Double,
    val correctCount: Int,
    val wrongCount: Int,
    val emptyCount: Int,
    val imagePath: String?,
    val scannedAt: Long = System.currentTimeMillis(),
    val isConfirmed: Boolean = false
)
