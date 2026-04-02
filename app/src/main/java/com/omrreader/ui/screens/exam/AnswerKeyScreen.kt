package com.omrreader.ui.screens.exam

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.abs

private data class WeightDialogState(
    val index: Int,
    val currentWeight: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnswerKeyScreen(
    examId: Long,
    onBack: () -> Unit,
    onFinish: () -> Unit,
    viewModel: ExamViewModel = hiltViewModel()
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var weightDialogState by remember { mutableStateOf<WeightDialogState?>(null) }
    var weightInput by remember { mutableStateOf("") }
    var showQrDialog by remember { mutableStateOf(false) }

    LaunchedEffect(examId) {
        viewModel.loadAnswerEditor(examId)
    }

    LaunchedEffect(Unit) {
        viewModel.uiMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val indexedItems = viewModel.answerItems.mapIndexed { index, item -> index to item }
    val groupedItems = indexedItems.groupBy { it.second.subjectIndex }
    val totalWeight = viewModel.getTotalWeight()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cevap Anahtarı") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Geri")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        viewModel.generateQr()
                        showQrDialog = viewModel.generatedQrBitmap != null
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("QR Oluştur")
                }
                Button(
                    onClick = {
                        viewModel.saveAnswerKeys(onFinish)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Kaydet")
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                val color = if (abs(100.0 - totalWeight) < 0.01) {
                    Color(0xFF1B5E20)
                } else {
                    MaterialTheme.colorScheme.error
                }
                Text(
                    text = "Toplam Ağırlık: ${"%.2f".format(totalWeight)} / 100.00",
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            groupedItems.forEach { (subjectIndex, rows) ->
                item {
                    Text(
                        text = viewModel.subjects.getOrNull(subjectIndex)?.name?.ifBlank { "DERS ${subjectIndex + 1}" }
                            ?: "DERS ${subjectIndex + 1}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(rows, key = { it.first }) { indexedItem ->
                    val globalIndex = indexedItem.first
                    val item = indexedItem.second
                    QuestionEditorRow(
                        questionNumber = item.questionNumber,
                        optionCount = viewModel.subjects.getOrNull(item.subjectIndex)?.optionCount ?: 4,
                        selectedAnswer = item.correctAnswer,
                        weight = item.weight,
                        isLocked = item.isWeightLocked,
                        onAnswerSelected = { answer -> viewModel.setAnswer(globalIndex, answer) },
                        onWeightClick = {
                            weightDialogState = WeightDialogState(globalIndex, item.weight)
                            weightInput = "%.2f".format(item.weight)
                        },
                        onLockClick = { viewModel.toggleLock(globalIndex) }
                    )
                }
            }
        }
    }

    if (weightDialogState != null) {
        AlertDialog(
            onDismissRequest = { weightDialogState = null },
            title = { Text("Soru Ağırlığı") },
            text = {
                OutlinedTextField(
                    value = weightInput,
                    onValueChange = { input ->
                        weightInput = input.filter { it.isDigit() || it == '.' || it == ',' }
                    },
                    label = { Text("Puan") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = weightInput.replace(',', '.').toDoubleOrNull()
                        if (parsed != null) {
                            viewModel.setWeight(weightDialogState!!.index, parsed)
                        }
                        weightDialogState = null
                    }
                ) {
                    Text("Uygula")
                }
            },
            dismissButton = {
                TextButton(onClick = { weightDialogState = null }) {
                    Text("Vazgeç")
                }
            }
        )
    }

    if (showQrDialog && viewModel.generatedQrBitmap != null) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text("QR Kod") },
            text = {
                Image(
                    bitmap = viewModel.generatedQrBitmap!!.asImageBitmap(),
                    contentDescription = "QR Kod",
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = { showQrDialog = false }) {
                    Text("Kapat")
                }
            }
        )
    }
}

@Composable
private fun QuestionEditorRow(
    questionNumber: Int,
    optionCount: Int,
    selectedAnswer: Int,
    weight: Double,
    isLocked: Boolean,
    onAnswerSelected: (Int) -> Unit,
    onWeightClick: () -> Unit,
    onLockClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = "Soru $questionNumber",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (option in 0 until optionCount) {
                val selected = selectedAnswer == option
                Button(
                    onClick = { onAnswerSelected(option) },
                    modifier = Modifier.weight(1f)
                ) {
                    val label = ('A'.code + option).toChar().toString()
                    val value = if (selected) "[$label]" else label
                    Text(value)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Puan: ${"%.2f".format(weight)}",
                modifier = Modifier.clickable(onClick = onWeightClick),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            TextButton(onClick = onLockClick) {
                Text(if (isLocked) "Kilidi Aç" else "Kilitle")
            }
        }
    }
}
