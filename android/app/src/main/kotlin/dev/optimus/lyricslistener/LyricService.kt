package dev.optimus.lyricslistener

import android.app.NotificationChannel
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import android.view.WindowManager
import android.graphics.PixelFormat
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import java.util.regex.Pattern
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.palette.graphics.Palette
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import androidx.recyclerview.widget.LinearSmoothScroller
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import kotlinx.serialization.SerialName
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong


class LyricService : NotificationListenerService() {

    private val TAG = "LyricService"
    private val NOTIFICATION_CHANNEL_ID = "LyricServiceChannel"
    private val NOTIFICATION_ID = 1
    private val HIGHLIGHT_UPDATE_INTERVAL_MS = 200L
    private val CONNECT_RETRY_DELAY_MS = 3000L
    private val MIN_REBIND_INTERVAL_MS = 10000L
    private val LISTENER_HEALTH_SHORT_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15)
    private val LISTENER_HEALTH_LONG_INTERVAL_MS = TimeUnit.MINUTES.toMillis(2)
    private val LISTENER_STALE_NOTIFICATION_THRESHOLD_MS = TimeUnit.MINUTES.toMillis(3)
    private val MAX_CONSECUTIVE_RECOVERY_ATTEMPTS = 3

    @Volatile private var windowManager: WindowManager? = null
    @Volatile private var lyricsView: View? = null
    @Volatile private var params: WindowManager.LayoutParams? = null
    private var lyricsRecyclerView: RecyclerView? = null
    private var songInfoTextView: TextView? = null
    private var expandCollapseButton: ImageButton? = null
    private var translateButton: ImageButton? = null
    private var lyricsAdapter: LyricsAdapter? = null
    private lateinit var linearLayoutManager: LinearLayoutManager

    private var lastDetectedSongTitle: String? = null
    private var lastDetectedSongArtist: String? = null
    @Volatile private var currentLyricsData: LyricsData? = null
    private var currentSongDurationMs: Long = 0L

    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var lyricsHighlightingJob: Job? = null

    private val lastListenerRebindAttempt = AtomicLong(0L)
    private val lastListenerHeartbeatMs = AtomicLong(0L)
    private val consecutiveListenerRecoveryAttempts = AtomicInteger(0)
    private val nextListenerHealthCheckAtMs = AtomicLong(0L)
    private val listenerHealthHandler by lazy { Handler(Looper.getMainLooper()) }
    private val listenerHealthCheckRunnable = Runnable { evaluateListenerHealth() }

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
    
    // Musixmatch related properties
    private var musixmatchUserToken: String? = null
    private var isShowingTranslatedLyrics = false
    private var currentLyricsHasTranslation = false


    // Musixmatch API Data Classes
    @Serializable data class MusixmatchTokenResponse(val message: MusixmatchTokenMessage)
    @Serializable data class MusixmatchTokenMessage(val body: MusixmatchTokenBody)
    @Serializable data class MusixmatchTokenBody(val user_token: String)

    @Serializable data class MusixmatchLyricsResponse(val message: MMXMessage)
    @Serializable data class MMXMessage(val body: MMXBody)
    @Serializable data class MMXBody(val macro_calls: MacroCalls)

    @Serializable data class MacroCalls(
        @SerialName("track.lyrics.get") val trackLyricsGet: MMXTrackLyricsGet? = null,
        @SerialName("track.subtitles.get") val trackSubtitlesGet: MMXTrackSubtitlesGet? = null
    )

    @Serializable data class MMXTrackLyricsGet(val message: MMXTrackLyricsGetMessage? = null)
    @Serializable data class MMXTrackLyricsGetMessage(val body: MMXLyricsBody? = null)
    @Serializable data class MMXLyricsBody(val lyrics: MMXLyrics? = null)
    @Serializable data class MMXLyrics(
        val instrumental: Int = 0,
        val lyrics_body: String? = null
    )

    @Serializable data class MMXTrackSubtitlesGet(val message: MMXTrackSubtitlesGetMessage? = null)
    @Serializable data class MMXTrackSubtitlesGetMessage(val body: MMXSubtitleListBody? = null)
    @Serializable data class MMXSubtitleListBody(val subtitle_list: List<MMXSubtitleHolder>? = null)
    @Serializable data class MMXSubtitleHolder(val subtitle: MMXSubtitle? = null)
    @Serializable data class MMXSubtitle(
        val subtitle_body: String? = null,
        val subtitle_translated: MMXSubtitleTranslated? = null
    )
    @Serializable data class MMXSubtitleTranslated(val subtitle_body: String? = null)


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
        data class Synced(
            val title: String,
            val artist: String?,
            val lines: List<TimedLyricLine>,
            val translatedLines: List<TimedLyricLine>? = null,
            override val durationMs: Long
        ) : LyricsData()
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
            // We must explicitly register the JSON converter for both the standard
            // `application/json` and the non-standard `text/plain`.
            // The Musixmatch API incorrectly returns its JSON response with a
            // `text/plain` content type, which would cause a NoTransformationFoundException
            // if not handled here.
            val converter = KotlinxSerializationConverter(Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            })
            register(ContentType.Application.Json, converter)
            register(ContentType.Text.Plain, converter)
        }
    }

    companion object {
        const val ACTION_SHOW_LYRICS = "dev.optimus.lyricslistener.ACTION_SHOW_LYRICS"
        const val ACTION_HIDE_LYRICS = "dev.optimus.lyricslistener.ACTION_HIDE_LYRICS"
        const val ACTION_USER_INITIATED_START = "dev.optimus.lyricslistener.ACTION_USER_INITIATED_START"
        const val ACTION_USER_INITIATED_STOP = "dev.optimus.lyricslistener.ACTION_USER_INITIATED_STOP"
        const val ATTRIBUTION_TIMESTAMP = -999L

        private const val LYRIC_API_BASE_URL = "https://lrclib.net/api/search"
        private val LRC_LINE_PATTERN: Pattern = Pattern.compile("(?<=\\[)(\\d{2,}):(\\d{2})([.:])(\\d{2,3})\\](.*)")

        private const val FLUTTER_SHARED_PREFERENCES = "FlutterSharedPreferences"
        private const val PREF_REMEMBER_WINDOW_POSITION = "flutter.remember_window_position"
        private const val PREF_REMEMBER_WINDOW_POSITION_X = "flutter.remember_window_position_x"
        private const val PREF_REMEMBER_WINDOW_POSITION_Y = "flutter.remember_window_position_y"
        private const val PREF_DYNAMIC_LYRICS_WINDOW_COLORS = "flutter.lyrics_window_dynamic_colors"
        private const val PREF_LYRICS_WINDOW_TITLE_COLOR = "flutter.lyrics_window_title_color"
    private const val PREF_LYRICS_WINDOW_BACKGROUND_COLOR = "flutter.lyrics_window_background_color"
    private const val PREF_LYRICS_WINDOW_HIGHLIGHT_COLOR = "flutter.lyrics_window_highlight_color"

    private val DEFAULT_STATIC_TITLE_COLOR = Color.parseColor("#FFE0E0E0")
    private val DEFAULT_STATIC_BACKGROUND_COLOR = Color.parseColor("#DD212121")
    private val DEFAULT_STATIC_HIGHLIGHT_COLOR = Color.argb(70, 200, 200, 200)

    private fun getStoredColorPreference(
        prefs: SharedPreferences,
        key: String,
        defaultColor: Int
    ): Int {
        val value = prefs.all[key]
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            else -> defaultColor
        }
    }

        // Musixmatch constants
        private const val MUSIXMATCH_TOKEN_URL = "https://apic.musixmatch.com/ws/1.1/token.get?app_id=mac-ios-v2.0"
        private const val MUSIXMATCH_API_BASE_URL = "https://apic.musixmatch.com/ws/1.1/macro.subtitles.get"
        private const val MUSIXMATCH_USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        private const val MUSIXMATCH_COOKIE = "mxm_bab=AB"
        const val MUSIXMATCH_ATTRIBUTION = "Lyrics provided by Musixmatch"


        var isServiceManuallyStarted = AtomicBoolean(false)
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate() called. Instance: ${this.hashCode()}. isServiceManuallyStarted: ${isServiceManuallyStarted.get()}")
        if (serviceJob.isCancelled) {
            serviceJob = SupervisorJob()
            serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
        }
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createPersistentNotification("Initializing..."))
        isLyricsExpanded = false
        _listenerEverConnected = false
        _isAttemptingConnection = false
        consecutiveListenerRecoveryAttempts.set(0)
        lastListenerHeartbeatMs.set(SystemClock.elapsedRealtime())

        initializeMusixmatchToken()
    }
    
    private fun initializeMusixmatchToken() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Attempting to get Musixmatch user token.")
                val response: MusixmatchTokenResponse = httpClient.get(MUSIXMATCH_TOKEN_URL) {
                    header("User-Agent", MUSIXMATCH_USER_AGENT)
                    header("Cookie", MUSIXMATCH_COOKIE)
                }.body()
                val token = response.message.body.user_token
                if (token.isNotBlank()) {
                    musixmatchUserToken = token
                    Log.i(TAG, "Successfully acquired Musixmatch user token.")
                } else {
                    Log.w(TAG, "Musixmatch token response was blank.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get Musixmatch user token", e)
                musixmatchUserToken = null
            }
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand (Instance: ${this.hashCode()}) with action: ${intent?.action}, flags: $flags, startId: $startId. isServiceManuallyStarted: ${isServiceManuallyStarted.get()}, ServiceJob Active: ${serviceJob.isActive}")

        if (intent?.action == ACTION_USER_INITIATED_STOP) {
            Log.i(TAG, "ACTION_USER_INITIATED_STOP received.")
            performStopActions()
            return START_NOT_STICKY
        }

        if (serviceJob.isCancelled) {
            serviceJob = SupervisorJob()
            serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
            Log.i(TAG, "onStartCommand: Recreated serviceJob and serviceScope as it was cancelled.")
        }

        isServiceManuallyStarted.set(true)
        consecutiveListenerRecoveryAttempts.set(0)
        lastListenerHeartbeatMs.set(SystemClock.elapsedRealtime())
        scheduleListenerHealthCheck(LISTENER_HEALTH_SHORT_INTERVAL_MS, "onStartCommand action=${intent?.action}", preferSooner = true)

        when (intent?.action) {
            ACTION_USER_INITIATED_START -> {
                Log.i(TAG, "ACTION_USER_INITIATED_START received. Forcing full re-initialization.")
                startForeground(NOTIFICATION_ID, createPersistentNotification("Service starting..."))
                _listenerEverConnected = false
                _isAttemptingConnection = false
                clearSongContextAndHideLyrics()
                requestNotificationListenerRebind("User initiated start")
                if(musixmatchUserToken == null) initializeMusixmatchToken()
                tryToConnectToActiveMediaSessions(delayMs = 0L)
            }
            ACTION_SHOW_LYRICS -> {
                Log.d(TAG, "ACTION_SHOW_LYRICS received.")
                startForeground(NOTIFICATION_ID, createPersistentNotification("Lyric Service Active"))
                val dataToShow = currentLyricsData ?: LyricsData.Info(
                    lastDetectedSongTitle, lastDetectedSongArtist,
                    if (lastDetectedSongTitle != null) "Loading lyrics..." else if (_listenerEverConnected) "Waiting for song..." else "Connecting listener...",
                    currentSongDurationMs
                )
                showLyricsWindow(dataToShow)
                if (lastDetectedSongTitle != null && dataToShow is LyricsData.Info && dataToShow.message.contains("Loading", ignoreCase = true)) {
                    fetchAndDisplayLyrics(lastDetectedSongTitle!!, lastDetectedSongArtist ?: "", currentSongDurationMs)
                } else if (lastDetectedSongTitle == null) {
                    _isAttemptingConnection = false
                    requestNotificationListenerRebind("Show lyrics action with no active song")
                    tryToConnectToActiveMediaSessions(delayMs = 0L)
                }
            }
            ACTION_HIDE_LYRICS -> {
                Log.d(TAG, "ACTION_HIDE_LYRICS received.")
                hideLyricsWindow()
            }
            else -> {
                Log.i(TAG, "Service (re)started with null or unhandled action (Intent: $intent, Action: ${intent?.action}). _listenerEverConnected: $_listenerEverConnected")
                startForeground(NOTIFICATION_ID, createPersistentNotification(
                    if (_listenerEverConnected && currentMediaSessionToken != null) "Service active. Last: $lastDetectedSongTitle"
                    else if (_listenerEverConnected) "Service active. Scanning..."
                    else "Service starting..."))
                _isAttemptingConnection = false
                if (!_listenerEverConnected) {
                    requestNotificationListenerRebind("General service start with no listener connection")
                }
                tryToConnectToActiveMediaSessions(delayMs = 300L)
            }
        }
        return START_STICKY
    }
    private fun performStopActions() {
        Log.i(TAG, "performStopActions: Initiating service stop procedures.")
        isServiceManuallyStarted.set(false)
        cancelListenerHealthChecks("performStopActions")
        consecutiveListenerRecoveryAttempts.set(0)
        hideLyricsWindow()
        cleanupMediaController()

        lastDetectedSongTitle = null
        lastDetectedSongArtist = null
        currentSongDurationMs = 0L
        currentLyricsData = null
        currentMediaSessionToken = null
        currentPlaybackState = null
        musixmatchUserToken = null

        _listenerEverConnected = false
        _isAttemptingConnection = false


        lyricsHighlightingJob?.cancel()
        lyricsHighlightingJob = null

        if (!serviceJob.isCancelled) {
            serviceJob.cancel()
        }


        Log.i(TAG, "performStopActions: Stopping foreground and self.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        Log.i(TAG, "performStopActions: stopSelf() called. ServiceJob Cancelled: ${serviceJob.isCancelled}")
    }


    override fun onListenerConnected() {
        super.onListenerConnected()
        if (serviceJob.isCancelled || !isServiceManuallyStarted.get()) {
            Log.w(TAG, "onListenerConnected: Ignoring as serviceJob is cancelled or service is not manually started. isCancelled=${serviceJob.isCancelled}, isManuallyStarted=${isServiceManuallyStarted.get()}");
            return
        }

        _listenerEverConnected = true
        _isAttemptingConnection = false
        consecutiveListenerRecoveryAttempts.set(0)
        lastListenerHeartbeatMs.set(SystemClock.elapsedRealtime())
        Log.i(TAG, "Notification Listener connected by system. (Instance: ${this.hashCode()})")
        updatePersistentNotification("Listener connected, scanning media...")
        tryToConnectToActiveMediaSessions(delayMs = 0L)
        scheduleListenerHealthCheck(LISTENER_HEALTH_LONG_INTERVAL_MS, "onListenerConnected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _listenerEverConnected = false
        _isAttemptingConnection = false
        lastListenerHeartbeatMs.set(SystemClock.elapsedRealtime())
        Log.w(TAG, "Notification Listener disconnected by system! (Instance: ${this.hashCode()}) Cleaning up.")
        clearSongContextAndHideLyrics()
        if (isServiceManuallyStarted.get()) {
            updatePersistentNotification("Listener disconnected. Check permissions.")
            requestNotificationListenerRebind("System disconnected listener")
            scheduleListenerHealthCheck(LISTENER_HEALTH_SHORT_INTERVAL_MS, "onListenerDisconnected", preferSooner = true)
        }
    }

    private val executeFindActiveMediaSessionsRunnable = Runnable { executeFindActiveMediaSessions() }

    private fun tryToConnectToActiveMediaSessions(delayMs: Long) {
        if (serviceJob.isCancelled || !isServiceManuallyStarted.get()) {
             Log.w(TAG, "tryToConnectToActiveMediaSessions: Aborting. ServiceJob cancelled or service not manually started. isCancelled=${serviceJob.isCancelled}, isManuallyStarted=${isServiceManuallyStarted.get()}")
             _isAttemptingConnection = false
             return
        }
        if (_isAttemptingConnection && delayMs > 0) {
            Log.d(TAG, "tryToConnectToActiveMediaSessions: Connection attempt already effectively scheduled (or running). Ignoring new delayed request ($delayMs ms).")
            return
        }
        Log.d(TAG, "tryToConnectToActiveMediaSessions scheduled with delay: $delayMs ms. _listenerEverConnected: $_listenerEverConnected. _isAttemptingConnection: $_isAttemptingConnection")
        _isAttemptingConnection = true

        Handler(Looper.getMainLooper()).removeCallbacks(executeFindActiveMediaSessionsRunnable)
        Handler(Looper.getMainLooper()).postDelayed(executeFindActiveMediaSessionsRunnable, delayMs)
    }

    private fun requestNotificationListenerRebind(reason: String, force: Boolean = false) {
        if (!isServiceManuallyStarted.get()) {
            Log.d(TAG, "requestNotificationListenerRebind: Skipping ($reason) because service is not marked as manually started.")
            return
        }

        if (!hasNotificationAccess()) {
            Log.w(TAG, "requestNotificationListenerRebind: Notification access not currently granted. Reason: $reason")
            handleMissingNotificationAccess("requestNotificationListenerRebind($reason)")
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (!force) {
            val lastAttempt = lastListenerRebindAttempt.get()
            if (now - lastAttempt < MIN_REBIND_INTERVAL_MS) {
                Log.d(TAG, "requestNotificationListenerRebind: Recent attempt ${now - lastAttempt}ms ago. Skipping. Reason: $reason")
                return
            }
            if (!lastListenerRebindAttempt.compareAndSet(lastAttempt, now)) {
                Log.d(TAG, "requestNotificationListenerRebind: Another attempt is in progress. Skipping duplicate. Reason: $reason")
                return
            }
        } else {
            lastListenerRebindAttempt.set(now)
        }

        val componentName = ComponentName(this, javaClass)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Log.i(TAG, "requestNotificationListenerRebind: Requesting system rebind. Reason: $reason")
                requestRebind(componentName)
            } else {
                Log.i(TAG, "requestNotificationListenerRebind: Toggling component to force rebind (legacy). Reason: $reason")
                toggleNotificationListenerComponent(componentName)
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "requestNotificationListenerRebind: requestRebind failed (${e.message}). Falling back to component toggle.")
            toggleNotificationListenerComponent(componentName)
        } catch (e: SecurityException) {
            Log.e(TAG, "requestNotificationListenerRebind: SecurityException during rebind request: ${e.message}", e)
        }
    }

    private fun toggleNotificationListenerComponent(componentName: ComponentName) {
        try {
            val packageManager = applicationContext.packageManager
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.i(TAG, "toggleNotificationListenerComponent: Component toggled to force listener rebind.")
        } catch (e: Exception) {
            Log.e(TAG, "toggleNotificationListenerComponent: Failed to toggle component for rebind: ${e.message}", e)
        }
    }

    private fun hasNotificationAccess(): Boolean {
        return try {
            val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            val componentName = ComponentName(this, javaClass).flattenToString()
            enabledListeners != null && enabledListeners.contains(componentName)
        } catch (e: Exception) {
            Log.w(TAG, "hasNotificationAccess: Unable to determine notification access state: ${e.message}")
            true
        }
    }


    private fun executeFindActiveMediaSessions() {
        if (serviceJob.isCancelled || !isServiceManuallyStarted.get()) {
             Log.w(TAG, "executeFindActiveMediaSessions: Aborting. ServiceJob cancelled or service not manually started. isCancelled=${serviceJob.isCancelled}, isManuallyStarted=${isServiceManuallyStarted.get()}")
             _isAttemptingConnection = false
             return
        }
        Log.d(TAG, "executeFindActiveMediaSessions: Starting media scan. _listenerEverConnected: $_listenerEverConnected. Current token: $currentMediaSessionToken")
        var activeNotificationsInternal: Array<StatusBarNotification>? = null
        try {
            activeNotificationsInternal = this.activeNotifications
            if (!_listenerEverConnected && activeNotificationsInternal != null) {
                Log.i(TAG, "executeFindActiveMediaSessions: Got active notifications, considering listener active for this scan.")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException getting active notifications: ${e.message}. Listener permission might be revoked.")
            _listenerEverConnected = false
            updatePersistentNotification("Error: Check Notification Access.")
            clearSongContextAndHideLyrics()
            requestNotificationListenerRebind("SecurityException while querying active notifications", force = true)
            _isAttemptingConnection = false
            return
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting active notifications: ${e.message}", e)
            updatePersistentNotification("Error accessing notifications. Retrying...")
             _isAttemptingConnection = false
            tryToConnectToActiveMediaSessions(CONNECT_RETRY_DELAY_MS)
            return
        }

        if (activeNotificationsInternal == null) {
            Log.w(TAG, "activeNotifications API returned null. Listener might not be fully bound or permission issue.")
            updatePersistentNotification(if (_listenerEverConnected) "Listener error. Retrying..." else "Waiting for listener connection...")
            if (!_listenerEverConnected) {
                requestNotificationListenerRebind("activeNotifications returned null")
            }
            _isAttemptingConnection = false
            tryToConnectToActiveMediaSessions(CONNECT_RETRY_DELAY_MS * 2)
            if (currentMediaSessionToken != null) clearSongContextAndHideLyrics()
            return
        }


        if (activeNotificationsInternal.isEmpty()) {
            Log.d(TAG, "No active media notifications found (list is empty).")
            updatePersistentNotification(if (_listenerEverConnected) "Waiting for song..." else "Listener connected, waiting for media app...")

            if (currentMediaSessionToken != null) {
                Log.d(TAG, "executeFindActiveMediaSessions: activeNotifications empty, clearing existing song context for token $currentMediaSessionToken.")
                clearSongContextAndHideLyrics()
            }
            _isAttemptingConnection = false
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
                } catch (e: Exception) { null }
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
            updatePersistentNotification(if (_listenerEverConnected) "Waiting for song..." else "Listener connected, waiting for media app...")

            if (currentMediaSessionToken != null) {
                clearSongContextAndHideLyrics()
            }
        }
        _isAttemptingConnection = false
    }


    private fun setupMediaController(token: MediaSession.Token) {
        if (serviceJob.isCancelled || !isServiceManuallyStarted.get()) { Log.w(TAG, "setupMediaController: Aborting. ServiceJob cancelled or service not manually started."); return }
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
                    if (serviceJob.isCancelled || !isServiceManuallyStarted.get()) { Log.w(TAG, "onPlaybackStateChanged: Ignoring."); return }
                    super.onPlaybackStateChanged(state)
                    if (newController.sessionToken != currentMediaSessionToken) {
                        Log.w(TAG, "onPlaybackStateChanged for a stale session (${newController.sessionToken}, pkg: ${newController.packageName}). Current active token is $currentMediaSessionToken. Ignoring.")
                        return
                    }
                    val oldStateValue = this@LyricService.currentPlaybackState?.state
                    this@LyricService.currentPlaybackState = state
                    Log.d(TAG, "onPlaybackStateChanged (for $currentMediaSessionToken): ${stateToString(state)}, Pos: ${state?.position}, Speed: ${state?.playbackSpeed}")

                    if (currentLyricsData is LyricsData.Synced) {
                        if (state?.state == PlaybackState.STATE_PLAYING) {
                            if (oldStateValue != PlaybackState.STATE_PLAYING || lyricsHighlightingJob == null || lyricsHighlightingJob?.isCompleted == true) {
                                startOrUpdateLyricsHighlighting()
                            }
                        } else {
                            lyricsHighlightingJob?.cancel()
                            Log.d(TAG, "Playback not active for $currentMediaSessionToken, cancelling highlighting job.")
                        }
                    }
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    if (serviceJob.isCancelled || !isServiceManuallyStarted.get()) { Log.w(TAG, "onMetadataChanged: Ignoring."); return }
                    super.onMetadataChanged(metadata)
                    if (newController.sessionToken != currentMediaSessionToken) {
                        Log.w(TAG, "onMetadataChanged for a stale session (${newController.sessionToken}, pkg: ${newController.packageName}). Current active token is $currentMediaSessionToken. Ignoring.")
                        return
                    }
                    Log.d(TAG, "onMetadataChanged (for $currentMediaSessionToken): Title: ${metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}")
                    processMediaMetadata(metadata, newController.playbackState, "Callback: MetadataChanged for $currentMediaSessionToken (${newController.packageName})")
                }

                override fun onSessionDestroyed() {
                    if (serviceJob.isCancelled || !isServiceManuallyStarted.get()) { Log.w(TAG, "onSessionDestroyed: Ignoring."); return }
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
        if (serviceJob.isCancelled || !isServiceManuallyStarted.get()) { Log.w(TAG, "processMediaMetadata: Aborting. ServiceJob cancelled or service not manually started."); return }
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
            isShowingTranslatedLyrics = false
            currentLyricsHasTranslation = false

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
                    is LyricsData.Synced -> if (cd.durationMs != newDuration) cd.copy(durationMs = newDuration) else cd
                    is LyricsData.Plain -> if (cd.durationMs != newDuration) cd.copy(durationMs = newDuration) else cd
                    is LyricsData.Info -> if (cd.durationMs != newDuration) cd.copy(durationMs = newDuration) else cd
                    is LyricsData.MismatchInfo -> {
                        val original = cd.originalLyricsData
                        if (original != null && original.durationMs != newDuration) {
                            val updatedOriginal = when (original) {
                                is LyricsData.Synced -> original.copy(durationMs = newDuration)
                                is LyricsData.Plain -> original.copy(durationMs = newDuration)
                                is LyricsData.Info -> original.copy(durationMs = newDuration)
                                else -> original
                            }
                            cd.copy(originalLyricsData = updatedOriginal)
                        } else {
                            cd
                        }
                    }
                    null -> null
                }
                 if (lyricsView != null && currentLyricsData != null) {
                    showLyricsWindow(currentLyricsData!!)
                }
            }
            if (this.currentPlaybackState != playbackState) {
                this.currentPlaybackState = playbackState
            }


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
         if (serviceJob.isCancelled || !isServiceManuallyStarted.get()) { Log.w(TAG, "onNotificationPosted: Ignoring. ServiceJob cancelled or service not manually started."); return }
        _onNotificationPosted(sbn, "SystemCallback: ${sbn.packageName}")
    }
    private fun _onNotificationPosted(sbn: StatusBarNotification, source: String) {
        if (serviceJob.isCancelled || !isServiceManuallyStarted.get()) { Log.w(TAG, "_onNotificationPosted: Ignoring. ServiceJob cancelled or service not manually started."); return }
        Log.d(TAG, "_onNotificationPosted (source: $source, pkg: ${sbn.packageName})")
        lastListenerHeartbeatMs.set(SystemClock.elapsedRealtime())
        if (consecutiveListenerRecoveryAttempts.get() != 0) {
            consecutiveListenerRecoveryAttempts.set(0)
        }
        scheduleListenerHealthCheck(LISTENER_HEALTH_LONG_INTERVAL_MS, "notification from ${sbn.packageName}")
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
        if (serviceJob.isCancelled || !isServiceManuallyStarted.get()) { Log.w(TAG, "onNotificationRemoved: Ignoring. ServiceJob cancelled or service not manually started."); return }
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
        isShowingTranslatedLyrics = false
        currentLyricsHasTranslation = false

        if (activeMediaController != null && (activeMediaController?.sessionToken == tokenThatWasActive || tokenThatWasActive == null)) {
            Log.d(TAG, "clearSongContextAndHideLyrics: Cleaning up activeMediaController for token $tokenThatWasActive (or if no specific token was active).")
            cleanupMediaController()
        }


        this.currentMediaSessionToken = null
        this.currentPlaybackState = null
        if(isServiceManuallyStarted.get()){
            updatePersistentNotification(if (_listenerEverConnected) "Waiting for song..." else "Listener connected, waiting for media app...")
        }
        hideLyricsWindow()
        Log.i(TAG, "Song context cleared. currentMediaSessionToken is now null. Last active token was $tokenThatWasActive.")
    }

    private fun isYouTubeBasedPlayer(packageName: String?): Boolean {
   if (packageName == null) return false
   if (packageName.contains("youtube.music", ignoreCase = true)) return false
   return listOf("youtube", "newpipe", "skytube", "libretube").any { packageName.contains(it, ignoreCase = true) }
}



private fun cleanYouTubeTitleForSearch(title: String): String {
    
    val originalTitle = title

    var cleaned = title.substringBefore("|").trim()

    val bracketRegex = Regex("""\s*[(\[{].*?[)\]}]\s*""")
    cleaned = cleaned.replace(bracketRegex, " ")

    val junkWords = listOf(
        "official music video", "music video", "official video", "official",
        "lyric video", "lyrics", "lyrical",
        "full song", "song",
        "official audio", "audio", "full audio",
        "video", "hd", "4k", "8k", "hq",
        "feat", "ft"
    )
    val junkRegex = Regex("""\b(${junkWords.joinToString("|")})\b""", RegexOption.IGNORE_CASE)
    cleaned = cleaned.replace(junkRegex, "")

    val emojiRegex = Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+")
    cleaned = cleaned.replace(emojiRegex, "")

    cleaned = cleaned.replace(Regex("[-/]"), " ")
    cleaned = cleaned.replace(Regex("\\s{2,}"), " ").trim()

    Log.d(TAG, "Cleaned YouTube title from '$originalTitle' to '$cleaned'")
    return cleaned
}

    private fun fetchAndDisplayLyrics(title: String, artist: String, durationFromMediaMs: Long) {
        if (serviceJob.isCancelled || !isServiceManuallyStarted.get()) { Log.w(TAG, "fetchAndDisplayLyrics: Aborting. ServiceJob cancelled or service not manually started."); return }
        lyricsHighlightingJob?.cancel()
        currentLyricsHasTranslation = false
        isShowingTranslatedLyrics = false

        val tokenForThisFetch = currentMediaSessionToken
        val titleForThisFetch = title
        val artistForThisFetch = artist

        serviceScope.launch(Dispatchers.IO) {
            if (!isActive) { Log.d(TAG, "fetchAndDisplayLyrics: CoroutineScope not active. Aborting."); return@launch}

            if (tokenForThisFetch != currentMediaSessionToken || titleForThisFetch != lastDetectedSongTitle || artistForThisFetch != (lastDetectedSongArtist ?: "")) {
                Log.d(TAG, "fetchAndDisplayLyrics: Context changed before network request for '$titleForThisFetch' by '$artistForThisFetch'. Current: '$lastDetectedSongTitle' by '${lastDetectedSongArtist ?: ""}', Token: $currentMediaSessionToken. Aborting.")
                return@launch
            }
            Log.d(TAG, "Fetching lyrics for '$titleForThisFetch' by '$artistForThisFetch' (Token: $tokenForThisFetch, Media Duration: ${durationFromMediaMs}ms)")

            var fetchedLyricsDataLocal: LyricsData? = null

            val useYouTubeLogic = isYouTubeBasedPlayer(activeMediaController?.packageName)

            try {
                // Try Musixmatch first, unless it's a YouTube-based player or token is missing
                if (!useYouTubeLogic && musixmatchUserToken != null) {
                    Log.d(TAG, "Attempting Musixmatch search for '$titleForThisFetch'")
                    fetchedLyricsDataLocal = searchWithMusixmatch(titleForThisFetch, artistForThisFetch, durationFromMediaMs)
                }

                // Fallback to LRCLib if Musixmatch fails, returns no lyrics, or if it's a YouTube player
                if (fetchedLyricsDataLocal == null || (fetchedLyricsDataLocal is LyricsData.Info && fetchedLyricsDataLocal.message.contains("not found", true))) {
                    if (useYouTubeLogic) Log.d(TAG, "Using LRCLib for YouTube-based player.")
                    else Log.d(TAG, "Musixmatch search failed or no lyrics found. Falling back to LRCLib.")
                    fetchedLyricsDataLocal = searchWithLrcLib(titleForThisFetch, artistForThisFetch, durationFromMediaMs)
                }


                if (!isActive || tokenForThisFetch != currentMediaSessionToken || titleForThisFetch != lastDetectedSongTitle || artistForThisFetch != (lastDetectedSongArtist ?: "")) {
                    Log.d(TAG, "fetchAndDisplayLyrics: Context changed AFTER lyrics processing for '$titleForThisFetch'. Discarding fetched lyrics.")
                    return@launch
                }

                currentLyricsData = fetchedLyricsDataLocal
                currentLyricsHasTranslation = (fetchedLyricsDataLocal as? LyricsData.Synced)?.translatedLines?.isNotEmpty() ?: false

                // Update song duration from lyrics if not present from media player
                if (currentLyricsData != null && currentSongDurationMs <= 0 && currentLyricsData!!.durationMs > 0) {
                    currentSongDurationMs = currentLyricsData!!.durationMs
                }

            } catch (e: Exception) {
                Log.e(TAG, "Exception fetching/processing lyrics for '$titleForThisFetch'", e)
                if (isActive && tokenForThisFetch == currentMediaSessionToken && titleForThisFetch == lastDetectedSongTitle && artistForThisFetch == (lastDetectedSongArtist ?: "")) {
                    currentLyricsData = LyricsData.Info(titleForThisFetch, artistForThisFetch.ifEmpty { null }, "Could not load lyrics.", durationFromMediaMs.takeIf { it > 0 } ?: 0L)
                }
            }

            if (isActive) {
                withContext(Dispatchers.Main.immediate) {
                     if ((!serviceJob.isCancelled && isServiceManuallyStarted.get()) && tokenForThisFetch == currentMediaSessionToken && titleForThisFetch == lastDetectedSongTitle && artistForThisFetch == (lastDetectedSongArtist ?: "")) {
                        currentLyricsData?.let { showLyricsWindow(it) }
                        if (currentLyricsData is LyricsData.Synced &&
                            activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING &&
                            activeMediaController?.sessionToken == tokenForThisFetch) {
                            startOrUpdateLyricsHighlighting()
                        }
                    } else {
                        Log.d(TAG, "Song changed or job cancelled/service stopped before lyrics for '$titleForThisFetch' could be displayed.")
                    }
                }
            }
        }
    }

    private suspend fun searchWithMusixmatch(title: String, artist: String, durationFromMediaMs: Long): LyricsData? {
        val token = musixmatchUserToken ?: return null
        if (title.isBlank() || artist.isBlank()) {
            Log.d(TAG, "Musixmatch search skipped: title or artist is blank.")
            return null
        }

        try {
            val encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8.toString())
            val encodedArtist = URLEncoder.encode(artist, StandardCharsets.UTF_8.toString())
            val url = "$MUSIXMATCH_API_BASE_URL?usertoken=$token&q_track=$encodedTitle&q_artist=$encodedArtist&app_id=mac-ios-v2.0&subtitle_format=json&selected_language=en&part=subtitle_translated"

            Log.d(TAG, "Fetching from Musixmatch: $url")
            val response: MusixmatchLyricsResponse = httpClient.get(url) {
                    header("User-Agent", MUSIXMATCH_USER_AGENT)
                    header("Cookie", MUSIXMATCH_COOKIE)
                }.body()

            val macroCalls = response.message.body.macro_calls
            val subtitlesGet = macroCalls.trackSubtitlesGet?.message?.body
            val lyricsGet = macroCalls.trackLyricsGet?.message?.body

            if (lyricsGet?.lyrics?.instrumental == 1) {
                return LyricsData.Info(title, artist, "This is an instrumental song... 🎵", durationFromMediaMs)
            }

            val subtitleHolder = subtitlesGet?.subtitle_list?.firstOrNull()?.subtitle
            val syncedLrc = subtitleHolder?.subtitle_body
            val translatedLrc = subtitleHolder?.subtitle_translated?.subtitle_body

            if (!syncedLrc.isNullOrBlank()) {
                val timedLines = parseSyncedLyrics(syncedLrc)
                val translatedLines = if (!translatedLrc.isNullOrBlank()) parseSyncedLyrics(translatedLrc) else null

                return if (timedLines.isNotEmpty()) {
                    val linesWithAttribution = timedLines.toMutableList().apply {
                        add(TimedLyricLine(ATTRIBUTION_TIMESTAMP, MUSIXMATCH_ATTRIBUTION))
                    }
                    val translatedWithAttribution = translatedLines?.toMutableList()?.apply {
                        add(TimedLyricLine(ATTRIBUTION_TIMESTAMP, MUSIXMATCH_ATTRIBUTION))
                    }
                    Log.d(TAG, "Found ${timedLines.size} synced lines from Musixmatch for '$title'. Translation available: ${translatedLines != null}")
                    LyricsData.Synced(title, artist, linesWithAttribution, translatedWithAttribution, durationFromMediaMs)
                } else null
            }

            val plainLyrics = lyricsGet?.lyrics?.lyrics_body
            if (!plainLyrics.isNullOrBlank()) {
                Log.d(TAG, "Found plain lyrics from Musixmatch for '$title'")
                return LyricsData.Plain(title, artist, plainLyrics, durationFromMediaMs)
            }

            Log.d(TAG, "No lyrics found on Musixmatch for '$title'")
            return LyricsData.Info(title, artist, "Lyrics not found.", durationFromMediaMs)

        } catch (e: Exception) {
            Log.e(TAG, "Exception during Musixmatch search for '$title'", e)
            return null
        }
    }
    
    private suspend fun searchWithLrcLib(title: String, artist: String, durationFromMediaMs: Long): LyricsData {
        var potentialMismatch = false
        var results: List<LyricResult>? = null
        val isFromYouTube = isYouTubeBasedPlayer(activeMediaController?.packageName)

        try {
            val initialQuery = if (isFromYouTube) {
                cleanYouTubeTitleForSearch(title)
            } else {
                if (artist.isNotBlank()) "$title $artist" else title
            }
            results = searchLrcLib(initialQuery)

            if (results.isNullOrEmpty() && !isFromYouTube) {
                if (artist.length > 10) {
                    Log.d(TAG, "LRCLib: Initial search failed for '$title'. Retrying with artist truncated.")
                    results = searchLrcLib("$title ${artist.take(10)}")
                    if (!results.isNullOrEmpty()) potentialMismatch = true
                }
                if (results.isNullOrEmpty() && title.isNotBlank()) {
                    Log.d(TAG, "LRCLib: Retry 1 failed for '$title'. Retrying with just track name.")
                    results = searchLrcLib(title)
                    if (!results.isNullOrEmpty()) potentialMismatch = true
                }
            }

            val chosenLyricResult: LyricResult?
            if (!results.isNullOrEmpty()) {
                val mediaDurationSec = if (durationFromMediaMs > 0) durationFromMediaMs / 1000.0 else -1.0
                val durationToleranceSec = 2.0
                val selectors = listOf<(LyricResult) -> Boolean>(
                    { !it.syncedLyrics.isNullOrBlank() && mediaDurationSec > 0 && kotlin.math.abs(it.duration - mediaDurationSec) <= durationToleranceSec },
                    { !it.syncedLyrics.isNullOrBlank() },
                    { !it.plainLyrics.isNullOrBlank() && mediaDurationSec > 0 && kotlin.math.abs(it.duration - mediaDurationSec) <= durationToleranceSec },
                    { !it.plainLyrics.isNullOrBlank() }
                )
                chosenLyricResult = selectors.firstNotNullOfOrNull { selector ->
                    results.firstOrNull { r -> !r.instrumental && selector(r) }
                } ?: results.firstOrNull()
            } else {
                chosenLyricResult = null
            }


            if (chosenLyricResult != null) {
                val lyricsApiDurationMs = (chosenLyricResult.duration * 1000).toLong()
                val finalDurationMs = if (durationFromMediaMs > 0) durationFromMediaMs else if (lyricsApiDurationMs > 0) lyricsApiDurationMs else 0L

                val actualContentData: LyricsData = if (chosenLyricResult.instrumental) {
                    LyricsData.Info(title, artist.ifEmpty { null }, "This is an instrumental song... 🎵", finalDurationMs)
                } else if (!chosenLyricResult.syncedLyrics.isNullOrBlank()) {
                    val timedLines = parseSyncedLyrics(chosenLyricResult.syncedLyrics)
                    if (timedLines.isNotEmpty()) {
                        Log.d(TAG, "LRCLib: Successfully parsed ${timedLines.size} synced lines for '$title'.")
                        val linesWithAttribution = timedLines.toMutableList().apply {
                            add(TimedLyricLine(ATTRIBUTION_TIMESTAMP, "Lyrics provided by LRCLib"))
                        }
                        LyricsData.Synced(title, artist.ifEmpty { null }, linesWithAttribution, null, finalDurationMs)
                    } else {
                        LyricsData.Plain(title, artist.ifEmpty { null }, chosenLyricResult.plainLyrics ?: "", finalDurationMs)
                    }
                } else if (!chosenLyricResult.plainLyrics.isNullOrBlank()) {
                    LyricsData.Plain(title, artist.ifEmpty { null }, chosenLyricResult.plainLyrics, finalDurationMs)
                } else {
                    LyricsData.Info(title, artist.ifEmpty { null }, "Lyrics not found (empty content).", finalDurationMs)
                }

                return if (potentialMismatch && actualContentData !is LyricsData.Info &&
                    (chosenLyricResult.trackName.lowercase().trim() != title.lowercase().trim() ||
                            chosenLyricResult.artistName.lowercase().trim() != artist.lowercase().trim().takeIf { it.isNotEmpty() } ?: chosenLyricResult.artistName.lowercase().trim()
                            )) {
                    Log.d(TAG, "LRCLib: Potential mismatch: API ('${chosenLyricResult.trackName}/${chosenLyricResult.artistName}') vs Query ('$title/$artist')")
                    LyricsData.MismatchInfo(title, artist.ifEmpty { null }, actualContentData)
                } else {
                    actualContentData
                }
            } else {
                return LyricsData.Info(title, artist.ifEmpty { null }, "Lyrics not found.", durationFromMediaMs.takeIf { it > 0 } ?: 0L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in LRCLib search for '$title'", e)
            return LyricsData.Info(title, artist.ifEmpty { null }, "Could not load lyrics.", durationFromMediaMs.takeIf { it > 0 } ?: 0L)
        }
    }
    
    private suspend fun searchLrcLib(query: String): List<LyricResult>? {
        if (query.isBlank()) return null
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val url = "$LYRIC_API_BASE_URL?q=$encodedQuery"
        Log.d(TAG, "Fetching lyrics from LRCLib: $url (Query: '$query')")
        return try {
            if (!currentCoroutineContext().isActive) {
                Log.w(TAG, "LRCLib: Coroutine no longer active before network call for '$query'.")
                return null
            }
            httpClient.get(url).body<List<LyricResult>>().also { Log.d(TAG, "LRCLib search for '$query' returned ${it.size} results.") }
        } catch (e: CancellationException) {
            Log.d(TAG, "LRCLib search cancelled for '$query'.")
            throw e
        }
        catch (e: Exception) {
            Log.e(TAG, "LRCLib search exception for '$query': ${e.message}")
            null
        }
    }
    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
    private fun showLyricsWindow(data: LyricsData) {
        if (serviceJob.isCancelled || !isServiceManuallyStarted.get()) { Log.w(TAG, "showLyricsWindow: Aborting. ServiceJob cancelled or service not manually started."); return }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { if (!serviceJob.isCancelled && isServiceManuallyStarted.get()) showLyricsWindow(data) }
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
                 if (actualCurrentData != null && actualCurrentData != data) {
                    showLyricsWindow(actualCurrentData)
                 } else if (actualCurrentData == null) {
                    showLyricsWindow(LyricsData.Info(null, null, if (_listenerEverConnected) "Waiting for song..." else "Connecting listener...", 0L))
                 }
             }
             return
        }

        if (data is LyricsData.Info && data.message.contains("Waiting for song",ignoreCase = true) &&
            lastDetectedSongTitle != null && currentLyricsData != data && currentLyricsData !is LyricsData.Info) {
            currentLyricsData?.let {
                Log.d(TAG, "showLyricsWindow: Was 'Waiting for song', but song '$lastDetectedSongTitle' is active with data. Re-showing with current data: $it")
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
                 lyricsView = null
                 return
            }

            songInfoTextView = lyricsView?.findViewById(R.id.songInfoTextView)
            lyricsRecyclerView = lyricsView?.findViewById(R.id.lyricsRecyclerView)
            expandCollapseButton = lyricsView?.findViewById(R.id.expandCollapseButton)
            translateButton = lyricsView?.findViewById(R.id.translateButton)
            val closeButton = lyricsView?.findViewById<ImageButton>(R.id.closeButton)

            lyricsAdapter = LyricsAdapter(this, emptyList())
            linearLayoutManager = LinearLayoutManager(this)

            lyricsRecyclerView?.layoutManager = linearLayoutManager
            lyricsRecyclerView?.adapter = lyricsAdapter
            lyricsRecyclerView?.itemAnimator = null

            closeButton?.setOnClickListener { hideLyricsWindow() }
            expandCollapseButton?.setOnClickListener { toggleLyricsExpansion() }
            translateButton?.setOnClickListener { toggleTranslation() }
            lyricsView?.setOnTouchListener(ViewMover())

            val overlayFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val savedWindowPosition = loadSavedWindowPosition()
            this.params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                x = savedWindowPosition?.first ?: 0
                y = savedWindowPosition?.second ?: 100
            }

            try {
                if (lyricsView?.isAttachedToWindow == false) {
                    this.windowManager?.addView(lyricsView, this.params)
                    Log.d(TAG, "Lyrics window added to WindowManager.")
                } else if (lyricsView == null) {
                    Log.e(TAG, "lyricsView became null after inflation before addView.")
                    return
                }
                 else {
                    Log.w(TAG, "LyricsView was unexpectedly already attached before initial addView.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding lyrics view to WindowManager: ${e.message}", e)
                this.lyricsView = null
                this.songInfoTextView = null
                this.lyricsRecyclerView = null
                this.expandCollapseButton = null
                this.translateButton = null
                this.lyricsAdapter = null
                return
            }
        } else {
            Log.d(TAG, "Lyrics window already exists. Updating content. Attached: ${lyricsView?.isAttachedToWindow}")
             if (this.windowManager == null || this.params == null) {
                Log.w(TAG, "WindowManager or LayoutParams became null while lyricsView exists. Re-initializing by hiding and showing.")
                hideLyricsWindow()
                showLyricsWindow(data)
                return
            }
        }

        translateButton?.isVisible = currentLyricsHasTranslation

        val songDisplayTitle = when (data) {
            is LyricsData.Synced -> data.artist?.takeIf { it.isNotBlank() }?.let { "${data.title} - $it" } ?: data.title
            is LyricsData.Plain -> data.artist?.takeIf { it.isNotBlank() }?.let { "${data.title} - $it" } ?: data.title
            is LyricsData.Info -> data.title?.takeIf { it.isNotBlank() }?.let { base -> data.artist?.takeIf { art -> art.isNotBlank() }?.let { "$base - $it" } ?: base } ?: data.message
            is LyricsData.MismatchInfo -> data.title?.takeIf { it.isNotBlank() }?.let { t -> data.artist?.takeIf { a -> a.isNotBlank() }?.let { a -> "Potential Mismatch: $t - $a" } ?: "Potential Mismatch: $t"} ?: "Potential song mismatch"
        }
        songInfoTextView?.text = songDisplayTitle

        val prefs = applicationContext.getSharedPreferences(FLUTTER_SHARED_PREFERENCES, Context.MODE_PRIVATE)
        val dynamicColoursEnabled = prefs.getBoolean(PREF_DYNAMIC_LYRICS_WINDOW_COLORS, true)
        val storedBackgroundColor = getStoredColorPreference(
            prefs,
            PREF_LYRICS_WINDOW_BACKGROUND_COLOR,
            DEFAULT_STATIC_BACKGROUND_COLOR
        )
        val storedTitleColor = getStoredColorPreference(
            prefs,
            PREF_LYRICS_WINDOW_TITLE_COLOR,
            DEFAULT_STATIC_TITLE_COLOR
        )
        val storedHighlightColor = getStoredColorPreference(
            prefs,
            PREF_LYRICS_WINDOW_HIGHLIGHT_COLOR,
            DEFAULT_STATIC_HIGHLIGHT_COLOR
        )
        val finalLyricsTextColor = Color.WHITE

        if (!dynamicColoursEnabled) {
            applyThemeToOverlayElements(storedBackgroundColor, storedTitleColor, finalLyricsTextColor, storedHighlightColor)
        } else {
            var finalOverlayBackgroundColor = DEFAULT_STATIC_BACKGROUND_COLOR
            var finalTitleAndIconColor = DEFAULT_STATIC_TITLE_COLOR
            var finalLyricsHighlightBgColor = DEFAULT_STATIC_HIGHLIGHT_COLOR

            val currentActiveMc = activeMediaController
            if (currentActiveMc != null && currentActiveMc.sessionToken == currentMediaSessionToken) {
                currentActiveMc.metadata?.let { metadata ->
                    val albumArtBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

                    if (albumArtBitmap != null) {
                        Palette.from(albumArtBitmap).generate { palette ->
                            palette?.let { p ->
                                var selectedBackgroundColorRgb: Int? = p.dominantSwatch?.rgb
                                if (selectedBackgroundColorRgb == null) {
                                    val fallbackBgSwatch = p.darkVibrantSwatch ?: p.vibrantSwatch ?: p.darkMutedSwatch ?: p.mutedSwatch
                                    selectedBackgroundColorRgb = fallbackBgSwatch?.rgb
                                }

                                val opaqueChosenBgColor = selectedBackgroundColorRgb ?: Color.parseColor("#FF212121")

                                finalOverlayBackgroundColor = selectedBackgroundColorRgb?.let {
                                    ColorUtils.setAlphaComponent(it, 221)
                                } ?: DEFAULT_STATIC_BACKGROUND_COLOR

                                val isBackgroundLight = ColorUtils.calculateLuminance(opaqueChosenBgColor) > 0.5
                                finalTitleAndIconColor = if (isBackgroundLight) {
                                    p.darkVibrantSwatch?.rgb ?: p.darkMutedSwatch?.rgb ?: p.mutedSwatch?.rgb ?: Color.BLACK
                                } else {
                                    p.lightVibrantSwatch?.rgb ?: p.lightMutedSwatch?.rgb ?: p.vibrantSwatch?.rgb ?: Color.WHITE
                                }

                                val contrastTitleBg = ColorUtils.calculateContrast(finalTitleAndIconColor, opaqueChosenBgColor)
                                if (contrastTitleBg < 5.0) {
                                    Log.w(TAG, "Low contrast ($contrastTitleBg) between title color (${Integer.toHexString(finalTitleAndIconColor)}) and OPAQUE BG (${Integer.toHexString(opaqueChosenBgColor)}). Forcing default title color.")
                                    finalTitleAndIconColor = if (isBackgroundLight) Color.BLACK else Color.WHITE
                                }

                                val highlightSwatch = if (isBackgroundLight) {
                                    p.darkMutedSwatch ?: p.darkVibrantSwatch ?: p.mutedSwatch ?: p.vibrantSwatch
                                } else {
                                    p.lightMutedSwatch ?: p.lightVibrantSwatch ?: p.vibrantSwatch ?: p.mutedSwatch
                                }
                                highlightSwatch?.rgb?.let {
                                    finalLyricsHighlightBgColor = ColorUtils.setAlphaComponent(it, 70)
                                }

                                Log.d(TAG, "Palette applied. BG Light: $isBackgroundLight. OverlayBG (translucent): #${Integer.toHexString(finalOverlayBackgroundColor)}, Title/Icon: #${Integer.toHexString(finalTitleAndIconColor)}, LyricHighlightBG: #${Integer.toHexString(finalLyricsHighlightBgColor)}")
                            } ?: Log.d(TAG, "Palette object was null. Using defaults.")
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
            if (dataToDisplayForAdapter.durationMs > 0 && currentSongDurationMs <= 0) {
                currentSongDurationMs = dataToDisplayForAdapter.durationMs
            }
        }

        val finalMessageForInfo = if (mismatchMessage != null && dataToDisplayForAdapter is LyricsData.Info) {
            "$mismatchMessage\n${dataToDisplayForAdapter.message}"
        } else if (dataToDisplayForAdapter is LyricsData.Info) {
            dataToDisplayForAdapter.message
        } else ""


        when(dataToDisplayForAdapter) {
            is LyricsData.Synced -> {
                val linesToShow = if (isShowingTranslatedLyrics) {
                    dataToDisplayForAdapter.translatedLines ?: dataToDisplayForAdapter.lines
                } else {
                    dataToDisplayForAdapter.lines
                }
                Log.d(TAG, "Displaying Synced lyrics: ${linesToShow.size} lines. Translated: $isShowingTranslatedLyrics")
                lyricsAdapter?.updateLyrics(linesToShow, true)
            }
            is LyricsData.Plain -> {
                Log.d(TAG, "Displaying Plain lyrics.")
                val plainLines = dataToDisplayForAdapter.lyrics.lines().mapIndexed { i, t -> TimedLyricLine(i.toLong(), t) }
                val attributionText = if (plainLines.any { it.text.contains(MUSIXMATCH_ATTRIBUTION, ignoreCase = true) }) null else "Lyrics provided by LRCLib"

                val linesWithAttribution = plainLines.toMutableList().apply {
                    attributionText?.let { add(TimedLyricLine(ATTRIBUTION_TIMESTAMP, it)) }
                }
                lyricsAdapter?.updateLyrics(linesWithAttribution, false)
            }
            is LyricsData.Info -> {
                Log.d(TAG, "Displaying Info: $finalMessageForInfo")
                lyricsAdapter?.updateLyrics(listOf(TimedLyricLine(0, finalMessageForInfo)), false)
            }
            is LyricsData.MismatchInfo -> {
                 Log.e(TAG, "Internal error: MismatchInfo was not unwrapped. Displaying error.")
                 lyricsAdapter?.updateLyrics(listOf(TimedLyricLine(0,"Error displaying lyrics (mismatch type).")), false)
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
    private fun applyThemeToOverlayElements(overlayBgColor: Int, titleIconColor: Int, lyricTextColor: Int, lyricHighlightBgColor: Int ) {
        lyricsView?.let { view ->
            val bgDrawable = view.background?.mutate()
            if (bgDrawable is GradientDrawable) {
                bgDrawable.setColor(overlayBgColor)
            } else {
                view.setBackgroundColor(overlayBgColor)
            }
        }

        songInfoTextView?.setTextColor(titleIconColor)
        
        translateButton?.setColorFilter(titleIconColor, PorterDuff.Mode.SRC_IN)
        expandCollapseButton?.setColorFilter(titleIconColor, PorterDuff.Mode.SRC_IN)
        lyricsView?.findViewById<ImageButton>(R.id.closeButton)?.setColorFilter(titleIconColor, PorterDuff.Mode.SRC_IN)

        lyricsAdapter?.updateThemeColors(
            normalLineTextColor = lyricTextColor,
            highlightedLineTextColor = lyricTextColor,
            highlightedLineBackgroundColor = lyricHighlightBgColor
        )
    }
    
    private fun toggleTranslation() {
        if (!currentLyricsHasTranslation) return
        isShowingTranslatedLyrics = !isShowingTranslatedLyrics
        Log.d(TAG, "Toggling translation. Show translated: $isShowingTranslatedLyrics")
        currentLyricsData?.let {
            // Re-call showLyricsWindow to update the adapter with the correct lyric set
            showLyricsWindow(it)
        }
    }
    
    private fun toggleLyricsExpansion() { isLyricsExpanded = !isLyricsExpanded; applyLyricsExpansionState() }
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
            if (isLyricsExpanded) R.drawable.ic_round_keyboard_arrow_up_24
            else R.drawable.ic_round_keyboard_arrow_down_24
        )
    }
    private fun startOrUpdateLyricsHighlighting() {
        if (serviceJob.isCancelled || !isServiceManuallyStarted.get()) { Log.w(TAG, "startOrUpdateLyricsHighlighting: Aborting. ServiceJob cancelled or service not manually started."); return }
        lyricsHighlightingJob?.cancel()
        val dataForHighlighting = this.currentLyricsData
        val controllerForHighlighting = this.activeMediaController
        val tokenForHighlighting = this.currentMediaSessionToken

        if (controllerForHighlighting == null || controllerForHighlighting.sessionToken != tokenForHighlighting ||
            dataForHighlighting !is LyricsData.Synced) {
            Log.d(TAG, "Not starting highlighter: Conditions not met. MC Valid: ${controllerForHighlighting != null}, Token Match: ${controllerForHighlighting?.sessionToken == tokenForHighlighting}, Data is Synced: ${dataForHighlighting is LyricsData.Synced}")
            return
        }
        if (dataForHighlighting.title != lastDetectedSongTitle || dataForHighlighting.artist != lastDetectedSongArtist) {
            Log.w(TAG, "Highlighting attempted for '${dataForHighlighting.title}/${dataForHighlighting.artist}' but current song context is '$lastDetectedSongTitle/$lastDetectedSongArtist'. Aborting.")
            return
        }

        val lines = if (isShowingTranslatedLyrics) {
            dataForHighlighting.translatedLines ?: dataForHighlighting.lines
        } else {
            dataForHighlighting.lines
        }
        if (lines.isEmpty()) {
            Log.d(TAG, "Not starting highlighter: selected line set is empty.")
            return
        }
        val songTotalDuration = if (dataForHighlighting.durationMs > 0) dataForHighlighting.durationMs else currentSongDurationMs

        lyricsHighlightingJob = serviceScope.launch(Dispatchers.Main) {
            if (!isActive) {Log.d(TAG, "LyricsHighlightingJob: Coroutine not active at start. Exiting."); return@launch}
            Log.d(TAG, "LyricsHighlightingJob: Started for '${dataForHighlighting.title}' (Token: $tokenForHighlighting). Lines: ${lines.size}, Duration: $songTotalDuration ms")
            var lastHighlightedIndex = -1

            while (isActive) {
                if(!isServiceManuallyStarted.get()) {
                    Log.d(TAG, "HighlightingJob: Service no longer manually started. Stopping job.")
                    break
                }
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
                for (i in lines.indices.reversed()) {
                    if (lines[i].timestamp != ATTRIBUTION_TIMESTAMP && lines[i].timestamp <= currentPositionMs) {
                        currentLineIndex = i
                        break
                    }
                }


                if (currentLineIndex != lastHighlightedIndex) {
                    Log.v(TAG, "Highlighting ($tokenForHighlighting): Pos ${currentPositionMs}ms. Line $currentLineIndex: '${lines.getOrNull(currentLineIndex)?.text?.take(30)}...'")
                    lyricsAdapter?.setHighlight(currentLineIndex)
                    lastHighlightedIndex = currentLineIndex

                    if (currentLineIndex != -1 && ::linearLayoutManager.isInitialized && lyricsRecyclerView?.isAttachedToWindow == true) {
                        val smoothScroller = object : LinearSmoothScroller(applicationContext) {
                            override fun getVerticalSnapPreference(): Int = LinearSmoothScroller.SNAP_TO_ANY

                            override fun calculateDyToMakeVisible(view: View, snapPreference: Int): Int {
                                val layoutManager = this.layoutManager ?: return super.calculateDyToMakeVisible(view, snapPreference)
                                if (!layoutManager.canScrollVertically()) {
                                    return 0
                                }
                                val params = view.layoutParams as RecyclerView.LayoutParams
                                val top = layoutManager.getDecoratedTop(view) - params.topMargin
                                val bottom = layoutManager.getDecoratedBottom(view) + params.bottomMargin
                                val parentTop = layoutManager.paddingTop
                                val parentBottom = layoutManager.height - layoutManager.paddingBottom
                                val viewCenter = top + (bottom - top) / 2
                                val parentCenter = parentTop + (parentBottom - parentTop) / 2
                                return parentCenter - viewCenter
                            
    }

                            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
                                return 90f / displayMetrics.densityDpi
                            }
                        }
                        smoothScroller.targetPosition = currentLineIndex.coerceAtLeast(0)

                        val firstVis = linearLayoutManager.findFirstVisibleItemPosition()
                        val lastVis = linearLayoutManager.findLastVisibleItemPosition()
                        val itemCount = lyricsAdapter?.itemCount ?: 0

                        var shouldScrollManually = true

                        if (itemCount > 0 && firstVis != RecyclerView.NO_POSITION && lastVis != RecyclerView.NO_POSITION) {
                             val targetView = linearLayoutManager.findViewByPosition(currentLineIndex)
                             if (targetView != null && lyricsRecyclerView != null) {
                                val parentCenterY = lyricsRecyclerView!!.height / 2
                                val viewTop = linearLayoutManager.getDecoratedTop(targetView)
                                val viewBottom = linearLayoutManager.getDecoratedBottom(targetView)
                                val viewCenterY = viewTop + (viewBottom - viewTop) / 2
                                val scrollThresholdPx = (20 * resources.displayMetrics.density).toInt()

                                if (kotlin.math.abs(viewCenterY - parentCenterY) < scrollThresholdPx) {
                                    shouldScrollManually = false
                                }
                             }
                        }

                        if (shouldScrollManually) {
                            if (itemCount > 0) {
                                linearLayoutManager.startSmoothScroll(smoothScroller)
                            }
                        }
                    }
                }
                if (songTotalDuration > 0 && currentPositionMs > songTotalDuration + 2000) {
                    Log.d(TAG, "Highlighting ($tokenForHighlighting): Song duration ($songTotalDuration ms) passed by ${currentPositionMs - songTotalDuration}ms. Stopping job.")
                    if (lastHighlightedIndex != -1) lyricsAdapter?.setHighlight(-1)
                    break
                }
                delay(HIGHLIGHT_UPDATE_INTERVAL_MS)
            }
            Log.d(TAG,"LyricsHighlightingJob: Ended/cancelled for '${dataForHighlighting.title}' (Token: $tokenForHighlighting). IsActive: $isActive")
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

        persistWindowPositionIfEnabled()

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
                this.translateButton = null
                this.lyricsAdapter = null
                Log.d(TAG, "Lyrics UI components nulled after hide.")
            }
        } else {
            Log.d(TAG, "hideLyricsWindow: lyricsView or windowManager was already null. No action needed.")
        }
    }

    private fun loadSavedWindowPosition(): Pair<Int, Int>? {
        return try {
            val prefs = applicationContext.getSharedPreferences(FLUTTER_SHARED_PREFERENCES, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PREF_REMEMBER_WINDOW_POSITION, false)) {
                null
            } else if (!prefs.contains(PREF_REMEMBER_WINDOW_POSITION_X) || !prefs.contains(PREF_REMEMBER_WINDOW_POSITION_Y)) {
                null
            } else {
                val savedPosition = Pair(
                    prefs.getInt(PREF_REMEMBER_WINDOW_POSITION_X, 0),
                    prefs.getInt(PREF_REMEMBER_WINDOW_POSITION_Y, 100)
                )
                Log.d(TAG, "Loaded saved lyrics window position: x=${savedPosition.first}, y=${savedPosition.second}")
                savedPosition
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load saved lyrics window position.", e)
            null
        }
    }

    private fun persistWindowPositionIfEnabled() {
        val currentParams = this.params ?: return
        try {
            val prefs = applicationContext.getSharedPreferences(FLUTTER_SHARED_PREFERENCES, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PREF_REMEMBER_WINDOW_POSITION, false)) {
                return
            }
            prefs.edit()
                .putInt(PREF_REMEMBER_WINDOW_POSITION_X, currentParams.x)
                .putInt(PREF_REMEMBER_WINDOW_POSITION_Y, currentParams.y)
                .apply()
            Log.d(TAG, "Persisted lyrics window position: x=${currentParams.x}, y=${currentParams.y}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist lyrics window position.", e)
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
                if (matcher.find() && matcher.start() == 1) {
                    try {
                        val minutes = matcher.group(1)!!.toInt()
                        val seconds = matcher.group(2)!!.toInt()
                        val millisStr = matcher.group(4)!!
                        val textFollowingTag = matcher.group(5)!!

                        val milliseconds = when {
                            millisStr.length == 2 -> millisStr.toInt() * 10
                            else -> millisStr.toInt()
                        }
                        val timestamp = TimeUnit.MINUTES.toMillis(minutes.toLong()) +
                                        TimeUnit.SECONDS.toMillis(seconds.toLong()) +
                                        milliseconds.toLong()

                        tempLinesForThisPhysicalLine.add(TimedLyricLine(timestamp, ""))

                        currentTextSegment = textFollowingTag.trimStart()
                    } catch (e: NumberFormatException) {
                        Log.w(TAG, "LRC timestamp number format error for part of line: '$lineContent'. Tag content: '${matcher.group(0)}'", e)
                        currentTextSegment = ""
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Generic LRC timestamp parse error for part of line: '$lineContent'. Matcher group 0: '${matcher.group(0)}'", e)
                        currentTextSegment = ""
                        break
                    }
                } else {
                    break
                }
            }

            if (tempLinesForThisPhysicalLine.isNotEmpty()) {
                val finalLyricTextForTags = currentTextSegment.trim()
                val displayText = if (finalLyricTextForTags.isBlank()) "🎶 ... 🎶" else finalLyricTextForTags
                tempLinesForThisPhysicalLine.forEach { timedLinePlaceholder ->
                    lines.add(timedLinePlaceholder.copy(text = displayText))
                }
            }
        }

        if (lines.isEmpty() && syncedLyricsText.isNotBlank()) {
            if (!syncedLyricsText.trimStart().startsWith("[")) {
                 Log.w(TAG, "No LRC tags found and content does not start with '['. Treating as plain text. Preview: ${syncedLyricsText.take(100)}")
                 return syncedLyricsText.lines().mapIndexedNotNull { index, textLine ->
                    val trimmedText = textLine.trim()
                    if (trimmedText.isNotBlank()) TimedLyricLine(index * 2000L, trimmedText) else null
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

        val showAction = NotificationCompat.Action.Builder(R.drawable.ic_visibility, getString(R.string.lyric_service_action_show), PendingIntent.getService(this, 0, showLyricsIntent, pendingIntentFlags)).build()
        val hideAction = NotificationCompat.Action.Builder(R.drawable.ic_visibility_off, getString(R.string.lyric_service_action_hide), PendingIntent.getService(this, 1, hideLyricsIntent, pendingIntentFlags)).build()
        val notificationSettingsIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        val fixAccessAction = NotificationCompat.Action.Builder(
            R.drawable.ic_settings_24,
            getString(R.string.lyric_service_action_fix_notification_access),
            PendingIntent.getActivity(this, 3, notificationSettingsIntent, pendingIntentFlags)
        ).build()

        val contentIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pContentIntent = PendingIntent.getActivity(this, 2, contentIntent, pendingIntentFlags)

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setOngoing(true)
            .setContentIntent(pContentIntent)
            .addAction(showAction)
            .addAction(hideAction)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (!hasNotificationAccess()) {
            builder.addAction(fixAccessAction)
        }
        return builder.build()
    }
    private fun updatePersistentNotification(text: String) {
         if (serviceJob.isCancelled || !isServiceManuallyStarted.get()) {
            Log.d(TAG, "updatePersistentNotification: Skipped update. serviceJob Cancelled: ${serviceJob.isCancelled}, Not Manually Started: ${!isServiceManuallyStarted.get()}. Text: $text")
            return
        }
        Log.d(TAG, "Updating persistent notification: $text")
        notificationManager.notify(NOTIFICATION_ID, createPersistentNotification(text))
    }

    private fun scheduleListenerHealthCheck(delayMs: Long, reason: String, preferSooner: Boolean = false) {
        if (!isServiceManuallyStarted.get()) {
            Log.d(TAG, "scheduleListenerHealthCheck: Skipping schedule because service is not manually started. Reason: $reason")
            cancelListenerHealthChecks("scheduleListenerHealthCheck skipped")
            return
        }

        val safeDelay = if (delayMs >= 0L) delayMs else LISTENER_HEALTH_SHORT_INTERVAL_MS
        val now = SystemClock.elapsedRealtime()
        val targetTime = now + safeDelay

        while (true) {
            val existingTarget = nextListenerHealthCheckAtMs.get()
            if (existingTarget != 0L) {
                if (preferSooner && existingTarget <= targetTime) {
                    Log.d(TAG, "scheduleListenerHealthCheck: Existing check is sooner (${existingTarget - now}ms). Keeping current schedule. Reason: $reason")
                    return
                }
                if (!preferSooner && existingTarget == targetTime) {
                    Log.d(TAG, "scheduleListenerHealthCheck: Existing check already scheduled at same time. Reason: $reason")
                    return
                }
            }

            if (nextListenerHealthCheckAtMs.compareAndSet(existingTarget, targetTime)) {
                listenerHealthHandler.removeCallbacks(listenerHealthCheckRunnable)
                listenerHealthHandler.postDelayed(listenerHealthCheckRunnable, safeDelay)
                Log.d(TAG, "scheduleListenerHealthCheck: Scheduled health check in ${safeDelay}ms (target=${targetTime - now}ms). Reason: $reason")
                return
            }
        }
    }

    private fun cancelListenerHealthChecks(reason: String) {
        listenerHealthHandler.removeCallbacks(listenerHealthCheckRunnable)
        nextListenerHealthCheckAtMs.set(0L)
        Log.d(TAG, "cancelListenerHealthChecks: Cancelled pending checks. Reason: $reason")
    }

    private fun evaluateListenerHealth() {
        if (serviceJob.isCancelled || !isServiceManuallyStarted.get()) {
            Log.d(TAG, "evaluateListenerHealth: Service inactive. Cancelling health monitoring.")
            cancelListenerHealthChecks("evaluateListenerHealth inactive")
            return
        }

        nextListenerHealthCheckAtMs.set(0L)
        val now = SystemClock.elapsedRealtime()
        var nextDelay = LISTENER_HEALTH_LONG_INTERVAL_MS

        if (!hasNotificationAccess()) {
            handleMissingNotificationAccess("Health check detected missing notification access")
            consecutiveListenerRecoveryAttempts.set(0)
            nextDelay = LISTENER_HEALTH_SHORT_INTERVAL_MS
        } else if (!_listenerEverConnected) {
            maybeShowStatusInfo("Waiting for notification listener connection…")
            updatePersistentNotification("Waiting for notification listener...")
            requestNotificationListenerRebind("Health check: listener never connected")
            tryToConnectToActiveMediaSessions(CONNECT_RETRY_DELAY_MS)
            val attempts = consecutiveListenerRecoveryAttempts.incrementAndGet()
            if (attempts >= MAX_CONSECUTIVE_RECOVERY_ATTEMPTS) {
                scheduleServiceRestart("Listener never connected after $attempts health checks")
                consecutiveListenerRecoveryAttempts.set(0)
            }
            nextDelay = LISTENER_HEALTH_SHORT_INTERVAL_MS
        } else {
            val heartbeat = lastListenerHeartbeatMs.get()
            val elapsedSinceHeartbeat = if (heartbeat == 0L) Long.MAX_VALUE else now - heartbeat
            if (elapsedSinceHeartbeat >= LISTENER_STALE_NOTIFICATION_THRESHOLD_MS) {
                maybeShowStatusInfo("Refreshing notification listener…")
                updatePersistentNotification("Refreshing notification listener...")
                requestNotificationListenerRebind("Health check: listener idle for ${if (elapsedSinceHeartbeat == Long.MAX_VALUE) "unknown" else "$elapsedSinceHeartbeat ms"}")
                tryToConnectToActiveMediaSessions(CONNECT_RETRY_DELAY_MS)
                val attempts = consecutiveListenerRecoveryAttempts.incrementAndGet()
                if (attempts >= MAX_CONSECUTIVE_RECOVERY_ATTEMPTS) {
                    scheduleServiceRestart("Listener idle for ${if (elapsedSinceHeartbeat == Long.MAX_VALUE) "unknown" else "$elapsedSinceHeartbeat ms"} after $attempts recovery attempts")
                    consecutiveListenerRecoveryAttempts.set(0)
                }
                nextDelay = LISTENER_HEALTH_SHORT_INTERVAL_MS
            } else {
                consecutiveListenerRecoveryAttempts.set(0)
                nextDelay = LISTENER_HEALTH_LONG_INTERVAL_MS
            }
        }

        scheduleListenerHealthCheck(nextDelay, "evaluateListenerHealth scheduled next", preferSooner = false)
    }

    private fun handleMissingNotificationAccess(reason: String) {
        Log.w(TAG, "handleMissingNotificationAccess: Notification access missing. Reason: $reason")
        updatePersistentNotification("Notification access missing. Tap to fix.")
        maybeShowStatusInfo("Notification access missing. Tap the notification to re-enable.")
    }

    private fun maybeShowStatusInfo(message: String) {
        if (!isServiceManuallyStarted.get()) return

        val currentData = currentLyricsData
        if (currentData is LyricsData.Info && currentData.message == message) {
            return
        }

        val isWindowVisible = lyricsView?.isVisible == true
        val shouldShowInfo = currentData == null || currentData is LyricsData.Info || isWindowVisible
        if (shouldShowInfo) {
            showLyricsWindow(
                LyricsData.Info(
                    lastDetectedSongTitle,
                    lastDetectedSongArtist,
                    message,
                    currentSongDurationMs
                )
            )
        }
    }

    private fun scheduleServiceRestart(reason: String) {
        if (!hasNotificationAccess()) {
            Log.w(TAG, "scheduleServiceRestart: Skipping restart because notification access is missing. Reason: $reason")
            handleMissingNotificationAccess("scheduleServiceRestart($reason)")
            return
        }

        Handler(Looper.getMainLooper()).post {
            try {
                val restartIntent = Intent(applicationContext, LyricService::class.java).apply {
                    action = ACTION_USER_INITIATED_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(restartIntent)
                } else {
                    @Suppress("DEPRECATION")
                    applicationContext.startService(restartIntent)
                }
                Log.i(TAG, "scheduleServiceRestart: Requested restart. Reason: $reason")
            } catch (e: Exception) {
                Log.e(TAG, "scheduleServiceRestart: Failed to restart service: ${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        val wasManuallyStarted = isServiceManuallyStarted.get()
        Log.i(TAG, "Service onDestroy(). Instance: ${this.hashCode()}. isServiceManuallyStarted: $wasManuallyStarted. Current token: $currentMediaSessionToken. ServiceJob Active: ${serviceJob.isActive}")
        isServiceManuallyStarted.set(false)

        if (serviceJob.isActive) { // Check if active before cancelling
            Log.d(TAG, "onDestroy: serviceJob was still active, cancelling.")
            serviceJob.cancel()
        }
        if (lyricsView != null) { // Check if view exists before trying to hide
            Log.d(TAG, "onDestroy: lyricsView was not null, attempting to hide.")
            hideLyricsWindow()
        }
        if (activeMediaController != null) { // Check if controller exists
             Log.d(TAG, "onDestroy: activeMediaController was not null, attempting to cleanup.")
            cleanupMediaController()
        }
        currentMediaSessionToken = null

        cancelListenerHealthChecks("onDestroy")
        Log.i(TAG, "Service fully destroyed. Instance: ${this.hashCode()}")
        super.onDestroy()

        if (wasManuallyStarted) {
            scheduleServiceRestart("Service destroyed while marked as manually started")
        }
    }

    private inner class ViewMover : View.OnTouchListener {
        private var initialX: Int = 0; private var initialY: Int = 0
        private var initialTouchX: Float = 0f; private var initialTouchY: Float = 0f
        private val touchSlop by lazy { android.view.ViewConfiguration.get(applicationContext).scaledTouchSlop }
        private var isDragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val currentParams = this@LyricService.params ?: return false
            val currentWindowManager = this@LyricService.windowManager ?: return false
            val currentLyricsView = this@LyricService.lyricsView ?: return false

            if (!currentLyricsView.isAttachedToWindow) return false

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
                        persistWindowPositionIfEnabled()
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = isDragging
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    isDragging = false
                    if (wasDragging) {
                        persistWindowPositionIfEnabled()
                    }
                    return false
                }
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