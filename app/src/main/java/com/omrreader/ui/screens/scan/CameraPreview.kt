package com.omrreader.ui.screens.scan

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.max
import kotlin.math.roundToInt
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun CameraPreview(
    onImageCaptured: (Bitmap) -> Unit,
    onError: (String) -> Unit,
    captureEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setTargetResolution(Size(1920, 1080))
            .build()
    }

    LaunchedEffect(lifecycleOwner) {
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
        } catch (e: Exception) {
            onError("Kamera başlatılamadı. Lütfen tekrar deneyin.")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            if (cameraProviderFuture.isDone) {
                cameraProviderFuture.get().unbindAll()
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        FloatingActionButton(
            onClick = {
                if (!captureEnabled) return@FloatingActionButton
                imageCapture.takePicture(
                    cameraExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val bitmap = image.toBitmap()
                                val normalized = normalizeCapturedBitmap(bitmap, image.imageInfo.rotationDegrees)
                                onImageCaptured(normalized)
                            } catch (t: Throwable) {
                                onError("Fotoğraf işlenemedi. Lütfen tekrar çekin.")
                            } finally {
                                image.close()
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            onError("Fotoğraf çekilemedi. Lütfen tekrar deneyin.")
                        }
                    }
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            Text(if (captureEnabled) "Çek" else "İşleniyor")
        }
    }
}

private fun normalizeCapturedBitmap(source: Bitmap, rotationDegrees: Int): Bitmap {
    val rotated = rotateIfNeeded(source, rotationDegrees)
    return downscaleIfNeeded(rotated, maxLongEdge = 2400)
}

private fun rotateIfNeeded(source: Bitmap, rotationDegrees: Int): Bitmap {
    if (rotationDegrees % 360 == 0) return source

    val matrix = Matrix().apply {
        postRotate(rotationDegrees.toFloat())
    }
    val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (rotated != source && !source.isRecycled) {
        source.recycle()
    }
    return rotated
}

private fun downscaleIfNeeded(source: Bitmap, maxLongEdge: Int): Bitmap {
    val longEdge = max(source.width, source.height)
    if (longEdge <= maxLongEdge) return source

    val scale = maxLongEdge.toDouble() / longEdge.toDouble()
    val targetWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (source.height * scale).roundToInt().coerceAtLeast(1)

    val scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    if (scaled != source && !source.isRecycled) {
        source.recycle()
    }
    return scaled
}
