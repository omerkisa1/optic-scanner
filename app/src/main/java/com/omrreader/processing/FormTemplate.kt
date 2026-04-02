package com.omrreader.processing

import android.graphics.Rect
import android.graphics.RectF

data class FormTemplate(
    val name: String,
    val pageWidth: Int,
    val pageHeight: Int,
    val markerSizeRatio: Float,
    val nameRegion: RectF,
    val numberRegion: RectF,
    val classRegion: RectF,
    val qrRegion: RectF,
    val subjects: List<SubjectGridTemplate>
) {

    fun resolveNameRegion(imageWidth: Int, imageHeight: Int): Rect {
        return nameRegion.toPixelRect(imageWidth, imageHeight)
    }

    fun resolveNumberRegion(imageWidth: Int, imageHeight: Int): Rect {
        return numberRegion.toPixelRect(imageWidth, imageHeight)
    }

    fun resolveClassRegion(imageWidth: Int, imageHeight: Int): Rect {
        return classRegion.toPixelRect(imageWidth, imageHeight)
    }

    fun resolveQrRegion(imageWidth: Int, imageHeight: Int): Rect {
        return qrRegion.toPixelRect(imageWidth, imageHeight)
    }

    fun resolveSubjects(imageWidth: Int, imageHeight: Int): List<SubjectGrid> {
        return subjects.map { it.resolve(imageWidth, imageHeight) }
    }

    fun resolveSubjects(
        imageWidth: Int,
        imageHeight: Int,
        overrides: List<SubjectGridOverride>
    ): List<SubjectGrid> {
        if (overrides.isEmpty()) {
            return resolveSubjects(imageWidth, imageHeight)
        }

        if (subjects.isEmpty()) return emptyList()

        return overrides.mapIndexed { index, override ->
            val template = subjects.getOrNull(index) ?: subjects.last()
            template.resolve(
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                rowsOverride = override.rows.coerceAtLeast(1),
                colsOverride = override.cols.coerceAtLeast(2),
                nameOverride = override.name.ifBlank { template.name }
            )
        }
    }

    companion object {
        val DEFAULT = FormTemplate(
            name = "standard_2x20",
            pageWidth = 1240,
            pageHeight = 1754,
            markerSizeRatio = 0.033f,
            nameRegion = RectF(0.15f, 0.05f, 0.75f, 0.04f),
            numberRegion = RectF(0.15f, 0.10f, 0.75f, 0.04f),
            classRegion = RectF(0.15f, 0.15f, 0.30f, 0.04f),
            qrRegion = RectF(0.05f, 0.20f, 0.15f, 0.10f),
            subjects = listOf(
                SubjectGridTemplate(
                    name = "DERS 1",
                    gridRegion = RectF(0.05f, 0.35f, 0.43f, 0.58f),
                    rows = 20,
                    cols = 4
                ),
                SubjectGridTemplate(
                    name = "DERS 2",
                    gridRegion = RectF(0.52f, 0.35f, 0.43f, 0.58f),
                    rows = 20,
                    cols = 4
                )
            )
        )
    }
}

data class SubjectGridOverride(
    val name: String,
    val rows: Int,
    val cols: Int
)

data class SubjectGridTemplate(
    val name: String,
    val gridRegion: RectF,
    val rows: Int,
    val cols: Int
) {
    fun resolve(
        imageWidth: Int,
        imageHeight: Int,
        rowsOverride: Int = rows,
        colsOverride: Int = cols,
        nameOverride: String = name
    ): SubjectGrid {
        val regionPx = gridRegion.toPixelRect(imageWidth, imageHeight)
        val gridWidth = (regionPx.right - regionPx.left).coerceAtLeast(1)
        val gridHeight = (regionPx.bottom - regionPx.top).coerceAtLeast(1)

        val finalRows = rowsOverride.coerceAtLeast(1)
        val finalCols = colsOverride.coerceAtLeast(2)

        val horizontalGap = (gridWidth / finalCols).coerceAtLeast(1)
        val verticalGap = (gridHeight / finalRows).coerceAtLeast(1)

        val bubbleWidth = (horizontalGap * 0.62f).toInt().coerceAtLeast(10)
        val bubbleHeight = (verticalGap * 0.62f).toInt().coerceAtLeast(10)

        return SubjectGrid(
            name = nameOverride,
            gridRegion = regionPx,
            rows = finalRows,
            cols = finalCols,
            bubbleWidth = bubbleWidth,
            bubbleHeight = bubbleHeight,
            horizontalGap = horizontalGap,
            verticalGap = verticalGap
        )
    }
}

data class SubjectGrid(
    val name: String,
    val gridRegion: Rect,
    val rows: Int,
    val cols: Int,
    val bubbleWidth: Int,
    val bubbleHeight: Int,
    val horizontalGap: Int,
    val verticalGap: Int
)

private fun RectF.toPixelRect(imageWidth: Int, imageHeight: Int): Rect {
    val left = (left * imageWidth).toInt()
    val top = (top * imageHeight).toInt()
    val width = (width() * imageWidth).toInt().coerceAtLeast(1)
    val height = (height() * imageHeight).toInt().coerceAtLeast(1)

    return Rect(
        left.coerceAtLeast(0),
        top.coerceAtLeast(0),
        (left + width).coerceAtMost(imageWidth),
        (top + height).coerceAtMost(imageHeight)
    )
}
