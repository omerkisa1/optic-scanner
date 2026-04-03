package com.omrreader.di

import android.content.Context
import androidx.room.Room
import com.omrreader.data.db.AppDatabase
import com.omrreader.data.db.dao.AnswerKeyDao
import com.omrreader.data.db.dao.ClassroomDao
import com.omrreader.data.db.dao.ClassroomExamResultDao
import com.omrreader.data.db.dao.ExamDao
import com.omrreader.data.db.dao.RosterStudentDao
import com.omrreader.data.db.dao.StudentResultDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "omr_database"
        )
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideExamDao(database: AppDatabase): ExamDao = database.examDao()

    @Provides
    fun provideAnswerKeyDao(database: AppDatabase): AnswerKeyDao = database.answerKeyDao()

    @Provides
    fun provideStudentResultDao(database: AppDatabase): StudentResultDao = database.studentResultDao()

    @Provides
    fun provideClassroomDao(database: AppDatabase): ClassroomDao = database.classroomDao()

    @Provides
    fun provideRosterStudentDao(database: AppDatabase): RosterStudentDao = database.rosterStudentDao()

    @Provides
    fun provideClassroomExamResultDao(database: AppDatabase): ClassroomExamResultDao = database.classroomExamResultDao()
}
