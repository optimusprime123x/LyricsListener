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

    private var windowManager: WindowManager? = null
    private var lyricsView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var lyricsRecyclerView: RecyclerView? = null
    private var songInfoTextView: TextView? = null
    private var expandCollapseButton: ImageButton? = null
    private var lyricsAdapter: LyricsAdapter? = null
    private lateinit var linearLayoutManager: LinearLayoutManager

    private var lastDetectedSongTitle: String? = null
    private var lastDetectedSongArtist: String? = null
    private var currentLyricsData: LyricsData? = null
    private var currentSongDurationMs: Long = 0L

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lyricsHighlightingJob: Job? = null

    private var activeMediaController: MediaController? = null
    private var mediaControllerCallback: MediaController.Callback? = null
    private var currentMediaSessionToken: MediaSession.Token? = null
    private var currentPlaybackState: PlaybackState? = null

    private var isLyricsExpanded = false
    private val COLLAPSED_LYRICS_MAX_HEIGHT_DP = 100
    private lateinit var notificationManager: NotificationManager
    private val EXPANDED_LYRICS_MAX_HEIGHT_DP = 300

    @Serializable
    data class LyricResult(
        val id: Int,
        val trackName: String,
        val artistName: String,
        val albumName: String? = null,
        val duration: Double, // in seconds
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
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate() called.")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createPersistentNotification("Waiting for song..."))
        isLyricsExpanded = false
        Log.d(TAG, "Service started in foreground.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand with action: ${intent?.action}")
        when (intent?.action) {
            ACTION_SHOW_LYRICS -> {
                if (lyricsView == null) {
                    if (currentLyricsData != null) {
                        showLyricsWindow(currentLyricsData!!)
                    } else if (!lastDetectedSongTitle.isNullOrEmpty()) {
                        val tempInfoData = LyricsData.Info(lastDetectedSongTitle, lastDetectedSongArtist, "Loading lyrics...", currentSongDurationMs)
                        currentLyricsData = tempInfoData
                        showLyricsWindow(tempInfoData)
                        fetchAndDisplayLyrics(lastDetectedSongTitle!!, lastDetectedSongArtist ?: "", currentSongDurationMs)
                    } else {
                        updatePersistentNotification("Waiting for song...")
                    }
                } else {
                     Log.d(TAG, "Show action called, but lyrics window already visible.")
                }
            }
            ACTION_HIDE_LYRICS -> hideLyricsWindow()
        }
        return START_STICKY
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification Listener connected. Requesting active notifications.")
        Handler(Looper.getMainLooper()).postDelayed({
             try {
                val activeNotifications = this.activeNotifications ?: return@postDelayed
                Log.d(TAG, "Found ${activeNotifications.size} active notifications on connect.")

                val playingNotifications = activeNotifications.mapNotNull { sbn ->
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
                            Log.w(TAG, "Error checking playback state for token on connect: $token", e)
                            null
                        }
                    } else {
                        null
                    }
                }

                val mostRecentPlayingSbn = playingNotifications.maxByOrNull {
                    it.notification.`when`.takeIf { w -> w > 0 } ?: it.postTime
                }

                mostRecentPlayingSbn?.let { sbn ->
                    Log.d(TAG, "Processing most recent active media notification from ${sbn.packageName} (postTime: ${sbn.postTime}, when: ${sbn.notification.`when`}) on connect.")
                    onNotificationPosted(sbn)
                } ?: Log.d(TAG, "No actively playing media notifications found on connect, or couldn't determine state.")

            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException getting active notifications on connect: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Exception getting active notifications on connect: ${e.message}")
            }
        }, 1000)
    }


    private fun setupMediaController(token: MediaSession.Token) {
        if (currentMediaSessionToken == token && activeMediaController != null) {
            Log.d(TAG, "MediaController already set up for this token ($token). Forcing metadata/playback state update.")
             activeMediaController?.playbackState?.let { mediaControllerCallback?.onPlaybackStateChanged(it) }
             activeMediaController?.metadata?.let { mediaControllerCallback?.onMetadataChanged(it) }
            return
        }

        Log.i(TAG, "Setting up new MediaController for token: $token. Previous token was: $currentMediaSessionToken")
        cleanupMediaController()

        try {
            val newController = MediaController(applicationContext, token)
            activeMediaController = newController
            currentMediaSessionToken = token

            mediaControllerCallback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    super.onPlaybackStateChanged(state)
                    if (newController.sessionToken != currentMediaSessionToken) {
                        Log.w(TAG, "onPlaybackStateChanged for a stale session (${newController.sessionToken}). Current is $currentMediaSessionToken. Ignoring.")
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
                        Log.w(TAG, "onMetadataChanged for a stale session (${newController.sessionToken}). Current is $currentMediaSessionToken. Ignoring.")
                        return
                    }
                    Log.d(TAG, "onMetadataChanged (for $currentMediaSessionToken): Title: ${metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}")
                    processMediaMetadata(metadata, newController.playbackState, "Callback: MetadataChanged for $currentMediaSessionToken")
                }

                override fun onSessionDestroyed() {
                    super.onSessionDestroyed()
                    if (newController.sessionToken == currentMediaSessionToken) {
                        Log.i(TAG, "MediaSession Destroyed for active token $currentMediaSessionToken. Cleaning up.")
                        clearSongContextAndHideLyrics()
                    } else {
                        Log.w(TAG, "MediaSession Destroyed for a stale token ${newController.sessionToken}. Current is $currentMediaSessionToken. Ignoring full clear.")
                    }
                }
            }
            newController.registerCallback(mediaControllerCallback!!, Handler(Looper.getMainLooper()))
            Log.d(TAG, "MediaController registered for token: $token from package ${newController.packageName}")

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
            Log.w(TAG, "processMediaMetadata called with metadata for a non-active session. Current: $currentMediaSessionToken, Source MC Token: ${activeMediaController?.sessionToken}. Bailing.")
            return
        }

        val newTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val newArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val newDuration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        Log.d(TAG, "Processing Meta ($source for $currentMediaSessionToken): Title='$newTitle', Artist='$newArtist', Duration='${newDuration}ms', PlaybackState: ${stateToString(playbackState)}")

        if (newTitle.isNullOrBlank() && metadata != null) {
            Log.d(TAG, "Media metadata ($source for $currentMediaSessionToken) missing title. Not processing as new song.")
            if (lastDetectedSongTitle != null) {
                 Log.d(TAG, "Title became null/blank for $currentMediaSessionToken, previously was '$lastDetectedSongTitle'. Clearing context if no other valid session.")
                 if (activeMediaController?.sessionToken == currentMediaSessionToken || currentMediaSessionToken == null) {
                     clearSongContextAndHideLyrics()
                 }
            }
            return
        }

        if (newTitle != lastDetectedSongTitle || newArtist != lastDetectedSongArtist) {
            lastDetectedSongTitle = newTitle
            lastDetectedSongArtist = newArtist
            currentSongDurationMs = if (newDuration > 0) newDuration else (currentLyricsData?.durationMs ?: 0L)
            this.currentPlaybackState = playbackState

            val songInfoForDisplay = newArtist?.takeIf { it.isNotBlank() }?.let { "$newTitle by $it" } ?: newTitle ?: "Unknown Song"
            Log.i(TAG, "New song detected ($source for $currentMediaSessionToken): $songInfoForDisplay")

            updatePersistentNotification("Lyrics for: $songInfoForDisplay")

            if (newTitle != null) {
                val loadingData = LyricsData.Info(newTitle, newArtist, "Loading lyrics...", currentSongDurationMs)
                currentLyricsData = loadingData
                if (lyricsView != null) {
                    showLyricsWindow(loadingData) // Update existing view with "Loading..."
                }
                fetchAndDisplayLyrics(newTitle, newArtist ?: "", currentSongDurationMs)
            } else {
                 Log.w(TAG, "New song detected but title is null. Cannot fetch lyrics.")
                 currentLyricsData = LyricsData.Info(null, newArtist, "Song title not available.", currentSongDurationMs)
                 if (lyricsView != null) {
                    showLyricsWindow(currentLyricsData!!)
                 }
            }
        } else {
            if (newDuration > 0 && newDuration != currentSongDurationMs) {
                currentSongDurationMs = newDuration
                Log.d(TAG,"Duration updated for '$newTitle' ($currentMediaSessionToken) to $newDuration ms")
                currentLyricsData = when(val cd = currentLyricsData) {
                    is LyricsData.Synced -> cd.copy(durationMs = newDuration)
                    is LyricsData.Plain -> cd.copy(durationMs = newDuration)
                    is LyricsData.Info -> cd.copy(durationMs = newDuration)
                    is LyricsData.MismatchInfo -> cd.copy(originalLyricsData = when(val old = cd.originalLyricsData) {
                        is LyricsData.Synced -> old.copy(durationMs = newDuration)
                        is LyricsData.Plain -> old.copy(durationMs = newDuration)
                        is LyricsData.Info -> old.copy(durationMs = newDuration)
                        is LyricsData.MismatchInfo -> old
                        null -> null
                    })
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
            Log.d(TAG, "Song is the same ($source for $currentMediaSessionToken): '$newTitle'. Playback state: ${stateToString(playbackState)}")
        }
    }

    private fun cleanupMediaController() {
        val tokenBeingCleaned = activeMediaController?.sessionToken ?: currentMediaSessionToken
        Log.d(TAG, "Cleaning up MediaController. Token to be cleaned (approx): $tokenBeingCleaned.")

        lyricsHighlightingJob?.cancel()
        lyricsHighlightingJob = null

        activeMediaController?.let { controller ->
            mediaControllerCallback?.let { cb ->
                try {
                    controller.unregisterCallback(cb)
                    Log.d(TAG, "Unregistered callback from controller for token (approx): $tokenBeingCleaned")
                } catch (e: Exception) {
                    Log.w(TAG, "Exception unregistering MediaController callback for $tokenBeingCleaned: ${e.message}")
                }
            }
        }
        activeMediaController = null
        mediaControllerCallback = null
        currentPlaybackState = null
        Log.d(TAG, "MediaController cleanup finished for token (approx): $tokenBeingCleaned. ActiveMC is now null.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val tokenFromSbn = extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)

        if (tokenFromSbn != null) {
            if (activeMediaController == null || tokenFromSbn != currentMediaSessionToken) {
                var newTempController: MediaController? = null
                var newPlaybackState: PlaybackState? = null
                try {
                    newTempController = MediaController(applicationContext, tokenFromSbn)
                    newPlaybackState = newTempController.playbackState
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating temp controller for $tokenFromSbn from ${sbn.packageName}: ${e.message}")
                    if (this.currentPlaybackState?.state == PlaybackState.STATE_PLAYING) { // Check service's tracked state
                        Log.w(TAG, "Could not get state for $tokenFromSbn, current session is playing. Not switching.")
                        return
                    }
                }

                val newPlaybackStateInt = newPlaybackState?.state
                val currentActivePlaybackStateInt = this.currentPlaybackState?.state

                val shouldSwitch = (newPlaybackStateInt == PlaybackState.STATE_PLAYING) ||
                                   (newPlaybackStateInt != PlaybackState.STATE_PLAYING &&
                                    (currentActivePlaybackStateInt == null || currentActivePlaybackStateInt != PlaybackState.STATE_PLAYING))

                if (shouldSwitch) {
                    Log.i(TAG, "Switching session. Old: $currentMediaSessionToken, New: $tokenFromSbn (pkg: ${sbn.packageName}). NewState: ${stateToString(newPlaybackState)}")
                    setupMediaController(tokenFromSbn)
                } else {
                    Log.d(TAG, "Not switching. New session $tokenFromSbn (pkg: ${sbn.packageName}) state ${stateToString(newPlaybackState)}, Current session $currentMediaSessionToken state ${stateToString(this.currentPlaybackState)}.")
                }

            } else { // tokenFromSbn == currentMediaSessionToken
                val notifTitle = extras.getString(Notification.EXTRA_TITLE)
                val notifArtist: String? = extras.getString(Notification.EXTRA_SUB_TEXT) ?: extras.getString(Notification.EXTRA_TEXT)
                val mcTitle = activeMediaController?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                val mcArtist = activeMediaController?.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: activeMediaController?.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)

                if ((notifTitle != null && notifTitle != mcTitle) ||
                    (notifArtist != null && notifArtist.isNotBlank() && notifArtist != mcArtist)) {
                    Log.w(TAG, "Notification for $currentMediaSessionToken (pkg: ${sbn.packageName}) has different text ('$notifTitle'/'$notifArtist') than MC ('$mcTitle'/'$mcArtist'). Re-processing MC metadata.")
                    processMediaMetadata(activeMediaController?.metadata, activeMediaController?.playbackState, "NotificationUpdateTrigger (Text Discrepancy for $currentMediaSessionToken)")
                } else {
                    Log.d(TAG, "Notification for active session $currentMediaSessionToken (pkg: ${sbn.packageName}). Text matches or no change. State: ${stateToString(activeMediaController?.playbackState)}")
                    activeMediaController?.playbackState?.let {
                        if (it.state != this.currentPlaybackState?.state || it.position != this.currentPlaybackState?.position) { // Also check position for seeking
                            Log.d(TAG, "Playback state in notification potentially differs from internal. Updating via MC callback.")
                            mediaControllerCallback?.onPlaybackStateChanged(it)
                        }
                    }
                }
            }
        } else {
            if (activeMediaController != null && sbn.packageName == activeMediaController!!.packageName) {
                Log.d(TAG, "Notification from active player ${sbn.packageName} without media token. Current song: $lastDetectedSongTitle")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        val removedToken = sbn.notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        val removedTitle = sbn.notification.extras.getString(Notification.EXTRA_TITLE)

        Log.d(TAG, "Notification removed: pkg=${sbn.packageName}, title='${removedTitle}', token=$removedToken. Current token=$currentMediaSessionToken")

        if (removedToken != null && removedToken == currentMediaSessionToken) {
            Log.d(TAG, "Notification for active MediaSession ($currentMediaSessionToken, pkg: ${sbn.packageName}) removed.")
            if (currentPlaybackState?.state != PlaybackState.STATE_PLAYING &&
                currentPlaybackState?.state != PlaybackState.STATE_BUFFERING) {
                Log.d(TAG, "Notification for $currentMediaSessionToken removed and playback not active/buffering. Consider this a potential stop.")
            }
        }
    }

    private fun clearSongContextAndHideLyrics() {
        Log.i(TAG, "Clearing song context. Was for token: $currentMediaSessionToken, Title: $lastDetectedSongTitle")
        val tokenBeingCleared = currentMediaSessionToken

        lastDetectedSongTitle = null
        lastDetectedSongArtist = null
        currentSongDurationMs = 0L
        currentLyricsData = null

        if (activeMediaController != null && activeMediaController?.sessionToken == tokenBeingCleared) {
             cleanupMediaController()
        }
        currentMediaSessionToken = null

        updatePersistentNotification("Waiting for song...")
        hideLyricsWindow()
        Log.i(TAG, "Song context cleared. No active session.")
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

            var fetchedLyricsData: LyricsData? = null
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
                    var actualContentData: LyricsData

                    if (lyricResult.instrumental) {
                        actualContentData = LyricsData.Info(fetchForTitle, fetchForArtist.ifEmpty { null }, "This is an instrumental song... 🎵", finalDurationMs)
                    } else {
                        var parsedSyncedData: LyricsData.Synced? = null
                        if (!lyricResult.syncedLyrics.isNullOrBlank()) {
                            try {
                                val timedLines = parseSyncedLyrics(lyricResult.syncedLyrics)
                                if (timedLines.isNotEmpty()) {
                                    parsedSyncedData = LyricsData.Synced(fetchForTitle, fetchForArtist.ifEmpty { null }, timedLines, finalDurationMs)
                                }
                            } catch (e: Exception) { Log.w(TAG, "Error parsing synced lyrics for '$fetchForTitle'", e) }
                        }
                        actualContentData = parsedSyncedData ?: if (plainLyricsText.isNotBlank()) {
                            LyricsData.Plain(fetchForTitle, fetchForArtist.ifEmpty { null }, plainLyricsText, finalDurationMs)
                        } else {
                            LyricsData.Info(fetchForTitle, fetchForArtist.ifEmpty { null }, "Lyrics not found (empty content).", finalDurationMs)
                        }
                    }
                    fetchedLyricsData = if (potentialMismatch && actualContentData !is LyricsData.Info &&
                                             (lyricResult.trackName.lowercase().trim() != fetchForTitle.lowercase().trim() ||
                                              lyricResult.artistName.lowercase().trim() != fetchForArtist.lowercase().trim() ) ) {
                        Log.d(TAG, "Potential mismatch: API ('${lyricResult.trackName}/${lyricResult.artistName}') vs Query ('$fetchForTitle/$fetchForArtist')")
                        LyricsData.MismatchInfo(fetchForTitle, fetchForArtist.ifEmpty { null }, actualContentData)
                    } else {
                        actualContentData
                    }
                } else {
                    fetchedLyricsData = LyricsData.Info(fetchForTitle, fetchForArtist.ifEmpty { null }, "Lyrics not found.", durationFromMediaMs.takeIf { it > 0 } ?: 0L)
                }

                if (fetchForToken != currentMediaSessionToken || fetchForTitle != lastDetectedSongTitle || fetchForArtist != (lastDetectedSongArtist ?: "")) {
                    Log.d(TAG, "Context changed during lyrics fetch for '$fetchForTitle'. Current is '$lastDetectedSongTitle' for token $currentMediaSessionToken. Discarding fetched lyrics.")
                    return@launch
                }

                currentLyricsData = fetchedLyricsData

                if (currentLyricsData != null && currentSongDurationMs <= 0 && currentLyricsData!!.durationMs > 0) {
                    currentSongDurationMs = currentLyricsData!!.durationMs
                    currentLyricsData = when (val cd = currentLyricsData!!) {
                        is LyricsData.Synced -> cd.copy(durationMs = currentSongDurationMs)
                        is LyricsData.Plain -> cd.copy(durationMs = currentSongDurationMs)
                        is LyricsData.Info -> cd.copy(durationMs = currentSongDurationMs)
                        is LyricsData.MismatchInfo -> cd.copy(originalLyricsData = when (val old = cd.originalLyricsData) {
                            is LyricsData.Synced -> old.copy(durationMs = currentSongDurationMs)
                            is LyricsData.Plain -> old.copy(durationMs = currentSongDurationMs)
                            is LyricsData.Info -> old.copy(durationMs = currentSongDurationMs)
                            is LyricsData.MismatchInfo -> old
                            null -> null
                        })
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
                    Log.d(TAG, "Song changed before lyrics for '$fetchForTitle' (token $fetchForToken) could be displayed. Current: '$lastDetectedSongTitle' (token $currentMediaSessionToken).")
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

        if (dataTitle != lastDetectedSongTitle || dataArtist != lastDetectedSongArtist) {
             Log.w(TAG, "showLyricsWindow called for '$dataTitle'/'$dataArtist', but current song is '$lastDetectedSongTitle'/'$lastDetectedSongArtist'. Aborting show.")
             return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot show lyrics window: Overlay permission not granted.")
            updatePersistentNotification("Tap to grant Overlay Permission")
            val permIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                 PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val contentPendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, permIntent, pendingIntentFlags)

            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentText("Overlay permission needed for lyrics.")
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentIntent(contentPendingIntent)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(NOTIFICATION_ID + 1, notification)
            return
        }

        Handler(Looper.getMainLooper()).post {
            if (dataTitle != lastDetectedSongTitle || dataArtist != lastDetectedSongArtist) {
                 Log.w(TAG, "showLyricsWindow (MainThread): Context changed. Expected '$dataTitle', current '$lastDetectedSongTitle'. Aborting UI update.")
                 return@post
            }

            val songDisplayTitle = when (data) {
                is LyricsData.Synced -> data.artist?.let { "${data.title} - $it" } ?: data.title
                is LyricsData.Plain -> data.artist?.let { "${data.title} - $it" } ?: data.title
                is LyricsData.Info -> data.title?.let { base -> data.artist?.let { "$base - $it" } ?: base } ?: data.message
                is LyricsData.MismatchInfo -> data.title?.let { t -> data.artist?.let { a -> "Mismatch? $t - $a" } ?: "Mismatch? $t"} ?: "Potential song mismatch"
            }

            if (lyricsView == null) {
                Log.d(TAG, "Inflating lyrics_overlay for '$songDisplayTitle'.")
                windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                try {
                    lyricsView = inflater.inflate(R.layout.lyrics_overlay, null)
                } catch (e: Exception) {
                     Log.e(TAG, "Error inflating R.layout.lyrics_overlay: ${e.message}", e)
                     return@post
                }

                songInfoTextView = lyricsView?.findViewById(R.id.songInfoTextView)
                lyricsRecyclerView = lyricsView?.findViewById(R.id.lyricsRecyclerView)
                expandCollapseButton = lyricsView?.findViewById(R.id.expandCollapseButton)
                val closeButton = lyricsView?.findViewById<ImageButton>(R.id.closeButton)

                lyricsAdapter = LyricsAdapter(this, emptyList())
                linearLayoutManager = LinearLayoutManager(this)
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
                params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply { x = 0; y = 100 }

                try {
                    windowManager?.addView(lyricsView, params)
                    Log.d(TAG, "Lyrics window added for '$songDisplayTitle'.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding lyrics view to WindowManager: ${e.message}", e)
                    lyricsView = null
                    return@post
                }
            }

            // --- Dynamic Theming Logic ---
            var finalBackgroundColor = Color.parseColor("#CC000000") // 80% opaque black
            var finalTextColor = Color.WHITE
            var finalIconColor = Color.WHITE
            var shadowColor = Color.parseColor("#AA000000") // Shadow for light text on dark bg

            if (activeMediaController?.sessionToken == currentMediaSessionToken) {
                activeMediaController?.metadata?.let { metadata ->
                    val albumArtBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    albumArtBitmap?.let { bitmap ->
                        Palette.from(bitmap).generate { palette ->
                            palette?.let { p ->
                                val bgSwatch = p.darkVibrantSwatch ?: p.dominantSwatch
                                bgSwatch?.rgb?.let { colorInt ->
                                    finalBackgroundColor = (colorInt and 0x00FFFFFF) or (0xCC000000.toInt()) // 80% opaque
                                    Log.d(TAG, "Palette BG: #${Integer.toHexString(finalBackgroundColor)}")

                                    val isDarkBg = ColorUtils.calculateLuminance(finalBackgroundColor) < 0.4
                                    if (isDarkBg) {
                                        finalTextColor = p.lightMutedSwatch?.rgb ?: p.lightVibrantSwatch?.rgb ?: Color.WHITE
                                        finalIconColor = Color.WHITE
                                        shadowColor = Color.parseColor("#AA000000") // Dark shadow for light text
                                    } else {
                                        finalTextColor = p.darkMutedSwatch?.rgb ?: p.darkVibrantSwatch?.rgb ?: Color.BLACK
                                        finalIconColor = Color.DKGRAY
                                        shadowColor = Color.parseColor("#AA888888") // Lighter shadow for dark text
                                    }
                                    Log.d(TAG, "Palette Text: #${Integer.toHexString(finalTextColor)}, Icon: #${Integer.toHexString(finalIconColor)}")
                                } ?: Log.d(TAG, "No suitable background swatch from Palette.")
                            } ?: Log.d(TAG, "Palette generation failed.")
                            // Apply colors after palette finishes, outside this inner lambda if it's async
                            lyricsView?.setBackgroundColor(finalBackgroundColor)
                            songInfoTextView?.setTextColor(finalTextColor)
                            songInfoTextView?.setShadowLayer(2f, 1f, 1f, shadowColor)
                            expandCollapseButton?.setColorFilter(finalIconColor, PorterDuff.Mode.SRC_IN)
                            lyricsView?.findViewById<ImageButton>(R.id.closeButton)?.setColorFilter(finalIconColor, PorterDuff.Mode.SRC_IN)
                        }
                        // Return from post {} handler, palette is async. Colors applied in palette's onGenerated.
                        // To handle this correctly, we should apply defaults, then update if palette succeeds.
                        // The current structure applies defaults, then palette attempts to override.
                    } ?: Log.d(TAG, "No album art bitmap for Palette. Using defaults.")
                } ?: Log.d(TAG, "No MediaController metadata for Palette. Using defaults.")
            } else {
                 Log.d(TAG, "MediaController token mismatch or null. Using default theme for overlay.")
            }

            // Apply defaults immediately, Palette will update them if successful
            lyricsView?.setBackgroundColor(finalBackgroundColor)
            songInfoTextView?.setTextColor(finalTextColor)
            songInfoTextView?.setShadowLayer(2f, 1f, 1f, shadowColor)
            expandCollapseButton?.setColorFilter(finalIconColor, PorterDuff.Mode.SRC_IN)
            lyricsView?.findViewById<ImageButton>(R.id.closeButton)?.setColorFilter(finalIconColor, PorterDuff.Mode.SRC_IN)
            // --- End Dynamic Theming Logic ---


            songInfoTextView?.text = songDisplayTitle
            lyricsHighlightingJob?.cancel()

            var displayedLyricsData: LyricsData
            var mismatchMessage: String? = null
            when (data) {
                is LyricsData.Synced, is LyricsData.Plain -> displayedLyricsData = data
                is LyricsData.Info -> {
                    displayedLyricsData = data
                    if (data.durationMs > 0 && currentSongDurationMs <= 0) currentSongDurationMs = data.durationMs
                }
                is LyricsData.MismatchInfo -> {
                    mismatchMessage = "Potential song mismatch."
                    val effectiveDuration = data.originalLyricsData?.durationMs ?: 0L
                    displayedLyricsData = data.originalLyricsData ?: LyricsData.Info(
                        data.title, data.artist, "Lyrics details unavailable (mismatch).", effectiveDuration)
                    if (displayedLyricsData is LyricsData.Info && displayedLyricsData.message.contains("Loading", ignoreCase = true)) {
                         displayedLyricsData = LyricsData.Info(data.title, data.artist, "Fetched data seems to be a mismatch.", (displayedLyricsData as LyricsData.Info).durationMs)
                    }
                }
            }

            val finalMessageForInfo = if (mismatchMessage != null && displayedLyricsData is LyricsData.Info) "$mismatchMessage\n${displayedLyricsData.message}"
                                      else if (displayedLyricsData is LyricsData.Info) displayedLyricsData.message
                                      else ""

            when(displayedLyricsData) {
                is LyricsData.Synced -> lyricsAdapter?.updateLyrics(displayedLyricsData.lines, true)
                is LyricsData.Plain -> lyricsAdapter?.updateLyrics(displayedLyricsData.lyrics.lines().mapIndexed { i, t -> TimedLyricLine(i.toLong(), t) }, false)
                is LyricsData.Info -> lyricsAdapter?.updateLyrics(listOf(TimedLyricLine(0, finalMessageForInfo)), false)
                is LyricsData.MismatchInfo -> { /* Already unwrapped */ }
            }
            lyricsRecyclerView?.isVisible = true

            applyLyricsExpansionState()
            if (displayedLyricsData is LyricsData.Synced &&
                activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING &&
                activeMediaController?.sessionToken == currentMediaSessionToken) {
                startOrUpdateLyricsHighlighting()
            }
        }
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
            Log.d(TAG, "Not starting highlighter: Conditions not met. MC: ${controllerForHighlighting != null}, Token Match: ${controllerForHighlighting?.sessionToken == tokenForHighlighting}, Data: ${dataForHighlighting?.javaClass?.simpleName}, Lines: ${ (dataForHighlighting as? LyricsData.Synced)?.lines?.size}")
            return
        }
        if (dataForHighlighting.title != lastDetectedSongTitle || dataForHighlighting.artist != lastDetectedSongArtist) {
            Log.w(TAG, "Highlighting attempted for '${dataForHighlighting.title}' but current song context is '$lastDetectedSongTitle'. Aborting.")
            return
        }

        val lines = dataForHighlighting.lines
        val songTotalDuration = if (dataForHighlighting.durationMs > 0) dataForHighlighting.durationMs else currentSongDurationMs

        lyricsHighlightingJob = serviceScope.launch(Dispatchers.Main) {
            Log.d(TAG, "LyricsHighlightingJob: Started for '${dataForHighlighting.title}' (Token: $tokenForHighlighting). Lines: ${lines.size}")
            var lastHighlightedIndex = -1

            while (isActive) {
                if (activeMediaController == null || activeMediaController!!.sessionToken != tokenForHighlighting) {
                    Log.w(TAG, "Highlighting: MediaController changed or became null during highlighting for $tokenForHighlighting. Stopping job.")
                    break
                }
                val state = activeMediaController!!.playbackState
                if (state == null || state.state != PlaybackState.STATE_PLAYING) {
                    Log.d(TAG, "Highlighting ($tokenForHighlighting): Playback not active (State: ${stateToString(state)}). Pausing loop.")
                    lyricsAdapter?.setHighlight(-1); lastHighlightedIndex = -1
                    delay(HIGHLIGHT_UPDATE_INTERVAL_MS * 2); continue
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
                    lyricsAdapter?.setHighlight(currentLineIndex); lastHighlightedIndex = currentLineIndex
                    if (currentLineIndex != -1 && ::linearLayoutManager.isInitialized) {
                        val firstVis = linearLayoutManager.findFirstVisibleItemPosition()
                        val lastVis = linearLayoutManager.findLastVisibleItemPosition()
                        if (firstVis != RecyclerView.NO_POSITION && lastVis != RecyclerView.NO_POSITION) {
                            val visCount = lastVis - firstVis + 1
                            if (currentLineIndex < firstVis || currentLineIndex >= lastVis - (visCount / 3).coerceAtLeast(1) || visCount < 4) {
                                lyricsRecyclerView?.smoothScrollToPosition(currentLineIndex.coerceAtLeast(0))
                            }
                        }
                    }
                }
                if (songTotalDuration > 0 && currentPositionMs > songTotalDuration + 1000) {
                    Log.d(TAG, "Highlighting ($tokenForHighlighting): Song duration ($songTotalDuration ms) reached. Stopping job.")
                    lyricsAdapter?.setHighlight(-1); break
                }
                delay(HIGHLIGHT_UPDATE_INTERVAL_MS)
            }
            Log.d(TAG,"LyricsHighlightingJob: Ended/cancelled for '${dataForHighlighting.title}' (Token: $tokenForHighlighting).")
            if (lyricsAdapter?.getCurrentHighlightedPosition() != -1 && !isActive) lyricsAdapter?.setHighlight(-1)
        }
    }

    private fun hideLyricsWindow() {
        Log.d(TAG, "hideLyricsWindow() called")
        lyricsHighlightingJob?.cancel(); lyricsHighlightingJob = null
        Handler(Looper.getMainLooper()).post {
            if (lyricsView != null && windowManager != null) {
                try { windowManager?.removeView(lyricsView); Log.d(TAG, "Lyrics window removed.") }
                catch (e: Exception) { Log.e(TAG, "Error hiding lyrics window", e) }
                finally { lyricsView = null; songInfoTextView = null; lyricsRecyclerView = null; expandCollapseButton = null; lyricsAdapter = null }
            }
        }
    }

    private fun parseSyncedLyrics(syncedLyricsText: String?): List<TimedLyricLine> {
        if (syncedLyricsText.isNullOrBlank()) return emptyList()
        val lines = mutableListOf<TimedLyricLine>()
        val lyricLineRegex = "\\[(\\d{2}):(\\d{2})[.:](\\d{2,3})\\](.*)".toRegex()
        val simpleLyricLineRegex = "\\[(\\d{2}):(\\d{2})\\](.*)".toRegex()

        syncedLyricsText.lines().forEach { line ->
            var textContentForMultipleTags: String? = null
            var matches = lyricLineRegex.findAll(line)
            if (!matches.any()) {
                matches = simpleLyricLineRegex.findAll(line)
            }

            for (matchResult in matches) {
                try {
                    val groups = matchResult.groupValues
                    val minutes = groups[1].toInt()
                    val seconds = groups[2].toInt()
                    val millisStr: String
                    val text: String

                    if (groups.size > 4 && matchResult.groups[3] != null) {
                        millisStr = groups[3]
                        text = groups[4].trim()
                    } else if (groups.size > 3 && matchResult.groups[3] != null) {
                        millisStr = "0"
                        text = groups[3].trim()
                    } else {
                        Log.w(TAG, "Unexpected regex match groups for LRC line: '$line'")
                        continue
                    }

                    val milliseconds = when {
                        millisStr.isEmpty() -> 0
                        millisStr.length == 2 -> millisStr.toInt() * 10
                        else -> millisStr.toInt()
                    }
                    val timestamp = TimeUnit.MINUTES.toMillis(minutes.toLong()) +
                                    TimeUnit.SECONDS.toMillis(seconds.toLong()) + milliseconds.toLong()
                    
                    textContentForMultipleTags = textContentForMultipleTags ?: text
                    if (textContentForMultipleTags.isNotEmpty()) {
                        lines.add(TimedLyricLine(timestamp, textContentForMultipleTags))
                    } else if (text.isNotEmpty()) {
                         lines.add(TimedLyricLine(timestamp, text))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "LRC parse error: '$line'. Match: '${matchResult.value}'", e)
                }
            }
        }
        if (lines.isEmpty() && syncedLyricsText.isNotBlank() && !syncedLyricsText.trimStart().startsWith("[")) {
            Log.w(TAG, "No LRC tags found in supposedly synced lyrics. Treating as plain. Preview: ${syncedLyricsText.take(100)}")
            return syncedLyricsText.lines().mapIndexedNotNull { i, t -> if (t.isNotBlank()) TimedLyricLine(i * 1000L, t.trim()) else null }
        }
        if (lines.isEmpty() && syncedLyricsText.isNotBlank()) {
            Log.w(TAG, "Synced lyrics text was present but no valid timed lines parsed. Original text preview: ${syncedLyricsText.take(100)}")
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
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun createPersistentNotification(text: String): Notification {
        val showLyricsIntent = Intent(this, LyricService::class.java).apply { action = ACTION_SHOW_LYRICS }
        val hideLyricsIntent = Intent(this, LyricService::class.java).apply { action = ACTION_HIDE_LYRICS }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        val showAction = NotificationCompat.Action.Builder(R.drawable.ic_visibility, "Show", PendingIntent.getService(this, 0, showLyricsIntent, flags)).build()
        val hideAction = NotificationCompat.Action.Builder(R.drawable.ic_visibility_off, "Hide", PendingIntent.getService(this, 1, hideLyricsIntent, flags)).build()
        val contentIntent = Intent(this, MainActivity::class.java).apply { this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pContentIntent = PendingIntent.getActivity(this, 2, contentIntent, flags)

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
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, createPersistentNotification(text))
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy(). Cleaning up for $currentMediaSessionToken.")
        serviceScope.cancel()
        cleanupMediaController()
        currentMediaSessionToken = null
        hideLyricsWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        Log.d(TAG, "Service fully destroyed.")
        super.onDestroy()
    }

    private inner class ViewMover : View.OnTouchListener {
        private var initialX: Int = 0; private var initialY: Int = 0
        private var initialTouchX: Float = 0f; private var initialTouchY: Float = 0f
        private val touchSlop by lazy { android.view.ViewConfiguration.get(applicationContext).scaledTouchSlop }
        private var isDragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (params == null || windowManager == null || lyricsView == null) return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params!!.x; initialY = params!!.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    isDragging = false; return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX; val dy = event.rawY - initialTouchY
                    if (!isDragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) isDragging = true
                    if (isDragging) {
                        params!!.x = initialX + dx.toInt(); params!!.y = initialY + dy.toInt()
                        try { windowManager?.updateViewLayout(lyricsView, params) }
                        catch (e: Exception) { Log.e(TAG, "Error updating view layout move: ${e.message}")}
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> return isDragging.also { if (it) isDragging = false }
            }
            return false
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