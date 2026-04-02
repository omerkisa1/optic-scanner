package com.omrreader.processing

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PerspectiveCorrector @Inject constructor() {

    fun correct(bitmap: Bitmap): Bitmap? {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        
        // 1. Grayscale & Blur
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        // 2. Edge Detection (Canny)
        val edges = Mat()
        Imgproc.Canny(gray, edges, 75.0, 200.0)

        // 3. Find Contours
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        // Find the largest 4-sided contour
        var maxArea = 0.0
        var docContour: MatOfPoint2f? = null

        for (contour in contours) {
            val contour2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)

            if (approx.rows() == 4) {
                val area = Imgproc.contourArea(approx)
                if (area > maxArea) {
                    maxArea = area
                    docContour = approx
                }
            }
        }

        if (docContour == null) {
            return null // Paper not detected properly
        }

        // Sort corners: top-left, top-right, bottom-right, bottom-left
        val points = docContour.toArray()
        points.sortBy { it.y } // sort by Y to separate top and bottom
        
        val topPoints = points.take(2).sortedBy { it.x }
        val bottomPoints = points.drop(2).sortedBy { it.x }
        
        val tl = topPoints[0]
        val tr = topPoints[1]
        val bl = bottomPoints[0]
        val br = bottomPoints[1]

        val srcPoints = MatOfPoint2f(tl, tr, br, bl)
        
        // Target dimensions based on our A4 target
        val width = FormTemplate.DEFAULT.pageWidth.toDouble()
        val height = FormTemplate.DEFAULT.pageHeight.toDouble()
        
        val dstPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(width, 0.0),
            Point(width, height),
            Point(0.0, height)
        )

        // Transform
        val transformMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        val correctedMat = Mat()
        Imgproc.warpPerspective(mat, correctedMat, transformMatrix, Size(width, height))

        val resultBitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(correctedMat, resultBitmap)

        return resultBitmap
    }
}
