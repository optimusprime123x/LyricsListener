package dev.optimus.lyricslistener

import android.graphics.Bitmap
import android.os.Build
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Turns album artwork into a small, static "colour field" (in the spirit of Apple Music's
 * lyrics backdrop): the trimmed cover is averaged into a coarse grid of cells, in linear light,
 * keeping every region's colour in its original position (sky stays on top, ground at the bottom).
 *
 * The overlay smoothly upscales this grid and blurs it, so the whole cover's palette fills any
 * window shape without visibly stretching or squishing recognisable artwork.
 */
internal object OverlayArtworkColorField {
    const val GRID_COLS = 6
    const val GRID_ROWS = 6
    private const val WORK_SIZE = 48

    class Field(
        val bitmap: Bitmap,
        /** Relative luminance (0..1) per cell, row-major, matching [bitmap]. */
        val luminanceGrid: Array<DoubleArray>
    )

    /** Raw cell averages, kept free of Android graphics calls so it can be unit tested. */
    class CellAverages(
        val cols: Int,
        val rows: Int,
        val colors: IntArray,
        val luminanceGrid: Array<DoubleArray>
    )

    private val srgbToLinearLut = DoubleArray(256) { channel ->
        val c = channel / 255.0
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun linearToSrgb8(linear: Double): Int {
        val l = linear.coerceIn(0.0, 1.0)
        val srgb = if (l <= 0.0031308) l * 12.92 else 1.055 * l.pow(1.0 / 2.4) - 0.055
        return (srgb * 255.0).roundToInt().coerceIn(0, 255)
    }

    fun build(source: Bitmap): Field {
        val work = downscaleForSampling(source)
        try {
            val width = work.width
            val height = work.height
            val pixels = IntArray(width * height)
            work.getPixels(pixels, 0, width, 0, 0, width, height)

            val trimX = (width * OverlayArtworkDimming.EDGE_TRIM_FRACTION).roundToInt()
                .coerceIn(0, (width - GRID_COLS) / 2)
            val trimY = (height * OverlayArtworkDimming.EDGE_TRIM_FRACTION).roundToInt()
                .coerceIn(0, (height - GRID_ROWS) / 2)
            val averages = averageCells(
                pixels = pixels,
                width = width,
                left = trimX,
                top = trimY,
                right = width - trimX,
                bottom = height - trimY,
                cols = GRID_COLS,
                rows = GRID_ROWS
            )
            val fieldBitmap = Bitmap.createBitmap(
                averages.colors,
                averages.cols,
                averages.rows,
                Bitmap.Config.ARGB_8888
            )
            return Field(fieldBitmap, averages.luminanceGrid)
        } finally {
            if (work !== source) {
                work.recycle()
            }
        }
    }

    /**
     * Averages the [left, right) x [top, bottom) region of an ARGB pixel buffer into a
     * cols x rows grid, in linear light so mixed colours stay vivid instead of muddy.
     */
    fun averageCells(
        pixels: IntArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        cols: Int,
        rows: Int
    ): CellAverages {
        val regionW = (right - left).coerceAtLeast(1)
        val regionH = (bottom - top).coerceAtLeast(1)
        val colors = IntArray(cols * rows)
        val luminanceGrid = Array(rows) { DoubleArray(cols) }

        for (row in 0 until rows) {
            val y0 = top + row * regionH / rows
            val y1 = (top + (row + 1) * regionH / rows).coerceAtLeast(y0 + 1)
            for (col in 0 until cols) {
                val x0 = left + col * regionW / cols
                val x1 = (left + (col + 1) * regionW / cols).coerceAtLeast(x0 + 1)
                var sumR = 0.0
                var sumG = 0.0
                var sumB = 0.0
                var count = 0
                for (y in y0 until y1) {
                    val rowOffset = y * width
                    for (x in x0 until x1) {
                        val argb = pixels[rowOffset + x]
                        sumR += srgbToLinearLut[(argb shr 16) and 0xFF]
                        sumG += srgbToLinearLut[(argb shr 8) and 0xFF]
                        sumB += srgbToLinearLut[argb and 0xFF]
                        count++
                    }
                }
                val n = count.coerceAtLeast(1).toDouble()
                val linR = sumR / n
                val linG = sumG / n
                val linB = sumB / n
                colors[row * cols + col] = (0xFF shl 24) or
                    (linearToSrgb8(linR) shl 16) or
                    (linearToSrgb8(linG) shl 8) or
                    linearToSrgb8(linB)
                luminanceGrid[row][col] = (0.2126 * linR + 0.7152 * linG + 0.0722 * linB).coerceIn(0.0, 1.0)
            }
        }
        return CellAverages(cols, rows, colors, luminanceGrid)
    }

    /** Progressive halving keeps every source pixel represented (a single big bilinear jump aliases). */
    private fun downscaleForSampling(source: Bitmap): Bitmap {
        var current = source
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && current.config == Bitmap.Config.HARDWARE) {
            current = current.copy(Bitmap.Config.ARGB_8888, false)
        }
        while (current.width > WORK_SIZE * 2 || current.height > WORK_SIZE * 2) {
            val next = Bitmap.createScaledBitmap(
                current,
                (current.width / 2).coerceAtLeast(WORK_SIZE),
                (current.height / 2).coerceAtLeast(WORK_SIZE),
                true
            )
            if (current !== source && current !== next) current.recycle()
            current = next
        }
        if (current.width != WORK_SIZE || current.height != WORK_SIZE) {
            val next = Bitmap.createScaledBitmap(current, WORK_SIZE, WORK_SIZE, true)
            if (current !== source && current !== next) current.recycle()
            current = next
        }
        return current
    }
}
