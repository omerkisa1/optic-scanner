package com.omrreader.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "roster_students",
    foreignKeys = [
        ForeignKey(
            entity = ClassroomEntity::class,
            parentColumns = ["id"],
            childColumns = ["classroomId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["classroomId", "studentNumber"], unique = true)]
)
data class RosterStudentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val classroomId: Long,
    val studentName: String,
    val studentNumber: String,
    val isActive: Boolean = true
)
