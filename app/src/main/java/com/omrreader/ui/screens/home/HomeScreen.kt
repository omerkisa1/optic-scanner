package com.omrreader.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToCreate: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToScan: (Long) -> Unit,
    onNavigateToCreateClassroom: () -> Unit = {},
    onNavigateToClassroomDetail: (Long) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val exams by viewModel.exams.collectAsState()
    val classrooms by viewModel.classrooms.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadExams() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OMR Scanner", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToCreate,
                containerColor = MaterialTheme.colorScheme.secondary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Yeni Sınav", tint = MaterialTheme.colorScheme.onSecondary)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (classrooms.isNotEmpty() || exams.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sınıflarım", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = onNavigateToCreateClassroom) {
                            Text("+ Yeni Sınıf")
                        }
                    }
                }

                if (classrooms.isEmpty()) {
                    item {
                        Text(
                            "Henüz sınıf eklenmedi.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                } else {
                    items(classrooms) { classroom ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateToClassroomDetail(classroom.id) },
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(classroom.courseName, fontWeight = FontWeight.Bold)
                                Text(
                                    "${classroom.gradeLevel} ${classroom.section} - ${classroom.educationType}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Sınavlarım", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }

            if (exams.isEmpty()) {
                item {
                    Text(
                        text = "Henüz sınav eklenmedi.\nYeni bir sınav oluşturmak için + butonuna basın.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(vertical = 32.dp)
                    )
                }
            } else {
                items(exams) { item ->
                    ExamCard(
                        item = item,
                        onClick = { onNavigateToDetail(item.exam.id) },
                        onDelete = { viewModel.deleteExam(item.exam) },
                        onScan = { onNavigateToScan(item.exam.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun ExamCard(
    item: HomeExamItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onScan: () -> Unit
) {
    val exam = item.exam
    val date = remember(exam.createdAt) {
        SimpleDateFormat("dd.MM.yyyy", Locale("tr", "TR")).format(Date(exam.createdAt))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = exam.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (exam.subjectCount <= 1) {
                        "${exam.questionsPerSubject} Soru"
                    } else {
                        "${exam.subjectCount} Ders - ${exam.questionsPerSubject} Soru"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Tarih: $date",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Taranan Öğrenci: ${item.scannedStudentCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onScan) {
                    Text("Tara")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Sil", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
