package com.omrreader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.omrreader.data.db.entity.RosterStudentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RosterStudentDao {
    @Query("SELECT * FROM roster_students WHERE classroomId = :classroomId AND isActive = 1 ORDER BY studentName")
    fun getStudentsByClassroom(classroomId: Long): Flow<List<RosterStudentEntity>>

    @Query("SELECT * FROM roster_students WHERE classroomId = :classroomId AND isActive = 1 ORDER BY studentName")
    suspend fun getStudentsByClassroomOnce(classroomId: Long): List<RosterStudentEntity>

    @Query("SELECT * FROM roster_students WHERE classroomId = :classroomId AND studentNumber = :number LIMIT 1")
    suspend fun findByNumber(classroomId: Long, number: String): RosterStudentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(students: List<RosterStudentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(student: RosterStudentEntity): Long

    @Query("DELETE FROM roster_students WHERE classroomId = :classroomId")
    suspend fun deleteAllByClassroom(classroomId: Long)
}
