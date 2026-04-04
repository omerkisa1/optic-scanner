package com.omrreader.ui.screens.exam

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.omrreader.processing.FormFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateExamScreen(
    onBack: () -> Unit,
    onNext: (Long) -> Unit,
    viewModel: ExamViewModel = hiltViewModel()
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var questionInput by remember(viewModel.questionCount) { mutableStateOf(viewModel.questionCount.toString()) }

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
                OutlinedTextField(
                    value = viewModel.examCode,
                    onValueChange = viewModel::onExamCodeChange,
                    label = { Text("Benzersiz Kod (Opsiyonel)") },
                    supportingText = { Text("Aynı isimli sınavları ayırt etmek için kullanılır.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            item {
                OutlinedTextField(
                    value = questionInput,
                    onValueChange = {
                        val filtered = it.filter { ch -> ch.isDigit() }
                        questionInput = filtered
                        filtered.toIntOrNull()?.let { count ->
                            viewModel.onQuestionCountChange(count.coerceIn(5, 50))
                        }
                    },
                    label = { Text("Soru Sayısı (5-50)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            item {
                Text(
                    text = "Form Formatı",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = viewModel.formFormat == FormFormat.CLASSIC_BORDERED,
                        onClick = { viewModel.onFormFormatChange(FormFormat.CLASSIC_BORDERED) },
                        label = { Text("Klasik (Tablolu)") }
                    )
                    FilterChip(
                        selected = viewModel.formFormat == FormFormat.COMPACT_ZIPGRADE,
                        onClick = { viewModel.onFormFormatChange(FormFormat.COMPACT_ZIPGRADE) },
                        label = { Text("Kompakt (Bordürsüz)") }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Sınıf / Öğretim / Şube (Opsiyonel)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Switch(
                        checked = viewModel.classConfigEnabled,
                        onCheckedChange = viewModel::onClassConfigEnabledChange
                    )
                }
            }

            if (viewModel.classConfigEnabled) {
                item {
                    OutlinedTextField(
                        value = viewModel.gradeLevel,
                        onValueChange = viewModel::onGradeLevelChange,
                        label = { Text("Sınıf (Örn: 1)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                item {
                    Text(
                        text = "Öğretim",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = viewModel.educationType == EducationType.FIRST,
                            onClick = { viewModel.onEducationTypeChange(EducationType.FIRST) },
                            label = { Text("1. Öğretim") }
                        )
                        FilterChip(
                            selected = viewModel.educationType == EducationType.SECOND,
                            onClick = { viewModel.onEducationTypeChange(EducationType.SECOND) },
                            label = { Text("2. Öğretim") }
                        )
                    }
                }

                item {
                    OutlinedTextField(
                        value = viewModel.branchInput,
                        onValueChange = viewModel::onBranchInputChange,
                        label = { Text("Şubeler (virgülle)") },
                        supportingText = { Text("Varsayılan: A,B  |  Örnek: A,B,C") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        }
    }
}

