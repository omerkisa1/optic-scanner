package com.omrreader.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "answer_keys",
    foreignKeys = [
        ForeignKey(
            entity = ExamEntity::class,
            parentColumns = ["id"],
            childColumns = ["examId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AnswerKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val examId: Long,
    val subjectIndex: Int,
    val questionNumber: Int,
    val correctAnswer: Int,
    val weight: Double,
    val isWeightLocked: Boolean = false
)
