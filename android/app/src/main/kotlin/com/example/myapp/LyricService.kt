package com.example.myapp

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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper
import android.os.SystemClock // Import for SystemClock
import androidx.core.view.isVisible

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
        abstract val durationMs: Long // Common property for all lyrics data types

        data class Plain(val title: String, val artist: String?, val lyrics: String, override val durationMs: Long) : LyricsData()
        data class Synced(val title: String, val artist: String?, val lines: List<TimedLyricLine>, override val durationMs: Long) : LyricsData()
        data class Info(val title: String?, val artist: String?, val message: String, override val durationMs: Long) : LyricsData()
        data class MismatchInfo(
            val title: String?,
            val artist: String?,
            val originalLyricsData: LyricsData? // Store the actual lyrics data if found during retry
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
        const val ACTION_SHOW_LYRICS = "com.example.myapp.ACTION_SHOW_LYRICS"
        const val ACTION_HIDE_LYRICS = "com.example.myapp.ACTION_HIDE_LYRICS"
        private const val LYRIC_API_BASE_URL = "https://lrclib.net/api/search"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate() called.")
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
                val activeNotifications = activeNotifications
                Log.d(TAG, "Found ${activeNotifications?.size ?: 0} active notifications on connect.")
                activeNotifications?.firstOrNull { sbn ->
                    sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) &&
                    MediaController(applicationContext, sbn.notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)!!)
                        .playbackState?.state == PlaybackState.STATE_PLAYING
                }?.let { sbn ->
                    Log.d(TAG, "Processing active media notification from ${sbn.packageName} on connect.")
                    onNotificationPosted(sbn)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException getting active notifications: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Exception getting active notifications: ${e.message}")
            }
        }, 1000)
    }


    private fun setupMediaController(token: MediaSession.Token) {
        if (currentMediaSessionToken == token && activeMediaController != null) {
            Log.d(TAG, "MediaController already set up for this token.")
             activeMediaController?.playbackState?.let { mediaControllerCallback?.onPlaybackStateChanged(it) }
             activeMediaController?.metadata?.let { mediaControllerCallback?.onMetadataChanged(it) }
            return
        }
        cleanupMediaController()
        try {
            activeMediaController = MediaController(applicationContext, token)
            currentMediaSessionToken = token
            mediaControllerCallback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    super.onPlaybackStateChanged(state)
                    val oldState = currentPlaybackState?.state
                    currentPlaybackState = state
                    Log.d(TAG, "onPlaybackStateChanged: ${stateToString(state)}, Pos: ${state?.position}, Speed: ${state?.playbackSpeed}")

                    if (currentLyricsData is LyricsData.Synced) {
                        if (state?.state == PlaybackState.STATE_PLAYING) {
                            if (oldState != PlaybackState.STATE_PLAYING) {
                                startOrUpdateLyricsHighlighting()
                            }
                            if (lyricsHighlightingJob == null || lyricsHighlightingJob?.isCompleted == true) {
                                startOrUpdateLyricsHighlighting()
                            }
                        } else {
                            lyricsHighlightingJob?.cancel()
                            Log.d(TAG, "Playback not active, cancelling highlighting job.")
                        }
                    }
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    super.onMetadataChanged(metadata)
                    Log.d(TAG, "onMetadataChanged: Title: ${metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}")
                    processMediaMetadata(metadata, activeMediaController?.playbackState, "Callback: MetadataChanged")
                }

                override fun onSessionDestroyed() {
                    super.onSessionDestroyed()
                    Log.d(TAG, "MediaSession Destroyed for token $currentMediaSessionToken. Cleaning up.")
                    clearSongContextAndHideLyrics()
                }
            }
            activeMediaController?.registerCallback(mediaControllerCallback!!, Handler(Looper.getMainLooper()))
            Log.d(TAG, "MediaController registered for token: $token from package ${activeMediaController?.packageName}")

            processMediaMetadata(activeMediaController?.metadata, activeMediaController?.playbackState, "Initial Setup for ${activeMediaController?.packageName}")
            activeMediaController?.playbackState?.let { mediaControllerCallback?.onPlaybackStateChanged(it) }


        } catch (e: Exception) {
            Log.e(TAG, "Error setting up MediaController for $token: ${e.message}", e)
            cleanupMediaController()
        }
    }

    private fun processMediaMetadata(metadata: MediaMetadata?, playbackState: PlaybackState?, source: String) {
        val newTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val newArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val newDuration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        Log.d(TAG, "Processing Meta ($source): Title='$newTitle', Artist='$newArtist', Duration='${newDuration}ms', PlaybackState: ${stateToString(playbackState)}")

        if (newTitle.isNullOrBlank()) {
            Log.d(TAG, "Media metadata ($source) missing title or title is blank. Not processing as new song.")
            if (lastDetectedSongTitle != null) {
                 Log.d(TAG, "Title became null/blank, previously was '$lastDetectedSongTitle'. Clearing context.")
                 clearSongContextAndHideLyrics()
            }
            return
        }

        if (newTitle != lastDetectedSongTitle || newArtist != lastDetectedSongArtist) {
            lastDetectedSongTitle = newTitle
            lastDetectedSongArtist = newArtist
            currentSongDurationMs = if (newDuration > 0) newDuration else (currentLyricsData?.durationMs ?: 0L)

            currentPlaybackState = playbackState
            val songInfoForDisplay = newArtist?.takeIf { it.isNotBlank() }?.let { "$newTitle by $it" } ?: newTitle
            Log.d(TAG, "New song detected ($source): $songInfoForDisplay")

            updatePersistentNotification("Lyrics for: $songInfoForDisplay")

            val loadingData = LyricsData.Info(newTitle, newArtist, "Loading lyrics...", currentSongDurationMs)
            currentLyricsData = loadingData
            if (lyricsView != null) {
                showLyricsWindow(loadingData)
            }
            fetchAndDisplayLyrics(newTitle, newArtist ?: "", currentSongDurationMs)
        } else {
            if (newDuration > 0 && newDuration != currentSongDurationMs) {
                currentSongDurationMs = newDuration
                Log.d(TAG,"Duration updated for '$newTitle' to $newDuration ms")
                currentLyricsData = when(val cd = currentLyricsData) {
                    is LyricsData.Synced -> cd.copy(durationMs = newDuration)
                    is LyricsData.Plain -> cd.copy(durationMs = newDuration)
                    is LyricsData.Info -> cd.copy(durationMs = newDuration)
                    is LyricsData.MismatchInfo -> cd.copy(originalLyricsData = when(val old = cd.originalLyricsData) {
                        is LyricsData.Synced -> old.copy(durationMs = newDuration)
                        is LyricsData.Plain -> old.copy(durationMs = newDuration)
                        is LyricsData.Info -> old.copy(durationMs = newDuration)
                        is LyricsData.MismatchInfo -> old // Should not happen, nested MismatchInfo
                        null -> null
                    })
                    null -> null
                }
            }
            currentPlaybackState = playbackState

            if (lyricsView != null && currentLyricsData is LyricsData.Synced && playbackState?.state == PlaybackState.STATE_PLAYING) {
                 startOrUpdateLyricsHighlighting()
            }
            Log.d(TAG, "Song is the same ($source): '$newTitle'. Playback state: ${stateToString(playbackState)}")
        }
    }

    private fun cleanupMediaController() {
        Log.d(TAG, "Cleaning up MediaController.")
        lyricsHighlightingJob?.cancel()
        try {
            activeMediaController?.unregisterCallback(mediaControllerCallback ?: return)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "MediaController callback already unregistered or never registered: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Exception unregistering MediaController callback: ${e.message}")
        }
        activeMediaController = null
        mediaControllerCallback = null
        currentMediaSessionToken = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        val notification = sbn.notification ?: return
        val extras = notification.extras
        val token = extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)

        if (token != null) {
            if (activeMediaController == null || currentMediaSessionToken != token ) {
                 Log.d(TAG, "MediaSession.Token detected from ${sbn.packageName}. Current token: $currentMediaSessionToken, New token: $token. Setting up MediaController.")
                 setupMediaController(token)
            } else {
                val notifTitle = extras.getString(Notification.EXTRA_TITLE)
                if (notifTitle != null && notifTitle != lastDetectedSongTitle && activeMediaController?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == lastDetectedSongTitle) {
                     Log.w(TAG, "Notification title '$notifTitle' differs from last detected '$lastDetectedSongTitle', but MediaController metadata still shows old. Forcing re-evaluation with controller's current metadata.")
                     processMediaMetadata(activeMediaController?.metadata, activeMediaController?.playbackState, "NotificationUpdateTrigger (Title Discrepancy)")
                } else {
                    Log.d(TAG, "Notification posted for already active session from ${sbn.packageName}. Token: $token. No change.")
                }
            }
        } else {
            if (activeMediaController != null && sbn.packageName == activeMediaController!!.packageName) {
                Log.d(TAG, "Notification from active player ${sbn.packageName} without media token. Might be transient. Current song: $lastDetectedSongTitle")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        val removedToken = sbn.notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        val removedTitle = sbn.notification.extras.getString(Notification.EXTRA_TITLE)

        Log.d(TAG, "Notification removed: pkg=${sbn.packageName}, title='${removedTitle}', token=$removedToken. Current token=$currentMediaSessionToken")

        if (removedToken != null && removedToken == currentMediaSessionToken) {
            Log.d(TAG, "Notification for active MediaSession removed (pkg: ${sbn.packageName}). Session might be ending.")
        } else if (removedTitle != null && removedTitle == lastDetectedSongTitle &&
                   (activeMediaController == null || sbn.packageName == activeMediaController?.packageName) ) {
             Log.d(TAG, "Notification for '$lastDetectedSongTitle' (pkg: ${sbn.packageName}) removed. This might indicate song stop.")
            if (activeMediaController == null || currentPlaybackState?.state != PlaybackState.STATE_PLAYING) {
                 Log.d(TAG, "Notification removed and playback not active or controller gone. Clearing context.")
                 clearSongContextAndHideLyrics()
            } else {
                Log.d(TAG, "Notification removed, but playback is still active. Lyrics context maintained.")
            }
        }
    }

    private fun clearSongContextAndHideLyrics() {
        Log.d(TAG, "Clearing song context and hiding lyrics.")
        lastDetectedSongTitle = null
        lastDetectedSongArtist = null
        currentSongDurationMs = 0L
        currentLyricsData = null
        cleanupMediaController()
        updatePersistentNotification("Waiting for song...")
        hideLyricsWindow()
    }

    private fun fetchAndDisplayLyrics(title: String, artist: String, durationFromMediaMs: Long) {
        lyricsHighlightingJob?.cancel()
        serviceScope.launch(Dispatchers.IO) {
            var fetchedLyricsData: LyricsData? = null
            try {
                var potentialMismatch = false
                val initialQuery = if (artist.isNotBlank()) "$title $artist" else title
                var results = searchLyrics(initialQuery)

                if (results.isNullOrEmpty() && artist.length > 10) {
                    Log.d(TAG, "Initial lyrics search failed for '$title' by '$artist'. Retrying with artist truncated.")
                    val retryQuery1 = "$title ${artist.take(10)}"
                    results = searchLyrics(retryQuery1)
                    if (!results.isNullOrEmpty()) potentialMismatch = true
                }

                if (results.isNullOrEmpty() && title.isNotBlank()) {
                    Log.d(TAG, "Retry 1 failed for '$title'. Retrying with just track name.")
                    val retryQuery2 = title
                    results = searchLyrics(retryQuery2)
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
                    
                    var actualContentData: LyricsData? = null

                    if (lyricResult.instrumental) {
                        actualContentData = LyricsData.Info(title, artist.ifEmpty { null }, "This is an instrumental song, let the music flow... 🎵", finalDurationMs)
                    } else {
                        var parsedSyncedData: LyricsData.Synced? = null
                        if (!lyricResult.syncedLyrics.isNullOrBlank()) {
                            try {
                                val timedLines = parseSyncedLyrics(lyricResult.syncedLyrics)
                                if (timedLines.isNotEmpty()) {
                                    Log.d(TAG, "Successfully parsed ${timedLines.size} synced lyric lines for '$title'.")
                                    parsedSyncedData = LyricsData.Synced(title, artist.ifEmpty { null }, timedLines, finalDurationMs)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Error parsing synced lyrics for '$title', will try plain if available.", e)
                            }
                        }

                        if (parsedSyncedData != null) {
                            actualContentData = parsedSyncedData
                        } else if (plainLyricsText.isNotBlank()) {
                            actualContentData = LyricsData.Plain(title, artist.ifEmpty { null }, plainLyricsText, finalDurationMs)
                        } else { 
                            actualContentData = LyricsData.Info(title, artist.ifEmpty { null }, "Lyrics not found (empty content).", finalDurationMs)
                        }
                    }
                    
                    fetchedLyricsData = if (potentialMismatch && actualContentData !is LyricsData.Info) {
                        LyricsData.MismatchInfo(title, artist.ifEmpty { null }, actualContentData)
                    } else {
                        actualContentData
                    }

                } else { 
                    Log.d(TAG, "No suitable lyric result found for '$title' after retries.")
                    fetchedLyricsData = LyricsData.Info(title, artist.ifEmpty { null }, "Lyrics not found.", durationFromMediaMs.takeIf { it > 0 } ?: 0L)
                }

                currentLyricsData = fetchedLyricsData
                
                if (currentSongDurationMs <= 0 && fetchedLyricsData != null && fetchedLyricsData.durationMs > 0) {
                    currentSongDurationMs = fetchedLyricsData.durationMs
                    currentLyricsData = when (val cd = currentLyricsData) {
                        is LyricsData.Synced -> cd.copy(durationMs = currentSongDurationMs)
                        is LyricsData.Plain -> cd.copy(durationMs = currentSongDurationMs)
                        is LyricsData.Info -> cd.copy(durationMs = currentSongDurationMs)
                        is LyricsData.MismatchInfo -> cd.copy(originalLyricsData = when (val old = cd.originalLyricsData) {
                            is LyricsData.Synced -> old.copy(durationMs = currentSongDurationMs)
                            is LyricsData.Plain -> old.copy(durationMs = currentSongDurationMs)
                            is LyricsData.Info -> old.copy(durationMs = currentSongDurationMs)
                            else -> old 
                        })
                        null -> null
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Exception while fetching or processing lyrics for '$title'", e)
                currentLyricsData = LyricsData.Info(title, artist.ifEmpty { null }, "Could not load lyrics: ${e.localizedMessage}.", durationFromMediaMs.takeIf { it > 0 } ?: 0L)
            }

            withContext(Dispatchers.Main) {
                if (lastDetectedSongTitle == title && lastDetectedSongArtist == (artist.ifEmpty { null })) {
                    currentLyricsData?.let { showLyricsWindow(it) }
                    if (currentLyricsData is LyricsData.Synced &&
                        activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        startOrUpdateLyricsHighlighting()
                    }
                } else {
                    Log.d(TAG, "Song changed while fetching lyrics for '$title'. Discarding fetched lyrics.")
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
            val response: HttpResponse = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                response.body<List<LyricResult>>().also { Log.d(TAG, "Search for '$query' returned ${it.size} results.") }
            } else {
                Log.e(TAG, "Lyrics search for '$query' failed with status: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during lyrics search for '$query'", e)
            null
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun showLyricsWindow(data: LyricsData) { // 'data' is the function parameter
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
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Overlay permission needed for lyrics.")
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentIntent(contentPendingIntent)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(NOTIFICATION_ID + 1, notification)
            return
        }

        Handler(Looper.getMainLooper()).post {
            val songDisplayTitle = when (data) {
                is LyricsData.Synced -> data.artist?.let { "${data.title} - $it" } ?: data.title
                is LyricsData.Plain -> data.artist?.let { "${data.title} - $it" } ?: data.title
                is LyricsData.Info -> data.title?.let { base -> data.artist?.let { "$base - $it" } ?: base } ?: data.message
                is LyricsData.MismatchInfo -> {
                    val songId = data.title?.let { t -> data.artist?.let { a -> "$t - $a" } ?: t } ?: "song"
                    "Potential mismatch for $songId"
                }
            }

            if (lyricsView == null) {
                Log.d(TAG, "Inflating lyrics_overlay layout.")
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
                    Log.d(TAG, "Lyrics window added.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding lyrics view to WindowManager: ${e.message}", e)
                    lyricsView = null
                    return@post
                }
            }

            songInfoTextView?.text = songDisplayTitle
            lyricsHighlightingJob?.cancel() 

            var displayedLyricsData: LyricsData // FIX: Was val, changed to var
            var mismatchMessage: String? = null 

            when (data) { // 'data' is the function parameter
                is LyricsData.Synced, is LyricsData.Plain -> {
                    displayedLyricsData = data
                }
                is LyricsData.Info -> {
                    displayedLyricsData = data
                    if (data.durationMs > 0 && currentSongDurationMs <=0) currentSongDurationMs = data.durationMs
                }
                is LyricsData.MismatchInfo -> { // 'data' here is the MismatchInfo from the function parameter
                    mismatchMessage = "Potential song mismatch." 
                    val effectiveDuration = data.originalLyricsData?.durationMs ?: 0L
                    // displayedLyricsData becomes the unwrapped original data, or a new Info object
                    displayedLyricsData = data.originalLyricsData ?: LyricsData.Info(
                        data.title, // title from the MismatchInfo parameter 'data'
                        data.artist, // artist from the MismatchInfo parameter 'data'
                        "Lyrics details unavailable for '${data.title ?: "this song"}' (possible mismatch).",
                        effectiveDuration
                    )
                    // If the unwrapped/new data is Info and says "Loading", refine the message
                    if (displayedLyricsData is LyricsData.Info && displayedLyricsData.message.contains("Loading", ignoreCase = true)) {
                         displayedLyricsData = LyricsData.Info( // Reassigning displayedLyricsData
                            data.title, // title from the MismatchInfo parameter 'data'
                            data.artist, // artist from the MismatchInfo parameter 'data'
                            "Fetched data for '${data.title ?: "this song"}' seems to be a mismatch. Displaying info.",
                            (displayedLyricsData as LyricsData.Info).durationMs // duration from the current displayedLyricsData (which is Info)
                        )
                    }
                }
            }

            val finalMessageForInfo = if (mismatchMessage != null && displayedLyricsData is LyricsData.Info) {
                 "$mismatchMessage\n${displayedLyricsData.message}"
            } else if (displayedLyricsData is LyricsData.Info) {
                displayedLyricsData.message
            } else {
                "" 
            }

            // 'displayedLyricsData' is now the variable holding the actual data to display
            when(displayedLyricsData) {
                is LyricsData.Synced -> {
                    lyricsAdapter?.updateLyrics(displayedLyricsData.lines, true)
                    lyricsRecyclerView?.isVisible = true
                }
                is LyricsData.Plain -> {
                    val plainAsTimedLines = displayedLyricsData.lyrics.lines().mapIndexed { idx, txt -> TimedLyricLine(idx.toLong(), txt) }
                    lyricsAdapter?.updateLyrics(plainAsTimedLines, false)
                    lyricsRecyclerView?.isVisible = true
                }
                is LyricsData.Info -> {
                    lyricsAdapter?.updateLyrics(listOf(TimedLyricLine(0, finalMessageForInfo)), false)
                    lyricsRecyclerView?.isVisible = true
                }
                is LyricsData.MismatchInfo -> { 
                    Log.e(TAG, "Unexpected MismatchInfo in display logic; should have been unwrapped by now.")
                    // FIX: Use 'displayedLyricsData' (which is MismatchInfo here) instead of 'data'
                    val fallbackMsg = displayedLyricsData.title?.let { t ->
                        displayedLyricsData.artist?.let { a -> "Mismatch for $t - $a" } ?: "Mismatch for $t"
                    } ?: "Potential song mismatch."
                    lyricsAdapter?.updateLyrics(listOf(TimedLyricLine(0, fallbackMsg)), false)
                    lyricsRecyclerView?.isVisible = true
                }
            }

            applyLyricsExpansionState()
            if (displayedLyricsData is LyricsData.Synced && activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
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
        val currentData = currentLyricsData
        val currentSessionController = activeMediaController
    
        if (currentSessionController == null || currentData !is LyricsData.Synced || currentData.lines.isEmpty()) {
            Log.d(TAG, "Not starting highlighter: No MediaController, not Synced lyrics, or no lyric lines. MC: ${currentSessionController != null}, Data: $currentData")
            return
        }

        val lines = currentData.lines
        val songTotalDuration = if (currentData.durationMs > 0) currentData.durationMs else currentSongDurationMs

        lyricsHighlightingJob = serviceScope.launch(Dispatchers.Main) {
            Log.d(TAG, "LyricsHighlightingJob: Started for '${currentData.title}'. Lines: ${lines.size}")
            var lastHighlightedIndex = -1

            while (isActive) { 
                val state = currentSessionController.playbackState
                if (state == null || state.state != PlaybackState.STATE_PLAYING) {
                    Log.d(TAG, "Highlighting: Playback not active (State: ${stateToString(state)}). Pausing highlight job loop.")
                    delay(HIGHLIGHT_UPDATE_INTERVAL_MS * 2) 
                    continue
                }
    
                var currentPositionMs = state.position
                val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1.0f
                
                if (state.lastPositionUpdateTime > 0) { 
                    val elapsedRealtimeSinceUpdate = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
                    currentPositionMs += (elapsedRealtimeSinceUpdate * speed).toLong()
                }
    
                var currentLineIndex = -1
                for (i in lines.indices.reversed()) {
                    if (lines[i].timestamp <= currentPositionMs) {
                        currentLineIndex = i
                        break
                    }
                }
                
                if (currentLineIndex != lastHighlightedIndex) { 
                    Log.v(TAG, "Highlighting: Pos ${currentPositionMs}ms. Line $currentLineIndex: '${lines.getOrNull(currentLineIndex)?.text?.take(30)}...'")
                    lyricsAdapter?.setHighlight(currentLineIndex)
                    lastHighlightedIndex = currentLineIndex
    
                    if (currentLineIndex != -1 && ::linearLayoutManager.isInitialized) {
                        val firstVisible = linearLayoutManager.findFirstVisibleItemPosition()
                        val lastVisible = linearLayoutManager.findLastVisibleItemPosition()
                        val visibleItemCount = lastVisible - firstVisible + 1

                        if (currentLineIndex < firstVisible || currentLineIndex > lastVisible - (visibleItemCount / 3) || visibleItemCount < 4) {
                           lyricsRecyclerView?.smoothScrollToPosition(currentLineIndex.coerceAtLeast(0))
                        }
                    }
                }

                if (songTotalDuration > 0 && currentPositionMs > songTotalDuration + 1000) { 
                    Log.d(TAG, "Highlighting: Song duration ($songTotalDuration ms) likely reached (pos $currentPositionMs ms). Stopping highlighting job.")
                    lyricsAdapter?.setHighlight(-1) 
                    break 
                }
                delay(HIGHLIGHT_UPDATE_INTERVAL_MS)
            }
            Log.d(TAG,"LyricsHighlightingJob: Ended or cancelled for '${currentData.title}'.")
        }
    }

    private fun hideLyricsWindow() {
        Log.d(TAG, "hideLyricsWindow() called")
        lyricsHighlightingJob?.cancel()
        Handler(Looper.getMainLooper()).post {
            if (lyricsView != null && windowManager != null) {
                try {
                    windowManager?.removeView(lyricsView)
                    Log.d(TAG, "Lyrics window removed.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error hiding lyrics window", e)
                } finally {
                    lyricsView = null
                    songInfoTextView = null
                    lyricsRecyclerView = null
                    expandCollapseButton = null
                    lyricsAdapter = null 
                }
            }
        }
    }

    private fun parseSyncedLyrics(syncedLyricsText: String?): List<TimedLyricLine> {
        if (syncedLyricsText.isNullOrBlank()) {
            Log.d(TAG, "parseSyncedLyrics: input is null or blank.")
            return emptyList()
        }

        val lines = mutableListOf<TimedLyricLine>()
        val lyricLineRegex = "\\[(\\d{2}):(\\d{2})(?:[.:](\\d{2,3}))?\\](.*)".toRegex()

        syncedLyricsText.lines().forEach { line ->
            var textContentForMultipleTags: String? = null
            for (matchResult in lyricLineRegex.findAll(line)) {
                try {
                    val minutes = matchResult.groupValues[1].toInt()
                    val seconds = matchResult.groupValues[2].toInt()
                    val millisStr = matchResult.groupValues[3] 

                    val milliseconds = when {
                        millisStr.isEmpty() -> 0 
                        millisStr.length == 2 -> millisStr.toInt() * 10 
                        else -> millisStr.toInt() 
                    }

                    val timestamp = TimeUnit.MINUTES.toMillis(minutes.toLong()) +
                            TimeUnit.SECONDS.toMillis(seconds.toLong()) +
                            milliseconds.toLong()
                    
                    val text = matchResult.groupValues[4].trim()
                    
                    if (textContentForMultipleTags == null) {
                        textContentForMultipleTags = text
                    }
                    if (textContentForMultipleTags?.isNotEmpty() == true) { 
                        lines.add(TimedLyricLine(timestamp, textContentForMultipleTags!!))
                    }


                } catch (e: NumberFormatException) {
                    Log.w(TAG, "Could not parse LRC timestamp components in line: '$line'. Match: '${matchResult.value}'", e)
                } catch (e: IndexOutOfBoundsException) {
                     Log.w(TAG, "Regex group issue in line: '$line'. Match: '${matchResult.value}'", e)
                }
            }
        }
        if (lines.isEmpty() && syncedLyricsText.isNotBlank()) {
            Log.w(TAG, "Synced lyrics text was present but no valid timed lines parsed. Original text: ${syncedLyricsText.substring(0, syncedLyricsText.length.coerceAtMost(100))}")
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
                setSound(null, null) 
                enableLights(false)
                enableVibration(false)
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createPersistentNotification(text: String): Notification {
        val showLyricsIntent = Intent(this, LyricService::class.java).apply { action = ACTION_SHOW_LYRICS }
        val hideLyricsIntent = Intent(this, LyricService::class.java).apply { action = ACTION_HIDE_LYRICS }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val showAction = NotificationCompat.Action.Builder(
            R.drawable.ic_visibility, 
            "Show",
            PendingIntent.getService(this, 0, showLyricsIntent, pendingIntentFlags)
        ).build()

        val hideAction = NotificationCompat.Action.Builder(
            R.drawable.ic_visibility_off, 
            "Hide",
            PendingIntent.getService(this, 1, hideLyricsIntent, pendingIntentFlags)
        ).build()

        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP 
        }
        val contentPendingIntent: PendingIntent = PendingIntent.getActivity(
            this, 2, contentIntent, pendingIntentFlags
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_icon) 
            .setOngoing(true) 
            .setContentIntent(contentPendingIntent) 
            .addAction(showAction)
            .addAction(hideAction)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) 
            .setPriority(NotificationCompat.PRIORITY_LOW) 
            .build()
    }

    private fun updatePersistentNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createPersistentNotification(text))
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy(). Cleaning up resources.")
        serviceScope.cancel() 
        cleanupMediaController()
        hideLyricsWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        Log.d(TAG, "Service fully destroyed.")
        super.onDestroy()
    }

    private inner class ViewMover : View.OnTouchListener {
        private var initialX: Int = 0
        private var initialY: Int = 0
        private var initialTouchX: Float = 0f
        private var initialTouchY: Float = 0f
        private val touchSlop by lazy { android.view.ViewConfiguration.get(applicationContext).scaledTouchSlop }
        private var isDragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (params == null || windowManager == null || lyricsView == null) return false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params!!.x
                    initialY = params!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    return true 
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!isDragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params!!.x = initialX + dx.toInt()
                        params!!.y = initialY + dy.toInt()
                        try {
                            windowManager?.updateViewLayout(lyricsView, params)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating view layout during move: ${e.message}")
                        }
                    }
                    return true 
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        isDragging = false
                        return true 
                    }
                    return false
                }
            }
            return false
        }
    }

    private fun stateToString(state: PlaybackState?): String {
        if (state == null) return "null"
        return when (state.state) {
            PlaybackState.STATE_NONE -> "NONE"
            PlaybackState.STATE_STOPPED -> "STOPPED"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
            PlaybackState.STATE_REWINDING -> "REWINDING"
            PlaybackState.STATE_BUFFERING -> "BUFFERING"
            PlaybackState.STATE_ERROR -> "ERROR"
            PlaybackState.STATE_CONNECTING -> "CONNECTING" 
            else -> "UNKNOWN (${state.state})"
        }
    }
}