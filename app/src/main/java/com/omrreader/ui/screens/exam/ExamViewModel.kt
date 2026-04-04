package com.omrreader.ui.screens.exam

import android.content.Context
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
import com.omrreader.export.FormGenerator
import com.omrreader.export.FormSubjectLayout
import com.omrreader.qr.QRGenerator
import com.omrreader.scoring.ScoringEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SubjectConfig(
    val name: String,
    val questionCount: Int,
    val optionCount: Int
)

enum class EducationType(val label: String) {
    FIRST("1. Öğretim"),
    SECOND("2. Öğretim")
}

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
    val weights: List<Double>,
    val optionCount: Int? = null
)

sealed class FormExportState {
    object Idle : FormExportState()
    object Loading : FormExportState()
    data class Success(val filePath: String) : FormExportState()
    data class Error(val message: String) : FormExportState()
}

private const val DEFAULT_OPTION_COUNT = 5

@HiltViewModel
class ExamViewModel @Inject constructor(
    private val examRepository: ExamRepository,
    private val scoringEngine: ScoringEngine,
    private val qrGenerator: QRGenerator,
    private val formGenerator: FormGenerator,
    private val gson: Gson
) : ViewModel() {

    var examName by mutableStateOf("")
        private set

    var examCode by mutableStateOf("")
        private set

    var questionCount by mutableIntStateOf(20)
        private set

    var classConfigEnabled by mutableStateOf(false)
        private set

    var gradeLevel by mutableStateOf("1")
        private set

    var educationType by mutableStateOf<EducationType?>(EducationType.FIRST)
        private set

    var branchInput by mutableStateOf("A,B")
        private set

    var subjectCount by mutableIntStateOf(1)
        private set

    var subjects by mutableStateOf(listOf(SubjectConfig("DERS 1", questionCount, DEFAULT_OPTION_COUNT)))
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

    private val _formExportState = MutableStateFlow<FormExportState>(FormExportState.Idle)
    val formExportState: StateFlow<FormExportState> = _formExportState.asStateFlow()

    fun onExamNameChange(value: String) {
        examName = value
        subjects = subjects.mapIndexed { index, subject ->
            if (index == 0) {
                subject.copy(name = value.trim().ifBlank { "DERS 1" })
            } else {
                subject
            }
        }
    }

    fun onExamCodeChange(value: String) {
        examCode = value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    fun onQuestionCountChange(value: Int) {
        if (value !in 5..50) return
        questionCount = value
        subjects = subjects.mapIndexed { index, subject ->
            if (index == 0) subject.copy(questionCount = value) else subject
        }
    }

    fun onClassConfigEnabledChange(enabled: Boolean) {
        classConfigEnabled = enabled
        if (enabled && branchInput.isBlank()) {
            branchInput = "A,B"
        }
    }

    fun onGradeLevelChange(value: String) {
        gradeLevel = value.filter { it.isDigit() }
    }

    fun onEducationTypeChange(value: EducationType) {
        educationType = value
    }

    fun onBranchInputChange(value: String) {
        branchInput = value.uppercase()
            .filter { it.isLetterOrDigit() || it == ',' || it == ' ' }
    }

    fun onSubjectCountChange(value: Int) {
        if (value !in 1..2) return
        subjectCount = value
        subjects = if (value == 1) {
            listOf(subjects.firstOrNull() ?: SubjectConfig("DERS 1", 20, DEFAULT_OPTION_COUNT))
        } else {
            val first = subjects.firstOrNull() ?: SubjectConfig("DERS 1", 20, DEFAULT_OPTION_COUNT)
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
        val baseName = examName.trim()
        if (baseName.isBlank()) {
            emitMessage("Sınav adı boş olamaz.")
            return
        }

        if (questionCount !in 5..50) {
            emitMessage("Soru sayısı 5 ile 50 arasında olmalı.")
            return
        }

        viewModelScope.launch {
            val normalizedBaseName = normalize(baseName)
            val normalizedCode = normalize(examCode)

            val existingExams = examRepository.getAllExams()
            val hasConflict = existingExams.any { exam ->
                val (existingBaseName, existingCode) = splitStoredExamName(exam.name)
                val sameBase = normalize(existingBaseName) == normalizedBaseName
                if (!sameBase) {
                    false
                } else if (normalizedCode.isBlank()) {
                    true
                } else {
                    normalize(existingCode.orEmpty()) == normalizedCode
                }
            }

            if (hasConflict) {
                if (normalizedCode.isBlank()) {
                    emitMessage("Bu sınav adı zaten var. Farklı ad girin veya benzersiz kod ekleyin.")
                } else {
                    emitMessage("Bu sınav adı ve kod kombinasyonu zaten var.")
                }
                return@launch
            }

            val displayName = buildDisplayName(baseName)
            val subjectConfig = SubjectConfig(
                name = baseName,
                questionCount = questionCount,
                optionCount = DEFAULT_OPTION_COUNT
            )

            subjectCount = 1
            subjects = listOf(subjectConfig)

            val examId = examRepository.createExam(
                name = displayName,
                subjectCount = 1,
                questionsPerSubject = subjectConfig.questionCount,
                optionCount = subjectConfig.optionCount
            )

            val defaultWeight = 100.0 / subjectConfig.questionCount
            for (questionNumber in 1..subjectConfig.questionCount) {
                examRepository.insertAnswerKey(
                    examId = examId,
                    subjectIndex = 0,
                    questionNumber = questionNumber,
                    correctAnswer = 0,
                    weight = defaultWeight,
                    isWeightLocked = false
                )
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
            questionCount = exam.questionsPerSubject
            currentQrData = exam.qrData

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
                    name = existing?.name?.ifBlank { defaultSubjectName(index, exam) }
                        ?: defaultSubjectName(index, exam),
                    questionCount = questionCount,
                    optionCount = exam.optionCount
                )
            }
            subjects = generatedSubjects
        }
    }

    fun setAnswer(itemIndex: Int, answer: Int) {
        val item = answerItems.getOrNull(itemIndex) ?: return
        val maxOptions = subjects.getOrNull(item.subjectIndex)?.optionCount ?: DEFAULT_OPTION_COUNT
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

        val qrData = buildQrData(exam, examId) ?: return
        currentQrData = qrData
        generatedQrBitmap = qrGenerator.generateQR(qrData)
    }

    fun exportTemplateForm(context: Context) {
        val exam = currentExam
        val examId = currentExamId

        if (exam == null || examId == null) {
            emitMessage("Form üretmek için önce sınav verileri yüklenmeli.")
            return
        }

        viewModelScope.launch {
            _formExportState.value = FormExportState.Loading

            val qrData = currentQrData ?: buildQrData(exam, examId)
            val layouts = subjects
                .ifEmpty { listOf(SubjectConfig("DERS 1", questionCount, DEFAULT_OPTION_COUNT)) }
                .mapIndexed { index, subject ->
                    FormSubjectLayout(
                        name = subject.name.ifBlank { "DERS ${index + 1}" },
                        questionCount = subject.questionCount.coerceAtLeast(1),
                        optionCount = subject.optionCount.coerceIn(2, 8)
                    )
                }

            val file = formGenerator.generateTemplatePdf(
                context = context,
                examName = exam.name,
                subjects = layouts,
                qrData = qrData
            )

            if (file != null) {
                currentQrData = qrData
                _formExportState.value = FormExportState.Success(file.absolutePath)
            } else {
                _formExportState.value = FormExportState.Error("Optik form oluşturulamadı.")
            }
        }
    }

    fun resetFormExportState() {
        _formExportState.value = FormExportState.Idle
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

    private fun defaultSubjectName(index: Int, exam: Exam): String {
        return if (exam.subjectCount == 1 && index == 0) {
            splitStoredExamName(exam.name).first.ifBlank { "DERS 1" }
        } else {
            "DERS ${index + 1}"
        }
    }

    private fun parseBranches(raw: String): List<String> {
        return raw.split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun buildClassDescriptor(): String? {
        if (!classConfigEnabled) return null

        val parts = mutableListOf<String>()
        val level = gradeLevel.trim()
        if (level.isNotBlank()) {
            parts += "$level. Sınıf"
        }

        educationType?.let { parts += it.label }

        val branches = parseBranches(branchInput)
        if (branches.isNotEmpty()) {
            parts += "${branches.joinToString("/")} Şubesi"
        }

        return parts.takeIf { it.isNotEmpty() }?.joinToString(" | ")
    }

    private fun buildDisplayName(baseName: String): String {
        val code = examCode.trim().uppercase()
        val baseWithCode = if (code.isBlank()) {
            baseName
        } else {
            "$baseName [$code]"
        }

        val classDescriptor = buildClassDescriptor()
        return if (classDescriptor.isNullOrBlank()) {
            baseWithCode
        } else {
            "$baseWithCode | $classDescriptor"
        }
    }

    private fun splitStoredExamName(raw: String): Pair<String, String?> {
        val identityPart = raw.substringBefore(" | ").trim()
        val codeMatch = Regex("\\[(.+)]$").find(identityPart)
        return if (codeMatch != null) {
            val code = codeMatch.groupValues[1].trim()
            val baseName = identityPart.removeRange(codeMatch.range).trim()
            baseName to code
        } else {
            identityPart to null
        }
    }

    private fun normalize(value: String): String {
        return value.trim().lowercase().replace(Regex("\\s+"), " ")
    }

    private fun buildQrData(exam: Exam, examId: Long): String? {
        if (answerItems.isEmpty()) {
            emitMessage("QR içeriği oluşturmak için soru anahtarı bulunamadı.")
            return null
        }

        val subjectPayloads = (0 until subjectCount).map { subjectIndex ->
            val rows = answerItems.filter { it.subjectIndex == subjectIndex }
                .sortedBy { it.questionNumber }
            QRSubject(
                name = subjects.getOrNull(subjectIndex)?.name?.ifBlank { "DERS ${subjectIndex + 1}" }
                    ?: "DERS ${subjectIndex + 1}",
                answers = rows.map { it.correctAnswer },
                weights = rows.map { (it.weight * 100).toInt() / 100.0 },
                optionCount = subjects.getOrNull(subjectIndex)?.optionCount
            )
        }

        val payload = QRPayload(
            id = "exam_$examId",
            name = exam.name,
            subjects = subjectPayloads,
            total = 100
        )

        return gson.toJson(payload)
    }

    private fun emitMessage(message: String) {
        viewModelScope.launch {
            _uiMessage.emit(message)
        }
    }
}
