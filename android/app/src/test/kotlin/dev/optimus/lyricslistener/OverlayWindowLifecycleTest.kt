package dev.optimus.lyricslistener

import android.media.session.PlaybackState
import org.junit.Assert.*
import org.junit.Test

class OverlayWindowLifecycleTest {
    private class Window(
        var isAttached: Boolean = false,
        var hasWindowParent: Boolean = true,
        var lyrics: String = "",
        var artwork: String? = null
    )

    private fun isManaged(window: Window): Boolean = OverlayWindowLifecycle.isManaged(
        window.isAttached,
        window.hasWindowParent
    )

    private fun select(windows: List<Window>, preferred: Window? = null) =
        OverlayWindowLifecycle.select(windows, preferred, ::isManaged)

    @Test fun rootIsManagedBeforeAttachmentAndUntilItsWindowParentIsRemoved() {
        assertFalse(OverlayWindowLifecycle.isManaged(false, false))
        assertTrue(OverlayWindowLifecycle.isManaged(false, true))
        assertTrue(OverlayWindowLifecycle.isManaged(true, true))
        assertTrue(OverlayWindowLifecycle.isManaged(true, false))
    }

    @Test fun consecutiveShowRequestsKeepTheSameRootBeforeItsFirstTraversal() {
        val pending = Window()
        repeat(3) {
            val selection = select(listOf(pending), pending)
            assertSame(pending, selection.survivor)
            assertTrue(selection.duplicates.isEmpty())
            assertTrue(selection.detached.isEmpty())
        }

        pending.isAttached = true
        assertSame(pending, select(listOf(pending), pending).survivor)
    }

    @Test fun preferredPendingRootIsNotReplacedByAnOlderAttachedWindow() {
        val old = Window(isAttached = true)
        val pending = Window()

        val selection = select(listOf(old, pending), pending)
        assertSame(pending, selection.survivor)
        assertEquals(listOf(old), selection.duplicates)
        assertTrue(selection.detached.isEmpty())
    }

    @Test fun pendingDuplicatesMustBeExplicitlyRemovedRatherThanForgotten() {
        val pendingDuplicate = Window()
        val current = Window(isAttached = true)

        val selection = select(listOf(pendingDuplicate, current), current)
        assertSame(current, selection.survivor)
        assertEquals(listOf(pendingDuplicate), selection.duplicates)
        assertTrue(selection.detached.isEmpty())
    }

    @Test fun detachedPreferredRootDoesNotHideAnotherManagedWindow() {
        val detached = Window(hasWindowParent = false)
        val pending = Window()

        val selection = select(listOf(detached, pending), detached)
        assertSame(pending, selection.survivor)
        assertEquals(listOf(detached), selection.detached)
        assertTrue(selection.duplicates.isEmpty())
    }

    @Test fun latestManagedRootIsSelectedWhenNoPreferredRootExists() {
        val old = Window(isAttached = true)
        val pending = Window()
        val detached = Window(hasWindowParent = false)

        val selection = select(listOf(old, pending, detached))
        assertSame(pending, selection.survivor)
        assertEquals(listOf(old), selection.duplicates)
        assertEquals(listOf(detached), selection.detached)
    }

    @Test fun failedRemovalOfAPendingRootKeepsItEligibleForCleanup() {
        val pending = Window()
        val current = Window(isAttached = true)
        // A failed removeViewImmediate leaves the root's parent in place, even though
        // isAttachedToWindow is still false. It must remain tracked for another attempt.
        assertEquals(listOf(pending), select(listOf(pending, current), current).duplicates)

        pending.hasWindowParent = false
        val selection = select(listOf(pending, current), current)
        assertEquals(listOf(pending), selection.detached)
        assertTrue(selection.duplicates.isEmpty())
    }

    @Test fun resumingAppleMusicAfterPausingSpotifyCreatesOnlyOneReplacementWindow() {
        val coordinator = HideOnPauseCoordinator()
        val windowManagerRoots = mutableListOf<Window>()
        val knownRoots = mutableListOf<Window>()
        var current: Window? = null
        var addViewCalls = 0

        fun show(lyrics: String, artwork: String? = null) {
            val selection = select(knownRoots, current)
            knownRoots.removeAll(selection.detached.toSet())
            selection.duplicates.forEach { duplicate ->
                duplicate.isAttached = false
                duplicate.hasWindowParent = false
                windowManagerRoots.remove(duplicate)
                knownRoots.remove(duplicate)
            }
            current = selection.survivor ?: Window().also {
                // Simulate addView returning with a parent but before attachment.
                windowManagerRoots.add(it)
                knownRoots.add(it)
                addViewCalls++
            }
            current!!.lyrics = lyrics
            current!!.artwork = artwork
            coordinator.onWindowShown()
        }

        fun hide() {
            knownRoots.forEach {
                it.isAttached = false
                it.hasWindowParent = false
                windowManagerRoots.remove(it)
            }
            knownRoots.clear()
            current = null
        }

        show("Apple Music lyrics", "Apple Music artwork")
        current!!.isAttached = true
        show("Spotify lyrics", "Spotify artwork")
        assertEquals(1, addViewCalls)

        val pauseAction = coordinator.onPlaybackStateUpdated(
            PlaybackState.STATE_PAUSED, true, true, current != null, true
        )
        assertEquals(HideOnPauseCoordinator.Action.HIDE_WINDOW, pauseAction)
        hide()

        val resumeAction = coordinator.onPlaybackStateUpdated(
            PlaybackState.STATE_PLAYING, true, true, current != null, true
        )
        assertEquals(HideOnPauseCoordinator.Action.SHOW_WINDOW, resumeAction)
        show("Loading Apple Music lyrics...")
        val restored = current!!
        assertFalse(restored.isAttached)

        // The metadata handler shows again immediately after the resume handler.
        show("Loading Apple Music lyrics...")
        // A fast cache hit/artwork update may also arrive before the first traversal.
        show("Apple Music lyrics", "Apple Music artwork")
        assertSame(restored, current)
        assertEquals(2, addViewCalls)
        assertEquals(listOf(restored), windowManagerRoots)
        assertEquals(listOf(restored), knownRoots)
        assertEquals("Apple Music lyrics", restored.lyrics)
        assertEquals("Apple Music artwork", restored.artwork)

        restored.isAttached = true
        assertSame(restored, select(knownRoots, current).survivor)
        hide()
        assertTrue(windowManagerRoots.isEmpty())
    }
}
