package com.omrreader.data.repository

import com.omrreader.data.db.dao.StudentResultDao
import com.omrreader.data.db.entity.StudentResultEntity
import com.omrreader.domain.model.StudentResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResultRepository @Inject constructor(
    private val resultDao: StudentResultDao
) {
    suspend fun getResultsForExam(examId: Long): List<StudentResult> {
        return resultDao.getResultsForExam(examId).map { it.toDomain() }
    }

    suspend fun getResultById(id: Long): StudentResult? {
        return resultDao.getResultById(id)?.toDomain()
    }

    suspend fun saveResult(result: StudentResult): Long {
        return resultDao.insertResult(result.toEntity())
    }

    suspend fun updateResult(result: StudentResult) {
        resultDao.updateResult(result.toEntity())
    }

    suspend fun deleteResult(result: StudentResult) {
        resultDao.deleteResult(result.toEntity())
    }

    suspend fun getAverageScore(examId: Long): Double {
        return resultDao.getAverageScoreForExam(examId) ?: 0.0
    }

    private fun StudentResultEntity.toDomain() = StudentResult(
        id, examId, studentName, studentNumber, className, answersJson, totalScore, correctCount, wrongCount, emptyCount, imagePath, scannedAt, isConfirmed
    )
    private fun StudentResult.toEntity() = StudentResultEntity(
        id, examId, studentName, studentNumber, className, answersJson, totalScore, correctCount, wrongCount, emptyCount, imagePath, scannedAt, isConfirmed
    )
}
