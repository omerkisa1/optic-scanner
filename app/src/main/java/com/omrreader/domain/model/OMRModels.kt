package com.omrreader.domain.model

enum class BubbleState {
    EMPTY,
    MARKED,
    FILLED,
    AMBIGUOUS
}

data class QuestionResult(
    val questionNumber: Int,
    val bubbleStates: List<BubbleState>,
    val selectedAnswer: Int?,
    val fillRatios: List<Double>,
    val isValid: Boolean
)

data class ScoreResult(
    val totalScore: Double,
    val correctCount: Int,
    val wrongCount: Int,
    val emptyCount: Int
)

data class ThresholdConfig(
    val emptyMax: Double = 0.35,
    val markedMin: Double = 0.35,
    val filledMin: Double = 0.85,
    val ambiguousRange: Double = 0.05
)

sealed class ProcessResult {
    data class Success(
        val studentName: String,
        val studentNumber: String,
        val className: String,
        val omrResults: List<QuestionResult>,
        val scoreResult: ScoreResult,
        val answerKey: OMRAnswerKeyResponse?, // We'll hold the parsed payload
        val correctedImagePath: String? = null
    ) : ProcessResult()

    data class Error(val message: String) : ProcessResult()
}

// Custom model to hold the QR code decoded format
data class OMRAnswerKeyResponse(
    val v: Int,
    val id: String,
    val name: String,
    val subjects: List<SubjectInfo>,
    val total: Int = 100
)

data class SubjectInfo(
    val name: String,
    val answers: List<Int>,
    val weights: List<Double>
)
