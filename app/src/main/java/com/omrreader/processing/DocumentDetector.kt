package com.omrreader.processing

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentDetector @Inject constructor() {

    // Step 2: Preprocessing
    fun preprocess(bitmap: Bitmap): Mat {
        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)

        val grayMat = Mat()
        Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_BGR2GRAY)

        // Enhance with CLAHE (Contrast Limited Adaptive Histogram Equalization)
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val claheMat = Mat()
        clahe.apply(grayMat, claheMat)

        val blurredMat = Mat()
        Imgproc.GaussianBlur(claheMat, blurredMat, Size(5.0, 5.0), 0.0)

        val thresholdMat = Mat()
        Imgproc.adaptiveThreshold(
            blurredMat,
            thresholdMat,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            11,
            2.0
        )

        return thresholdMat
    }

    // Step 3: Document Detection
    fun findDocumentContour(preprocessedMat: Mat): MatOfPoint? {
        val edges = Mat()
        Imgproc.Canny(preprocessedMat, edges, 75.0, 200.0)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            edges,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        return contours.maxByOrNull { Imgproc.contourArea(it) }
    }

    // Step 4: Perspective Correction (CRITICAL)
    fun correctPerspective(srcMat: Mat, contour: MatOfPoint): Mat {
        val contour2f = MatOfPoint2f(*contour.toArray())
        val perimeter = Imgproc.arcLength(contour2f, true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(contour2f, approx, 0.02 * perimeter, true)

        if (approx.toArray().size != 4) {
            throw IllegalArgumentException("Could not find a perfect 4-corner document contour")
        }

        val points = sortCorners(approx.toArray())

        val width = maxOf(
            calculateDistance(points[0], points[1]),
            calculateDistance(points[2], points[3])
        )
        val height = maxOf(
            calculateDistance(points[0], points[3]),
            calculateDistance(points[1], points[2])
        )

        val dstPoints = arrayOf(
            Point(0.0, 0.0),
            Point(width, 0.0),
            Point(width, height),
            Point(0.0, height)
        )

        val srcPointsMat = MatOfPoint2f(*points)
        val dstPointsMat = MatOfPoint2f(*dstPoints)

        val perspectiveTransform = Imgproc.getPerspectiveTransform(srcPointsMat, dstPointsMat)
        val warpedMat = Mat()
        Imgproc.warpPerspective(srcMat, warpedMat, perspectiveTransform, Size(width, height))

        return warpedMat
    }

    private fun sortCorners(points: Array<Point>): Array<Point> {
        val sortedByY = points.sortedBy { it.y }
        val topCorners = sortedByY.take(2).sortedBy { it.x }
        val bottomCorners = sortedByY.takeLast(2).sortedByDescending { it.x }

        return arrayOf(topCorners[0], topCorners[1], bottomCorners[0], bottomCorners[1])
    }

    private fun calculateDistance(p1: Point, p2: Point): Double {
        return Math.sqrt(Math.pow(p1.x - p2.x, 2.0) + Math.pow(p1.y - p2.y, 2.0))
    }
}
