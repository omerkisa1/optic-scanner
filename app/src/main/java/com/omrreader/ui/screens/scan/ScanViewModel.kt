package com.omrreader.ui.screens.scan

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omrreader.data.repository.ExamRepository
import com.omrreader.data.repository.ResultRepository
import com.omrreader.domain.model.Exam
import com.omrreader.domain.model.OMRAnswerKeyResponse
import com.omrreader.domain.model.ProcessResult
import com.omrreader.domain.model.ScoreResult
import com.omrreader.domain.model.SubjectInfo
import com.omrreader.domain.model.StudentResult
import com.omrreader.domain.usecase.ProcessOMRUseCase
import com.omrreader.scoring.ScoringEngine
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ScanState {
    object Ready : ScanState()
    object Processing : ScanState()
    data class ReviewReady(val result: ProcessResult.Success) : ScanState()
    data class Error(val message: String) : ScanState()
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val processOMRUseCase: ProcessOMRUseCase,
    private val examRepository: ExamRepository,
    private val resultRepository: ResultRepository,
    private val scoringEngine: ScoringEngine,
    private val gson: Gson
) : ViewModel() {

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Ready)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _activeExamId = MutableStateFlow<Long?>(null)
    val activeExamId: StateFlow<Long?> = _activeExamId.asStateFlow()

    private val _reviewResult = MutableStateFlow<ProcessResult.Success?>(null)
    val reviewResult: StateFlow<ProcessResult.Success?> = _reviewResult.asStateFlow()

    private val _answerOverrides = MutableStateFlow<Map<Int, Int?>>(emptyMap())
    val answerOverrides: StateFlow<Map<Int, Int?>> = _answerOverrides.asStateFlow()

    fun setActiveExam(examId: Long) {
        _activeExamId.value = examId
    }

    fun processImage(bitmap: Bitmap) {
        if (_scanState.value is ScanState.Processing) return

        viewModelScope.launch(Dispatchers.Default) {
            _scanState.value = ScanState.Processing
            try {
                val exam = _activeExamId.value?.let { examRepository.getExamById(it) }
                val fallbackAnswerKey = exam?.let { buildFallbackAnswerKey(it) }
                val result = processOMRUseCase.process(
                    bitmap = bitmap,
                    expectedExam = exam,
                    fallbackAnswerKey = fallbackAnswerKey
                )
                _scanState.value = when (result) {
                    is ProcessResult.Success -> {
                        _reviewResult.value = result
                        _answerOverrides.value = emptyMap()
                        ScanState.ReviewReady(result)
                    }
                    is ProcessResult.Error -> ScanState.Error(result.message)
                }
            } catch (t: Throwable) {
                _scanState.value = ScanState.Error("Tarama sırasında hata oluştu. Lütfen tekrar deneyin.")
            } finally {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }

    fun consumeReviewNavigation() {
        if (_scanState.value is ScanState.ReviewReady) {
            _scanState.value = ScanState.Ready
        }
    }

    fun clearError() {
        if (_scanState.value is ScanState.Error) {
            _scanState.value = ScanState.Ready
        }
    }

    fun updateAnswerOverride(questionIndex: Int, answer: Int?) {
        val current = _reviewResult.value ?: return
        if (questionIndex !in current.omrResults.indices) return

        _answerOverrides.value = _answerOverrides.value.toMutableMap().also { overrides ->
            overrides[questionIndex] = answer
        }
    }

    fun getEffectiveAnswers(): List<Int?> {
        val current = _reviewResult.value ?: return emptyList()
        return current.omrResults.mapIndexed { index, questionResult ->
            _answerOverrides.value[index] ?: questionResult.selectedAnswer
        }
    }

    fun getCorrectAnswers(): List<Int> {
        val current = _reviewResult.value ?: return emptyList()
        return current.answerKey?.subjects?.flatMap { it.answers } ?: emptyList()
    }

    fun getEffectiveScore(): ScoreResult? {
        val current = _reviewResult.value ?: return null
        val correctAnswers = current.answerKey?.subjects?.flatMap { it.answers } ?: return current.scoreResult
        val weights = current.answerKey.subjects.flatMap { it.weights }
        val answers = getEffectiveAnswers()
        return scoringEngine.calculateScore(answers, correctAnswers, weights)
    }

    fun saveConfirmedResult(
        studentName: String,
        studentNumber: String,
        className: String,
        onCompleted: (Boolean, String, Long) -> Unit
    ) {
        val examId = _activeExamId.value
        val current = _reviewResult.value

        if (examId == null || current == null) {
            onCompleted(false, "Tarama sonucu bulunamadı.", 0L)
            return
        }

        viewModelScope.launch {
            val exam = examRepository.getExamById(examId)
            if (exam == null) {
                onCompleted(false, "Sınav bilgisi bulunamadı.", 0L)
                return@launch
            }

            val finalAnswers = getEffectiveAnswers()
            val finalScore = getEffectiveScore() ?: current.scoreResult

            val answerRows = current.omrResults.mapIndexed { index, question ->
                mapOf(
                    "q" to question.questionNumber,
                    "marked" to finalAnswers.getOrNull(index),
                    "state" to if (question.isValid) "VALID" else "MULTI"
                )
            }

            val entity = StudentResult(
                examId = examId,
                studentName = studentName.ifBlank { null },
                studentNumber = studentNumber.ifBlank { null },
                className = className.ifBlank { null },
                answersJson = gson.toJson(answerRows),
                totalScore = finalScore.totalScore,
                correctCount = finalScore.correctCount,
                wrongCount = finalScore.wrongCount,
                emptyCount = finalScore.emptyCount,
                imagePath = current.debugImagePath ?: current.correctedImagePath,
                isConfirmed = true
            )

            val savedId = resultRepository.saveResult(entity)
            onCompleted(true, "Sonuç kaydedildi.", savedId)
            resetReviewSession()
        }
    }

    fun resetReviewSession() {
        _scanState.value = ScanState.Ready
        _reviewResult.value = null
        _answerOverrides.value = emptyMap()
    }

    private suspend fun buildFallbackAnswerKey(exam: Exam): OMRAnswerKeyResponse? {
        val keys = examRepository.getAnswerKeysForExam(exam.id)
        if (keys.isEmpty()) return null

        val subjects = keys
            .groupBy { it.subjectIndex }
            .toSortedMap()
            .map { (subjectIndex, subjectKeys) ->
                val ordered = subjectKeys.sortedBy { it.questionNumber }
                val answers = ordered.map { it.correctAnswer }
                val weights = ordered.map { it.weight }

                SubjectInfo(
                    name = defaultSubjectName(exam, subjectIndex),
                    answers = answers,
                    weights = weights,
                    optionCount = inferOptionCount(answers, exam.optionCount)
                )
            }

        if (subjects.isEmpty()) return null

        return OMRAnswerKeyResponse(
            v = 1,
            id = "exam_${exam.id}",
            name = exam.name,
            subjects = subjects,
            total = 100
        )
    }

    private fun inferOptionCount(answers: List<Int>, fallback: Int): Int {
        val inferred = (answers.maxOrNull() ?: -1) + 1
        return inferred.coerceAtLeast(fallback).coerceIn(2, 8)
    }

    private fun defaultSubjectName(exam: Exam, subjectIndex: Int): String {
        return if (exam.subjectCount == 1 && subjectIndex == 0) {
            exam.name.substringBefore("|").substringBefore("[").trim().ifBlank { "DERS 1" }
        } else {
            "DERS ${subjectIndex + 1}"
        }
    }
}
