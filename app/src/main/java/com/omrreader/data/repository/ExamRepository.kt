package com.omrreader.data.repository

import com.omrreader.data.db.dao.AnswerKeyDao
import com.omrreader.data.db.dao.ExamDao
import com.omrreader.data.db.entity.AnswerKeyEntity
import com.omrreader.data.db.entity.ExamEntity
import com.omrreader.domain.model.AnswerKey
import com.omrreader.domain.model.Exam
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExamRepository @Inject constructor(
    private val examDao: ExamDao,
    private val answerKeyDao: AnswerKeyDao
) {
    suspend fun getAllExams(): List<Exam> {
        return examDao.getAllExams().map { it.toDomain() }
    }

    suspend fun getExamById(id: Long): Exam? {
        return examDao.getExamById(id)?.toDomain()
    }

    suspend fun insertExam(exam: Exam): Long {
        return examDao.insertExam(exam.toEntity())
    }

    suspend fun createExam(
        name: String,
        subjectCount: Int,
        questionsPerSubject: Int,
        optionCount: Int
    ): Long {
        return insertExam(
            Exam(
                name = name,
                subjectCount = subjectCount,
                questionsPerSubject = questionsPerSubject,
                optionCount = optionCount
            )
        )
    }

    suspend fun updateExam(exam: Exam) {
        examDao.updateExam(exam.toEntity())
    }

    suspend fun deleteExam(exam: Exam) {
        examDao.deleteExam(exam.toEntity())
    }

    suspend fun getAnswerKeysForExam(examId: Long): List<AnswerKey> {
        return answerKeyDao.getAnswerKeysForExam(examId).map { it.toDomain() }
    }

    suspend fun saveAnswerKeys(keys: List<AnswerKey>) {
        answerKeyDao.insertAnswerKeys(keys.map { it.toEntity() })
    }

    suspend fun updateAnswerKeys(keys: List<AnswerKey>) {
        answerKeyDao.updateAnswerKeys(keys.map { it.toEntity() })
    }

    suspend fun insertAnswerKey(
        examId: Long,
        subjectIndex: Int,
        questionNumber: Int,
        correctAnswer: Int,
        weight: Double,
        isWeightLocked: Boolean = false
    ) {
        answerKeyDao.insertAnswerKeys(
            listOf(
                AnswerKeyEntity(
                    examId = examId,
                    subjectIndex = subjectIndex,
                    questionNumber = questionNumber,
                    correctAnswer = correctAnswer,
                    weight = weight,
                    isWeightLocked = isWeightLocked
                )
            )
        )
    }

    private fun ExamEntity.toDomain() = Exam(id, name, subjectCount, questionsPerSubject, optionCount, totalPoints, createdAt, qrData)
    private fun Exam.toEntity() = ExamEntity(id, name, subjectCount, questionsPerSubject, optionCount, totalPoints, createdAt, qrData)
    
    private fun AnswerKeyEntity.toDomain() = AnswerKey(id, examId, subjectIndex, questionNumber, correctAnswer, weight, isWeightLocked)
    private fun AnswerKey.toEntity() = AnswerKeyEntity(id, examId, subjectIndex, questionNumber, correctAnswer, weight, isWeightLocked)
}
