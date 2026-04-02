package com.omrreader.ui.screens.exam

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.omrreader.data.repository.ExamRepository
import com.omrreader.domain.model.AnswerKey
import com.omrreader.domain.model.Exam
import com.omrreader.qr.QRGenerator
import com.omrreader.scoring.ScoringEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SubjectConfig(
    val name: String,
    val questionCount: Int,
    val optionCount: Int
)

data class QuestionEditorState(
    val id: Long = 0,
    val subjectIndex: Int,
    val questionNumber: Int,
    val correctAnswer: Int,
    val weight: Double,
    val isWeightLocked: Boolean
)

private data class QRPayload(
    val v: Int = 1,
    val id: String,
    val name: String,
    val subjects: List<QRSubject>,
    val total: Int = 100
)

private data class QRSubject(
    val name: String,
    val answers: List<Int>,
    val weights: List<Double>
)

@HiltViewModel
class ExamViewModel @Inject constructor(
    private val examRepository: ExamRepository,
    private val scoringEngine: ScoringEngine,
    private val qrGenerator: QRGenerator,
    private val gson: Gson
) : ViewModel() {

    var examName by mutableStateOf("")
        private set

    var subjectCount by mutableIntStateOf(1)
        private set

    var subjects by mutableStateOf(listOf(SubjectConfig("DERS 1", 20, 4)))
        private set

    var answerItems by mutableStateOf<List<QuestionEditorState>>(emptyList())
        private set

    var generatedQrBitmap by mutableStateOf<Bitmap?>(null)
        private set

    var currentExam by mutableStateOf<Exam?>(null)
        private set

    private var currentExamId: Long? = null
    private var currentQrData: String? = null

    private val _uiMessage = MutableSharedFlow<String>()
    val uiMessage: SharedFlow<String> = _uiMessage

    fun onExamNameChange(value: String) {
        examName = value
    }

    fun onSubjectCountChange(value: Int) {
        if (value !in 1..2) return
        subjectCount = value
        subjects = if (value == 1) {
            listOf(subjects.firstOrNull() ?: SubjectConfig("DERS 1", 20, 4))
        } else {
            val first = subjects.firstOrNull() ?: SubjectConfig("DERS 1", 20, 4)
            val second = subjects.getOrNull(1) ?: SubjectConfig("DERS 2", first.questionCount, first.optionCount)
            listOf(first.copy(name = first.name.ifBlank { "DERS 1" }), second.copy(name = second.name.ifBlank { "DERS 2" }))
        }
    }

    fun onSubjectNameChange(index: Int, value: String) {
        subjects = subjects.mapIndexed { i, subject ->
            if (i == index) subject.copy(name = value) else subject
        }
    }

    fun onSubjectQuestionCountChange(index: Int, value: Int) {
        if (value !in 5..50) return
        subjects = subjects.mapIndexed { i, subject ->
            if (i == index) subject.copy(questionCount = value) else subject
        }
    }

    fun onSubjectOptionCountChange(index: Int, value: Int) {
        if (value !in 4..5) return
        subjects = subjects.mapIndexed { i, subject ->
            if (i == index) subject.copy(optionCount = value) else subject
        }
    }

    fun createExam(onCreated: (Long) -> Unit) {
        if (examName.isBlank()) {
            emitMessage("Sınav adı boş olamaz.")
            return
        }

        viewModelScope.launch {
            val totalQuestions = subjects.sumOf { it.questionCount }
            if (totalQuestions == 0) {
                emitMessage("Soru sayısı geçersiz.")
                return@launch
            }

            val examId = examRepository.createExam(
                name = examName.trim(),
                subjectCount = subjectCount,
                questionsPerSubject = subjects.first().questionCount,
                optionCount = subjects.first().optionCount
            )

            val defaultWeight = 100.0 / totalQuestions
            subjects.forEachIndexed { subjectIndex, config ->
                for (questionNumber in 1..config.questionCount) {
                    examRepository.insertAnswerKey(
                        examId = examId,
                        subjectIndex = subjectIndex,
                        questionNumber = questionNumber,
                        correctAnswer = 0,
                        weight = defaultWeight,
                        isWeightLocked = false
                    )
                }
            }

            onCreated(examId)
        }
    }

    fun loadAnswerEditor(examId: Long) {
        if (currentExamId == examId && answerItems.isNotEmpty()) return

        currentExamId = examId
        viewModelScope.launch {
            val exam = examRepository.getExamById(examId)
            if (exam == null) {
                emitMessage("Sınav bulunamadı.")
                return@launch
            }

            currentExam = exam
            examName = exam.name
            subjectCount = exam.subjectCount

            val loadedKeys = examRepository.getAnswerKeysForExam(examId)
            val subjectNames = subjects

            answerItems = if (loadedKeys.isEmpty()) {
                val total = exam.subjectCount * exam.questionsPerSubject
                val defaultWeight = 100.0 / total
                buildList {
                    for (subjectIndex in 0 until exam.subjectCount) {
                        for (questionNumber in 1..exam.questionsPerSubject) {
                            add(
                                QuestionEditorState(
                                    subjectIndex = subjectIndex,
                                    questionNumber = questionNumber,
                                    correctAnswer = 0,
                                    weight = defaultWeight,
                                    isWeightLocked = false
                                )
                            )
                        }
                    }
                }
            } else {
                loadedKeys.sortedWith(compareBy<AnswerKey> { it.subjectIndex }.thenBy { it.questionNumber })
                    .map {
                        QuestionEditorState(
                            id = it.id,
                            subjectIndex = it.subjectIndex,
                            questionNumber = it.questionNumber,
                            correctAnswer = it.correctAnswer,
                            weight = it.weight,
                            isWeightLocked = it.isWeightLocked
                        )
                    }
            }

            val generatedSubjects = (0 until subjectCount).map { index ->
                val existing = subjectNames.getOrNull(index)
                val questionCount = answerItems.count { it.subjectIndex == index }
                SubjectConfig(
                    name = existing?.name?.ifBlank { "DERS ${index + 1}" } ?: "DERS ${index + 1}",
                    questionCount = questionCount,
                    optionCount = exam.optionCount
                )
            }
            subjects = generatedSubjects
        }
    }

    fun setAnswer(itemIndex: Int, answer: Int) {
        val item = answerItems.getOrNull(itemIndex) ?: return
        val maxOptions = subjects.getOrNull(item.subjectIndex)?.optionCount ?: 4
        if (answer !in 0 until maxOptions) return

        answerItems = answerItems.toMutableList().also {
            it[itemIndex] = item.copy(correctAnswer = answer)
        }
    }

    fun setWeight(itemIndex: Int, newWeight: Double) {
        if (itemIndex !in answerItems.indices) return
        if (newWeight <= 0.0) {
            emitMessage("Ağırlık sıfırdan büyük olmalıdır.")
            return
        }

        val lockedWeights = answerItems.mapIndexedNotNull { index, item ->
            when {
                index == itemIndex -> index to newWeight
                item.isWeightLocked -> index to item.weight
                else -> null
            }
        }.toMap()

        val recalculated = scoringEngine.recalculateWeights(
            questionCount = answerItems.size,
            lockedWeights = lockedWeights,
            total = 100.0
        )

        if (recalculated == null) {
            emitMessage("Kilitli puanlar 100'ü aşamaz.")
            return
        }

        answerItems = answerItems.mapIndexed { index, item ->
            item.copy(
                weight = recalculated[index],
                isWeightLocked = lockedWeights.containsKey(index)
            )
        }
    }

    fun toggleLock(itemIndex: Int) {
        if (itemIndex !in answerItems.indices) return

        val toggledItems = answerItems.toMutableList()
        val current = toggledItems[itemIndex]
        toggledItems[itemIndex] = current.copy(isWeightLocked = !current.isWeightLocked)

        val lockedWeights = toggledItems.mapIndexedNotNull { index, item ->
            if (item.isWeightLocked) index to item.weight else null
        }.toMap()

        val recalculated = scoringEngine.recalculateWeights(
            questionCount = toggledItems.size,
            lockedWeights = lockedWeights,
            total = 100.0
        )

        if (recalculated == null) {
            emitMessage("Kilitli puanlar 100'e eşit veya büyük olamaz.")
            return
        }

        answerItems = toggledItems.mapIndexed { index, item ->
            item.copy(weight = recalculated[index])
        }
    }

    fun generateQr() {
        val exam = currentExam
        val examId = currentExamId
        if (exam == null || examId == null || answerItems.isEmpty()) {
            emitMessage("QR oluşturmak için önce sınav verileri yüklenmeli.")
            return
        }

        val subjectPayloads = (0 until subjectCount).map { subjectIndex ->
            val rows = answerItems.filter { it.subjectIndex == subjectIndex }
                .sortedBy { it.questionNumber }
            QRSubject(
                name = subjects.getOrNull(subjectIndex)?.name?.ifBlank { "DERS ${subjectIndex + 1}" }
                    ?: "DERS ${subjectIndex + 1}",
                answers = rows.map { it.correctAnswer },
                weights = rows.map { (it.weight * 100).toInt() / 100.0 }
            )
        }

        val payload = QRPayload(
            id = "exam_$examId",
            name = exam.name,
            subjects = subjectPayloads,
            total = 100
        )

        currentQrData = gson.toJson(payload)
        generatedQrBitmap = qrGenerator.generateQR(currentQrData.orEmpty())
    }

    fun saveAnswerKeys(onSaved: () -> Unit) {
        val examId = currentExamId
        val exam = currentExam
        if (examId == null || exam == null) {
            emitMessage("Sınav bilgisi bulunamadı.")
            return
        }

        viewModelScope.launch {
            val keys = answerItems.map {
                AnswerKey(
                    id = it.id,
                    examId = examId,
                    subjectIndex = it.subjectIndex,
                    questionNumber = it.questionNumber,
                    correctAnswer = it.correctAnswer,
                    weight = it.weight,
                    isWeightLocked = it.isWeightLocked
                )
            }
            examRepository.saveAnswerKeys(keys)

            val updatedExam = exam.copy(qrData = currentQrData)
            examRepository.updateExam(updatedExam)
            onSaved()
        }
    }

    fun getTotalWeight(): Double {
        return answerItems.sumOf { it.weight }
    }

    private fun emitMessage(message: String) {
        viewModelScope.launch {
            _uiMessage.emit(message)
        }
    }
}
