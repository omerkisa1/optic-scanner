package com.omrreader.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "classroom_exam_results",
    foreignKeys = [
        ForeignKey(
            entity = ClassroomEntity::class,
            parentColumns = ["id"],
            childColumns = ["classroomId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = StudentResultEntity::class,
            parentColumns = ["id"],
            childColumns = ["resultId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RosterStudentEntity::class,
            parentColumns = ["id"],
            childColumns = ["rosterStudentId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class ClassroomExamResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val classroomId: Long,
    val resultId: Long,
    val rosterStudentId: Long?,
    val examId: Long,
    val assignedAt: Long = System.currentTimeMillis()
)
