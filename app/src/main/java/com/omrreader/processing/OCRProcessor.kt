package com.omrreader.processing

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class OCRResult(
    val rawText: String,
    val confidence: Float,
    val processedText: String,
    val region: String,
    val allCandidates: List<String> = emptyList()
)

@Singleton
class OCRProcessor @Inject constructor() {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val trLocale = Locale("tr", "TR")
    private val minTargetWidth = 1000.0

    suspend fun recognize(bitmap: Bitmap, region: android.graphics.Rect, regionName: String): OCRResult {
        return try {
            val fullImage = Mat()
            Utils.bitmapToMat(bitmap, fullImage)

            val safeRegion = sanitizeRegion(region, fullImage.cols(), fullImage.rows())
            val roi = prepareROIForOCR(fullImage, safeRegion)

            val binaryCandidateMat = enhanceForOCR(roi)
            val grayCandidateMat = prepareEnhancedGrayVersion(roi)

            val (textBinary, confBinary) = recognizeCandidate(binaryCandidateMat)
            val (textGray, confGray) = recognizeCandidate(grayCandidateMat)

            val bestRaw = if (confBinary >= confGray) textBinary else textGray
            val bestConfidence = maxOf(confBinary, confGray)

            val processed = postProcess(bestRaw, regionName)
            val candidates = listOf(textBinary, textGray)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

            OCRResult(
                rawText = bestRaw,
                confidence = bestConfidence,
                processedText = processed,
                region = regionName,
                allCandidates = candidates
            )
        } catch (t: Throwable) {
            t.printStackTrace()
            OCRResult("", 0f, "", regionName)
        }
    }

    private fun sanitizeRegion(region: android.graphics.Rect, maxWidth: Int, maxHeight: Int): Rect {
        val left = region.left.coerceIn(0, maxWidth - 1)
        val top = region.top.coerceIn(0, maxHeight - 1)
        val right = region.right.coerceIn(left + 1, maxWidth)
        val bottom = region.bottom.coerceIn(top + 1, maxHeight)
        return Rect(left, top, right - left, bottom - top)
    }

    private fun prepareROIForOCR(fullImage: Mat, roiRect: Rect): Mat {
        var roi = Mat(fullImage, roiRect).clone()

        if (roi.cols() < minTargetWidth) {
            val scale = minTargetWidth / roi.cols().coerceAtLeast(1).toDouble()
            val upscaled = Mat()
            Imgproc.resize(roi, upscaled, Size(0.0, 0.0), scale, scale, Imgproc.INTER_CUBIC)
            roi = upscaled
        }

        return roi
    }

    private fun enhanceForOCR(roi: Mat): Mat {
        val gray = toGray(roi)

        val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(gray, enhanced)

        val denoised = Mat()
        Imgproc.bilateralFilter(enhanced, denoised, 9, 75.0, 75.0)

        val kernel = Mat(3, 3, CvType.CV_32F)
        kernel.put(
            0,
            0,
            0.0, -1.0, 0.0,
            -1.0, 5.0, -1.0,
            0.0, -1.0, 0.0
        )

        val sharpened = Mat()
        Imgproc.filter2D(denoised, sharpened, -1, kernel)

        val binary = Mat()
        Imgproc.adaptiveThreshold(
            sharpened,
            binary,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            21,
            10.0
        )

        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, closeKernel)

        val padded = Mat()
        Core.copyMakeBorder(binary, padded, 20, 20, 20, 20, Core.BORDER_CONSTANT, Scalar(255.0))
        return padded
    }

    private fun prepareEnhancedGrayVersion(roi: Mat): Mat {
        val gray = toGray(roi)

        val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(gray, enhanced)

        val scale = minTargetWidth / enhanced.cols().coerceAtLeast(1).toDouble()
        val appliedScale = scale.coerceAtLeast(1.0)
        val upscaled = Mat()
        Imgproc.resize(enhanced, upscaled, Size(0.0, 0.0), appliedScale, appliedScale, Imgproc.INTER_CUBIC)

        val padded = Mat()
        Core.copyMakeBorder(upscaled, padded, 20, 20, 20, 20, Core.BORDER_CONSTANT, Scalar(255.0))
        return padded
    }

    private fun toGray(input: Mat): Mat {
        val gray = Mat()
        when (input.channels()) {
            4 -> Imgproc.cvtColor(input, gray, Imgproc.COLOR_RGBA2GRAY)
            3 -> Imgproc.cvtColor(input, gray, Imgproc.COLOR_BGR2GRAY)
            else -> input.copyTo(gray)
        }
        return gray
    }

    private suspend fun recognizeCandidate(candidateMat: Mat): Pair<String, Float> {
        val image = InputImage.fromBitmap(matToBitmap(candidateMat), 0)
        val result = recognizer.process(image).await()
        val text = result.text.trim().replace(Regex("\\s+"), " ")
        val confidence = getAverageConfidence(result, text)
        return text to confidence
    }

    private fun matToBitmap(input: Mat): Bitmap {
        val rgba = Mat()
        when (input.channels()) {
            1 -> Imgproc.cvtColor(input, rgba, Imgproc.COLOR_GRAY2RGBA)
            3 -> Imgproc.cvtColor(input, rgba, Imgproc.COLOR_BGR2RGBA)
            else -> input.copyTo(rgba)
        }

        val output = Bitmap.createBitmap(
            rgba.cols().coerceAtLeast(1),
            rgba.rows().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(rgba, output)
        return output
    }

    private fun postProcess(rawText: String, fieldType: String): String {
        var text = rawText.trim()

        when (fieldType) {
            "name" -> {
                text = text.replace(Regex("[0-9@#$%^&*()_+=\\[\\]{}|\\\\<>/]"), "")
                text = text
                    .replace("0", "O")
                    .replace("1", "I")
                    .replace("|", "I")
                    .replace("}", ")")
                    .replace("{", "(")
                    .replace(Regex("\\s+"), " ")
                    .replace(Regex("[^A-Za-zÇĞİÖŞÜçğıöşü\\s'-]"), "")
                    .trim()

                text = text.split(" ")
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { word ->
                        capitalizeTurkishWord(word)
                    }
            }

            "number" -> {
                text = rawText
                    .replace("O", "0").replace("o", "0")
                    .replace("I", "1").replace("l", "1")
                    .replace("S", "5").replace("s", "5")
                    .replace("B", "8")
                    .filter { it.isDigit() }
            }

            "class" -> {
                text = text
                    .replace(Regex("\\s+"), " ")
                    .replace(Regex("[^A-Za-z0-9ÇĞİÖŞÜçğıöşü/\\s.-]"), "")
                    .trim()
            }
        }

        return text
    }

    private fun capitalizeTurkishWord(rawWord: String): String {
        val word = rawWord.lowercase(trLocale)
        if (word.isBlank()) return word
        return when (word.first()) {
            'i' -> "İ" + word.drop(1)
            else -> word.replaceFirstChar { it.titlecase(trLocale) }
        }
    }

    private fun getAverageConfidence(visionText: Text, recognizedText: String): Float {
        val elements = visionText.textBlocks
            .flatMap { it.lines }
            .flatMap { it.elements }

        val confidences = elements.mapNotNull { element ->
            extractElementConfidence(element)
        }

        if (confidences.isNotEmpty()) {
            return confidences.average().toFloat().coerceIn(0f, 1f)
        }

        if (recognizedText.isBlank()) return 0f
        val compact = recognizedText.replace(Regex("\\s+"), "")
        if (compact.isBlank()) return 0f

        val alnumRatio = compact.count { it.isLetterOrDigit() }.toFloat() / compact.length.coerceAtLeast(1)
        val lengthScore = (compact.length.coerceAtMost(20).toFloat() / 20f)
        val heuristic = 0.35f + (alnumRatio * 0.35f) + (lengthScore * 0.30f)
        return heuristic.coerceIn(0.30f, 0.90f)
    }

    private fun extractElementConfidence(element: Text.Element): Float? {
        return try {
            val method = element.javaClass.methods.firstOrNull {
                it.name == "getConfidence" && it.parameterCount == 0
            } ?: return null

            val rawValue = method.invoke(element) ?: return null
            when (rawValue) {
                is Float -> rawValue
                is Double -> rawValue.toFloat()
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
