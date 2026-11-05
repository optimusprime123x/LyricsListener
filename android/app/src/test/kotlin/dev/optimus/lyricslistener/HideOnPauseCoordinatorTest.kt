package dev.optimus.lyricslistener

import android.media.session.PlaybackState
import org.junit.Assert.*
import org.junit.Test

class HideOnPauseCoordinatorTest {

    @Test
    fun `pausing active playback hides window and resuming re-shows it`() {
        val coordinator = HideOnPauseCoordinator()

        val onPause = coordinator.onPlaybackStateUpdated(
            stateCode = PlaybackState.STATE_PAUSED,
            hideOnPauseEnabled = true,
            isLyricsOverlayActive = true,
            isWindowCurrentlyShown = true,
            hasActiveSession = true
        )
        assertEquals(HideOnPauseCoordinator.Action.HIDE_WINDOW, onPause)
        assertTrue(coordinator.wasHiddenByPausePreference)

        val onPlay = coordinator.onPlaybackStateUpdated(
            stateCode = PlaybackState.STATE_PLAYING,
            hideOnPauseEnabled = true,
            isLyricsOverlayActive = true,
            isWindowCurrentlyShown = false,
            hasActiveSession = true
        )
        assertEquals(HideOnPauseCoordinator.Action.SHOW_WINDOW, onPlay)
        assertFalse(coordinator.wasHiddenByPausePreference)
    }

    @Test
    fun `metadata update followed immediately by onPlaybackStateChanged does not swallow hide or show`() {
        val coordinator = HideOnPauseCoordinator()
        var windowShown = true

        // Simulate processMediaMetadata seeing STATE_PAUSED right before onPlaybackStateChanged
        val metaPause = coordinator.onPlaybackStateUpdated(
            stateCode = PlaybackState.STATE_PAUSED,
            hideOnPauseEnabled = true,
            isLyricsOverlayActive = true,
            isWindowCurrentlyShown = windowShown,
            hasActiveSession = true
        )
        assertEquals(HideOnPauseCoordinator.Action.HIDE_WINDOW, metaPause)
        windowShown = false

        val callbackPause = coordinator.onPlaybackStateUpdated(
            stateCode = PlaybackState.STATE_PAUSED,
            hideOnPauseEnabled = true,
            isLyricsOverlayActive = true,
            isWindowCurrentlyShown = windowShown,
            hasActiveSession = true
        )
        assertEquals(HideOnPauseCoordinator.Action.NONE, callbackPause)
        assertTrue(coordinator.wasHiddenByPausePreference)

        // Simulate processMediaMetadata seeing STATE_PLAYING right before onPlaybackStateChanged
        val metaPlay = coordinator.onPlaybackStateUpdated(
            stateCode = PlaybackState.STATE_PLAYING,
            hideOnPauseEnabled = true,
            isLyricsOverlayActive = true,
            isWindowCurrentlyShown = windowShown,
            hasActiveSession = true
        )
        assertEquals(HideOnPauseCoordinator.Action.SHOW_WINDOW, metaPlay)
        windowShown = true
        coordinator.onWindowShown()

        val callbackPlay = coordinator.onPlaybackStateUpdated(
            stateCode = PlaybackState.STATE_PLAYING,
            hideOnPauseEnabled = true,
            isLyricsOverlayActive = true,
            isWindowCurrentlyShown = windowShown,
            hasActiveSession = true
        )
        assertEquals(HideOnPauseCoordinator.Action.NONE, callbackPlay)
        assertFalse(coordinator.wasHiddenByPausePreference)
    }

    @Test
    fun `async lyrics completion while paused suppresses window and marks hidden so resume shows it`() {
        val coordinator = HideOnPauseCoordinator()

        // Connect to an already-paused session; async lyrics fetch completes while paused
        val suppressed = coordinator.shouldSuppressShowWindow(
            hasActiveSession = true,
            stateCode = PlaybackState.STATE_PAUSED,
            hideOnPauseEnabled = true,
            isLyricsOverlayActive = true
        )
        assertTrue(suppressed)
        assertTrue(coordinator.wasHiddenByPausePreference)

        // When user starts playback, window automatically appears
        val onPlay = coordinator.onPlaybackStateUpdated(
            stateCode = PlaybackState.STATE_PLAYING,
            hideOnPauseEnabled = true,
            isLyricsOverlayActive = true,
            isWindowCurrentlyShown = false,
            hasActiveSession = true
        )
        assertEquals(HideOnPauseCoordinator.Action.SHOW_WINDOW, onPlay)
    }

    @Test
    fun `transient buffering or track skipping does not flicker visible window`() {
        val coordinator = HideOnPauseCoordinator()

        for (transientState in listOf(
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_CONNECTING
        )) {
            val action = coordinator.onPlaybackStateUpdated(
                stateCode = transientState,
                hideOnPauseEnabled = true,
                isLyricsOverlayActive = true,
                isWindowCurrentlyShown = true,
                hasActiveSession = true
            )
            assertEquals(HideOnPauseCoordinator.Action.NONE, action)
            assertFalse(
                coordinator.shouldSuppressShowWindow(
                    hasActiveSession = true,
                    stateCode = transientState,
                    hideOnPauseEnabled = true,
                    isLyricsOverlayActive = true
                )
            )
        }
    }

    @Test
    fun `manual reopen while paused stays open until next play-pause cycle`() {
        val coordinator = HideOnPauseCoordinator()

        coordinator.onPlaybackStateUpdated(
            stateCode = PlaybackState.STATE_PAUSED,
            hideOnPauseEnabled = true,
            isLyricsOverlayActive = true,
            isWindowCurrentlyShown = true,
            hasActiveSession = true
        )
        assertTrue(coordinator.wasHiddenByPausePreference)

        // User taps "Show Lyrics" or expands bubble while still paused
        coordinator.onManualReopen(hasActiveSession = true, stateCode = PlaybackState.STATE_PAUSED)
        assertFalse(
            coordinator.shouldSuppressShowWindow(
                hasActiveSession = true,
                stateCode = PlaybackState.STATE_PAUSED,
                hideOnPauseEnabled = true,
                isLyricsOverlayActive = true
            )
        )
        coordinator.onWindowShown()

        // Subsequent paused updates or retries while paused must not re-hide the window
        val stillPaused = coordinator.onPlaybackStateUpdated(
            stateCode = PlaybackState.STATE_PAUSED,
            hideOnPauseEnabled = true,
            isLyricsOverlayActive = true,
            isWindowCurrentlyShown = true,
            hasActiveSession = true
        )
        assertEquals(HideOnPauseCoordinator.Action.NONE, stillPaused)

        // Resuming playback clears the manual override so the next pause hides again
        coordinator.onPlaybackStateUpdated(
            stateCode = PlaybackState.STATE_PLAYING,
            hideOnPauseEnabled = true,
            isLyricsOverlayActive = true,
            isWindowCurrentlyShown = true,
            hasActiveSession = true
        )
        val nextPause = coordinator.onPlaybackStateUpdated(
            stateCode = PlaybackState.STATE_PAUSED,
            hideOnPauseEnabled = true,
            isLyricsOverlayActive = true,
            isWindowCurrentlyShown = true,
            hasActiveSession = true
        )
        assertEquals(HideOnPauseCoordinator.Action.HIDE_WINDOW, nextPause)
    }

    @Test
    fun `live preference toggle while paused immediately hides or re-shows window`() {
        val coordinator = HideOnPauseCoordinator()

        // Currently paused with hide-on-pause disabled -> window is visible
        val enabledWhilePaused = coordinator.onPreferenceChanged(
            hideOnPauseEnabled = true,
            stateCode = PlaybackState.STATE_PAUSED,
            isLyricsOverlayActive = true,
            isWindowCurrentlyShown = true,
            hasActiveSession = true
        )
        assertEquals(HideOnPauseCoordinator.Action.HIDE_WINDOW, enabledWhilePaused)
        assertTrue(coordinator.wasHiddenByPausePreference)

        // User disables hide-on-pause while still paused -> window immediately re-shows
        val disabledWhilePaused = coordinator.onPreferenceChanged(
            hideOnPauseEnabled = false,
            stateCode = PlaybackState.STATE_PAUSED,
            isLyricsOverlayActive = true,
            isWindowCurrentlyShown = false,
            hasActiveSession = true
        )
        assertEquals(HideOnPauseCoordinator.Action.SHOW_WINDOW, disabledWhilePaused)
        assertFalse(coordinator.wasHiddenByPausePreference)
    }

    @Test
    fun `minimising to bubble or dismissing clears pause hidden flag`() {
        val coordinator = HideOnPauseCoordinator()

        coordinator.onPlaybackStateUpdated(
            stateCode = PlaybackState.STATE_PAUSED,
            hideOnPauseEnabled = true,
            isLyricsOverlayActive = true,
            isWindowCurrentlyShown = true,
            hasActiveSession = true
        )
        assertTrue(coordinator.wasHiddenByPausePreference)

        coordinator.onOverlayMinimisedOrDismissed()
        assertFalse(coordinator.wasHiddenByPausePreference)

        val onPlayInBubble = coordinator.onPlaybackStateUpdated(
            stateCode = PlaybackState.STATE_PLAYING,
            hideOnPauseEnabled = true,
            isLyricsOverlayActive = false,
            isWindowCurrentlyShown = false,
            hasActiveSession = true
        )
        assertEquals(HideOnPauseCoordinator.Action.NONE, onPlayInBubble)
    }
}
