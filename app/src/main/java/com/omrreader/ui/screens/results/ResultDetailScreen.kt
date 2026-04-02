package com.omrreader.ui.screens.results

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

data class AnswerDetailRow(
    val questionNumber: Int,
    val marked: Int?,
    val state: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultDetailScreen(
    resultId: Long,
    onBack: () -> Unit,
    viewModel: ResultViewModel = hiltViewModel()
) {
    val result by viewModel.selectedResult.collectAsState()

    LaunchedEffect(resultId) {
        viewModel.loadResultDetail(resultId)
    }

    val rows = remember(result?.answersJson) {
        parseAnswerRows(result?.answersJson.orEmpty())
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

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Soru ${row.questionNumber}")
                        Text("Cevap: ${answerLabel(row.marked)}")
                        Text(row.state)
                    }
                }
            }
        }
    }
}

private fun parseAnswerRows(answersJson: String): List<AnswerDetailRow> {
    if (answersJson.isBlank()) return emptyList()

    return try {
        val listType = object : TypeToken<List<Map<String, Any?>>>() {}.type
        val rows: List<Map<String, Any?>> = Gson().fromJson(answersJson, listType)
        rows.mapNotNull { map ->
            val q = (map["q"] as? Number)?.toInt() ?: return@mapNotNull null
            val marked = (map["marked"] as? Number)?.toInt()
            val state = map["state"]?.toString() ?: "-"
            AnswerDetailRow(q, marked, state)
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun answerLabel(answer: Int?): String {
    if (answer == null) return "-"
    return ('A'.code + answer).toChar().toString()
}
