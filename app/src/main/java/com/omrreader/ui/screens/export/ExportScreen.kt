package com.omrreader.ui.screens.export

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.omrreader.ui.screens.results.ExportState
import com.omrreader.ui.screens.results.ResultViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    examId: Long,
    onBack: () -> Unit,
    viewModel: ResultViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val exportState by viewModel.exportState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var pendingMimeType by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(exportState) {
        when (val state = exportState) {
            is ExportState.Success -> {
                val file = File(state.filePath)
                val mime = pendingMimeType ?: "application/octet-stream"
                if (file.exists()) {
                    shareFile(context, file, mime)
                } else {
                    snackbarHostState.showSnackbar("Dosya bulunamadı.")
                }
                viewModel.resetExportState()
            }

            is ExportState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.resetExportState()
            }

            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dışa Aktar") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Geri") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    pendingMimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    viewModel.exportExcel(examId, context)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Excel Olarak Dışa Aktar")
            }

            Button(
                onClick = {
                    pendingMimeType = "application/pdf"
                    viewModel.exportPdf(examId, context)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text("PDF Olarak Dışa Aktar")
            }

            if (exportState is ExportState.Loading) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 20.dp))
                Text(
                    text = "Dosya hazırlanıyor...",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

fun shareFile(context: Context, file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Dışa Aktar"))
}
