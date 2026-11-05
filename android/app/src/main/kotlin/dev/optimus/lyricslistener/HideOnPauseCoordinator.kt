package dev.optimus.lyricslistener

import android.media.session.PlaybackState

/**
 * Coordinates the "Hide lyrics window on pause" state machine across MediaController
 * callbacks, metadata updates, async lyrics fetches, manual window reopens, and
 * real-time preference changes.
 */
internal class HideOnPauseCoordinator {
    enum class Action {
        NONE,
        HIDE_WINDOW,
        SHOW_WINDOW
    }

    @Volatile
    var wasHiddenByPausePreference: Boolean = false
        private set

    @Volatile
    var isPausedWindowManuallyOpened: Boolean = false
        private set

    fun onPlaybackStateUpdated(
        stateCode: Int?,
        hideOnPauseEnabled: Boolean,
        isLyricsOverlayActive: Boolean,
        isWindowCurrentlyShown: Boolean,
        hasActiveSession: Boolean
    ): Action {
        if (!hasActiveSession) {
            return Action.NONE
        }

        if (stateCode == PlaybackState.STATE_PLAYING) {
            isPausedWindowManuallyOpened = false
            if (wasHiddenByPausePreference) {
                wasHiddenByPausePreference = false
                if (isLyricsOverlayActive && !isWindowCurrentlyShown) {
                    return Action.SHOW_WINDOW
                }
            }
            return Action.NONE
        }

        // During transient transport transitions (buffering/seeking/skipping), do not
        // hide an already-visible window, and if already hidden by pause, wait until
        // actual STATE_PLAYING unless the user just disabled hide-on-pause.
        if (isTransientTransportState(stateCode)) {
            if (!hideOnPauseEnabled && wasHiddenByPausePreference) {
                wasHiddenByPausePreference = false
                if (isLyricsOverlayActive && !isWindowCurrentlyShown) {
                    return Action.SHOW_WINDOW
                }
            }
            return Action.NONE
        }

        if (hideOnPauseEnabled) {
            if (isLyricsOverlayActive && !isPausedWindowManuallyOpened) {
                wasHiddenByPausePreference = true
                if (isWindowCurrentlyShown) {
                    return Action.HIDE_WINDOW
                }
            }
        } else if (wasHiddenByPausePreference) {
            wasHiddenByPausePreference = false
            if (isLyricsOverlayActive && !isWindowCurrentlyShown) {
                return Action.SHOW_WINDOW
            }
        }

        return Action.NONE
    }

    fun onPreferenceChanged(
        hideOnPauseEnabled: Boolean,
        stateCode: Int?,
        isLyricsOverlayActive: Boolean,
        isWindowCurrentlyShown: Boolean,
        hasActiveSession: Boolean
    ): Action {
        if (hideOnPauseEnabled) {
            isPausedWindowManuallyOpened = false
        }
        return onPlaybackStateUpdated(
            stateCode = stateCode,
            hideOnPauseEnabled = hideOnPauseEnabled,
            isLyricsOverlayActive = isLyricsOverlayActive,
            isWindowCurrentlyShown = isWindowCurrentlyShown,
            hasActiveSession = hasActiveSession
        )
    }

    fun shouldSuppressShowWindow(
        hasActiveSession: Boolean,
        stateCode: Int?,
        hideOnPauseEnabled: Boolean,
        isLyricsOverlayActive: Boolean
    ): Boolean {
        if (!isLyricsOverlayActive || !hasActiveSession) {
            return false
        }
        if (stateCode == PlaybackState.STATE_PLAYING) {
            isPausedWindowManuallyOpened = false
            wasHiddenByPausePreference = false
            return false
        }
        if (isPausedWindowManuallyOpened) {
            return false
        }
        if (isTransientTransportState(stateCode)) {
            return wasHiddenByPausePreference && hideOnPauseEnabled
        }
        if (wasHiddenByPausePreference || hideOnPauseEnabled) {
            wasHiddenByPausePreference = true
            return true
        }
        return false
    }

    fun onWindowShown() {
        wasHiddenByPausePreference = false
    }

    fun onManualReopen(hasActiveSession: Boolean, stateCode: Int?) {
        wasHiddenByPausePreference = false
        isPausedWindowManuallyOpened = hasActiveSession && stateCode != PlaybackState.STATE_PLAYING
    }

    fun onOverlayMinimisedOrDismissed() {
        wasHiddenByPausePreference = false
        isPausedWindowManuallyOpened = false
    }

    fun reset() {
        wasHiddenByPausePreference = false
        isPausedWindowManuallyOpened = false
    }

    companion object {
        internal fun isTransientTransportState(stateCode: Int?): Boolean {
            return when (stateCode) {
                PlaybackState.STATE_FAST_FORWARDING,
                PlaybackState.STATE_REWINDING,
                PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_CONNECTING,
                PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
                PlaybackState.STATE_SKIPPING_TO_NEXT,
                PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
                else -> false
            }
        }
    }
}
