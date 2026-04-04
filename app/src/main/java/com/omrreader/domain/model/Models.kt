package com.omrreader.domain.model

data class Exam(
    val id: Long = 0,
    val name: String,
    val subjectCount: Int,
    val questionsPerSubject: Int,
    val optionCount: Int,
    val totalPoints: Double = 100.0,
    val createdAt: Long = System.currentTimeMillis(),
    val qrData: String? = null,
    val formFormat: String = "CLASSIC_BORDERED"
)

data class AnswerKey(
    val id: Long = 0,
    val examId: Long,
    val subjectIndex: Int,
    val questionNumber: Int,
    val correctAnswer: Int,
    val weight: Double,
    val isWeightLocked: Boolean = false
)

data class StudentResult(
    val id: Long = 0,
    val examId: Long,
    val studentName: String?,
    val studentNumber: String?,
    val className: String?,
    val answersJson: String,
    val totalScore: Double,
    val correctCount: Int,
    val wrongCount: Int,
    val emptyCount: Int,
    val imagePath: String?,
    val scannedAt: Long = System.currentTimeMillis(),
    val isConfirmed: Boolean = false
)
