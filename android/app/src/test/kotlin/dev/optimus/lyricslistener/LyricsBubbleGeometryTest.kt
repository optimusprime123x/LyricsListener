package dev.optimus.lyricslistener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsBubbleGeometryTest {
    private val portrait = LyricsWindowBounds(480, 746)

    @Test fun dismissedBubbleDoesNotReturnToTheBottomAfterShowAndMinimise() {
        val placement = LyricsBubblePlacement()
        placement.remember(0, 325) // Position just before the bubble was dropped on Dismiss.
        placement.forget()

        val next = placement.positionFor(portrait, 90, -120)
        assertEquals(Pair(195, -120), next) // Right edge, aligned with the reopened lyrics.
    }

    @Test fun reopeningWithoutDismissalPreservesTheUsersBubblePosition() {
        val placement = LyricsBubblePlacement()
        placement.remember(-195, 80)
        assertEquals(Pair(-195, 80), placement.positionFor(portrait, 90, -120))
    }

    @Test fun placementRemainsSafeAfterRotationOrNearTheBottom() {
        val placement = LyricsBubblePlacement()
        placement.remember(192, 325)
        assertEquals(Pair(192, 111), placement.positionFor(LyricsWindowBounds(746, 312), 90, 0))
        placement.forget()
        assertEquals(Pair(195, 328), placement.positionFor(portrait, 90, 600))
    }

    @Test fun distantBubbleFollowsTheFingerWithoutAttraction() {
        assertEquals(LyricsBubbleMagnet.Pull(), pull(113f, 0f))
        assertEquals(LyricsBubbleMagnet.Pull(), pull(112f, 0f))
    }

    @Test fun approachingTheTargetPullsGentlyBeforeCapture() {
        val pull = pull(96f, 0f)
        assertFalse(pull.captured)
        assertEquals(-48f, pull.x, 0.001f)
        assertEquals(0f, pull.y, 0.001f)
        assertTrue(kotlin.math.abs(pull(85f, 0f).x) > kotlin.math.abs(pull(110f, 0f).x))
    }

    @Test fun closeBubbleIsCenteredAndCapturedIncludingAtTheBottomEdge() {
        val pull = pull(0f, 78f)
        assertTrue(pull.captured)
        assertEquals(0f, pull.x, 0.001f)
        assertEquals(-78f, pull.y, 0.001f)
        assertEquals(LyricsBubbleMagnet.Pull(captured = true), pull(0f, 0f))
    }

    @Test fun attractionUsesCircularDistanceAndReleasesWhenDraggedAway() {
        assertFalse(pull(79f, 79f).captured)
        assertTrue(pull(40f, -40f).captured)
        assertEquals(LyricsBubbleMagnet.Pull(), pull(150f, 0f))
        val diagonal = pull(60f, -60f)
        assertTrue(diagonal.x < 0f && diagonal.y > 0f)
    }

    private fun pull(x: Float, y: Float) = LyricsBubbleMagnet.pull(x, y, 0f, 0f, 80f, 112f)
}
