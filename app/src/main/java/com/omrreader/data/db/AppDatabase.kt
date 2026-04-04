package com.omrreader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.omrreader.data.db.dao.AnswerKeyDao
import com.omrreader.data.db.dao.ClassroomDao
import com.omrreader.data.db.dao.ClassroomExamResultDao
import com.omrreader.data.db.dao.ExamDao
import com.omrreader.data.db.dao.RosterStudentDao
import com.omrreader.data.db.dao.StudentResultDao
import com.omrreader.data.db.entity.AnswerKeyEntity
import com.omrreader.data.db.entity.ClassroomEntity
import com.omrreader.data.db.entity.ClassroomExamResultEntity
import com.omrreader.data.db.entity.ExamEntity
import com.omrreader.data.db.entity.RosterStudentEntity
import com.omrreader.data.db.entity.StudentResultEntity

@Database(
    entities = [
        ExamEntity::class,
        AnswerKeyEntity::class,
        StudentResultEntity::class,
        ClassroomEntity::class,
        RosterStudentEntity::class,
        ClassroomExamResultEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun examDao(): ExamDao
    abstract fun answerKeyDao(): AnswerKeyDao
    abstract fun studentResultDao(): StudentResultDao
    abstract fun classroomDao(): ClassroomDao
    abstract fun rosterStudentDao(): RosterStudentDao
    abstract fun classroomExamResultDao(): ClassroomExamResultDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS classrooms (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        courseName TEXT NOT NULL,
                        gradeLevel TEXT NOT NULL,
                        section TEXT NOT NULL,
                        educationType TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS roster_students (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        classroomId INTEGER NOT NULL,
                        studentName TEXT NOT NULL,
                        studentNumber TEXT NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        FOREIGN KEY (classroomId) REFERENCES classrooms(id) ON DELETE CASCADE
                    )
                """)
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_roster_students_classroomId_studentNumber
                    ON roster_students(classroomId, studentNumber)
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS classroom_exam_results (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        classroomId INTEGER NOT NULL,
                        resultId INTEGER NOT NULL,
                        rosterStudentId INTEGER,
                        examId INTEGER NOT NULL,
                        assignedAt INTEGER NOT NULL,
                        FOREIGN KEY (classroomId) REFERENCES classrooms(id) ON DELETE CASCADE,
                        FOREIGN KEY (resultId) REFERENCES student_results(id) ON DELETE CASCADE,
                        FOREIGN KEY (rosterStudentId) REFERENCES roster_students(id) ON DELETE SET NULL
                    )
                """)
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exams ADD COLUMN formFormat TEXT NOT NULL DEFAULT 'CLASSIC_BORDERED'")
            }
        }
    }
}
