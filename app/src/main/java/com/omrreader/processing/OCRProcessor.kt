package com.omrreader.processing

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Locale
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
    private val trLocale = Locale("tr", "TR")

    suspend fun recognize(bitmap: Bitmap, region: android.graphics.Rect, regionName: String): OCRResult {
        return try {
            val safeRegion = sanitizeRegion(region, bitmap.width, bitmap.height)
            val cropped = Bitmap.createBitmap(
                bitmap,
                safeRegion.left,
                safeRegion.top,
                safeRegion.width(),
                safeRegion.height()
            )
            val prepared = upscaleAndSharpen(cropped)
            val image = InputImage.fromBitmap(prepared, 0)
            
            val result = recognizer.process(image).await()
            val text = result.text.trim().replace(Regex("\\s+"), " ")

            val processed = when (regionName) {
                "number" -> postProcessNumber(text)
                "name" -> postProcessName(text)
                "class" -> postProcessClass(text)
                else -> text.trim()
            }

            OCRResult(
                rawText = text,
                confidence = if (processed.isNotBlank()) 0.85f else 0.0f,
                processedText = processed,
                region = regionName
            )
        } catch (t: Throwable) {
            t.printStackTrace()
            OCRResult("", 0f, "", regionName)
        }
    }

    private fun sanitizeRegion(region: android.graphics.Rect, maxWidth: Int, maxHeight: Int): android.graphics.Rect {
        val left = region.left.coerceIn(0, maxWidth - 1)
        val top = region.top.coerceIn(0, maxHeight - 1)
        val right = region.right.coerceIn(left + 1, maxWidth)
        val bottom = region.bottom.coerceIn(top + 1, maxHeight)
        return android.graphics.Rect(left, top, right, bottom)
    }

    private fun upscaleAndSharpen(source: Bitmap, scale: Double = 2.0): Bitmap {
        val input = Mat()
        Utils.bitmapToMat(source, input)

        val upscaled = Mat()
        Imgproc.resize(input, upscaled, Size(0.0, 0.0), scale, scale, Imgproc.INTER_CUBIC)

        val kernel = Mat(3, 3, CvType.CV_32F)
        kernel.put(
            0,
            0,
            0.0, -1.0, 0.0,
            -1.0, 5.0, -1.0,
            0.0, -1.0, 0.0
        )

        val sharpened = Mat()
        Imgproc.filter2D(upscaled, sharpened, -1, kernel)

        val output = Bitmap.createBitmap(
            sharpened.cols().coerceAtLeast(1),
            sharpened.rows().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(sharpened, output)
        return output
    }

    private fun postProcessName(rawText: String): String {
        if (rawText.isBlank()) return ""

        var text = rawText
            .replace("|", "I")
            .replace("0", "O")
            .replace("1", "I")
            .replace("5", "S")

        text = text.replace(Regex("[^A-Za-zÇĞİÖŞÜçğıöşü\\s'-]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        return text.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.lowercase(trLocale).replaceFirstChar { ch -> ch.titlecase(trLocale) }
            }
    }

    private fun postProcessNumber(rawText: String): String {
        return rawText.filter { it.isDigit() }
    }

    private fun postProcessClass(rawText: String): String {
        return rawText
            .replace("|", "I")
            .replace(Regex("[^A-Za-z0-9ÇĞİÖŞÜçğıöşü/\\s-]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .uppercase(trLocale)
    }
}
