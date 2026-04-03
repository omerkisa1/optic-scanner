package com.omrreader.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.omrreader.data.db.entity.ClassroomEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ClassroomDao {
    @Query("SELECT * FROM classrooms ORDER BY createdAt DESC")
    fun getAllClassrooms(): Flow<List<ClassroomEntity>>

    @Query("SELECT * FROM classrooms WHERE id = :id")
    suspend fun getClassroom(id: Long): ClassroomEntity?

    @Insert
    suspend fun insert(classroom: ClassroomEntity): Long

    @Update
    suspend fun update(classroom: ClassroomEntity)

    @Delete
    suspend fun delete(classroom: ClassroomEntity)
}
