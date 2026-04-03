package com.omrreader.processing

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.roundToInt

data class FormTemplate(
    // Perspective warp output size (A4 ratio)
    val normalizedWidth: Int = 1000,
    val normalizedHeight: Int = 1414,
    // Four corner black markers
    val markerSize: Int = 60,
    val markerMargin: Int = 20,
    // RectF fields are used as (x, y, width, height) in normalized ratios
    // Fallback OCR regions target box interiors (labels and borders excluded as much as possible).
    val nameRegion: RectF = RectF(0.16f, 0.078f, 0.68f, 0.024f),
    val numberRegion: RectF = RectF(0.16f, 0.128f, 0.68f, 0.024f),
    val classRegion: RectF = RectF(0.16f, 0.178f, 0.68f, 0.024f),
    // Optional QR region, if null scanner can use manual answer key
    val qrRegion: RectF? = null,
    val grids: List<GridRegion> = listOf(
        GridRegion(
            region = RectF(0.05f, 0.27f, 0.43f, 0.58f),
            rows = 10,
            cols = 5
        ),
        GridRegion(
            region = RectF(0.52f, 0.27f, 0.43f, 0.58f),
            rows = 10,
            cols = 5
        )
    )
) {

    fun resolveNameRegion(imageWidth: Int, imageHeight: Int): Rect {
        return nameRegion.toPixelRectBySize(imageWidth, imageHeight)
    }

    fun resolveNumberRegion(imageWidth: Int, imageHeight: Int): Rect {
        return numberRegion.toPixelRectBySize(imageWidth, imageHeight)
    }

    fun resolveClassRegion(imageWidth: Int, imageHeight: Int): Rect {
        return classRegion.toPixelRectBySize(imageWidth, imageHeight)
    }

    fun resolveQrRegion(imageWidth: Int, imageHeight: Int): Rect? {
        return qrRegion?.toPixelRectBySize(imageWidth, imageHeight)
    }

    fun resolveGrids(
        imageWidth: Int,
        imageHeight: Int,
        overrides: List<GridOverride> = emptyList()
    ): List<ResolvedGridRegion> {
        if (grids.isEmpty()) return emptyList()

        val effective = if (overrides.isEmpty()) {
            grids
        } else {
            overrides.mapIndexed { index, override ->
                val base = grids.getOrNull(index) ?: grids.last()
                base.copy(
                    rows = override.rows.coerceAtLeast(1),
                    cols = override.cols.coerceAtLeast(2)
                )
            }
        }

        return effective.map { grid ->
            val regionPx = grid.region.toPixelRectBySize(imageWidth, imageHeight)
            val gridWidth = (regionPx.right - regionPx.left).coerceAtLeast(1)
            val gridHeight = (regionPx.bottom - regionPx.top).coerceAtLeast(1)
            val cellWidth = (gridWidth / grid.cols).coerceAtLeast(1)
            val cellHeight = (gridHeight / grid.rows).coerceAtLeast(1)

            ResolvedGridRegion(
                region = regionPx,
                rows = grid.rows,
                cols = grid.cols,
                cellWidth = cellWidth,
                cellHeight = cellHeight
            )
        }
    }

    fun markerCentersForImage(imageWidth: Int, imageHeight: Int): MarkerPoints {
        val scaleX = imageWidth.toFloat() / normalizedWidth.toFloat()
        val scaleY = imageHeight.toFloat() / normalizedHeight.toFloat()
        val halfMarker = markerSize / 2f

        return MarkerPoints(
            topLeft = PointF((markerMargin + halfMarker) * scaleX, (markerMargin + halfMarker) * scaleY),
            topRight = PointF(
                (normalizedWidth - markerMargin - halfMarker) * scaleX,
                (markerMargin + halfMarker) * scaleY
            ),
            bottomRight = PointF(
                (normalizedWidth - markerMargin - halfMarker) * scaleX,
                (normalizedHeight - markerMargin - halfMarker) * scaleY
            ),
            bottomLeft = PointF(
                (markerMargin + halfMarker) * scaleX,
                (normalizedHeight - markerMargin - halfMarker) * scaleY
            )
        )
    }

    fun markerCornerTargetsNormalized(): MarkerPoints {
        return MarkerPoints(
            topLeft = PointF(markerMargin.toFloat(), markerMargin.toFloat()),
            topRight = PointF((normalizedWidth - markerMargin).toFloat(), markerMargin.toFloat()),
            bottomRight = PointF(
                (normalizedWidth - markerMargin).toFloat(),
                (normalizedHeight - markerMargin).toFloat()
            ),
            bottomLeft = PointF(markerMargin.toFloat(), (normalizedHeight - markerMargin).toFloat())
        )
    }

    fun pageCornerTargetsNormalized(): MarkerPoints {
        return MarkerPoints(
            topLeft = PointF(0f, 0f),
            topRight = PointF((normalizedWidth - 1).toFloat(), 0f),
            bottomRight = PointF((normalizedWidth - 1).toFloat(), (normalizedHeight - 1).toFloat()),
            bottomLeft = PointF(0f, (normalizedHeight - 1).toFloat())
        )
    }

    companion object {
        val DEFAULT = FormTemplate()
    }
}

data class GridOverride(
    val rows: Int,
    val cols: Int
)

data class GridRegion(
    val region: RectF,
    val rows: Int,
    val cols: Int
)

data class ResolvedGridRegion(
    val region: Rect,
    val rows: Int,
    val cols: Int,
    val cellWidth: Int,
    val cellHeight: Int
)

data class MarkerPoints(
    val topLeft: PointF,
    val topRight: PointF,
    val bottomRight: PointF,
    val bottomLeft: PointF
)

private fun RectF.toPixelRectBySize(imageWidth: Int, imageHeight: Int): Rect {
    val x = (left * imageWidth).roundToInt()
    val y = (top * imageHeight).roundToInt()
    val width = (right * imageWidth).roundToInt().coerceAtLeast(1)
    val height = (bottom * imageHeight).roundToInt().coerceAtLeast(1)

    return Rect(
        x.coerceIn(0, imageWidth - 1),
        y.coerceIn(0, imageHeight - 1),
        (x + width).coerceIn(1, imageWidth),
        (y + height).coerceIn(1, imageHeight)
    )
}
