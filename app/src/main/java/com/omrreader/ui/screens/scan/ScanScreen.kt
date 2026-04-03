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
        val padding = 40f
        val bracketLen = 50f
        val strokeW = 3f

        val left = padding
        val top = padding * 2
        val right = size.width - padding
        val bottom = size.height - padding * 3

        val color = when (markerState) {
            is MarkerGuideState.Ready -> Color(0xFF4CAF50)
            is MarkerGuideState.Partial -> Color(0xFFFFC107)
            MarkerGuideState.NotFound -> Color.White.copy(alpha = 0.5f)
        }

        drawRect(Color.Black.copy(alpha = 0.3f),
            topLeft = Offset.Zero,
            size = Size(size.width, top))
        drawRect(Color.Black.copy(alpha = 0.3f),
            topLeft = Offset(0f, bottom),
            size = Size(size.width, size.height - bottom))
        drawRect(Color.Black.copy(alpha = 0.3f),
            topLeft = Offset(0f, top),
            size = Size(left, bottom - top))
        drawRect(Color.Black.copy(alpha = 0.3f),
            topLeft = Offset(right, top),
            size = Size(size.width - right, bottom - top))

        drawLine(color, Offset(left, top), Offset(left + bracketLen, top), strokeWidth = strokeW)
        drawLine(color, Offset(left, top), Offset(left, top + bracketLen), strokeWidth = strokeW)

        drawLine(color, Offset(right, top), Offset(right - bracketLen, top), strokeWidth = strokeW)
        drawLine(color, Offset(right, top), Offset(right, top + bracketLen), strokeWidth = strokeW)

        drawLine(color, Offset(left, bottom), Offset(left + bracketLen, bottom), strokeWidth = strokeW)
        drawLine(color, Offset(left, bottom), Offset(left, bottom - bracketLen), strokeWidth = strokeW)

        drawLine(color, Offset(right, bottom), Offset(right - bracketLen, bottom), strokeWidth = strokeW)
        drawLine(color, Offset(right, bottom), Offset(right, bottom - bracketLen), strokeWidth = strokeW)

        if (markerState is MarkerGuideState.Ready) {
            markerState.markers.forEach { marker ->
                val cx = marker.x * size.width
                val cy = marker.y * size.height
                drawCircle(Color(0xFF4CAF50), radius = 8f, center = Offset(cx, cy))
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
        is MarkerGuideState.Ready -> "Kağıt algılandı" to Color(0xFF4CAF50)
        is MarkerGuideState.Partial -> "Kağıdı çerçeveye hizalayın" to Color(0xFFFFC107)
        MarkerGuideState.NotFound -> "Optik formu çerçeveye yerleştirin" to Color.White
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
