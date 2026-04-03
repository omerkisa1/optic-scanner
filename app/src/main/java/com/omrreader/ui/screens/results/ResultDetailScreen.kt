package com.omrreader.ui.screens.results

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = result?.studentName ?: "İsimsiz",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text("Numara: ${result?.studentNumber ?: "-"}")
            Text("Sınıf: ${result?.className ?: "-"}")
            Text(
                text = "Puan: ${result?.totalScore ?: 0.0}",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp)
            )

            Text(
                text = "Soru Detayı",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Soru",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Öğrenci",
                    modifier = Modifier.weight(1.2f),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Doğru",
                    modifier = Modifier.weight(1.2f),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Durum",
                    modifier = Modifier.weight(0.8f),
                    fontWeight = FontWeight.SemiBold
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows) { row ->
                    val (symbol, symbolColor) = statusIcon(row.marked, row.correct)
                    Card {
                        Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp, horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = row.questionNumber.toString(),
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = answerLabel(row.marked),
                                modifier = Modifier.weight(1.2f)
                            )
                            Text(
                                text = answerLabel(row.correct),
                                modifier = Modifier.weight(1.2f)
                            )
                            Box(modifier = Modifier.weight(0.8f)) {
                                Text(
                                    text = symbol,
                                    color = symbolColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
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
