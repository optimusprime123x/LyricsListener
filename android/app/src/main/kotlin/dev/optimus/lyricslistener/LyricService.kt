//LyricService.kt : 
package dev.optimus.lyricslistener

import android.app.NotificationChannel
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.app.Notification
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import android.view.WindowManager
import android.graphics.PixelFormat
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.view.MotionEvent
import android.app.PendingIntent
import android.provider.Settings
import android.widget.ImageButton
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import dev.optimus.lyricslistener.R
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.view.isVisible
import androidx.palette.graphics.Palette
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import androidx.core.graphics.ColorUtils


class LyricService : NotificationListenerService() {

    private val TAG = "LyricService"
    private val NOTIFICATION_CHANNEL_ID = "LyricServiceChannel"
    private val NOTIFICATION_ID = 1
    private val HIGHLIGHT_UPDATE_INTERVAL_MS = 200L
    private val CONNECT_RETRY_DELAY_MS = 2000L

    @Volatile private var windowManager: WindowManager? = null
    @Volatile private var lyricsView: View? = null
    @Volatile private var params: WindowManager.LayoutParams? = null
    private var lyricsRecyclerView: RecyclerView? = null
    private var songInfoTextView: TextView? = null
    private var expandCollapseButton: ImageButton? = null
    private var lyricsAdapter: LyricsAdapter? = null
    private lateinit var linearLayoutManager: LinearLayoutManager // Stays lateinit, will be assigned in showLyricsWindow

    private var lastDetectedSongTitle: String? = null
    private var lastDetectedSongArtist: String? = null
    @Volatile private var currentLyricsData: LyricsData? = null
    private var currentSongDurationMs: Long = 0L

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var lyricsHighlightingJob: Job? = null

    @Volatile private var activeMediaController: MediaController? = null
    private var mediaControllerCallback: MediaController.Callback? = null
    @Volatile private var currentMediaSessionToken: MediaSession.Token? = null
    private var currentPlaybackState: PlaybackState? = null

    private var isLyricsExpanded = false
    private val COLLAPSED_LYRICS_MAX_HEIGHT_DP = 100
    private lateinit var notificationManager: NotificationManager
    private val EXPANDED_LYRICS_MAX_HEIGHT_DP = 300


    @Volatile private var _listenerEverConnected = false
    @Volatile private var _isAttemptingConnection = false


    @Serializable
    data class LyricResult(
        val id: Int,
        val trackName: String,
        val artistName: String,
        val albumName: String? = null,
        val duration: Double,
        val instrumental: Boolean,
        val plainLyrics: String?,
        val syncedLyrics: String? = null
    )

    sealed class LyricsData {
        abstract val durationMs: Long

        data class Plain(val title: String, val artist: String?, val lyrics: String, override val durationMs: Long) : LyricsData()
        data class Synced(val title: String, val artist: String?, val lines: List<TimedLyricLine>, override val durationMs: Long) : LyricsData()
        data class Info(val title: String?, val artist: String?, val message: String, override val durationMs: Long) : LyricsData()
        data class MismatchInfo(
            val title: String?,
            val artist: String?,
            val originalLyricsData: LyricsData?
        ) : LyricsData() {
            override val durationMs: Long
                get() = originalLyricsData?.durationMs ?: 0L
        }
    }


    data class TimedLyricLine(
        val timestamp: Long,
        val text: String
    )

    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            })
        }
    }

    companion object {
        const val ACTION_SHOW_LYRICS = "dev.optimus.lyricslistener.ACTION_SHOW_LYRICS"
        const val ACTION_HIDE_LYRICS = "dev.optimus.lyricslistener.ACTION_HIDE_LYRICS"
        private const val LYRIC_API_BASE_URL = "https://lrclib.net/api/search"
        private val LRC_LINE_PATTERN: Pattern = Pattern.compile("(?<=\\[)(\\d{2,}):(\\d{2})([.:])(\\d{2,3})\\](.*)")
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate() called. Instance: ${this.hashCode()}")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createPersistentNotification("Initializing Lyric Service..."))
        isLyricsExpanded = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand (Instance: ${this.hashCode()}) with action: ${intent?.action}, flags: $flags, startId: $startId. Current token: $currentMediaSessionToken, LyricsView Null: ${lyricsView == null}")
        startForeground(NOTIFICATION_ID, createPersistentNotification("Lyric Service Active"))

        when (intent?.action) {
            ACTION_SHOW_LYRICS -> {
                Log.d(TAG, "ACTION_SHOW_LYRICS received.")
                val dataToShow = currentLyricsData ?: LyricsData.Info(
                    lastDetectedSongTitle,
                    lastDetectedSongArtist,
                    if (lastDetectedSongTitle != null) "Loading lyrics..." else "Waiting for song...",
                    currentSongDurationMs
                )
                showLyricsWindow(dataToShow)

                if (lastDetectedSongTitle != null && dataToShow is LyricsData.Info && dataToShow.message.contains("Loading", ignoreCase = true)) {
                    fetchAndDisplayLyrics(lastDetectedSongTitle!!, lastDetectedSongArtist ?: "", currentSongDurationMs)
                } else if (lastDetectedSongTitle == null) {
                    tryToConnectToActiveMediaSessions(delayMs = 0L)
                }
            }
            ACTION_HIDE_LYRICS -> {
                Log.d(TAG, "ACTION_HIDE_LYRICS received.")
                hideLyricsWindow()
            }
            else -> {
                Log.i(TAG, "Service (re)started with null or unhandled action (Intent: $intent, Action: ${intent?.action}). Current token: $currentMediaSessionToken. Attempting to scan for media.")
                updatePersistentNotification("Service active. Scanning media...")
                tryToConnectToActiveMediaSessions(delayMs = 500L) // Standard delay for restart scan
            }
        }
        return START_STICKY
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _listenerEverConnected = true
        _isAttemptingConnection = false
        Log.d(TAG, "Notification Listener connected by system. (Instance: ${this.hashCode()})")
        updatePersistentNotification("Listener connected, scanning media...")
        tryToConnectToActiveMediaSessions(delayMs = 300L)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _listenerEverConnected = false
        _isAttemptingConnection = false
        Log.w(TAG, "Notification Listener disconnected by system! (Instance: ${this.hashCode()}) Cleaning up.")
        clearSongContextAndHideLyrics()
        updatePersistentNotification("Listener disconnected. Check permissions.")
    }

    private val executeFindActiveMediaSessionsRunnable = Runnable { executeFindActiveMediaSessions() }

    private fun tryToConnectToActiveMediaSessions(delayMs: Long) {
        if (_isAttemptingConnection && delayMs > 0) {
            Log.d(TAG, "tryToConnectToActiveMediaSessions: Connection attempt already effectively scheduled (or running) with flag. Ignoring new delayed request with delay $delayMs ms.")
            return
        }
        Log.d(TAG, "tryToConnectToActiveMediaSessions scheduled with delay: $delayMs ms. _listenerEverConnected: $_listenerEverConnected")
        if(delayMs > 0) { // Only set flag for actual delayed attempts, immediate ones will clear it quickly
            _isAttemptingConnection = true
        }

        Handler(Looper.getMainLooper()).removeCallbacks(executeFindActiveMediaSessionsRunnable)
        Handler(Looper.getMainLooper()).postDelayed(executeFindActiveMediaSessionsRunnable, delayMs)
    }


    private fun executeFindActiveMediaSessions() {
        Log.d(TAG, "executeFindActiveMediaSessions: Starting media scan. _listenerEverConnected: $_listenerEverConnected. Current token: $currentMediaSessionToken")
        var activeNotificationsInternal: Array<StatusBarNotification>? = null
        try {
            activeNotificationsInternal = this.activeNotifications
             _isAttemptingConnection = false // Reset flag as we are now executing
            if (!_listenerEverConnected && activeNotificationsInternal != null) {
                Log.i(TAG, "executeFindActiveMediaSessions: Got active notifications, promoting _listenerEverConnected to true.")
                _listenerEverConnected = true
                updatePersistentNotification("Listener active, scanning...")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException getting active notifications: ${e.message}. Listener permission might be revoked.")
            _listenerEverConnected = false
            _isAttemptingConnection = false
            updatePersistentNotification("Error: Check Notification Access.")
            clearSongContextAndHideLyrics()
            return
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting active notifications: ${e.message}", e)
            _isAttemptingConnection = false
            updatePersistentNotification("Error accessing notifications. Retrying...")
            tryToConnectToActiveMediaSessions(CONNECT_RETRY_DELAY_MS)
            return
        }

        if (activeNotificationsInternal == null) {
            Log.w(TAG, "activeNotifications API returned null. Listener might not be fully bound or permission issue.")
            if (_listenerEverConnected) {
                updatePersistentNotification("Listener error. Retrying connection...")
            } else {
                updatePersistentNotification("Waiting for listener connection...")
            }
            _isAttemptingConnection = false // Ensure flag is false before retry
            tryToConnectToActiveMediaSessions(CONNECT_RETRY_DELAY_MS * 2)
            if (currentMediaSessionToken != null) clearSongContextAndHideLyrics()
            return
        }


        if (activeNotificationsInternal.isEmpty()) {
            Log.d(TAG, "No active media notifications found (list is empty).")
            if (_listenerEverConnected) updatePersistentNotification("Waiting for song...")
            else updatePersistentNotification("Listener connected, waiting for song...")

            if (currentMediaSessionToken != null) {
                Log.d(TAG, "executeFindActiveMediaSessions: activeNotifications is empty, clearing existing song context for token $currentMediaSessionToken.")
                clearSongContextAndHideLyrics()
            }
            return
        }

        Log.d(TAG, "Found ${activeNotificationsInternal.size} active notifications.")
        val playingNotifications = activeNotificationsInternal.mapNotNull { sbn ->
            val token = sbn.notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
            if (token != null) {
                try {
                    val controller = MediaController(applicationContext, token)
                    if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        sbn
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking playback state for token $token (pkg ${sbn.packageName}): ${e.message}")
                    null
                }
            } else {
                null
            }
        }

        var mostRecentSbnToProcess = playingNotifications.maxByOrNull {
            it.notification.`when`.takeIf { w -> w > 0 } ?: it.postTime
        }

        if (mostRecentSbnToProcess == null && activeNotificationsInternal.isNotEmpty()) {
            Log.d(TAG, "No actively playing media. Checking for any recent media notification to establish context.")
            mostRecentSbnToProcess = activeNotificationsInternal.maxByOrNull {
                 it.notification.`when`.takeIf { w -> w > 0 } ?: it.postTime
            }
        }

        mostRecentSbnToProcess?.let { sbn ->
            Log.d(TAG, "Processing most recent media notification from ${sbn.packageName} (postTime: ${sbn.postTime}, when: ${sbn.notification.`when`}).")
            _onNotificationPosted(sbn, "From executeFindActiveMediaSessions")
        } ?: run {
            Log.d(TAG, "No suitable media notifications found to process.")
            if (_listenerEverConnected) updatePersistentNotification("Waiting for song...")
            else updatePersistentNotification("Listener connected, waiting for song...")

            if (currentMediaSessionToken != null) {
                Log.d(TAG, "executeFindActiveMediaSessions: No SBN to process, clearing existing song context for token $currentMediaSessionToken.")
                clearSongContextAndHideLyrics()
            }
        }
    }


    private fun setupMediaController(token: MediaSession.Token) {
        if (activeMediaController != null && currentMediaSessionToken == token && activeMediaController!!.sessionToken == token) {
            Log.d(TAG, "MediaController already set up for this token ($token). Forcing metadata/playback state update.")
            activeMediaController?.playbackState?.let { mediaControllerCallback?.onPlaybackStateChanged(it) }
            activeMediaController?.metadata?.let { mediaControllerCallback?.onMetadataChanged(it) }
            return
        }

        Log.i(TAG, "Setting up new MediaController for token: $token. Previous token was: $currentMediaSessionToken, Previous controller: ${activeMediaController?.sessionToken}")
        cleanupMediaController()

        try {
            val newController = MediaController(applicationContext, token)
            mediaControllerCallback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    super.onPlaybackStateChanged(state)
                    if (newController.sessionToken != currentMediaSessionToken) {
                        Log.w(TAG, "onPlaybackStateChanged for a stale session (${newController.sessionToken}, pkg: ${newController.packageName}). Current active token is $currentMediaSessionToken. Ignoring.")
                        return
                    }
                    val oldState = this@LyricService.currentPlaybackState?.state
                    this@LyricService.currentPlaybackState = state
                    Log.d(TAG, "onPlaybackStateChanged (for $currentMediaSessionToken): ${stateToString(state)}, Pos: ${state?.position}, Speed: ${state?.playbackSpeed}")

                    if (currentLyricsData is LyricsData.Synced) {
                        if (state?.state == PlaybackState.STATE_PLAYING) {
                            if (oldState != PlaybackState.STATE_PLAYING || lyricsHighlightingJob == null || lyricsHighlightingJob?.isCompleted == true) {
                                startOrUpdateLyricsHighlighting()
                            }
                        } else {
                            lyricsHighlightingJob?.cancel()
                            Log.d(TAG, "Playback not active for $currentMediaSessionToken, cancelling highlighting job.")
                        }
                    }
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    super.onMetadataChanged(metadata)
                    if (newController.sessionToken != currentMediaSessionToken) {
                        Log.w(TAG, "onMetadataChanged for a stale session (${newController.sessionToken}, pkg: ${newController.packageName}). Current active token is $currentMediaSessionToken. Ignoring.")
                        return
                    }
                    Log.d(TAG, "onMetadataChanged (for $currentMediaSessionToken): Title: ${metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}")
                    processMediaMetadata(metadata, newController.playbackState, "Callback: MetadataChanged for $currentMediaSessionToken (${newController.packageName})")
                }

                override fun onSessionDestroyed() {
                    super.onSessionDestroyed()
                    Log.d(TAG, "onSessionDestroyed received for token ${newController.sessionToken} from pkg ${newController.packageName}. Current service token: $currentMediaSessionToken")
                    if (newController.sessionToken == currentMediaSessionToken) {
                        Log.i(TAG, "MediaSession Destroyed for active token $currentMediaSessionToken (pkg: ${newController.packageName}). Cleaning up context.")
                        clearSongContextAndHideLyrics()
                    } else {
                        Log.w(TAG, "MediaSession Destroyed for a token ${newController.sessionToken} (pkg: ${newController.packageName}) that is NOT the current active token ($currentMediaSessionToken).")
                         if (activeMediaController != null && activeMediaController?.sessionToken == newController.sessionToken) {
                            Log.d(TAG, "The destroyed session's controller was indeed our activeMediaController. Cleaning it up, but not clearing full song context unless currentMediaSessionToken matches.")
                            cleanupMediaController()
                        }
                    }
                }
            }
            newController.registerCallback(mediaControllerCallback!!, Handler(Looper.getMainLooper()))

            activeMediaController = newController
            currentMediaSessionToken = token

            Log.d(TAG, "MediaController registered for token: $token from package ${newController.packageName}. currentMediaSessionToken is now $currentMediaSessionToken.")

            processMediaMetadata(newController.metadata, newController.playbackState, "Initial Setup for $token (${newController.packageName})")
            newController.playbackState?.let { mediaControllerCallback?.onPlaybackStateChanged(it) }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up MediaController for $token: ${e.message}", e)
            if (currentMediaSessionToken == token) {
                clearSongContextAndHideLyrics()
            }
        }
    }

    private fun processMediaMetadata(metadata: MediaMetadata?, playbackState: PlaybackState?, source: String) {
        if (activeMediaController?.sessionToken != currentMediaSessionToken && metadata != null) {
            Log.w(TAG, "processMediaMetadata called with metadata for a non-active session. Current: $currentMediaSessionToken, Metadata's Controller Token: ${activeMediaController?.sessionToken}. Source: $source. Bailing.")
            return
        }

        val newTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val newArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val newDuration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        Log.d(TAG, "Processing Meta ($source): Title='$newTitle', Artist='$newArtist', Duration='${newDuration}ms', PlaybackState: ${stateToString(playbackState)}")

        if (newTitle.isNullOrBlank() && metadata != null) {
            Log.d(TAG, "Media metadata ($source) missing title. Not processing as new song.")
            if (lastDetectedSongTitle != null && (activeMediaController?.sessionToken == currentMediaSessionToken || currentMediaSessionToken == null)) {
                 Log.d(TAG, "Title became null/blank for current session $currentMediaSessionToken, previously was '$lastDetectedSongTitle'. Clearing context.")
                 clearSongContextAndHideLyrics()
            }
            return
        }

        if (newTitle != lastDetectedSongTitle || newArtist != lastDetectedSongArtist) {
            Log.i(TAG, "New song detected ($source): '$newTitle' by '$newArtist'. Old: '$lastDetectedSongTitle' by '$lastDetectedSongArtist'. Token: $currentMediaSessionToken")
            lastDetectedSongTitle = newTitle
            lastDetectedSongArtist = newArtist
            currentSongDurationMs = if (newDuration > 0) newDuration else (currentLyricsData?.durationMs ?: 0L)
            this.currentPlaybackState = playbackState

            val songInfoForDisplay = newArtist?.takeIf { it.isNotBlank() }?.let { "$newTitle by $it" } ?: newTitle ?: "Unknown Song"
            updatePersistentNotification("Lyrics for: $songInfoForDisplay")

            if (newTitle != null) {
                val loadingData = LyricsData.Info(newTitle, newArtist, "Loading lyrics...", currentSongDurationMs)
                currentLyricsData = loadingData
                if (lyricsView != null) {
                    showLyricsWindow(loadingData)
                }
                fetchAndDisplayLyrics(newTitle, newArtist ?: "", currentSongDurationMs)
            } else {
                 Log.w(TAG, "New song detected but title is null. Cannot fetch lyrics. Token: $currentMediaSessionToken")
                 currentLyricsData = LyricsData.Info(null, newArtist, "Song title not available.", currentSongDurationMs)
                 if (lyricsView != null) {
                    showLyricsWindow(currentLyricsData!!)
                 }
            }
        } else {
             if (newDuration > 0 && newDuration != currentSongDurationMs) {
                Log.d(TAG,"Duration updated for '$newTitle' ($currentMediaSessionToken) to $newDuration ms")
                currentSongDurationMs = newDuration
                currentLyricsData = when(val cd = currentLyricsData) {
                    is LyricsData.Synced -> cd.copy(durationMs = newDuration)
                    is LyricsData.Plain -> cd.copy(durationMs = newDuration)
                    is LyricsData.Info -> cd.copy(durationMs = newDuration)
                    is LyricsData.MismatchInfo -> {
                        val original = cd.originalLyricsData
                        val updatedOriginal = when (original) {
                            is LyricsData.Synced -> original.copy(durationMs = newDuration)
                            is LyricsData.Plain -> original.copy(durationMs = newDuration)
                            is LyricsData.Info -> original.copy(durationMs = newDuration)
                            is LyricsData.MismatchInfo -> original
                            null -> null
                        }
                        cd.copy(originalLyricsData = updatedOriginal)
                    }
                    null -> null
                }
                 if (lyricsView != null && currentLyricsData != null) {
                    showLyricsWindow(currentLyricsData!!)
                }
            }
            this.currentPlaybackState = playbackState

            if (lyricsView != null && currentLyricsData is LyricsData.Synced &&
                playbackState?.state == PlaybackState.STATE_PLAYING &&
                activeMediaController?.sessionToken == currentMediaSessionToken) {
                 startOrUpdateLyricsHighlighting()
            }
            Log.d(TAG, "Song is the same ($source): '$newTitle'. Playback state: ${stateToString(playbackState)}. Token: $currentMediaSessionToken")
        }
    }

    private fun cleanupMediaController() {
        val controllerToClean = activeMediaController
        val callbackToUnregister = mediaControllerCallback
        val tokenAssociatedWithController = controllerToClean?.sessionToken

        Log.d(TAG, "cleanupMediaController: Attempting to clean up controller for token (approx): $tokenAssociatedWithController. Current service active token: $currentMediaSessionToken")

        lyricsHighlightingJob?.cancel()
        lyricsHighlightingJob = null

        if (controllerToClean != null && callbackToUnregister != null) {
            try {
                controllerToClean.unregisterCallback(callbackToUnregister)
                Log.d(TAG, "Unregistered callback from controller for token: $tokenAssociatedWithController")
            } catch (e: Exception) {
                Log.w(TAG, "Exception unregistering MediaController callback for $tokenAssociatedWithController: ${e.message}")
            }
        }

        if (this.activeMediaController == controllerToClean) {
            this.activeMediaController = null
            this.mediaControllerCallback = null
            Log.d(TAG, "Set activeMediaController and mediaControllerCallback to null.")
        } else {
            Log.d(TAG, "cleanupMediaController: The controller being cleaned ($tokenAssociatedWithController) was not the current activeMediaController (${this.activeMediaController?.sessionToken}). Not nullifying service's main activeMediaController instance here.")
        }
        Log.d(TAG, "MediaController cleanup finished. Service's activeMediaController is now ${if (this.activeMediaController == null) "null" else "still set to ${this.activeMediaController?.sessionToken}"}.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        _onNotificationPosted(sbn, "SystemCallback: ${sbn.packageName}")
    }

    private fun _onNotificationPosted(sbn: StatusBarNotification, source: String) {
        Log.d(TAG, "_onNotificationPosted (source: $source, pkg: ${sbn.packageName})")
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val tokenFromSbn = extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)

        if (tokenFromSbn != null) {
            if (activeMediaController == null || tokenFromSbn != currentMediaSessionToken) {
                var newSbnPlaybackState: PlaybackState? = null
                try {
                    val tempController = MediaController(applicationContext, tokenFromSbn)
                    newSbnPlaybackState = tempController.playbackState
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating temp controller for $tokenFromSbn from ${sbn.packageName} to check state: ${e.message}")
                    if (this.currentPlaybackState?.state == PlaybackState.STATE_PLAYING) {
                        Log.w(TAG, "Could not get state for $tokenFromSbn, current session is playing. Not switching based on this SBN.")
                        return
                    }
                }

                val newSbnIsPlaying = newSbnPlaybackState?.state == PlaybackState.STATE_PLAYING
                val currentServiceIsPlaying = this.currentPlaybackState?.state == PlaybackState.STATE_PLAYING
                val shouldSwitch = newSbnIsPlaying || (!newSbnIsPlaying && !currentServiceIsPlaying)

                if (shouldSwitch) {
                    Log.i(TAG, "Switching session. Old: $currentMediaSessionToken (State: ${stateToString(this.currentPlaybackState)}), New SBN Token: $tokenFromSbn (Pkg: ${sbn.packageName}, SBN State: ${stateToString(newSbnPlaybackState)})")
                    setupMediaController(tokenFromSbn)
                } else {
                    Log.d(TAG, "Not switching. New SBN token $tokenFromSbn (Pkg: ${sbn.packageName}, SBN State: ${stateToString(newSbnPlaybackState)}), Current service token $currentMediaSessionToken (Service State: ${stateToString(this.currentPlaybackState)}).")
                }

            } else {
                Log.d(TAG, "Notification update for active session $currentMediaSessionToken (pkg: ${sbn.packageName}). Current MC State: ${stateToString(activeMediaController?.playbackState)}")

                val mcMetadata = activeMediaController?.metadata
                val mcPlaybackState = activeMediaController?.playbackState

                val notifTitle = extras.getString(Notification.EXTRA_TITLE)
                if (mcMetadata != null && notifTitle != null && notifTitle != mcMetadata.getString(MediaMetadata.METADATA_KEY_TITLE)) {
                    Log.w(TAG, "Notification title ('$notifTitle') for $currentMediaSessionToken differs from MC title ('${mcMetadata.getString(MediaMetadata.METADATA_KEY_TITLE)}'). Re-processing MC metadata.")
                    processMediaMetadata(mcMetadata, mcPlaybackState, "NotificationUpdateTrigger (Title Discrepancy for $currentMediaSessionToken)")
                } else {
                    Log.d(TAG, "Notification for active session $currentMediaSessionToken. Triggering playback state update from MC if needed.")
                    mcPlaybackState?.let { mediaControllerCallback?.onPlaybackStateChanged(it) }
                }
            }
        } else {
            if (activeMediaController != null && sbn.packageName == activeMediaController!!.packageName) {
                Log.d(TAG, "Notification from active player ${sbn.packageName} (${activeMediaController?.sessionToken}) but without a media token. Current song: $lastDetectedSongTitle")
            } else {
                 Log.v(TAG, "Notification without media token from ${sbn.packageName}. Ignoring for media purposes.")
            }
        }
    }


    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        val removedToken = sbn.notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        val removedTitle = sbn.notification.extras.getString(Notification.EXTRA_TITLE)

        Log.d(TAG, "Notification removed: pkg=${sbn.packageName}, title='${removedTitle}', token=$removedToken. Current service token=$currentMediaSessionToken")

        if (removedToken != null && removedToken == currentMediaSessionToken) {
            Log.d(TAG, "Notification for active MediaSession ($currentMediaSessionToken, pkg: ${sbn.packageName}) removed.")
            if (currentPlaybackState?.state != PlaybackState.STATE_PLAYING &&
                currentPlaybackState?.state != PlaybackState.STATE_BUFFERING) {
                Log.d(TAG, "Notification for $currentMediaSessionToken removed and playback not active/buffering. MediaSession might be ending soon or already destroyed.")
            }
        }
    }

    private fun clearSongContextAndHideLyrics() {
        val tokenThatWasActive = currentMediaSessionToken
        Log.i(TAG, "clearSongContextAndHideLyrics: Starting. Was for token: $tokenThatWasActive, Title: $lastDetectedSongTitle")

        lastDetectedSongTitle = null
        lastDetectedSongArtist = null
        currentSongDurationMs = 0L
        currentLyricsData = null

        if (activeMediaController != null && activeMediaController?.sessionToken == tokenThatWasActive) {
            Log.d(TAG, "clearSongContextAndHideLyrics: Cleaning up activeMediaController for token $tokenThatWasActive.")
            cleanupMediaController()
        } else if (activeMediaController != null && tokenThatWasActive == null) {
            Log.d(TAG, "clearSongContextAndHideLyrics: No specific prior token, but an activeMediaController exists. Cleaning it up.")
            cleanupMediaController()
        }


        this.currentMediaSessionToken = null
        this.currentPlaybackState = null

        updatePersistentNotification("Waiting for song...")
        hideLyricsWindow()
        Log.i(TAG, "Song context cleared. currentMediaSessionToken is now null. Last active token was $tokenThatWasActive.")
    }


    private fun fetchAndDisplayLyrics(title: String, artist: String, durationFromMediaMs: Long) {
        lyricsHighlightingJob?.cancel()
        val fetchForToken = currentMediaSessionToken
        val fetchForTitle = title
        val fetchForArtist = artist

        serviceScope.launch(Dispatchers.IO) {
            if (fetchForToken != currentMediaSessionToken || fetchForTitle != lastDetectedSongTitle || fetchForArtist != (lastDetectedSongArtist ?: "")) {
                Log.d(TAG, "fetchAndDisplayLyrics: Context changed before fetch could start for '$fetchForTitle'. Current is '$lastDetectedSongTitle' for token $currentMediaSessionToken. Aborting.")
                return@launch
            }
            Log.d(TAG, "Fetching lyrics for '$fetchForTitle' by '$fetchForArtist' (Token: $fetchForToken, Media Duration: ${durationFromMediaMs}ms)")

            var fetchedLyricsDataLocal: LyricsData? = null
            try {
                var potentialMismatch = false
                val initialQuery = if (fetchForArtist.isNotBlank()) "$fetchForTitle $fetchForArtist" else fetchForTitle
                var results = searchLyrics(initialQuery)

                if (results.isNullOrEmpty() && fetchForArtist.length > 10) {
                    Log.d(TAG, "Initial lyrics search failed for '$fetchForTitle'. Retrying with artist truncated.")
                    results = searchLyrics("$fetchForTitle ${fetchForArtist.take(10)}")
                    if (!results.isNullOrEmpty()) potentialMismatch = true
                }
                if (results.isNullOrEmpty() && fetchForTitle.isNotBlank()) {
                    Log.d(TAG, "Retry 1 failed for '$fetchForTitle'. Retrying with just track name.")
                    results = searchLyrics(fetchForTitle)
                    if (!results.isNullOrEmpty()) potentialMismatch = true
                }

                val lyricResult = results?.firstOrNull { !it.instrumental && (!it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank()) }
                    ?: results?.firstOrNull { !it.instrumental && !it.plainLyrics.isNullOrBlank() }
                    ?: results?.firstOrNull { !it.syncedLyrics.isNullOrBlank() }
                    ?: results?.firstOrNull()

                if (lyricResult != null) {
                    val lyricsApiDurationMs = (lyricResult.duration * 1000).toLong()
                    val finalDurationMs = if (durationFromMediaMs > 0) durationFromMediaMs else lyricsApiDurationMs
                    val plainLyricsText = lyricResult.plainLyrics ?: ""
                    val actualContentData: LyricsData = if (lyricResult.instrumental) {
                        LyricsData.Info(fetchForTitle, fetchForArtist.ifEmpty { null }, "This is an instrumental song... 🎵", finalDurationMs)
                    } else {
                        var parsedSyncedData: LyricsData.Synced? = null
                        if (!lyricResult.syncedLyrics.isNullOrBlank()) {
                            try {
                                val timedLines = parseSyncedLyrics(lyricResult.syncedLyrics)
                                if (timedLines.isNotEmpty()) {
                                    Log.d(TAG, "Successfully parsed ${timedLines.size} synced lines for '$fetchForTitle'.")
                                    parsedSyncedData = LyricsData.Synced(fetchForTitle, fetchForArtist.ifEmpty { null }, timedLines, finalDurationMs)
                                } else {
                                    Log.w(TAG, "parseSyncedLyrics returned empty list for '$fetchForTitle' even though syncedLyrics was not blank.")
                                }
                            } catch (e: Exception) { Log.w(TAG, "Error parsing synced lyrics for '$fetchForTitle'", e) }
                        }
                        parsedSyncedData ?: if (plainLyricsText.isNotBlank()) {
                            LyricsData.Plain(fetchForTitle, fetchForArtist.ifEmpty { null }, plainLyricsText, finalDurationMs)
                        } else {
                            LyricsData.Info(fetchForTitle, fetchForArtist.ifEmpty { null }, "Lyrics not found (empty content).", finalDurationMs)
                        }
                    }
                    fetchedLyricsDataLocal = if (potentialMismatch && actualContentData !is LyricsData.Info &&
                                             (lyricResult.trackName.lowercase().trim() != fetchForTitle.lowercase().trim() ||
                                              lyricResult.artistName.lowercase().trim() != fetchForArtist.lowercase().trim() ) ) {
                        Log.d(TAG, "Potential mismatch: API ('${lyricResult.trackName}/${lyricResult.artistName}') vs Query ('$fetchForTitle/$fetchForArtist')")
                        LyricsData.MismatchInfo(fetchForTitle, fetchForArtist.ifEmpty { null }, actualContentData)
                    } else {
                        actualContentData
                    }
                } else {
                    fetchedLyricsDataLocal = LyricsData.Info(fetchForTitle, fetchForArtist.ifEmpty { null }, "Lyrics not found.", durationFromMediaMs.takeIf { it > 0 } ?: 0L)
                }

                if (fetchForToken != currentMediaSessionToken || fetchForTitle != lastDetectedSongTitle || fetchForArtist != (lastDetectedSongArtist ?: "")) {
                    Log.d(TAG, "Context changed during/after lyrics fetch for '$fetchForTitle'. Current is '$lastDetectedSongTitle' for token $currentMediaSessionToken. Discarding fetched lyrics.")
                    return@launch
                }

                currentLyricsData = fetchedLyricsDataLocal

                if (currentLyricsData != null && currentSongDurationMs <= 0 && currentLyricsData!!.durationMs > 0) {
                    Log.d(TAG, "Updating currentSongDurationMs from API lyrics data to ${currentLyricsData!!.durationMs}ms")
                    currentSongDurationMs = currentLyricsData!!.durationMs
                    currentLyricsData = when (val cd = currentLyricsData!!) {
                        is LyricsData.Synced -> cd.copy(durationMs = currentSongDurationMs)
                        is LyricsData.Plain -> cd.copy(durationMs = currentSongDurationMs)
                        is LyricsData.Info -> cd.copy(durationMs = currentSongDurationMs)
                        is LyricsData.MismatchInfo -> {
                            val original = cd.originalLyricsData
                            val updatedOriginal = when (original) {
                                is LyricsData.Synced -> original.copy(durationMs = currentSongDurationMs)
                                is LyricsData.Plain -> original.copy(durationMs = currentSongDurationMs)
                                is LyricsData.Info -> original.copy(durationMs = currentSongDurationMs)
                                is LyricsData.MismatchInfo -> original
                                null -> null
                            }
                            cd.copy(originalLyricsData = updatedOriginal)
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Exception fetching/processing lyrics for '$fetchForTitle'", e)
                if (fetchForToken == currentMediaSessionToken && fetchForTitle == lastDetectedSongTitle) {
                    currentLyricsData = LyricsData.Info(fetchForTitle, fetchForArtist.ifEmpty { null }, "Could not load lyrics.", durationFromMediaMs.takeIf { it > 0 } ?: 0L)
                }
            }

            withContext(Dispatchers.Main) {
                if (fetchForToken == currentMediaSessionToken && fetchForTitle == lastDetectedSongTitle && fetchForArtist == (lastDetectedSongArtist ?: "")) {
                    currentLyricsData?.let { showLyricsWindow(it) }
                    if (currentLyricsData is LyricsData.Synced &&
                        activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING &&
                        activeMediaController?.sessionToken == currentMediaSessionToken) {
                        startOrUpdateLyricsHighlighting()
                    }
                } else {
                    Log.d(TAG, "Song changed before lyrics for '$fetchForTitle' (token $fetchForToken) could be displayed on UI. Current: '$lastDetectedSongTitle' (token $currentMediaSessionToken).")
                }
            }
        }
    }

    private suspend fun searchLyrics(query: String): List<LyricResult>? {
        if (query.isBlank()) return null
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val url = "$LYRIC_API_BASE_URL?q=$encodedQuery"
        Log.d(TAG, "Fetching lyrics from: $url (Query: '$query')")
        return try {
            httpClient.get(url).body<List<LyricResult>>().also { Log.d(TAG, "Search for '$query' returned ${it.size} results.") }
        } catch (e: Exception) {
            Log.e(TAG, "Lyrics search exception for '$query': ${e.message}")
            null
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun showLyricsWindow(data: LyricsData) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { showLyricsWindow(data) }
            return
        }

        val dataTitle = when(data) {
            is LyricsData.Plain -> data.title
            is LyricsData.Synced -> data.title
            is LyricsData.Info -> data.title
            is LyricsData.MismatchInfo -> data.title
        }
        val dataArtist = when(data) {
            is LyricsData.Plain -> data.artist
            is LyricsData.Synced -> data.artist
            is LyricsData.Info -> data.artist
            is LyricsData.MismatchInfo -> data.artist
        }

        if (data !is LyricsData.Info && (dataTitle != lastDetectedSongTitle || dataArtist != lastDetectedSongArtist)) {
             Log.w(TAG, "showLyricsWindow (MainThread): Called for '$dataTitle'/'$dataArtist', but current song is '$lastDetectedSongTitle'/'$lastDetectedSongArtist'. Aborting show.")
             if (lyricsView != null && lyricsView?.isAttachedToWindow == true) {
                 val actualCurrentData = currentLyricsData
                 if (actualCurrentData == null || (actualCurrentData is LyricsData.Info && actualCurrentData.message.contains("Waiting for song", ignoreCase = true))) {
                    showLyricsWindow(LyricsData.Info(null, null, "Waiting for song...", 0L))
                 } else if (actualCurrentData != null) {
                    showLyricsWindow(actualCurrentData)
                 }
             }
             return
        }

        if (data is LyricsData.Info && data.message.contains("Waiting for song",ignoreCase = true) && lastDetectedSongTitle != null && currentLyricsData != data) {
            currentLyricsData?.let {
                Log.d(TAG, "showLyricsWindow: Was 'Waiting for song', but song '$lastDetectedSongTitle' is active. Re-showing with current data: $it")
                showLyricsWindow(it)
                return
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot show lyrics window: Overlay permission not granted.")
            updatePersistentNotification("Tap to grant Overlay Permission")
            val permIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                 PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val contentPendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, permIntent, pendingIntentFlags)

            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Permission Required")
                .setContentText("Overlay permission needed for lyrics display.")
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentIntent(contentPendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            notificationManager.notify(NOTIFICATION_ID + 1, notification)
            return
        }

        if (lyricsView == null) {
            Log.d(TAG, "Inflating lyrics_overlay. Current song title for display: ${
                when (data) {
                    is LyricsData.Synced -> data.title
                    is LyricsData.Plain -> data.title
                    is LyricsData.Info -> data.title ?: data.message
                    is LyricsData.MismatchInfo -> data.title ?: "Potential Mismatch"
                }
            }")
            this.windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            try {
                lyricsView = inflater.inflate(R.layout.lyrics_overlay, null)
            } catch (e: Exception) {
                 Log.e(TAG, "Error inflating R.layout.lyrics_overlay: ${e.message}", e)
                 return
            }

            songInfoTextView = lyricsView?.findViewById(R.id.songInfoTextView)
            lyricsRecyclerView = lyricsView?.findViewById(R.id.lyricsRecyclerView)
            expandCollapseButton = lyricsView?.findViewById(R.id.expandCollapseButton)
            val closeButton = lyricsView?.findViewById<ImageButton>(R.id.closeButton)

            lyricsAdapter = LyricsAdapter(this, emptyList())
            linearLayoutManager = LinearLayoutManager(this) // Ensure a new instance

            lyricsRecyclerView?.layoutManager = linearLayoutManager
            lyricsRecyclerView?.adapter = lyricsAdapter

            closeButton?.setOnClickListener { hideLyricsWindow() }
            expandCollapseButton?.setOnClickListener { toggleLyricsExpansion() }
            lyricsView?.setOnTouchListener(ViewMover())

            val overlayFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            this.params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                x = 0
                y = 100
            }

            try {
                if (lyricsView?.isAttachedToWindow == false) {
                    this.windowManager?.addView(lyricsView, this.params)
                    Log.d(TAG, "Lyrics window added to WindowManager.")
                } else {
                    Log.w(TAG, "LyricsView was unexpectedly already attached or became null before addView.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding lyrics view to WindowManager: ${e.message}", e)
                this.lyricsView = null
                this.songInfoTextView = null
                this.lyricsRecyclerView = null
                this.expandCollapseButton = null
                this.lyricsAdapter = null
                return
            }
        } else {
            Log.d(TAG, "Lyrics window already exists. Updating content. Attached: ${lyricsView?.isAttachedToWindow}")
             if (this.windowManager == null || this.params == null) {
                Log.w(TAG, "WindowManager or LayoutParams became null while lyricsView exists. Re-initializing.")
                hideLyricsWindow()
                showLyricsWindow(data)
                return
            }
        }


        val songDisplayTitle = when (data) {
            is LyricsData.Synced -> data.artist?.takeIf { it.isNotBlank() }?.let { "${data.title} - $it" } ?: data.title
            is LyricsData.Plain -> data.artist?.takeIf { it.isNotBlank() }?.let { "${data.title} - $it" } ?: data.title
            is LyricsData.Info -> data.title?.takeIf { it.isNotBlank() }?.let { base -> data.artist?.takeIf { art -> art.isNotBlank() }?.let { "$base - $it" } ?: base } ?: data.message
            is LyricsData.MismatchInfo -> data.title?.takeIf { it.isNotBlank() }?.let { t -> data.artist?.takeIf { a -> a.isNotBlank() }?.let { a -> "Potential Mismatch: $t - $a" } ?: "Potential Mismatch: $t"} ?: "Potential song mismatch"
        }
        songInfoTextView?.text = songDisplayTitle

        // Default colors, will be used if Palette fails or no album art
        var finalOverlayBackgroundColor = Color.parseColor("#DD212121") // Dark semi-transparent
        var finalTitleAndIconColor = Color.parseColor("#FFE0E0E0")    // Light Gray
        val finalLyricsTextColor = Color.WHITE                               // White for lyrics text
        var finalLyricsHighlightBgColor = Color.argb(70, 200, 200, 200) // Light gray, semi-transparent highlight

        val currentActiveMc = activeMediaController
        if (currentActiveMc != null && currentActiveMc.sessionToken == currentMediaSessionToken) {
            currentActiveMc.metadata?.let { metadata ->
                val albumArtBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

                if (albumArtBitmap != null) {
                    Palette.from(albumArtBitmap).generate { palette -> // Async callback
                        palette?.let { p ->
                            var selectedBackgroundColorRgb: Int? = null

                            // 1. Determine Overlay Background Color - Prioritize dominant
                            selectedBackgroundColorRgb = p.dominantSwatch?.rgb

                            // Fallback if dominantSwatch is not available or not suitable (e.g. too light/dark for a BG)
                            // For now, just fallback if null. Could add luminance checks here if needed.
                            if (selectedBackgroundColorRgb == null) {
                                val fallbackBgSwatch = p.darkVibrantSwatch ?: p.vibrantSwatch ?: p.darkMutedSwatch ?: p.mutedSwatch
                                selectedBackgroundColorRgb = fallbackBgSwatch?.rgb
                            }

                            selectedBackgroundColorRgb?.let { color ->
                                finalOverlayBackgroundColor = ColorUtils.setAlphaComponent(color, 221) // ~87% opacity
                            }
                            // If selectedBackgroundColorRgb is still null, finalOverlayBackgroundColor retains its default

                            // 2. Determine if Background is Light or Dark
                            val isBackgroundLight = ColorUtils.calculateLuminance(finalOverlayBackgroundColor) > 0.5

                            // 3. Determine Title and Icon Color based on Background Luminance
                            if (isBackgroundLight) {
                                // Background is light, so we want a dark foreground (title/icon)
                                finalTitleAndIconColor = p.darkVibrantSwatch?.rgb
                                    ?: p.darkMutedSwatch?.rgb
                                    ?: p.mutedSwatch?.rgb // Another potentially dark swatch
                                    ?: Color.BLACK      // Absolute fallback
                            } else {
                                // Background is dark, so we want a light foreground (title/icon)
                                finalTitleAndIconColor = p.lightVibrantSwatch?.rgb
                                    ?: p.lightMutedSwatch?.rgb
                                    ?: p.vibrantSwatch?.rgb // Another potentially light swatch
                                    ?: Color.WHITE        // Absolute fallback
                            }

                            // 4. Determine Lyrics Highlight Background Color
                            val highlightSwatch = p.lightMutedSwatch ?: p.lightVibrantSwatch ?: p.vibrantSwatch ?: p.mutedSwatch
                            highlightSwatch?.rgb?.let { color ->
                                finalLyricsHighlightBgColor = ColorUtils.setAlphaComponent(color, 70) // ~27% opacity
                            }

                            Log.d(TAG, "Palette applied. BG Dominant used: ${p.dominantSwatch != null && selectedBackgroundColorRgb == p.dominantSwatch?.rgb}. BG Light: $isBackgroundLight. OverlayBG: #${Integer.toHexString(finalOverlayBackgroundColor)}, Title/Icon: #${Integer.toHexString(finalTitleAndIconColor)}, LyricHighlightBG: #${Integer.toHexString(finalLyricsHighlightBgColor)}")
                        } ?: run {
                            Log.d(TAG, "Palette object was null after generation. Using pre-set defaults.")
                        }
                        applyThemeToOverlayElements(finalOverlayBackgroundColor, finalTitleAndIconColor, finalLyricsTextColor, finalLyricsHighlightBgColor)
                    }
                } else {
                    Log.d(TAG, "No album art bitmap. Applying default theme.")
                    applyThemeToOverlayElements(finalOverlayBackgroundColor, finalTitleAndIconColor, finalLyricsTextColor, finalLyricsHighlightBgColor)
                }
            } ?: run {
                Log.d(TAG, "No MediaController metadata. Applying default theme.")
                applyThemeToOverlayElements(finalOverlayBackgroundColor, finalTitleAndIconColor, finalLyricsTextColor, finalLyricsHighlightBgColor)
            }
        } else {
            Log.d(TAG, "No active MediaController or token mismatch for theme. Applying default theme.")
            applyThemeToOverlayElements(finalOverlayBackgroundColor, finalTitleAndIconColor, finalLyricsTextColor, finalLyricsHighlightBgColor)
        }


        lyricsHighlightingJob?.cancel()

        val dataToDisplayForAdapter: LyricsData
        val mismatchMessage: String?

        if (data is LyricsData.MismatchInfo) {
            mismatchMessage = "Potential song mismatch."
            val effectiveDuration = data.originalLyricsData?.durationMs ?: currentSongDurationMs.takeIf { it > 0 } ?: 0L
            var tempActualData = data.originalLyricsData ?: LyricsData.Info(
                data.title, data.artist, "Lyrics details unavailable (mismatch).", effectiveDuration
            )
            if (tempActualData is LyricsData.Info && tempActualData.message.contains("Loading", ignoreCase = true)) {
                tempActualData = LyricsData.Info(data.title, data.artist, "Fetched data seems to be a mismatch.", effectiveDuration)
            }
            dataToDisplayForAdapter = tempActualData
        } else {
            mismatchMessage = null
            dataToDisplayForAdapter = data
            if (dataToDisplayForAdapter is LyricsData.Info) {
                if (dataToDisplayForAdapter.durationMs > 0 && currentSongDurationMs <= 0) {
                    currentSongDurationMs = dataToDisplayForAdapter.durationMs
                }
            }
        }

        val finalMessageForInfo = if (mismatchMessage != null && dataToDisplayForAdapter is LyricsData.Info) {
            "$mismatchMessage\n${dataToDisplayForAdapter.message}"
        } else if (dataToDisplayForAdapter is LyricsData.Info) {
            dataToDisplayForAdapter.message
        } else ""


        when(dataToDisplayForAdapter) {
            is LyricsData.Synced -> {
                Log.d(TAG, "Displaying Synced lyrics: ${dataToDisplayForAdapter.lines.size} lines.")
                lyricsAdapter?.updateLyrics(dataToDisplayForAdapter.lines, true)
            }
            is LyricsData.Plain -> {
                Log.d(TAG, "Displaying Plain lyrics.")
                lyricsAdapter?.updateLyrics(dataToDisplayForAdapter.lyrics.lines().mapIndexed { i, t -> TimedLyricLine(i.toLong(), t) }, false)
            }
            is LyricsData.Info -> {
                Log.d(TAG, "Displaying Info: $finalMessageForInfo")
                lyricsAdapter?.updateLyrics(listOf(TimedLyricLine(0, finalMessageForInfo)), false)
            }
            else -> {
                Log.e(TAG, "Internal error: dataToDisplayForAdapter was unexpected type: ${dataToDisplayForAdapter.javaClass.simpleName}. Displaying error.")
                lyricsAdapter?.updateLyrics(listOf(TimedLyricLine(0,"Error displaying lyrics.")), false)
            }
        }
        lyricsRecyclerView?.isVisible = true

        applyLyricsExpansionState()
        if (dataToDisplayForAdapter is LyricsData.Synced &&
            activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING &&
            activeMediaController?.sessionToken == currentMediaSessionToken) {
            startOrUpdateLyricsHighlighting()
        }
    }

    private fun applyThemeToOverlayElements(
        overlayBgColor: Int,
        titleIconColor: Int,
        lyricTextColor: Int,
        lyricHighlightBgColor: Int
    ) {
        lyricsView?.setBackgroundColor(overlayBgColor)
        songInfoTextView?.setTextColor(titleIconColor)

        val isTitleIconLight = ColorUtils.calculateLuminance(titleIconColor) > 0.4
        val shadowColor = ColorUtils.setAlphaComponent(if (isTitleIconLight) Color.BLACK else Color.WHITE, 150)
        songInfoTextView?.setShadowLayer(1.8f, 2f, 2f, shadowColor)

        expandCollapseButton?.setColorFilter(titleIconColor, PorterDuff.Mode.SRC_IN)
        lyricsView?.findViewById<ImageButton>(R.id.closeButton)?.setColorFilter(titleIconColor, PorterDuff.Mode.SRC_IN)

        lyricsAdapter?.updateThemeColors(
            normalLineTextColor = lyricTextColor,
            highlightedLineTextColor = lyricTextColor,
            highlightedLineBackgroundColor = lyricHighlightBgColor
        )
    }

    private fun toggleLyricsExpansion() {
        isLyricsExpanded = !isLyricsExpanded
        applyLyricsExpansionState()
    }

    private fun applyLyricsExpansionState() {
        lyricsRecyclerView?.let { rv ->
            val targetHeightInPx = if (isLyricsExpanded) EXPANDED_LYRICS_MAX_HEIGHT_DP.dpToPx() else COLLAPSED_LYRICS_MAX_HEIGHT_DP.dpToPx()
            val currentParams = rv.layoutParams
            if (currentParams.height != targetHeightInPx) {
                currentParams.height = targetHeightInPx
                rv.layoutParams = currentParams
            }
        }
        expandCollapseButton?.setImageResource(
            if (isLyricsExpanded) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float
        )
    }

   private fun startOrUpdateLyricsHighlighting() {
        lyricsHighlightingJob?.cancel()
        val dataForHighlighting = this.currentLyricsData
        val controllerForHighlighting = this.activeMediaController
        val tokenForHighlighting = this.currentMediaSessionToken

        if (controllerForHighlighting == null || controllerForHighlighting.sessionToken != tokenForHighlighting ||
            dataForHighlighting !is LyricsData.Synced || dataForHighlighting.lines.isEmpty()) {
            Log.d(TAG, "Not starting highlighter: Conditions not met. MC Valid: ${controllerForHighlighting != null}, Token Match: ${controllerForHighlighting?.sessionToken == tokenForHighlighting}, Data is Synced: ${dataForHighlighting is LyricsData.Synced}, Lines not empty: ${(dataForHighlighting as? LyricsData.Synced)?.lines?.isNotEmpty() ?: false}")
            return
        }
        if (dataForHighlighting.title != lastDetectedSongTitle || dataForHighlighting.artist != lastDetectedSongArtist) {
            Log.w(TAG, "Highlighting attempted for '${dataForHighlighting.title}/${dataForHighlighting.artist}' but current song context is '$lastDetectedSongTitle/$lastDetectedSongArtist'. Aborting.")
            return
        }

        val lines = dataForHighlighting.lines
        val songTotalDuration = if (dataForHighlighting.durationMs > 0) dataForHighlighting.durationMs else currentSongDurationMs

        lyricsHighlightingJob = serviceScope.launch(Dispatchers.Main) {
            Log.d(TAG, "LyricsHighlightingJob: Started for '${dataForHighlighting.title}' (Token: $tokenForHighlighting). Lines: ${lines.size}, Duration: $songTotalDuration ms")
            var lastHighlightedIndex = -1

            while (isActive) {
                val currentMC = activeMediaController
                if (currentMC == null || currentMC.sessionToken != tokenForHighlighting) {
                    Log.w(TAG, "Highlighting ($tokenForHighlighting): MediaController changed or became null during highlighting. Stopping job.")
                    break
                }
                val state = currentMC.playbackState
                if (state == null || state.state != PlaybackState.STATE_PLAYING) {
                    Log.d(TAG, "Highlighting ($tokenForHighlighting): Playback not active (State: ${stateToString(state)}). Pausing loop, clearing highlight.")
                    if (lastHighlightedIndex != -1) {
                        lyricsAdapter?.setHighlight(-1)
                        lastHighlightedIndex = -1
                    }
                    delay(HIGHLIGHT_UPDATE_INTERVAL_MS * 2)
                    continue
                }

                var currentPositionMs = state.position
                val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1.0f
                if (state.lastPositionUpdateTime > 0) {
                    currentPositionMs += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * speed).toLong()
                }

                var currentLineIndex = -1
                for (i in lines.indices.reversed()) { if (lines[i].timestamp <= currentPositionMs) { currentLineIndex = i; break } }

                if (currentLineIndex != lastHighlightedIndex) {
                    Log.v(TAG, "Highlighting ($tokenForHighlighting): Pos ${currentPositionMs}ms. Line $currentLineIndex: '${lines.getOrNull(currentLineIndex)?.text?.take(30)}...'")
                    lyricsAdapter?.setHighlight(currentLineIndex)
                    lastHighlightedIndex = currentLineIndex

                    if (currentLineIndex != -1 && ::linearLayoutManager.isInitialized && lyricsRecyclerView?.isAttachedToWindow == true) {
                        val firstVis = linearLayoutManager.findFirstVisibleItemPosition()
                        val lastVis = linearLayoutManager.findLastVisibleItemPosition()
                        if (firstVis != RecyclerView.NO_POSITION && lastVis != RecyclerView.NO_POSITION) {
                            val visCount = lastVis - firstVis + 1
                            if (currentLineIndex < firstVis || currentLineIndex >= lastVis - (visCount / 3).coerceAtLeast(1) || visCount < 4) {
                                lyricsRecyclerView?.smoothScrollToPosition(currentLineIndex.coerceAtLeast(0))
                            }
                        } else {
                            lyricsRecyclerView?.smoothScrollToPosition(currentLineIndex.coerceAtLeast(0))
                        }
                    }
                }
                if (songTotalDuration > 0 && currentPositionMs > songTotalDuration + 1000) {
                    Log.d(TAG, "Highlighting ($tokenForHighlighting): Song duration ($songTotalDuration ms) passed by ${currentPositionMs - songTotalDuration}ms. Stopping job.")
                    if (lastHighlightedIndex != -1) lyricsAdapter?.setHighlight(-1)
                    break
                }
                delay(HIGHLIGHT_UPDATE_INTERVAL_MS)
            }
            Log.d(TAG,"LyricsHighlightingJob: Ended/cancelled for '${dataForHighlighting.title}' (Token: $tokenForHighlighting).")
            if (lyricsAdapter?.getCurrentHighlightedPosition() != -1 && !isActive) {
                 lyricsAdapter?.setHighlight(-1)
            }
        }
    }

    private fun hideLyricsWindow() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { hideLyricsWindow() }
            return
        }
        Log.d(TAG, "hideLyricsWindow() called. LyricsView Null: ${lyricsView == null}, Attached: ${lyricsView?.isAttachedToWindow}")
        lyricsHighlightingJob?.cancel(); lyricsHighlightingJob = null

        val wm = this.windowManager
        val lv = this.lyricsView

        if (lv != null && wm != null) {
            try {
                if (lv.isAttachedToWindow) {
                    wm.removeView(lv)
                    Log.d(TAG, "Lyrics window removed from WindowManager.")
                } else {
                    Log.d(TAG, "Lyrics window was not attached or already removed prior to hide call.")
                }
            }
            catch (e: Exception) { Log.e(TAG, "Error hiding lyrics window", e) }
            finally {
                this.lyricsView = null
                this.songInfoTextView = null
                this.lyricsRecyclerView = null
                this.expandCollapseButton = null
                this.lyricsAdapter = null
                Log.d(TAG, "Lyrics UI components nulled.")
            }
        } else {
            Log.d(TAG, "hideLyricsWindow: lyricsView or windowManager was already null.")
        }
    }

    private fun parseSyncedLyrics(syncedLyricsText: String?): List<TimedLyricLine> {
        if (syncedLyricsText.isNullOrBlank()) return emptyList()
        val lines = mutableListOf<TimedLyricLine>()

        syncedLyricsText.lines().forEach { lineContent ->
            var currentTextSegment = lineContent.trim()
            val tempLinesForThisPhysicalLine = mutableListOf<TimedLyricLine>()

            while (currentTextSegment.startsWith("[")) {
                val matcher = LRC_LINE_PATTERN.matcher(currentTextSegment)
                if (matcher.find() && matcher.start() == 1) { // Ensure '[' is the first char of the segment we're checking
                    try {
                        val minutes = matcher.group(1)!!.toInt()
                        val seconds = matcher.group(2)!!.toInt()
                        val millisStr = matcher.group(4)!!
                        val textFollowingTag = matcher.group(5)!!

                        val milliseconds = when {
                            millisStr.length == 2 -> millisStr.toInt() * 10 // Handles 2-digit centiseconds
                            else -> millisStr.toInt() // Handles 3-digit milliseconds
                        }
                        val timestamp = TimeUnit.MINUTES.toMillis(minutes.toLong()) +
                                        TimeUnit.SECONDS.toMillis(seconds.toLong()) +
                                        milliseconds.toLong()

                        tempLinesForThisPhysicalLine.add(TimedLyricLine(timestamp, "")) // Placeholder text

                        currentTextSegment = textFollowingTag.trimStart() // Prepare for next tag or actual text
                    } catch (e: NumberFormatException) {
                        Log.w(TAG, "LRC timestamp number format error for part of line: '$lineContent'. Tag content: '${matcher.group(0)}'", e)
                        currentTextSegment = "" // Stop processing this line if a tag is malformed
                        break 
                    } catch (e: Exception) {
                        Log.w(TAG, "Generic LRC timestamp parse error for part of line: '$lineContent'. Matcher group 0: '${matcher.group(0)}'", e)
                        currentTextSegment = "" // Stop processing this line
                        break
                    }
                } else {
                    // No more valid tags at the beginning of currentTextSegment
                    break
                }
            }

            // Assign the remaining text (or "..." if blank) to all timestamps found on this physical line
            if (tempLinesForThisPhysicalLine.isNotEmpty()) {
                val finalLyricTextForTags = currentTextSegment.trim()
                val displayText = if (finalLyricTextForTags.isBlank()) "🎶 ... 🎶" else finalLyricTextForTags
                tempLinesForThisPhysicalLine.forEach { timedLinePlaceholder ->
                    lines.add(timedLinePlaceholder.copy(text = displayText))
                }
            } else if (currentTextSegment.isNotBlank() && !lineContent.trim().startsWith("[")) {
                // This means the original line didn't start with a tag, or all tags were malformed
                // Log.v(TAG, "Line without parseable LRC tags or only malformed tags: '$lineContent'");
            }
        }

        // Fallback for non-LRC or improperly formatted synced lyrics
        if (lines.isEmpty() && syncedLyricsText.isNotBlank()) {
            if (!syncedLyricsText.trimStart().startsWith("[")) {
                 Log.w(TAG, "No LRC tags found and content does not start with '['. Treating as plain text. Preview: ${syncedLyricsText.take(100)}")
                 // Attempt to create some timed lines from plain text, just in case
                 return syncedLyricsText.lines().mapIndexedNotNull { index, textLine ->
                    val trimmedText = textLine.trim()
                    if (trimmedText.isNotBlank()) TimedLyricLine(index * 2000L, trimmedText) else null // Arbitrary 2s interval
                }
            } else {
                Log.w(TAG, "Synced lyrics text was present (and likely LRC-formatted) but no valid timed lines parsed. Original text preview: ${syncedLyricsText.take(150).replace("\n", " \\n ")}")
            }
        }
        return lines.sortedBy { it.timestamp }
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.lyric_service_channel_name)
            val descriptionText = getString(R.string.lyric_service_channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setSound(null, null); enableLights(false); enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createPersistentNotification(text: String): Notification {
        val showLyricsIntent = Intent(this, LyricService::class.java).apply { action = ACTION_SHOW_LYRICS }
        val hideLyricsIntent = Intent(this, LyricService::class.java).apply { action = ACTION_HIDE_LYRICS }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val showAction = NotificationCompat.Action.Builder(R.drawable.ic_visibility, "Show", PendingIntent.getService(this, 0, showLyricsIntent, pendingIntentFlags)).build()
        val hideAction = NotificationCompat.Action.Builder(R.drawable.ic_visibility_off, "Hide", PendingIntent.getService(this, 1, hideLyricsIntent, pendingIntentFlags)).build()

        val contentIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pContentIntent = PendingIntent.getActivity(this, 2, contentIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setOngoing(true)
            .setContentIntent(pContentIntent)
            .addAction(showAction)
            .addAction(hideAction)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updatePersistentNotification(text: String) {
        Log.d(TAG, "Updating persistent notification: $text")
        notificationManager.notify(NOTIFICATION_ID, createPersistentNotification(text))
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy(). Instance: ${this.hashCode()}. Cleaning up for $currentMediaSessionToken.")
        serviceJob.cancel()
        hideLyricsWindow()
        cleanupMediaController()
        currentMediaSessionToken = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        Log.d(TAG, "Service fully destroyed. Instance: ${this.hashCode()}")
        super.onDestroy()
    }

    private inner class ViewMover : View.OnTouchListener {
        private var initialX: Int = 0; private var initialY: Int = 0
        private var initialTouchX: Float = 0f; private var initialTouchY: Float = 0f
        private val touchSlop by lazy { android.view.ViewConfiguration.get(applicationContext).scaledTouchSlop }
        private var isDragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val currentParams = this@LyricService.params
            val currentWindowManager = this@LyricService.windowManager
            val currentLyricsView = this@LyricService.lyricsView

            if (currentParams == null || currentWindowManager == null || currentLyricsView?.isAttachedToWindow == false) return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = currentParams.x; initialY = currentParams.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    isDragging = false; return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX; val dy = event.rawY - initialTouchY
                    if (!isDragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        isDragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (isDragging) {
                        currentParams.x = initialX + dx.toInt(); currentParams.y = initialY + dy.toInt()
                        try { currentWindowManager.updateViewLayout(currentLyricsView, currentParams) }
                        catch (e: Exception) { Log.e(TAG, "Error updating view layout move: ${e.message}")}
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        isDragging = false
                        return true // Consume the event if it was a drag
                    }
                    return false // Don't consume if it was a click (allow onClickListeners to fire)
                }
                MotionEvent.ACTION_CANCEL -> {
                     v.parent?.requestDisallowInterceptTouchEvent(false)
                     isDragging = false
                     return false
                }
            }
            return false // Default case, don't consume
        }
    }

    private fun stateToString(state: PlaybackState?): String {
        if (state == null) return "null_state"
        return when (state.state) {
            PlaybackState.STATE_NONE -> "NONE"
            PlaybackState.STATE_STOPPED -> "STOPPED"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_FAST_FORWARDING -> "FF"
            PlaybackState.STATE_REWINDING -> "REW"
            PlaybackState.STATE_BUFFERING -> "BUFFERING"
            PlaybackState.STATE_ERROR -> "ERROR"
            PlaybackState.STATE_CONNECTING -> "CONNECTING"
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIP_PREV"
            PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIP_NEXT"
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIP_QUEUE"
            else -> "UNKNOWN (${state.state})"
        }
    }
}