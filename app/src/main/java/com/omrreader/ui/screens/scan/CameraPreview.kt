package com.omrreader.ui.screens.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun CameraPreview(
    onImageCaptured: (Bitmap) -> Unit,
    onError: (String) -> Unit,
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
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
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
                imageCapture.takePicture(
                    cameraExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val bitmap = image.toBitmap()
                                onImageCaptured(bitmap)
                            } catch (e: Exception) {
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
            Text("Çek")
        }
    }
}

private fun ImageProxy.toBitmap(): Bitmap {
    val nv21 = toNv21ByteArray()
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 95, out)
    val imageBytes = out.toByteArray()
    val rawBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

    val matrix = Matrix().apply {
        postRotate(imageInfo.rotationDegrees.toFloat())
    }
    return Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
}

private fun ImageProxy.toNv21ByteArray(): ByteArray {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    yBuffer.get(nv21, 0, ySize)

    val uBytes = ByteArray(uSize)
    val vBytes = ByteArray(vSize)
    uBuffer.get(uBytes)
    vBuffer.get(vBytes)

    var outputOffset = ySize
    val chromaHeight = height / 2
    val chromaWidth = width / 2

    var uRowStart = 0
    var vRowStart = 0

    for (row in 0 until chromaHeight) {
        var uIndex = uRowStart
        var vIndex = vRowStart
        for (col in 0 until chromaWidth) {
            nv21[outputOffset++] = vBytes[vIndex]
            nv21[outputOffset++] = uBytes[uIndex]
            uIndex += planes[1].pixelStride
            vIndex += planes[2].pixelStride
        }
        uRowStart += planes[1].rowStride
        vRowStart += planes[2].rowStride
    }

    return nv21
}
