package com.omrreader.ui.screens.classroom

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassroomDetailScreen(
    classroomId: Long,
    onBack: () -> Unit,
    onOpenRoster: (Long) -> Unit,
    viewModel: ClassroomViewModel = hiltViewModel()
) {
    val classroom by viewModel.getClassroom(classroomId).collectAsState(initial = null)
    val students by viewModel.getStudents(classroomId).collectAsState(initial = emptyList())
    val results by viewModel.getClassroomResults(classroomId).collectAsState(initial = emptyList())
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(classroom?.courseName ?: "Sınıf Detayı") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Geri") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                classroom?.let { c ->
                    Text(c.courseName, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${c.gradeLevel} ${c.section} - ${c.educationType}",
                        color = Color.Gray
                    )
                    Text("${students.size} öğrenci", color = Color.Gray)
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onOpenRoster(classroomId) }) {
                        Text("Öğrenci Listesi")
                    }
                    if (results.isNotEmpty()) {
                        Button(onClick = {
                            viewModel.exportClassroomExcel(
                                context = context,
                                classroomId = classroomId,
                                results = results
                            ) { file ->
                                if (file != null) {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/vnd.ms-excel"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(shareIntent, "Excel Raporu")
                                    )
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Rapor oluşturulamadı")
                                    }
                                }
                            }
                        }) {
                            Text("Excel İndir")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (results.isNotEmpty()) {
                item {
                    Text("Sınav Sonuçları", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                items(results) { result ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    result.rosterName ?: result.ocrName ?: "Bilinmiyor",
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    result.rosterNumber ?: result.ocrNumber ?: "-",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "${"%.1f".format(result.totalScore)}",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "D:${result.correctCount} Y:${result.wrongCount} B:${result.emptyCount}",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    Text(
                        "Henüz sınav sonucu atanmadı.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }
        }
    }
}
