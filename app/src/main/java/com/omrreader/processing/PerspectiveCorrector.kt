package com.omrreader.processing

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PerspectiveCorrector @Inject constructor() {

    fun correct(bitmap: Bitmap): Bitmap? {
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

        val sourcePoints = detectMarkerBasedCorners(markerBinary, source.width(), source.height())
            ?: detectPageContourCorners(gray)
            ?: return null

        val width = FormTemplate.DEFAULT.pageWidth.toDouble()
        val height = FormTemplate.DEFAULT.pageHeight.toDouble()
        val destinationPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(width - 1.0, 0.0),
            Point(width - 1.0, height - 1.0),
            Point(0.0, height - 1.0)
        )

        val transformMatrix = Imgproc.getPerspectiveTransform(MatOfPoint2f(*sourcePoints), destinationPoints)
        val corrected = Mat()
        Imgproc.warpPerspective(source, corrected, transformMatrix, Size(width, height))

        val output = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(corrected, output)
        return output
    }

    private fun detectMarkerBasedCorners(binary: Mat, imageWidth: Int, imageHeight: Int): Array<Point>? {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val imageArea = imageWidth.toDouble() * imageHeight.toDouble()
        val minMarkerArea = imageArea * 0.00012
        val maxMarkerArea = imageArea * 0.03

        val candidates = contours.mapNotNull { contour ->
            val area = Imgproc.contourArea(contour)
            if (area !in minMarkerArea..maxMarkerArea) return@mapNotNull null

            val rect = Imgproc.boundingRect(contour)
            if (rect.width <= 0 || rect.height <= 0) return@mapNotNull null

            val aspectRatio = rect.width.toDouble() / rect.height.toDouble()
            if (aspectRatio < 0.55 || aspectRatio > 1.45) return@mapNotNull null

            val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, 0.05 * peri, true)
            if (approx.total() < 4) return@mapNotNull null

            val bboxArea = (rect.width * rect.height).toDouble().coerceAtLeast(1.0)
            val fillRatio = area / bboxArea
            if (fillRatio < 0.35) return@mapNotNull null

            MarkerCandidate(
                rect = rect,
                center = Point(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0)
            )
        }

        if (candidates.size < 4) return null

        val cornerTargets = listOf(
            CornerType.TOP_LEFT to Point(0.0, 0.0),
            CornerType.TOP_RIGHT to Point(imageWidth.toDouble(), 0.0),
            CornerType.BOTTOM_RIGHT to Point(imageWidth.toDouble(), imageHeight.toDouble()),
            CornerType.BOTTOM_LEFT to Point(0.0, imageHeight.toDouble())
        )

        val selected = mutableMapOf<CornerType, MarkerCandidate>()
        val pool = candidates.toMutableList()

        for ((cornerType, target) in cornerTargets) {
            val preferred = pool.filter { matchesQuadrant(it.center, cornerType, imageWidth, imageHeight) }
            val sourcePool = if (preferred.isNotEmpty()) preferred else pool
            val best = sourcePool.minByOrNull { distanceSquared(it.center, target) } ?: return null
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
