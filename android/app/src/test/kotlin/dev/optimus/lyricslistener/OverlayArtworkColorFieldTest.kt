package dev.optimus.lyricslistener

import org.junit.Assert.*
import org.junit.Test

class OverlayArtworkColorFieldTest {

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    @Test
    fun `cells keep each region of the cover in its original position`() {
        // 12x12 cover: blue sky on top half, orange ground on bottom half
        val size = 12
        val sky = argb(40, 110, 230)
        val ground = argb(230, 120, 30)
        val pixels = IntArray(size * size) { idx -> if (idx / size < size / 2) sky else ground }

        val result = OverlayArtworkColorField.averageCells(pixels, size, 0, 0, size, size, cols = 3, rows = 2)

        for (col in 0 until 3) {
            assertEquals(sky, result.colors[col])
            assertEquals(ground, result.colors[3 + col])
        }
        assertTrue(result.luminanceGrid[0][0] != result.luminanceGrid[1][0])
    }

    @Test
    fun `averaging happens in linear light so mixed colours stay bright instead of muddy`() {
        // Checkerboard of pure black and pure white averages to ~188 in sRGB (not a muddy 128).
        val size = 4
        val pixels = IntArray(size * size) { idx -> if ((idx / size + idx % size) % 2 == 0) argb(0, 0, 0) else argb(255, 255, 255) }

        val result = OverlayArtworkColorField.averageCells(pixels, size, 0, 0, size, size, cols = 1, rows = 1)
        val c = result.colors[0]

        assertEquals(188.0, red(c).toDouble(), 2.0)
        assertEquals(red(c), green(c))
        assertEquals(red(c), blue(c))
        assertEquals(0.5, result.luminanceGrid[0][0], 0.01)
    }

    @Test
    fun `trimmed border is excluded from the colour field`() {
        // 10x10 dark cover with a 1px white border; trimming 1px leaves only dark cells.
        val size = 10
        val dark = argb(20, 20, 30)
        val pixels = IntArray(size * size) { idx ->
            val x = idx % size
            val y = idx / size
            if (x == 0 || y == 0 || x == size - 1 || y == size - 1) argb(255, 255, 255) else dark
        }

        val result = OverlayArtworkColorField.averageCells(pixels, size, 1, 1, size - 1, size - 1, cols = 2, rows = 2)

        result.colors.forEach { assertEquals(dark, it) }
        assertTrue(result.luminanceGrid.all { row -> row.all { it < 0.02 } })
    }
}
