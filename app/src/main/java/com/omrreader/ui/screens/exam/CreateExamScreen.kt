package com.omrreader.ui.screens.exam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateExamScreen(
    onBack: () -> Unit,
    onNext: (Long) -> Unit,
    viewModel: ExamViewModel = hiltViewModel()
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Yeni Sınav") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Geri")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Button(
                onClick = { viewModel.createExam(onNext) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("İleri")
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                OutlinedTextField(
                    value = viewModel.examName,
                    onValueChange = viewModel::onExamNameChange,
                    label = { Text("Sınav Adı") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    singleLine = true
                )
            }

            item {
                Text(
                    text = "Ders Sayısı",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = viewModel.subjectCount == 1,
                        onClick = { viewModel.onSubjectCountChange(1) },
                        label = { Text("1 Ders") }
                    )
                    FilterChip(
                        selected = viewModel.subjectCount == 2,
                        onClick = { viewModel.onSubjectCountChange(2) },
                        label = { Text("2 Ders") }
                    )
                }
            }

            items(viewModel.subjects.size) { index ->
                val subject = viewModel.subjects[index]
                SubjectConfigCard(
                    index = index,
                    config = subject,
                    onNameChange = { viewModel.onSubjectNameChange(index, it) },
                    onQuestionCountChange = { viewModel.onSubjectQuestionCountChange(index, it) },
                    onOptionCountChange = { viewModel.onSubjectOptionCountChange(index, it) }
                )
            }
        }
    }
}

@Composable
private fun SubjectConfigCard(
    index: Int,
    config: SubjectConfig,
    onNameChange: (String) -> Unit,
    onQuestionCountChange: (Int) -> Unit,
    onOptionCountChange: (Int) -> Unit
) {
    var questionInput by remember(config.questionCount) { mutableStateOf(config.questionCount.toString()) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Ders ${index + 1}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        OutlinedTextField(
            value = config.name,
            onValueChange = onNameChange,
            label = { Text("Ders Adı") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = questionInput,
            onValueChange = {
                questionInput = it.filter { ch -> ch.isDigit() }
                val parsed = questionInput.toIntOrNull()
                if (parsed != null) onQuestionCountChange(parsed.coerceIn(5, 50))
            },
            label = { Text("Soru Sayısı (5-50)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = config.optionCount == 4,
                onClick = { onOptionCountChange(4) },
                label = { Text("4 Şık") }
            )
            FilterChip(
                selected = config.optionCount == 5,
                onClick = { onOptionCountChange(5) },
                label = { Text("5 Şık") }
            )
        }
    }
}
