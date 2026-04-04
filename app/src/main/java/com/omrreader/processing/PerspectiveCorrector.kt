package com.omrreader.processing

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Corrects perspective distortion of a photographed OMR answer sheet.
 *
 * Detection strategy (4-stage fallback):
 *
 *  1. Otsu global threshold → detectMarkerBasedCorners()
 *     Best for high-contrast black squares on white paper (our default form).
 *
 *  2. Adaptive threshold (blockSize=41) → detectMarkerBasedCorners()
 *     Handles uneven lighting where global threshold fails.
 *
 *  3. Multi-threshold tolerant scan → findMarkersInBinaryTolerant()
 *     Looser area/solidity filter; last resort before contour-based fallback.
 *
 *  4. detectPageContourCorners() – finds the largest rectangular contour.
 *     Works without markers but less accurate.
 *
 *  5. resizeFallbackIfLikelyAligned() – centre-crop + resize with no warp.
 *     Last-ditch fallback; warns user to re-shoot if result is poor.
 *
 * Marker geometry (FormTemplate.DEFAULT):
 *   Size   : 40 × 40 px in normalised space
 *   Margin : 80 px white border
 *   Expected area on a typical portrait phone photo (≈3000×4000):
 *     width  ≈ 3000 × 40/1200 ≈ 100 px
 *     height ≈ 4000 × 40/1700 ≈ 94  px  →  area ≈ 9 400 px²
 */
@Singleton
class PerspectiveCorrector @Inject constructor() {

    fun correct(bitmap: Bitmap, template: FormTemplate = FormTemplate.DEFAULT): Bitmap? {
        val source = Mat()
        Utils.bitmapToMat(bitmap, source)

        val gray = Mat()
        if (source.channels() == 4) {
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
        } else {
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
        }
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        // ── Stage 1: Otsu (global) threshold – best for solid black squares ──
        val otsuBinary = Mat()
        Imgproc.threshold(gray, otsuBinary, 0.0, 255.0,
            Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)

        val rawMarkerPoints =
            detectMarkerBasedCorners(otsuBinary, source.width(), source.height(), template)

        // ── Stage 2: adaptive threshold – handles uneven lighting ────────────
            ?: run {
                val adaptBinary = Mat()
                Imgproc.adaptiveThreshold(
                    gray, adaptBinary, 255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY_INV,
                    41, 10.0
                )
                val result = detectMarkerBasedCorners(adaptBinary, source.width(), source.height(), template)
                adaptBinary.release()
                result
            }

        // ── Stage 3: tolerant multi-threshold scan ───────────────────────────
            ?: detectMarkersMultiThreshold(gray, source.width(), source.height(), template)

        otsuBinary.release()

        // Refine raw marker corners with centroid-based sub-pixel accuracy
        val markerPoints = rawMarkerPoints?.let { refineMarkerCorners(gray, it) }

        // ── Stage 4: page contour fallback ───────────────────────────────────
        val sourcePoints = markerPoints
            ?: detectPageContourCorners(gray)
            ?: detectPageContourCorners(run {
                val b = Mat()
                Imgproc.threshold(gray, b, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
                b
            })

        // ── Stage 5: resize-only fallback ────────────────────────────────────
        if (sourcePoints == null) {
            gray.release(); source.release()
            return resizeFallbackIfLikelyAligned(source, template)
        }

        val destinationTargets = if (markerPoints != null) {
            template.markerCornerTargetsNormalized()
        } else {
            template.pageCornerTargetsNormalized()
        }

        val width  = template.normalizedWidth.toDouble()
        val height = template.normalizedHeight.toDouble()

        val destinationPoints = MatOfPoint2f(
            destinationTargets.topLeft.toCvPoint(),
            destinationTargets.topRight.toCvPoint(),
            destinationTargets.bottomRight.toCvPoint(),
            destinationTargets.bottomLeft.toCvPoint()
        )

        val transformMatrix = Imgproc.getPerspectiveTransform(
            MatOfPoint2f(*sourcePoints), destinationPoints
        )
        val corrected = Mat()
        Imgproc.warpPerspective(source, corrected, transformMatrix, Size(width, height))

        gray.release()
        source.release()

        val output = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(corrected, output)
        corrected.release()
        return output
    }

    // ── Centroid-based sub-pixel marker refinement ───────────────────────────

    fun refineMarkerCenter(binary: Mat, roughCenter: Point, searchRadius: Int = 40): Point {
        val x1 = (roughCenter.x - searchRadius).toInt().coerceAtLeast(0)
        val y1 = (roughCenter.y - searchRadius).toInt().coerceAtLeast(0)
        val x2 = (roughCenter.x + searchRadius).toInt().coerceAtMost(binary.cols() - 1)
        val y2 = (roughCenter.y + searchRadius).toInt().coerceAtMost(binary.rows() - 1)
        if (x2 <= x1 || y2 <= y1) return roughCenter

        val roi = binary.submat(y1, y2, x1, x2)
        val moments = Imgproc.moments(roi, true)
        roi.release()

        return if (moments.m00 > 0) {
            Point(moments.m10 / moments.m00 + x1, moments.m01 / moments.m00 + y1)
        } else {
            roughCenter
        }
    }

    private fun refineMarkerCorners(gray: Mat, corners: Array<Point>): Array<Point> {
        val binary = Mat()
        Imgproc.threshold(gray, binary, 0.0, 255.0,
            Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        val refined = corners.map { refineMarkerCenter(binary, it) }.toTypedArray()
        binary.release()
        return refined
    }

    // ── Stage 1 & 2: marker-based corner detection ───────────────────────────
    //
    // Looks for 4 filled square contours near the expected corner positions.
    // Tolerances are calibrated for 40×40 px markers (FormTemplate.DEFAULT).

    private fun detectMarkerBasedCorners(
        binary: Mat,
        imageWidth: Int,
        imageHeight: Int,
        template: FormTemplate
    ): Array<Point>? {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            binary, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )
        hierarchy.release()

        // Expected marker size in image-pixel space
        val expectedW = imageWidth.toDouble()  * template.markerSize.toDouble() / template.normalizedWidth.toDouble()
        val expectedH = imageHeight.toDouble() * template.markerSize.toDouble() / template.normalizedHeight.toDouble()
        val expectedArea = (expectedW * expectedH).coerceAtLeast(36.0)

        // Generous tolerances: 0.10 – 12× area, 0.15 – 4× linear
        val minArea  = expectedArea * 0.10
        val maxArea  = expectedArea * 12.0
        val minW     = (expectedW * 0.15).coerceAtLeast(5.0)
        val maxW     = expectedW * 4.0
        val minH     = (expectedH * 0.15).coerceAtLeast(5.0)
        val maxH     = expectedH * 4.0

        val candidates = contours.mapNotNull { contour ->
            val area = Imgproc.contourArea(contour)
            if (area !in minArea..maxArea) return@mapNotNull null

            val rect = Imgproc.boundingRect(contour)
            if (rect.width <= 0 || rect.height <= 0) return@mapNotNull null
            if (rect.width.toDouble()  !in minW..maxW) return@mapNotNull null
            if (rect.height.toDouble() !in minH..maxH) return@mapNotNull null

            // Aspect ratio: squares should be 0.55 – 1.80 (printed squares can
            // look rectangular due to lens distortion or non-square printouts)
            val aspect = rect.width.toDouble() / rect.height.toDouble()
            if (aspect < 0.55 || aspect > 1.80) return@mapNotNull null

            // Solidity: solid filled squares ≥ 0.50 (was 0.35 – kept generous)
            val bboxArea = (rect.width * rect.height).toDouble().coerceAtLeast(1.0)
            if (area / bboxArea < 0.50) return@mapNotNull null

            // Rough polygon check: should be roughly quadrilateral
            val c2f  = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.04 * peri, true)
            if (approx.total() < 3L || approx.total() > 14L) return@mapNotNull null

            MarkerCandidate(
                rect   = rect,
                center = Point(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0)
            )
        }

        if (candidates.size < 4) return null

        // Match candidates to the 4 expected corner positions
        val expected       = template.markerCentersForImage(imageWidth, imageHeight)
        val imageDiagonal  = hypot(imageWidth.toDouble(), imageHeight.toDouble()).coerceAtLeast(1.0)
        // Allow up to 35 % of diagonal – covers cases where form is off-centre
        val maxDistance    = imageDiagonal * 0.35

        val cornerTargets = listOf(
            CornerType.TOP_LEFT     to expected.topLeft,
            CornerType.TOP_RIGHT    to expected.topRight,
            CornerType.BOTTOM_RIGHT to expected.bottomRight,
            CornerType.BOTTOM_LEFT  to expected.bottomLeft
        )

        val selected = mutableMapOf<CornerType, MarkerCandidate>()
        val pool     = candidates.toMutableList()

        for ((cornerType, target) in cornerTargets) {
            val targetCv = target.toCvPoint()

            // Prefer candidates in the correct image quadrant; fall back to any
            val preferred = pool.filter {
                matchesQuadrant(it.center, cornerType, imageWidth, imageHeight) &&
                    distance(it.center, targetCv) <= maxDistance
            }
            val sourcePool = preferred.ifEmpty {
                pool.filter { distance(it.center, targetCv) <= maxDistance }
            }
            if (sourcePool.isEmpty()) return null

            val best = sourcePool.minByOrNull { candidate ->
                markerScore(candidate, targetCv, expectedW, expectedH, maxDistance)
            } ?: return null

            selected[cornerType] = best
            pool.remove(best)
        }

        val tl = selected[CornerType.TOP_LEFT]     ?: return null
        val tr = selected[CornerType.TOP_RIGHT]    ?: return null
        val br = selected[CornerType.BOTTOM_RIGHT] ?: return null
        val bl = selected[CornerType.BOTTOM_LEFT]  ?: return null

        // Return the OUTER corner of each marker so the warp maps it to
        // markerCornerTargetsNormalized() exactly.
        return arrayOf(
            Point(tl.rect.x.toDouble(),                         tl.rect.y.toDouble()),
            Point((tr.rect.x + tr.rect.width).toDouble(),       tr.rect.y.toDouble()),
            Point((br.rect.x + br.rect.width).toDouble(), (br.rect.y + br.rect.height).toDouble()),
            Point(bl.rect.x.toDouble(),                   (bl.rect.y + bl.rect.height).toDouble())
        )
    }

    // ── Stage 3: tolerant multi-threshold ────────────────────────────────────

    private fun detectMarkersMultiThreshold(
        gray: Mat,
        imageWidth: Int,
        imageHeight: Int,
        template: FormTemplate
    ): Array<Point>? {
        val generators: List<(Mat) -> Mat> = listOf(
            { src ->
                val b = Mat()
                Imgproc.threshold(src, b, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
                b
            },
            { src ->
                val b = Mat()
                Imgproc.adaptiveThreshold(src, b, 255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY_INV, 21, 5.0)
                b
            },
            { src ->
                val b = Mat()
                Imgproc.threshold(src, b, 50.0, 255.0, Imgproc.THRESH_BINARY_INV)
                b
            }
        )

        for (gen in generators) {
            val binary = gen(gray)
            val result = findMarkersInBinaryTolerant(binary, imageWidth, imageHeight)
            binary.release()
            if (result != null) return result
        }
        return null
    }

    private fun findMarkersInBinaryTolerant(
        binary: Mat,
        imageWidth: Int,
        imageHeight: Int
    ): Array<Point>? {
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            binary.clone(), contours, Mat(),
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )

        val imageArea = imageWidth.toDouble() * imageHeight.toDouble()
        val minArea   = imageArea * 0.0005   // a little more permissive than 0.001
        val maxArea   = imageArea * 0.04

        val candidates = contours.mapNotNull { contour ->
            val area = Imgproc.contourArea(contour)
            if (area < minArea || area > maxArea) return@mapNotNull null

            val rect   = Imgproc.boundingRect(contour)
            if (rect.width <= 0 || rect.height <= 0) return@mapNotNull null

            val aspect = rect.width.toDouble() / rect.height.toDouble()
            if (aspect < 0.5 || aspect > 2.0) return@mapNotNull null

            val solidity = area / (rect.width * rect.height).toDouble().coerceAtLeast(1.0)
            if (solidity < 0.60) return@mapNotNull null

            Point(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0)
        }

        if (candidates.size < 4) return null

        val corners = listOf(
            Point(0.0,              0.0),
            Point(imageWidth.toDouble(), 0.0),
            Point(0.0,              imageHeight.toDouble()),
            Point(imageWidth.toDouble(), imageHeight.toDouble())
        )

        val selected = corners.map { corner ->
            candidates.minByOrNull { hypot(it.x - corner.x, it.y - corner.y) }!!
        }

        if (selected.distinct().size < 4) return null

        val tl = selected[0]; val tr = selected[1]
        val bl = selected[2]; val br = selected[3]
        return arrayOf(tl, tr, br, bl)
    }

    // ── Stage 4: page contour ─────────────────────────────────────────────────

    private fun detectPageContourCorners(gray: Mat): Array<Point>? {
        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)

        // Dilate slightly to close gaps in the page border
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, edges, kernel)
        kernel.release()

        val contours  = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        edges.release(); hierarchy.release()

        val imageArea = gray.width().toDouble() * gray.height().toDouble()
        var best: MatOfPoint2f? = null
        var bestArea = 0.0

        for (contour in contours) {
            val c2f  = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)

            if (approx.total() == 4L) {
                val area = Imgproc.contourArea(approx)
                if (area > imageArea * 0.15 && area > bestArea) {
                    bestArea = area
                    best = approx
                }
            }
        }

        val points = best?.toArray() ?: return null
        return orderPoints(points)
    }

    private fun orderPoints(points: Array<Point>): Array<Point> {
        val tl = points.minByOrNull { it.x + it.y }  ?: points[0]
        val br = points.maxByOrNull { it.x + it.y }  ?: points[0]
        val tr = points.minByOrNull { it.y - it.x }  ?: points[0]
        val bl = points.maxByOrNull { it.y - it.x }  ?: points[0]
        return arrayOf(tl, tr, br, bl)
    }

    // ── Stage 5: resize-only fallback ─────────────────────────────────────────

    private fun resizeFallbackIfLikelyAligned(source: Mat, template: FormTemplate): Bitmap? {
        if (source.empty() || source.width() <= 1 || source.height() <= 1) return null

        val sourceRatio  = source.width().toDouble() / source.height().toDouble()
        val targetRatio  = template.normalizedWidth.toDouble() / template.normalizedHeight.toDouble()
        val rotatedRatio = source.height().toDouble() / source.width().toDouble()

        val needsRotate = abs(rotatedRatio - targetRatio) + 0.02 < abs(sourceRatio - targetRatio)
        val oriented = if (needsRotate) {
            Mat().also { Core.rotate(source, it, Core.ROTATE_90_CLOCKWISE) }
        } else {
            source
        }

        val cropped = centerCropToAspect(oriented, targetRatio)

        val resized = Mat()
        Imgproc.resize(
            cropped, resized,
            Size(template.normalizedWidth.toDouble(), template.normalizedHeight.toDouble())
        )

        val output = Bitmap.createBitmap(
            template.normalizedWidth, template.normalizedHeight, Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(resized, output)
        resized.release()
        return output
    }

    private fun centerCropToAspect(source: Mat, targetRatio: Double): Mat {
        val w = source.width(); val h = source.height()
        if (w <= 1 || h <= 1) return source

        val currentRatio = w.toDouble() / h.toDouble()
        if (abs(currentRatio - targetRatio) <= 0.01) return source

        return if (currentRatio > targetRatio) {
            val cropW = (h * targetRatio).toInt().coerceIn(1, w)
            Mat(source, Rect((w - cropW) / 2, 0, cropW, h))
        } else {
            val cropH = (w / targetRatio).toInt().coerceIn(1, h)
            Mat(source, Rect(0, (h - cropH) / 2, w, cropH))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun matchesQuadrant(point: Point, cornerType: CornerType, w: Int, h: Int): Boolean {
        val hw = w / 2.0; val hh = h / 2.0
        return when (cornerType) {
            CornerType.TOP_LEFT     -> point.x <= hw && point.y <= hh
            CornerType.TOP_RIGHT    -> point.x >= hw && point.y <= hh
            CornerType.BOTTOM_RIGHT -> point.x >= hw && point.y >= hh
            CornerType.BOTTOM_LEFT  -> point.x <= hw && point.y >= hh
        }
    }

    private fun distance(a: Point, b: Point): Double =
        hypot(a.x - b.x, a.y - b.y)

    private fun markerScore(
        candidate: MarkerCandidate,
        target: Point,
        expectedW: Double,
        expectedH: Double,
        maxDistance: Double
    ): Double {
        val distNorm    = distance(candidate.center, target) / maxDistance.coerceAtLeast(1.0)
        val widthPenalty  = abs(candidate.rect.width  - expectedW) / expectedW.coerceAtLeast(1.0)
        val heightPenalty = abs(candidate.rect.height - expectedH) / expectedH.coerceAtLeast(1.0)
        // Distance weighted more heavily than size penalty
        return distNorm * 0.65 + widthPenalty * 0.175 + heightPenalty * 0.175
    }

    private fun PointF.toCvPoint(): Point = Point(x.toDouble(), y.toDouble())

    private data class MarkerCandidate(val rect: Rect, val center: Point)

    private enum class CornerType {
        TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT
    }
}
