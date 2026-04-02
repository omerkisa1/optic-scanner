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

        val markerBinary = Mat()
        Imgproc.adaptiveThreshold(
            gray,
            markerBinary,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            41,
            10.0
        )

        val markerPoints = detectMarkerBasedCorners(markerBinary, source.width(), source.height(), template)
        val sourcePoints = markerPoints
            ?: detectPageContourCorners(gray)
            ?: detectPageContourCorners(markerBinary)

        if (sourcePoints == null) {
            return resizeFallbackIfLikelyAligned(source, template)
        }

        val destinationTargets = if (markerPoints != null) {
            template.markerCornerTargetsNormalized()
        } else {
            template.pageCornerTargetsNormalized()
        }

        val width = template.normalizedWidth.toDouble()
        val height = template.normalizedHeight.toDouble()
        val destinationPoints = MatOfPoint2f(
            destinationTargets.topLeft.toCvPoint(),
            destinationTargets.topRight.toCvPoint(),
            destinationTargets.bottomRight.toCvPoint(),
            destinationTargets.bottomLeft.toCvPoint()
        )

        val transformMatrix = Imgproc.getPerspectiveTransform(MatOfPoint2f(*sourcePoints), destinationPoints)
        val corrected = Mat()
        Imgproc.warpPerspective(source, corrected, transformMatrix, Size(width, height))

        val output = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(corrected, output)
        return output
    }

    private fun detectMarkerBasedCorners(
        binary: Mat,
        imageWidth: Int,
        imageHeight: Int,
        template: FormTemplate
    ): Array<Point>? {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val expectedMarkerWidth = imageWidth.toDouble() * template.markerSize.toDouble() / template.normalizedWidth.toDouble()
        val expectedMarkerHeight = imageHeight.toDouble() * template.markerSize.toDouble() / template.normalizedHeight.toDouble()
        val expectedMarkerArea = (expectedMarkerWidth * expectedMarkerHeight).coerceAtLeast(36.0)
        val minMarkerArea = expectedMarkerArea * 0.20
        val maxMarkerArea = expectedMarkerArea * 8.0
        val minMarkerWidth = (expectedMarkerWidth * 0.20).coerceAtLeast(6.0)
        val maxMarkerWidth = expectedMarkerWidth * 3.8
        val minMarkerHeight = (expectedMarkerHeight * 0.20).coerceAtLeast(6.0)
        val maxMarkerHeight = expectedMarkerHeight * 3.8

        val candidates = contours.mapNotNull { contour ->
            val area = Imgproc.contourArea(contour)
            if (area !in minMarkerArea..maxMarkerArea) return@mapNotNull null

            val rect = Imgproc.boundingRect(contour)
            if (rect.width <= 0 || rect.height <= 0) return@mapNotNull null
            if (rect.width.toDouble() !in minMarkerWidth..maxMarkerWidth) return@mapNotNull null
            if (rect.height.toDouble() !in minMarkerHeight..maxMarkerHeight) return@mapNotNull null

            val contour2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, 0.04 * peri, true)
            if (approx.total() < 4L || approx.total() > 12L) return@mapNotNull null

            val aspectRatio = rect.width.toDouble() / rect.height.toDouble()
            if (aspectRatio < 0.60 || aspectRatio > 1.40) return@mapNotNull null

            val bboxArea = (rect.width * rect.height).toDouble().coerceAtLeast(1.0)
            val fillRatio = area / bboxArea
            if (fillRatio < 0.35) return@mapNotNull null

            MarkerCandidate(
                rect = rect,
                center = Point(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0)
            )
        }

        if (candidates.size < 4) return null

        val expected = template.markerCentersForImage(imageWidth, imageHeight)
        val imageDiagonal = hypot(imageWidth.toDouble(), imageHeight.toDouble()).coerceAtLeast(1.0)
        val maxDistance = imageDiagonal * 0.28

        val cornerTargets = listOf(
            CornerType.TOP_LEFT to expected.topLeft,
            CornerType.TOP_RIGHT to expected.topRight,
            CornerType.BOTTOM_RIGHT to expected.bottomRight,
            CornerType.BOTTOM_LEFT to expected.bottomLeft
        )

        val selected = mutableMapOf<CornerType, MarkerCandidate>()
        val pool = candidates.toMutableList()

        for ((cornerType, target) in cornerTargets) {
            val targetCv = target.toCvPoint()
            val preferred = pool.filter {
                matchesQuadrant(it.center, cornerType, imageWidth, imageHeight) &&
                    distance(it.center, targetCv) <= maxDistance
            }
            val sourcePool = if (preferred.isNotEmpty()) preferred else pool.filter { distance(it.center, targetCv) <= maxDistance }
            if (sourcePool.isEmpty()) return null

            val best = sourcePool.minByOrNull { candidate ->
                markerScore(
                    candidate = candidate,
                    target = targetCv,
                    expectedMarkerWidth = expectedMarkerWidth,
                    expectedMarkerHeight = expectedMarkerHeight,
                    maxDistance = maxDistance
                )
            } ?: return null
            selected[cornerType] = best
            pool.remove(best)
        }

        val tl = selected[CornerType.TOP_LEFT] ?: return null
        val tr = selected[CornerType.TOP_RIGHT] ?: return null
        val br = selected[CornerType.BOTTOM_RIGHT] ?: return null
        val bl = selected[CornerType.BOTTOM_LEFT] ?: return null

        return arrayOf(
            Point(tl.rect.x.toDouble(), tl.rect.y.toDouble()),
            Point((tr.rect.x + tr.rect.width).toDouble(), tr.rect.y.toDouble()),
            Point((br.rect.x + br.rect.width).toDouble(), (br.rect.y + br.rect.height).toDouble()),
            Point(bl.rect.x.toDouble(), (bl.rect.y + bl.rect.height).toDouble())
        )
    }

    private fun detectPageContourCorners(gray: Mat): Array<Point>? {
        val edges = Mat()
        Imgproc.Canny(gray, edges, 75.0, 200.0)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val imageArea = gray.width().toDouble() * gray.height().toDouble()
        var best: MatOfPoint2f? = null
        var bestArea = 0.0

        for (contour in contours) {
            val contour2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)

            if (approx.total() == 4L) {
                val area = Imgproc.contourArea(approx)
                if (area > imageArea * 0.2 && area > bestArea) {
                    bestArea = area
                    best = approx
                }
            }
        }

        val points = best?.toArray() ?: return null
        return orderPoints(points)
    }

    private fun orderPoints(points: Array<Point>): Array<Point> {
        val tl = points.minByOrNull { it.x + it.y } ?: points[0]
        val br = points.maxByOrNull { it.x + it.y } ?: points[0]
        val tr = points.minByOrNull { it.y - it.x } ?: points[0]
        val bl = points.maxByOrNull { it.y - it.x } ?: points[0]
        return arrayOf(tl, tr, br, bl)
    }

    private fun matchesQuadrant(point: Point, cornerType: CornerType, width: Int, height: Int): Boolean {
        val halfW = width / 2.0
        val halfH = height / 2.0
        return when (cornerType) {
            CornerType.TOP_LEFT -> point.x <= halfW && point.y <= halfH
            CornerType.TOP_RIGHT -> point.x >= halfW && point.y <= halfH
            CornerType.BOTTOM_RIGHT -> point.x >= halfW && point.y >= halfH
            CornerType.BOTTOM_LEFT -> point.x <= halfW && point.y >= halfH
        }
    }

    private fun distanceSquared(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    private fun distance(a: Point, b: Point): Double {
        return kotlin.math.sqrt(distanceSquared(a, b))
    }

    private fun markerScore(
        candidate: MarkerCandidate,
        target: Point,
        expectedMarkerWidth: Double,
        expectedMarkerHeight: Double,
        maxDistance: Double
    ): Double {
        val centerDistance = distance(candidate.center, target) / maxDistance.coerceAtLeast(1.0)
        val widthPenalty = abs(candidate.rect.width - expectedMarkerWidth) / expectedMarkerWidth.coerceAtLeast(1.0)
        val heightPenalty = abs(candidate.rect.height - expectedMarkerHeight) / expectedMarkerHeight.coerceAtLeast(1.0)
        return (centerDistance * 0.60) + (widthPenalty * 0.20) + (heightPenalty * 0.20)
    }

    private fun resizeFallbackIfLikelyAligned(source: Mat, template: FormTemplate): Bitmap? {
        if (source.empty() || source.width() <= 1 || source.height() <= 1) return null

        val sourceRatio = source.width().toDouble() / source.height().toDouble()
        val targetRatio = template.normalizedWidth.toDouble() / template.normalizedHeight.toDouble()
        val rotatedRatio = source.height().toDouble() / source.width().toDouble()

        val needsRotate = abs(rotatedRatio - targetRatio) + 0.02 < abs(sourceRatio - targetRatio)
        val oriented = if (needsRotate) {
            Mat().also { rotated -> Core.rotate(source, rotated, Core.ROTATE_90_CLOCKWISE) }
        } else {
            source
        }

        val cropped = centerCropToAspect(oriented, targetRatio)

        val resized = Mat()
        Imgproc.resize(
            cropped,
            resized,
            Size(template.normalizedWidth.toDouble(), template.normalizedHeight.toDouble())
        )

        val output = Bitmap.createBitmap(
            template.normalizedWidth,
            template.normalizedHeight,
            Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(resized, output)
        return output
    }

    private fun centerCropToAspect(source: Mat, targetRatio: Double): Mat {
        val width = source.width()
        val height = source.height()
        if (width <= 1 || height <= 1) return source

        val currentRatio = width.toDouble() / height.toDouble()
        if (abs(currentRatio - targetRatio) <= 0.01) {
            return source
        }

        return if (currentRatio > targetRatio) {
            val cropWidth = (height * targetRatio).toInt().coerceIn(1, width)
            val x = ((width - cropWidth) / 2).coerceAtLeast(0)
            Mat(source, Rect(x, 0, cropWidth, height))
        } else {
            val cropHeight = (width / targetRatio).toInt().coerceIn(1, height)
            val y = ((height - cropHeight) / 2).coerceAtLeast(0)
            Mat(source, Rect(0, y, width, cropHeight))
        }
    }

    private fun PointF.toCvPoint(): Point = Point(x.toDouble(), y.toDouble())

    private data class MarkerCandidate(
        val rect: Rect,
        val center: Point
    )

    private enum class CornerType {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_RIGHT,
        BOTTOM_LEFT
    }
}
