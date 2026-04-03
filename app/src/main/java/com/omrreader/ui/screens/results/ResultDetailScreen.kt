package com.omrreader.ui.screens.results

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

data class AnswerDetailRow(
    val questionNumber: Int,
    val marked: Int?,
    val correct: Int?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultDetailScreen(
    resultId: Long,
    onBack: () -> Unit,
    viewModel: ResultViewModel = hiltViewModel()
) {
    val result by viewModel.selectedResult.collectAsState()
    val correctAnswers by viewModel.selectedCorrectAnswers.collectAsState()

    LaunchedEffect(resultId) {
        viewModel.loadResultDetail(resultId)
    }

    val rows = remember(result?.answersJson, correctAnswers) {
        parseAnswerRows(result?.answersJson.orEmpty(), correctAnswers)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Öğrenci Detayı") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Geri")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = result?.studentName ?: "İsimsiz",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("Numara: ${result?.studentNumber ?: "-"}")
                        Text("Sınıf: ${result?.className ?: "-"}")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Puan: ${"%.1f".format(result?.totalScore ?: 0.0)}",
                            fontSize = 24.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Doğru: ${result?.correctCount ?: 0} | Yanlış: ${result?.wrongCount ?: 0} | Boş: ${result?.emptyCount ?: 0}"
                        )
                    }
                }
            }

            item {
                Text(
                    text = "Soru Detayları",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }

            items(rows) { row ->
                val isCorrect = row.marked != null && row.marked == row.correct
                val isEmpty = row.marked == null

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            when {
                                isEmpty -> Color.Gray.copy(alpha = 0.1f)
                                isCorrect -> Color(0xFF4CAF50).copy(alpha = 0.1f)
                                else -> Color(0xFFF44336).copy(alpha = 0.1f)
                            },
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Soru ${row.questionNumber}",
                        modifier = Modifier.width(70.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("Cevap: ${answerLabel(row.marked)}")
                    Text("Doğru: ${answerLabel(row.correct)}")
                    if (isEmpty) {
                        Text(
                            text = "—",
                            color = Color.Gray,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Icon(
                            imageVector = if (isCorrect) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            tint = if (isCorrect) Color(0xFF4CAF50) else Color(0xFFF44336)
                        )
                    }
                }
            }
        }
    }
}

private fun parseAnswerRows(answersJson: String, correctAnswers: List<Int>): List<AnswerDetailRow> {
    if (answersJson.isBlank()) {
        return correctAnswers.mapIndexed { index, correct ->
            AnswerDetailRow(
                questionNumber = index + 1,
                marked = null,
                correct = correct
            )
        }
    }

    return try {
        val listType = object : TypeToken<List<Map<String, Any?>>>() {}.type
        val rows: List<Map<String, Any?>> = Gson().fromJson(answersJson, listType)
        val parsedRows = rows.mapNotNull { map ->
            val q = (map["q"] as? Number)?.toInt() ?: return@mapNotNull null
            val marked = (map["marked"] as? Number)?.toInt()
            if (q <= 0) return@mapNotNull null
            AnswerDetailRow(q, marked, correctAnswers.getOrNull(q - 1))
        }

        val maxQuestion = maxOf(
            correctAnswers.size,
            parsedRows.maxOfOrNull { it.questionNumber } ?: 0
        )
        if (maxQuestion == 0) return emptyList()

        val byQuestion = parsedRows.associateBy { it.questionNumber }
        (1..maxQuestion).map { questionNumber ->
            val row = byQuestion[questionNumber]
            AnswerDetailRow(
                questionNumber = questionNumber,
                marked = row?.marked,
                correct = correctAnswers.getOrNull(questionNumber - 1)
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun answerLabel(answer: Int?): String {
    if (answer == null) return "—"
    return ('A'.code + answer).toChar().toString()
}

private fun statusIcon(marked: Int?, correct: Int?): Pair<String, Color> {
    return when {
        marked == null || correct == null -> "○" to Color(0xFFB8860B)
        marked == correct -> "✓" to Color(0xFF2E7D32)
        else -> "✗" to Color(0xFFC62828)
    }
}
