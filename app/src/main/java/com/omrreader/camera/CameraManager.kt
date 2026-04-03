package com.omrreader.camera

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@Singleton
class CameraManager @Inject constructor() {

    suspend fun bindCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        imageCapture: ImageCapture
    ) {
        val cameraProvider = getCameraProvider(context)

        // Minimum Resolution 1280x720, step 1
        val preview = Preview.Builder()
            .setTargetResolution(android.util.Size(1280, 720))
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
        } catch (exc: Exception) {
            throw Exception("Use case binding failed", exc)
        }
    }

    // Step 1: Real-time Blur Detection (Laplacian Variance)
    fun isBlurry(bitmap: Bitmap, threshold: Double = 100.0): Boolean {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
        
        val laplacian = Mat()
        Imgproc.Laplacian(gray, laplacian, CvType.CV_64F, 3)
        // Ensure Laplacian Mat is in a format where variance calculation works without overflow
        
        val mean = org.opencv.core.MatOfDouble()
        val stddev = org.opencv.core.MatOfDouble()
        org.opencv.core.Core.meanStdDev(laplacian, mean, stddev)
        
        val variance = Math.pow(stddev.get(0, 0)[0], 2.0)
        return variance < threshold
    }

    private suspend fun getCameraProvider(context: Context): ProcessCameraProvider = suspendCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                continuation.resume(future.get())
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
