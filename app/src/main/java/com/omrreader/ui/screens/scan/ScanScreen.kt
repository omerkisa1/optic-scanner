package com.omrreader.ui.screens.scan

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ScanScreen(
    examId: Long,
    onBack: () -> Unit,
    onScanSuccess: () -> Unit,
    viewModel: ScanViewModel = hiltViewModel()
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val scanState by viewModel.scanState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var markerState by remember { mutableStateOf<MarkerGuideState>(MarkerGuideState.NotFound) }

    LaunchedEffect(examId) {
        viewModel.setActiveExam(examId)
    }

    LaunchedEffect(Unit) {
        if (cameraPermissionState.status !is PermissionStatus.Granted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    LaunchedEffect(scanState) {
        when (val state = scanState) {
            is ScanState.ReviewReady -> {
                onScanSuccess()
            }

            is ScanState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.clearError()
            }

            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Form Tara") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Geri")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        if (cameraPermissionState.status is PermissionStatus.Granted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                CameraPreview(
                    onImageCaptured = viewModel::processImage,
                    onMarkerStateChanged = { state ->
                        markerState = state
                    },
                    onError = { message ->
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(message)
                        }
                    },
                    autoCaptureOnReady = true,
                    captureEnabled = scanState !is ScanState.Processing,
                    modifier = Modifier.fillMaxSize()
                )

                if (scanState !is ScanState.Processing) {
                    MarkerGuideOverlay(
                        markerState = markerState,
                        modifier = Modifier.fillMaxSize()
                    )
                    MarkerGuideHint(
                        markerState = markerState,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                    )
                }

                if (scanState is ScanState.Processing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "Kağıt işleniyor...",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Kamera izni gerekli. Ayarlardan izin verin.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge
                )
                Button(
                    onClick = { cameraPermissionState.launchPermissionRequest() },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("İzni Tekrar İste")
                }
                Button(
                    onClick = onBack,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text("Geri Dön")
                }
            }
        }
    }
}

@Composable
private fun MarkerGuideOverlay(
    markerState: MarkerGuideState,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        when (markerState) {
            is MarkerGuideState.Ready -> {
                val points = markerState.markers.map { marker ->
                    Offset(marker.x * size.width, marker.y * size.height)
                }
                if (points.size == 4) {
                    val path = Path().apply {
                        moveTo(points[0].x, points[0].y)
                        lineTo(points[1].x, points[1].y)
                        lineTo(points[2].x, points[2].y)
                        lineTo(points[3].x, points[3].y)
                        close()
                    }
                    drawPath(
                        path = path,
                        color = Color(0xFF2E7D32),
                        style = Stroke(width = 6f)
                    )
                }

                markerState.markers.forEach { marker ->
                    val cx = marker.x * size.width
                    val cy = marker.y * size.height
                    drawRect(
                        color = Color(0xFF2E7D32),
                        topLeft = Offset(cx - 24f, cy - 24f),
                        size = Size(48f, 48f),
                        style = Stroke(width = 4f)
                    )
                }
            }

            is MarkerGuideState.Partial -> {
                drawRect(
                    color = Color(0xFFF9A825),
                    topLeft = Offset(8f, 8f),
                    size = Size(size.width - 16f, size.height - 16f),
                    style = Stroke(width = 5f)
                )

                markerState.markers.forEach { marker ->
                    val cx = marker.x * size.width
                    val cy = marker.y * size.height
                    drawRect(
                        color = Color(0xFFF9A825),
                        topLeft = Offset(cx - 22f, cy - 22f),
                        size = Size(44f, 44f),
                        style = Stroke(width = 3f)
                    )
                }
            }

            MarkerGuideState.NotFound -> {
                drawRect(
                    color = Color(0xFFD32F2F),
                    topLeft = Offset(8f, 8f),
                    size = Size(size.width - 16f, size.height - 16f),
                    style = Stroke(width = 5f)
                )
            }
        }
    }
}

@Composable
private fun MarkerGuideHint(
    markerState: MarkerGuideState,
    modifier: Modifier = Modifier
) {
    val (text, color) = when (markerState) {
        is MarkerGuideState.Ready -> "Hazır - Çek" to Color(0xFF2E7D32)
        is MarkerGuideState.Partial -> "Kağıdı düzelt..." to Color(0xFFF9A825)
        MarkerGuideState.NotFound -> "Kağıt algılanamıyor" to Color(0xFFD32F2F)
    }

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
