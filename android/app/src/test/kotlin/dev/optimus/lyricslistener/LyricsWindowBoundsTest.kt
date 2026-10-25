package dev.optimus.lyricslistener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsWindowBoundsTest {
    private val portrait = LyricsWindowBounds(1080, 2280)

    @Test fun expandingNearTopKeepsTheHeaderAndControlsOnScreen() {
        for (previousHeight in listOf(60, 180)) {
            val nearTop = -(portrait.height - previousHeight) / 2 + 8
            val position = portrait.constrainPosition(0, nearTop, 1080, 400)
            assertContained(portrait, position, 1080, 400)
            assertEquals(0, position.first)
            assertTrue(position.second > nearTop)
        }
    }

    @Test fun expandingNearBottomKeepsTheWholeWindowOnScreen() {
        val position = portrait.constrainPosition(0, 1100, 1080, 400)
        assertContained(portrait, position, 1080, 400)
        assertEquals(940, position.second)
    }

    @Test fun resizingDoesNotMoveAnAlreadySafePosition() {
        assertEquals(Pair(0, 100), portrait.constrainPosition(0, 100, 1080, 400))
    }

    @Test fun draggingCannotLoseControlsAtAnyEdge() {
        for (x in listOf(-10000, 10000)) {
            for (y in listOf(-10000, 10000)) {
                assertContained(portrait, portrait.constrainPosition(x, y, 64, 64), 64, 64)
            }
        }
    }

    @Test fun aSavedOffScreenPositionIsRecoveredEvenAtIntegerExtremes() {
        assertContained(
            portrait, portrait.constrainPosition(Int.MAX_VALUE, Int.MIN_VALUE, 1080, 180), 1080, 180
        )
    }

    @Test fun rotatingToAShortScreenMakesRoomForTheHeader() {
        // Dimensions already exclude the status/navigation bars and a landscape cutout.
        val landscape = LyricsWindowBounds(2200, 320)
        val lyricHeight = landscape.constrainLyricsHeight(300, 72)
        assertEquals(248, lyricHeight)
        val windowHeight = lyricHeight + 72
        assertContained(
            landscape, landscape.constrainPosition(0, 900, landscape.width, windowHeight),
            landscape.width, windowHeight
        )
    }

    @Test fun oddScreenDimensionsDoNotLeaveAPixelOffScreen() {
        val bounds = LyricsWindowBounds(101, 201)
        for (offset in listOf(Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertContained(bounds, bounds.constrainPosition(offset, offset, 64, 64), 64, 64)
        }
    }

    @Test fun anOversizedWindowIsCenteredRatherThanGivenAnInvalidClampRange() {
        assertEquals(Pair(0, 0), LyricsWindowBounds(200, 100).constrainPosition(1000, -1000, 300, 180))
    }

    @Test fun compactLyricsKeepTheirRequestedHeightWhenThereIsRoom() {
        assertEquals(100, portrait.constrainLyricsHeight(100, 72))
    }

    private fun assertContained(bounds: LyricsWindowBounds, position: Pair<Int, Int>, width: Int, height: Int) {
        val left = (bounds.width - width) / 2 + position.first
        val top = (bounds.height - height) / 2 + position.second
        assertTrue("Left edge $left is off screen", left >= 0)
        assertTrue("Top edge $top is off screen", top >= 0)
        assertTrue("Right edge is off screen", left + width <= bounds.width)
        assertTrue("Bottom edge is off screen", top + height <= bounds.height)
    }
}
