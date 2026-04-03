package com.omrreader.ui.screens.scan

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

private data class OverrideDialogState(
    val questionIndex: Int,
    val optionCount: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onConfirmSaved: () -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    viewModel: ScanViewModel
) {
    val reviewResult by viewModel.reviewResult.collectAsState()
    viewModel.answerOverrides.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var overrideDialog by remember { mutableStateOf<OverrideDialogState?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var showDebugDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.consumeReviewNavigation()
    }

    if (reviewResult == null) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Tarama Önizleme") }) }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Tarama sonucu bulunamadı.")
                Button(
                    onClick = onRetake,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text("Tarama Ekranına Dön")
                }
                Button(
                    onClick = onCancel,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Ana Sayfa")
                }
            }
        }
        return
    }

    val result = reviewResult!!
    var studentName by remember(result.studentName) { mutableStateOf(result.studentName) }
    var studentNumber by remember(result.studentNumber) { mutableStateOf(result.studentNumber) }
    var className by remember(result.className) { mutableStateOf(result.className) }

    val nameConfidence = result.ocrConfidence["name"] ?: 0f
    val numberConfidence = result.ocrConfidence["number"] ?: 0f
    val classConfidence = result.ocrConfidence["class"] ?: 0f

    val effectiveAnswers = viewModel.getEffectiveAnswers()
    val correctAnswers = viewModel.getCorrectAnswers()
    val score = viewModel.getEffectiveScore() ?: result.scoreResult

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tarama Sonucu Onay") }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "OCR Alanları",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
            EditableFieldWithConfidence(
                label = "Ad Soyad",
                value = studentName,
                confidence = nameConfidence,
                onValueChange = { studentName = it }
            )
            EditableFieldWithConfidence(
                label = "Numara",
                value = studentNumber,
                confidence = numberConfidence,
                onValueChange = { studentNumber = it }
            )
            EditableFieldWithConfidence(
                label = "Sınıf",
                value = className,
                confidence = classConfidence,
                onValueChange = { className = it }
            )

            val hasDebugImage = !result.debugImagePath.isNullOrBlank()
            val hasOmrLogs = result.omrDebugLines.isNotEmpty()

            if (hasDebugImage || hasOmrLogs) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (hasDebugImage) {
                        Button(
                            onClick = { showDebugDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Debug Görüntüsü Göster")
                        }
                    }

                    if (hasOmrLogs) {
                        Button(
                            onClick = { showLogDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Log Göster")
                        }
                    }
                }
            }

            Text(
                text = "Cevaplar",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(result.omrResults) { index, question ->
                    val selected = effectiveAnswers.getOrNull(index)
                    val correct = correctAnswers.getOrNull(index)
                    val status = when {
                        !question.isValid -> "⚠"
                        selected == null -> "—"
                        selected == correct -> "✓"
                        else -> "✗"
                    }
                    val statusColor = when (status) {
                        "✓" -> Color(0xFF2E7D32)
                        "✗" -> Color(0xFFC62828)
                        "⚠" -> Color(0xFFF9A825)
                        else -> Color(0xFF757575)
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                overrideDialog = OverrideDialogState(
                                    questionIndex = index,
                                    optionCount = question.bubbleStates.size
                                )
                            }
                            .padding(vertical = 2.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Soru ${question.questionNumber}",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = status,
                                    color = statusColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Text(text = "İşaret: ${bubbleVisuals(selected, question.bubbleStates.size)}")
                            Text(text = "Doğru: ${answerLabel(correct)}")
                        }
                    }
                }
            }

            Text(
                text = "Doğru: ${score.correctCount} | Yanlış: ${score.wrongCount} | Boş: ${score.emptyCount} | Puan: ${"%.2f".format(score.totalScore)}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 10.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        isSaving = true
                        viewModel.saveConfirmedResult(
                            studentName = studentName,
                            studentNumber = studentNumber,
                            className = className
                        ) { success, message ->
                            isSaving = false
                            if (success) {
                                onConfirmSaved()
                            } else {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(message)
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isSaving
                ) {
                    Text("Onayla ve Kaydet")
                }

                Button(
                    onClick = {
                        viewModel.resetReviewSession()
                        onRetake()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isSaving
                ) {
                    Text("Tekrar Çek")
                }

                Button(
                    onClick = {
                        viewModel.resetReviewSession()
                        onCancel()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isSaving
                ) {
                    Text("İptal")
                }
            }
        }

        if (isSaving) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }

    if (overrideDialog != null) {
        val dialog = overrideDialog!!
        AlertDialog(
            onDismissRequest = { overrideDialog = null },
            title = { Text("Cevap Değiştir") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (option in 0 until dialog.optionCount) {
                            Button(onClick = {
                                viewModel.updateAnswerOverride(dialog.questionIndex, option)
                                overrideDialog = null
                            }) {
                                Text(answerLabel(option))
                            }
                        }
                    }
                    TextButton(onClick = {
                        viewModel.updateAnswerOverride(dialog.questionIndex, null)
                        overrideDialog = null
                    }) {
                        Text("Boş")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { overrideDialog = null }) {
                    Text("Kapat")
                }
            }
        )
    }

    if (showDebugDialog) {
        val debugBitmap = remember(result.debugImagePath) {
            result.debugImagePath
                ?.takeIf { it.isNotBlank() }
                ?.let { path -> BitmapFactory.decodeFile(path) }
        }

        Dialog(onDismissRequest = { showDebugDialog = false }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
            ) {
                if (debugBitmap != null) {
                    Image(
                        bitmap = debugBitmap.asImageBitmap(),
                        contentDescription = "OMR debug görüntüsü",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = "Debug görüntüsü yüklenemedi.",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                TextButton(
                    onClick = { showDebugDialog = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Text("Kapat")
                }
            }
        }
    }

    if (showLogDialog) {
        Dialog(onDismissRequest = { showLogDialog = false }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "FillRatio Logları",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    result.thresholdDebugImagePath?.takeIf { it.isNotBlank() }?.let { thresholdPath ->
                        Text(
                            text = "Threshold: $thresholdPath",
                            color = Color(0xFFB3E5FC),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    result.debugImagePath?.takeIf { it.isNotBlank() }?.let { overlayPath ->
                        Text(
                            text = "Overlay: $overlayPath",
                            color = Color(0xFFB3E5FC),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(result.omrDebugLines.ifEmpty { listOf("Log bulunamadı.") }) { line ->
                            Text(
                                text = line,
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                TextButton(
                    onClick = { showLogDialog = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Text("Kapat")
                }
            }
        }
    }
}

@Composable
private fun EditableFieldWithConfidence(
    label: String,
    value: String,
    confidence: Float,
    onValueChange: (String) -> Unit
) {
    val (confidenceColor, confidenceIcon, confidenceLabel) = when {
        confidence > 0.8f -> Triple(Color(0xFF2E7D32), "✓", "Güvenli")
        confidence >= 0.5f -> Triple(Color(0xFFF9A825), "⚠", "Kontrol edin")
        else -> Triple(Color(0xFFC62828), "✗", "Muhtemelen yanlış")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.weight(1f),
            singleLine = true
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(confidenceColor, shape = CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(confidenceIcon, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Text(
                text = confidenceLabel,
                color = confidenceColor,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun answerLabel(answer: Int?): String {
    if (answer == null) return "-"
    return ('A'.code + answer).toChar().toString()
}

private fun bubbleVisuals(selected: Int?, optionCount: Int): String {
    return buildString {
        for (index in 0 until optionCount) {
            append(if (selected == index) "●" else "○")
            if (index < optionCount - 1) append(" ")
        }
    }
}
