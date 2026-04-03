package com.omrreader.ui.screens.scan

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.hypot
import kotlin.math.roundToInt
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

sealed class MarkerGuideState {
    object NotFound : MarkerGuideState()
    data class Partial(val markers: List<MarkerPoint>) : MarkerGuideState()
    data class Ready(val markers: List<MarkerPoint>) : MarkerGuideState()
}

data class MarkerPoint(
    val x: Float,
    val y: Float
)

@Composable
fun CameraPreview(
    onImageCaptured: (Bitmap) -> Unit,
    onMarkerStateChanged: (MarkerGuideState) -> Unit = {},
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
    val captureInFlight = remember { AtomicBoolean(false) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setTargetResolution(Size(1920, 1080))
            .build()
    }

    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    fun captureAction() {
        if (!captureEnabled) return
        if (!captureInFlight.compareAndSet(false, true)) return

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
                        captureInFlight.set(false)
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    captureInFlight.set(false)
                    onError("Fotoğraf çekilemedi. Lütfen tekrar deneyin.")
                }
            }
        )
    }

    LaunchedEffect(lifecycleOwner) {
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        imageAnalysis.clearAnalyzer()
        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            try {
                if (!captureEnabled) {
                    previewView.post { onMarkerStateChanged(MarkerGuideState.NotFound) }
                    return@setAnalyzer
                }

                val bitmap = imageProxy.toBitmap()
                val normalized = normalizeCapturedBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
                val markerState = detectMarkerState(normalized)
                previewView.post {
                    onMarkerStateChanged(markerState)
                }

                if (!normalized.isRecycled) {
                    normalized.recycle()
                }
            } catch (_: Throwable) {
                previewView.post { onMarkerStateChanged(MarkerGuideState.NotFound) }
            } finally {
                imageProxy.close()
            }
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
                imageAnalysis
            )
        } catch (e: Exception) {
            onError("Kamera başlatılamadı. Lütfen tekrar deneyin.")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onMarkerStateChanged(MarkerGuideState.NotFound)
            imageAnalysis.clearAnalyzer()
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
                captureAction()
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

private fun detectMarkerState(bitmap: Bitmap): MarkerGuideState {
    if (bitmap.isRecycled || bitmap.width <= 1 || bitmap.height <= 1) {
        return MarkerGuideState.NotFound
    }

    val source = Mat()
    Utils.bitmapToMat(bitmap, source)

    val gray = Mat()
    if (source.channels() == 4) {
        Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
    } else {
        Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
    }
    Imgproc.GaussianBlur(gray, gray, org.opencv.core.Size(5.0, 5.0), 0.0)

    val binary = Mat()
    Imgproc.adaptiveThreshold(
        gray,
        binary,
        255.0,
        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
        Imgproc.THRESH_BINARY_INV,
        41,
        10.0
    )

    val contours = mutableListOf<MatOfPoint>()
    val hierarchy = Mat()
    Imgproc.findContours(binary.clone(), contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

    val imageArea = source.width().toDouble() * source.height().toDouble()
    val minArea = imageArea * 0.0006
    val maxArea = imageArea * 0.08

    val candidates = contours.mapNotNull { contour ->
        val area = Imgproc.contourArea(contour)
        if (area !in minArea..maxArea) return@mapNotNull null

        val rect = Imgproc.boundingRect(contour)
        if (rect.width < 8 || rect.height < 8) return@mapNotNull null

        val aspect = rect.width.toDouble() / rect.height.toDouble().coerceAtLeast(1.0)
        if (aspect < 0.8 || aspect > 1.2) return@mapNotNull null

        val fill = area / (rect.width.toDouble() * rect.height.toDouble()).coerceAtLeast(1.0)
        if (fill < 0.45) return@mapNotNull null

        Point(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0)
    }

    if (candidates.isEmpty()) return MarkerGuideState.NotFound

    val selected = selectCornerMarkers(candidates, source.width().toDouble(), source.height().toDouble())
    return when {
        selected.size == 4 -> MarkerGuideState.Ready(selected.map { it.toMarkerPoint(source.width(), source.height()) })
        selected.size >= 2 -> MarkerGuideState.Partial(selected.map { it.toMarkerPoint(source.width(), source.height()) })
        candidates.size >= 2 -> MarkerGuideState.Partial(
            candidates.take(2).map { it.toMarkerPoint(source.width(), source.height()) }
        )
        else -> MarkerGuideState.NotFound
    }
}

private fun selectCornerMarkers(candidates: List<Point>, width: Double, height: Double): List<Point> {
    val pool = candidates.toMutableList()
    val diagonal = hypot(width, height).coerceAtLeast(1.0)
    val maxDistance = diagonal * 0.35

    val cornerTargets = listOf(
        Point(0.0, 0.0),
        Point(width, 0.0),
        Point(width, height),
        Point(0.0, height)
    )

    val selected = mutableListOf<Point>()
    for (target in cornerTargets) {
        val best = pool.minByOrNull { point ->
            val dx = point.x - target.x
            val dy = point.y - target.y
            (dx * dx) + (dy * dy)
        } ?: continue

        val distance = hypot(best.x - target.x, best.y - target.y)
        if (distance <= maxDistance) {
            selected.add(best)
            pool.remove(best)
        }
    }

    return selected
}

private fun Point.toMarkerPoint(width: Int, height: Int): MarkerPoint {
    val nx = (x / width.toDouble()).toFloat().coerceIn(0f, 1f)
    val ny = (y / height.toDouble()).toFloat().coerceIn(0f, 1f)
    return MarkerPoint(nx, ny)
}
