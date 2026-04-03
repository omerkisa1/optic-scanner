package com.omrreader.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "classrooms")
data class ClassroomEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseName: String,
    val gradeLevel: String,
    val section: String,
    val educationType: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    val displayName: String
        get() = "$courseName - $gradeLevel $section ($educationType)"
}
