package com.omrreader.processing

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

data class OCRResult(
    val rawText: String,
    val confidence: Float,
    val processedText: String,
    val region: String
)

@Singleton
class OCRProcessor @Inject constructor() {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap, region: android.graphics.Rect, regionName: String): OCRResult {
        return try {
            val cropped = Bitmap.createBitmap(bitmap, region.left, region.top, region.width(), region.height())
            val image = InputImage.fromBitmap(cropped, 0)
            
            val result = recognizer.process(image).await()
            val text = result.text.trim()
            
            // Basic post-processing
            val processed = when (regionName) {
                "number" -> text.filter { it.isDigit() }
                "name" -> text // Maybe filter non-letters
                else -> text
            }

            OCRResult(
                rawText = text,
                confidence = 0.8f, // ML Kit v2 doesn't always expose confidence per block easily without deep dive
                processedText = processed,
                region = regionName
            )
        } catch (e: Exception) {
            e.printStackTrace()
            OCRResult("", 0f, "", regionName)
        }
    }
}
