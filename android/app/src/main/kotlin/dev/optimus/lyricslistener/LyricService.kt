package dev.optimus.lyricslistener

import android.app.NotificationChannel
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.view.WindowManager
import android.graphics.PixelFormat
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.view.MotionEvent
import android.view.ViewGroup
import android.app.PendingIntent
import android.provider.Settings
import android.widget.ImageButton
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
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
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import androidx.recyclerview.widget.LinearSmoothScroller
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import kotlinx.serialization.SerialName
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.selects.select
import android.widget.ImageView


class LyricService : NotificationListenerService() {

    private val TAG = "LyricService"
    private val NOTIFICATION_CHANNEL_ID = "LyricServiceChannel"
    private val NOTIFICATION_ID = 1
    private val HIGHLIGHT_UPDATE_INTERVAL_MS = 200L
    private val CONNECT_RETRY_DELAY_MS = 2500L
    private val CONNECT_FAST_RETRY_DELAY_MS = 1000L
    private val CONNECT_FAST_RETRY_ATTEMPTS = 2
    private val SONG_TRANSITION_CLEAR_GRACE_MS = 750L
    private val MIN_REBIND_INTERVAL_MS = 8000L
    private val INITIAL_BIND_REBIND_INTERVAL_MS = 2500L
    private val LISTENER_HEALTH_SHORT_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15)
    private val LISTENER_HEALTH_LONG_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1)
    private val LISTENER_STALE_NOTIFICATION_THRESHOLD_MS = TimeUnit.MINUTES.toMillis(1)
    private val MAX_CONSECUTIVE_RECOVERY_ATTEMPTS = 3
    private val LISTENER_COMPONENT_RESET_DELAY_MS = TimeUnit.SECONDS.toMillis(6)
    private val LISTENER_COMPONENT_RESET_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30)
    private val LISTENER_COMPONENT_REENABLE_DELAY_MS = 350L

    @Volatile private var windowManager: WindowManager? = null
    @Volatile private var lyricsView: View? = null
    @Volatile private var params: WindowManager.LayoutParams? = null
    // Keep track of every overlay instance so we can clean up a residual window even if the main reference drifts.
    private val knownLyricsOverlayViews = mutableSetOf<View>()
    private var lyricsRecyclerView: RecyclerView? = null
    private var songInfoTextView: TextView? = null
    private var expandCollapseButton: ImageButton? = null
    private var translateButton: ImageButton? = null
    private var overlayAlbumArtImageView: ImageView? = null
    private var overlayScrimView: View? = null
    private var headerDivider: View? = null
    private var overlayContentLayout: ConstraintLayout? = null
    private var miniTouchInterceptor: RecyclerView.OnItemTouchListener? = null
    private var viewMover: ViewMover? = null
    private var lyricsAdapter: LyricsAdapter? = null
    private lateinit var linearLayoutManager: LinearLayoutManager

    private var lastDetectedSongTitle: String? = null
    private var lastDetectedSongArtist: String? = null
    @Volatile private var currentLyricsData: LyricsData? = null
    private var currentSongDurationMs: Long = 0L

    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var lyricsHighlightingJob: Job? = null

    private fun normalizeSongStringForComparison(value: String?): String {
        return value?.trim()?.lowercase()?.replace(Regex("\\s+"), " ") ?: ""
    }

    private fun matchesCurrentSongContext(title: String?, artist: String?): Boolean {
        val normalizedCurrentTitle = normalizeSongStringForComparison(lastDetectedSongTitle)
        if (normalizedCurrentTitle.isEmpty()) return false

        val normalizedIncomingTitle = normalizeSongStringForComparison(title)
        if (normalizedIncomingTitle.isEmpty()) return false

        val normalizedCurrentArtist = normalizeSongStringForComparison(lastDetectedSongArtist)
        val normalizedIncomingArtist = normalizeSongStringForComparison(artist)

        return normalizedIncomingTitle == normalizedCurrentTitle &&
                normalizedIncomingArtist == normalizedCurrentArtist
    }

    private fun matchesCurrentSongContextForLyrics(data: LyricsData?): Boolean {
        if (data == null) return false

        val title = when (data) {
            is LyricsData.Plain -> data.title
            is LyricsData.Synced -> data.title
            is LyricsData.Info -> data.title
            is LyricsData.MismatchInfo -> data.title
        }
        val artist = when (data) {
            is LyricsData.Plain -> data.artist
            is LyricsData.Synced -> data.artist
            is LyricsData.Info -> data.artist
            is LyricsData.MismatchInfo -> data.artist
        }

        if (matchesCurrentSongContext(title, artist)) {
            return true
        }

        if (!isCustomSourcedLyrics(data)) {
            return false
        }

        val relaxedMatch = matchesCurrentSongContextRelaxedForCustom(title, artist)
        if (relaxedMatch) {
            Log.d(
                TAG,
                "Accepted relaxed current-song guard for custom lyrics: incoming='$title/$artist', current='$lastDetectedSongTitle/$lastDetectedSongArtist'"
            )
        }
        return relaxedMatch
    }

    private fun isCustomSourcedLyrics(data: LyricsData?): Boolean {
        return when (data) {
            is LyricsData.Synced -> {
                val allLines = buildList {
                    addAll(data.lines)
                    data.translatedLines?.let { addAll(it) }
                }
                allLines.any { line ->
                    line.timestamp == ATTRIBUTION_TIMESTAMP &&
                        line.text.contains("Lyrics provided by custom", ignoreCase = true)
                }
            }
            is LyricsData.MismatchInfo -> isCustomSourcedLyrics(data.originalLyricsData)
            else -> false
        }
    }

    private fun matchesCurrentSongContextRelaxedForCustom(title: String?, artist: String?): Boolean {
        val normalizedCurrentTitle = normalizeSongStringForComparison(lastDetectedSongTitle)
        val normalizedIncomingTitle = normalizeSongStringForComparison(title)
        if (normalizedCurrentTitle.isEmpty() || normalizedIncomingTitle.isEmpty()) {
            return false
        }

        if (!areCustomGuardTitlesSimilar(normalizedIncomingTitle, normalizedCurrentTitle)) {
            return false
        }

        val normalizedCurrentArtist = normalizeSongStringForComparison(lastDetectedSongArtist)
        val normalizedIncomingArtist = normalizeSongStringForComparison(artist)
        if (normalizedCurrentArtist.isEmpty() || normalizedIncomingArtist.isEmpty()) {
            return true
        }

        if (normalizedIncomingArtist == normalizedCurrentArtist) {
            return true
        }

        val currentPrimary = normalizeSongStringForComparison(extractPrimaryArtistForSearch(normalizedCurrentArtist))
        val incomingPrimary = normalizeSongStringForComparison(extractPrimaryArtistForSearch(normalizedIncomingArtist))

        if (currentPrimary.isNotEmpty() && incomingPrimary.isNotEmpty()) {
            if (currentPrimary == incomingPrimary) return true
            if (currentPrimary.length >= 3 && incomingPrimary.contains(currentPrimary)) return true
            if (incomingPrimary.length >= 3 && currentPrimary.contains(incomingPrimary)) return true
        }

        return false
    }

    private fun areCustomGuardTitlesSimilar(incomingTitle: String, currentTitle: String): Boolean {
        if (incomingTitle == currentTitle) return true

        if (incomingTitle.length >= 6 && currentTitle.contains(incomingTitle)) return true
        if (currentTitle.length >= 6 && incomingTitle.contains(currentTitle)) return true

        val incomingWords = incomingTitle
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .toSet()
        val currentWords = currentTitle
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .toSet()

        if (incomingWords.isEmpty() || currentWords.isEmpty()) return false

        val overlap = incomingWords.intersect(currentWords).size
        val minSize = minOf(incomingWords.size, currentWords.size)
        return overlap >= 2 && overlap.toDouble() / minSize >= 0.7
    }

    private val lastListenerRebindAttempt = AtomicLong(0L)
    private val lastListenerHeartbeatMs = AtomicLong(0L)
    private val listenerUnboundSinceMs = AtomicLong(0L)
    private val lastListenerComponentResetAttemptMs = AtomicLong(0L)
    private val consecutiveListenerRecoveryAttempts = AtomicInteger(0)
    private val scanRetryAttempt = AtomicInteger(0)
    private val nextListenerHealthCheckAtMs = AtomicLong(0L)
    private val lastNotificationAccessDebugLogMs = AtomicLong(0L)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val listenerHealthHandler by lazy { Handler(Looper.getMainLooper()) }
    private val listenerHealthCheckRunnable = Runnable { evaluateListenerHealth() }
    private var pendingSongContextClearRunnable: Runnable? = null

    @Volatile private var activeMediaController: MediaController? = null
    private var mediaControllerCallback: MediaController.Callback? = null
    @Volatile private var currentMediaSessionToken: MediaSession.Token? = null
    private var currentPlaybackState: PlaybackState? = null

    private enum class LyricsWindowMode { MINI, COMPACT, EXPANDED }
    private var lyricsWindowMode = LyricsWindowMode.COMPACT
    private var defaultLyricsWindowMode = LyricsWindowMode.COMPACT
    private var lyricsWindowModeExpanding = true // direction: true = expanding, false = collapsing
    private val COLLAPSED_LYRICS_MAX_HEIGHT_DP = 100
    private lateinit var notificationManager: NotificationManager
    private val EXPANDED_LYRICS_MAX_HEIGHT_DP = 300
    private val OVERLAY_ARTWORK_ZOOM = 1.25f
    private val OVERLAY_ARTWORK_VERTICAL_BIAS = 0.08f
    private val overlayArtworkColorFilter by lazy {
        val saturationMatrix = ColorMatrix().apply { setSaturation(1.2f) }
        val brightnessMatrix = ColorMatrix(
            floatArrayOf(
                0.5f, 0f, 0f, 0f, 0f,
                0f, 0.5f, 0f, 0f, 0f,
                0f, 0f, 0.5f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        saturationMatrix.postConcat(brightnessMatrix)
        ColorMatrixColorFilter(saturationMatrix)
    }

    private enum class LyricsProvider {
        MUSIXMATCH,
        CUSTOM,
        LRCLIB
    }

    private data class ProviderLyricsResult(
        val provider: LyricsProvider,
        val data: LyricsData?,
        val cacheSource: String? = null
    )


    @Volatile private var _listenerEverConnected = false
    @Volatile private var _listenerCurrentlyBound = false
    @Volatile private var _isAttemptingConnection = false
    @Volatile private var wasHiddenByPausePreference = false

    @Volatile private var currentNotificationText: String? = null
    @Volatile private var pendingDebugNotificationReset = false
    
    // Musixmatch related properties
    private var musixmatchUserToken: String? = null
    private var isShowingTranslatedLyrics = false
    private var currentLyricsHasTranslation = false

    // Lyrics cache manager
    private lateinit var cacheManager: LyricsCacheManager


    // Musixmatch API Data Classes
    @Serializable data class MusixmatchTokenResponse(val message: MusixmatchTokenMessage)
    @Serializable data class MusixmatchTokenMessage(val body: MusixmatchTokenBody)
    @Serializable data class MusixmatchTokenBody(val user_token: String)

    @Serializable data class MusixmatchLyricsResponse(val message: MMXMessage)
    @Serializable data class MMXMessage(val body: MMXBody)
    @Serializable data class MMXBody(val macro_calls: MacroCalls)

    @Serializable data class MacroCalls(
        @SerialName("track.lyrics.get") val trackLyricsGet: MMXTrackLyricsGet? = null,
        @SerialName("track.subtitles.get") val trackSubtitlesGet: MMXTrackSubtitlesGet? = null,
        @SerialName("matcher.track.get") val matcherTrackGet: MMXMatcherTrackGet? = null
    )

    @Serializable data class MMXMatcherTrackGet(val message: MMXMatcherTrackGetMessage? = null)
    @Serializable data class MMXMatcherTrackGetMessage(val body: MMXMatcherTrackBody? = null)
    @Serializable data class MMXMatcherTrackBody(val track: MMXMatchedTrack? = null)
    @Serializable data class MMXMatchedTrack(val track_length: Int = 0)

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

    @Serializable
    data class CustomLyricsResponse(
        val song: String? = null,
        val artist: String? = null,
        val source: String? = null,
        val durationMs: Long? = null,
        val lrc: List<CustomLyricsLine>? = null
    )

    @Serializable
    data class CustomLyricsLine(
        val time: String? = null,
        val timeMs: Long? = null,
        val text: String? = null
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
        const val ACTION_DEBUG_ACTIVE_NOTIFICATION = "dev.optimus.lyricslistener.ACTION_DEBUG_ACTIVE_NOTIFICATION"
        const val ACTION_DEBUG_REFRESH_MUSIXMATCH_TOKEN = "dev.optimus.lyricslistener.ACTION_DEBUG_REFRESH_MUSIXMATCH_TOKEN"
        private const val DEBUG_NOTIFICATION_TEXT = "Debugging media notification..."
        private const val ENABLED_NOTIFICATION_LISTENERS_KEY = "enabled_notification_listeners"
        const val ATTRIBUTION_TIMESTAMP = -999L

        private const val LYRIC_API_BASE_URL = "https://lrclib.net/api/search"
        private const val PROVIDER_DURATION_TOLERANCE_MS = 3000L
        private const val CUSTOM_LYRIC_API_BASE_URL = "https://lyrics-hiwayajcyq-uw.a.run.app/"
        private val LRC_LINE_PATTERN: Pattern = Pattern.compile("(?<=\\[)(\\d{2,}):(\\d{2})([.:])(\\d{2,3})\\](.*)")

        private const val FLUTTER_SHARED_PREFERENCES = "FlutterSharedPreferences"
        private const val PREF_REMEMBER_WINDOW_POSITION = "flutter.remember_window_position"
        private const val PREF_REMEMBER_WINDOW_POSITION_X = "flutter.remember_window_position_x"
        private const val PREF_REMEMBER_WINDOW_POSITION_Y = "flutter.remember_window_position_y"
        private const val PREF_DYNAMIC_LYRICS_WINDOW_COLORS = "flutter.lyrics_window_dynamic_colors"
        private const val PREF_SIMULATE_LEGACY_OVERLAY = "flutter.simulate_legacy_overlay"
        private const val PREF_LYRICS_WINDOW_TITLE_COLOR = "flutter.lyrics_window_title_color"
    private const val PREF_LYRICS_WINDOW_BACKGROUND_COLOR = "flutter.lyrics_window_background_color"
    private const val PREF_LYRICS_WINDOW_HIGHLIGHT_COLOR = "flutter.lyrics_window_highlight_color"
    private const val PREF_HIDE_WINDOW_ON_PAUSE = "flutter.hide_lyrics_window_on_pause"
    private const val PREF_LYRICS_WINDOW_SIZE = "flutter.lyrics_window_size"

    private val DEFAULT_STATIC_TITLE_COLOR = Color.parseColor("#F0E8E8E8")
    private val DEFAULT_STATIC_BACKGROUND_COLOR = Color.parseColor("#E6181818")
    private val DEFAULT_STATIC_HIGHLIGHT_COLOR = Color.argb(85, 200, 200, 200)

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
        const val MUSIXMATCH_ATTRIBUTION_CACHE = "Lyrics provided by Musixmatch (cached)"


        var isServiceManuallyStarted = AtomicBoolean(false)
            private set
        val isMusixmatchTokenAvailableForDebug = AtomicBoolean(false)
        /** Masked form of the current token (first/last 4 chars) for the debug screen. */
        val musixmatchTokenPreviewForDebug = AtomicReference<String?>(null)
        /** Bumped after every token fetch attempt so the debug screen can wait for an outcome. */
        val musixmatchTokenFetchSeq = AtomicInteger(0)
        /** Error of the most recent token fetch attempt, or null if it succeeded. */
        val musixmatchTokenLastFetchError = AtomicReference<String?>(null)

        fun maskMusixmatchToken(token: String): String =
            if (token.length <= 8) "*".repeat(token.length) else "${token.take(4)}…${token.takeLast(4)}"
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
        startForegroundWithNotification("Initializing...")
        defaultLyricsWindowMode = loadDefaultLyricsWindowMode()
        lyricsWindowMode = defaultLyricsWindowMode
        lyricsWindowModeExpanding = true
        _listenerEverConnected = false
        _listenerCurrentlyBound = false
        _isAttemptingConnection = false
        consecutiveListenerRecoveryAttempts.set(0)
        lastListenerHeartbeatMs.set(SystemClock.elapsedRealtime())

        // Initialize lyrics cache
        cacheManager = LyricsCacheManager(this)

        setMusixmatchUserToken(null)
        initializeMusixmatchToken("service create")
    }

    private fun markListenerConnected(reason: String) {
        listenerUnboundSinceMs.set(0L)
        if (!_listenerCurrentlyBound) {
            _listenerCurrentlyBound = true
            Log.i(TAG, "markListenerConnected: Listener is currently bound ($reason).")
        }
        if (!_listenerEverConnected) {
            _listenerEverConnected = true
            Log.i(TAG, "markListenerConnected: Listener considered connected ($reason).")
        }
    }

    private fun markListenerUnbound(reason: String) {
        val now = SystemClock.elapsedRealtime()
        val previousUnboundSince = listenerUnboundSinceMs.getAndUpdate { existing ->
            if (existing == 0L) now else existing
        }

        if (_listenerCurrentlyBound) {
            _listenerCurrentlyBound = false
            Log.w(TAG, "refreshListenerBindingState: Listener no longer appears bound ($reason).")
        } else if (previousUnboundSince == 0L) {
            Log.w(TAG, "Notification listener service not yet bound.")
        }
    }

    private fun isListenerBoundBySystem(): Boolean {
        return try {
            currentInterruptionFilter != INTERRUPTION_FILTER_UNKNOWN
        } catch (e: Exception) {
            Log.w(TAG, "isListenerBoundBySystem: Unable to read interruption filter: ${e.message}")
            false
        }
    }

    private fun refreshListenerBindingState(reason: String): Boolean {
        val isBound = isListenerBoundBySystem()
        if (isBound) {
            markListenerConnected(reason)
        } else {
            markListenerUnbound(reason)
        }
        return isBound
    }

    private fun cancelPendingSongContextClear(reason: String) {
        val pendingRunnable = pendingSongContextClearRunnable ?: return
        mainHandler.removeCallbacks(pendingRunnable)
        pendingSongContextClearRunnable = null
        Log.d(TAG, "cancelPendingSongContextClear: Cancelled pending clear. Reason: $reason")
    }

    private fun scheduleSongContextClearAndHideLyrics(reason: String) {
        if (!isServiceManuallyStarted.get()) return

        val expectedToken = currentMediaSessionToken
        val expectedTitle = lastDetectedSongTitle
        val expectedArtist = lastDetectedSongArtist

        cancelPendingSongContextClear("reschedule for $reason")

        lateinit var runnable: Runnable
        runnable = Runnable {
            if (pendingSongContextClearRunnable !== runnable) {
                return@Runnable
            }
            pendingSongContextClearRunnable = null

            val tokenUnchanged = currentMediaSessionToken == expectedToken
            val titleUnchanged = lastDetectedSongTitle == expectedTitle
            val artistUnchanged = lastDetectedSongArtist == expectedArtist

            if (!tokenUnchanged || !titleUnchanged || !artistUnchanged) {
                Log.d(
                    TAG,
                    "scheduleSongContextClearAndHideLyrics: Skipping delayed clear for $reason because context changed to '$lastDetectedSongTitle'/'$lastDetectedSongArtist' ($currentMediaSessionToken)."
                )
                return@Runnable
            }

            Log.i(TAG, "scheduleSongContextClearAndHideLyrics: Executing delayed clear. Reason: $reason")
            clearSongContextAndHideLyrics()
        }

        pendingSongContextClearRunnable = runnable
        mainHandler.postDelayed(runnable, SONG_TRANSITION_CLEAR_GRACE_MS)
        Log.d(
            TAG,
            "scheduleSongContextClearAndHideLyrics: Scheduled clear in ${SONG_TRANSITION_CLEAR_GRACE_MS}ms. Reason: $reason. Token=$expectedToken, Title='$expectedTitle'"
        )
    }

    private fun startForegroundWithNotification(text: String) {
        currentNotificationText = text
        Log.d(TAG, "startForegroundWithNotification: $text")
        startForeground(NOTIFICATION_ID, createPersistentNotification(text))
    }

    private fun maybeResetDebugNotification(reason: String) {
        if (!pendingDebugNotificationReset) return
        pendingDebugNotificationReset = false
        if (currentNotificationText == DEBUG_NOTIFICATION_TEXT) {
            Log.d(TAG, "maybeResetDebugNotification: Resetting debug notification. Reason: $reason")
            updatePersistentNotification(buildStatusNotificationText())
        } else {
            Log.d(TAG, "maybeResetDebugNotification: Debug already replaced. Reason: $reason")
        }
    }
    
    private fun setMusixmatchUserToken(token: String?) {
        musixmatchUserToken = token
        isMusixmatchTokenAvailableForDebug.set(token != null)
        musixmatchTokenPreviewForDebug.set(token?.let { maskMusixmatchToken(it) })
    }

    /**
     * Fetches a fresh Musixmatch user token. On success it replaces the current one;
     * on failure the existing token (if any) is kept so a transient error can't drop it.
     */
    private fun initializeMusixmatchToken(reason: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Attempting to get Musixmatch user token. Reason: $reason")
                val response: MusixmatchTokenResponse = httpClient.get(MUSIXMATCH_TOKEN_URL) {
                    header("User-Agent", MUSIXMATCH_USER_AGENT)
                    header("Cookie", MUSIXMATCH_COOKIE)
                }.body()
                val token = response.message.body.user_token
                if (token.isNotBlank()) {
                    val replaced = musixmatchUserToken != null
                    setMusixmatchUserToken(token)
                    musixmatchTokenLastFetchError.set(null)
                    Log.i(TAG, "Successfully acquired Musixmatch user token (${maskMusixmatchToken(token)}). Replaced existing: $replaced")
                } else {
                    musixmatchTokenLastFetchError.set("Token response was blank")
                    Log.w(TAG, "Musixmatch token response was blank. Keeping existing token: ${musixmatchUserToken != null}")
                }
            } catch (e: Exception) {
                musixmatchTokenLastFetchError.set(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
                Log.e(TAG, "Failed to get Musixmatch user token. Keeping existing token: ${musixmatchUserToken != null}", e)
            } finally {
                musixmatchTokenFetchSeq.incrementAndGet()
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
                startForegroundWithNotification("Service starting...")
                _listenerEverConnected = false
                _listenerCurrentlyBound = false
                _isAttemptingConnection = false
                listenerUnboundSinceMs.set(0L)
                lastListenerRebindAttempt.set(0L)
                lastListenerComponentResetAttemptMs.set(0L)
                clearSongContextAndHideLyrics()
                scanRetryAttempt.set(0)
                requestNotificationListenerRebind("User initiated start", force = true)
                if (!refreshListenerBindingState("user initiated start")) {
                    // requestRebind() only re-registers a listener that was snoozed via requestUnbind(),
                    // so a listener dropped by a process kill needs the component toggled to reconnect.
                    Log.w(TAG, "Notification listener not bound at user start. Forcing component reset to reconnect.")
                    maybeResetNotificationListenerComponent("User initiated start while listener unbound", force = true)
                }
                if (musixmatchUserToken == null) initializeMusixmatchToken("user initiated start")
                tryToConnectToActiveMediaSessions(delayMs = 0L)
            }
            ACTION_SHOW_LYRICS -> {
                Log.d(TAG, "ACTION_SHOW_LYRICS received.")
                startForegroundWithNotification(buildStatusNotificationText())
                val dataToShow = currentLyricsData ?: LyricsData.Info(
                    lastDetectedSongTitle, lastDetectedSongArtist,
                    if (lastDetectedSongTitle != null) "Loading lyrics..." else if (refreshListenerBindingState("ACTION_SHOW_LYRICS status")) "Waiting for song..." else "Connecting listener...",
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
                wasHiddenByPausePreference = false
                hideLyricsWindow()
                updatePersistentNotification(buildStatusNotificationText())
            }
            ACTION_DEBUG_ACTIVE_NOTIFICATION -> {
                Log.i(TAG, "ACTION_DEBUG_ACTIVE_NOTIFICATION received. Running active media scan for debug.")
                pendingDebugNotificationReset = true
                startForegroundWithNotification(DEBUG_NOTIFICATION_TEXT)
                tryToConnectToActiveMediaSessions(delayMs = 0L)
            }
            ACTION_DEBUG_REFRESH_MUSIXMATCH_TOKEN -> {
                Log.i(TAG, "ACTION_DEBUG_REFRESH_MUSIXMATCH_TOKEN received. Requesting a new Musixmatch user token.")
                startForegroundWithNotification(buildStatusNotificationText())
                initializeMusixmatchToken("debug regenerate")
            }
            else -> {
                Log.i(TAG, "Service (re)started with null or unhandled action (Intent: $intent, Action: ${intent?.action}). _listenerEverConnected: $_listenerEverConnected")
                startForegroundWithNotification(
                    if (_listenerEverConnected && currentMediaSessionToken != null) "Service active. Last: $lastDetectedSongTitle"
                    else if (_listenerEverConnected) "Service active. Scanning..."
                    else "Service starting...")
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
        wasHiddenByPausePreference = false
        cancelPendingSongContextClear("performStopActions")
        mainHandler.removeCallbacks(executeFindActiveMediaSessionsRunnable)
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
        setMusixmatchUserToken(null)

        _listenerEverConnected = false
        _listenerCurrentlyBound = false
        _isAttemptingConnection = false
        listenerUnboundSinceMs.set(0L)
        lastListenerComponentResetAttemptMs.set(0L)


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

        markListenerConnected("onListenerConnected")
        _isAttemptingConnection = false
        consecutiveListenerRecoveryAttempts.set(0)
        scanRetryAttempt.set(0)
        lastListenerHeartbeatMs.set(SystemClock.elapsedRealtime())
        Log.i(TAG, "Notification Listener connected by system. (Instance: ${this.hashCode()})")
        updatePersistentNotification("Listener connected, scanning media...")
        tryToConnectToActiveMediaSessions(delayMs = 0L)
        scheduleListenerHealthCheck(LISTENER_HEALTH_LONG_INTERVAL_MS, "onListenerConnected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _listenerEverConnected = false
        _listenerCurrentlyBound = false
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

        mainHandler.removeCallbacks(executeFindActiveMediaSessionsRunnable)
        mainHandler.postDelayed(executeFindActiveMediaSessionsRunnable, delayMs)
    }

    /** Delay before re-running the media scan after a failed attempt: quick first, then back off. */
    private fun nextScanRetryDelayMs(slowMultiplier: Int = 1): Long {
        val attempt = scanRetryAttempt.getAndIncrement()
        return if (attempt < CONNECT_FAST_RETRY_ATTEMPTS) CONNECT_FAST_RETRY_DELAY_MS else CONNECT_RETRY_DELAY_MS * slowMultiplier
    }

    private fun currentListenerRebindIntervalMs(): Long {
        return if (!_listenerEverConnected || !_listenerCurrentlyBound) {
            INITIAL_BIND_REBIND_INTERVAL_MS
        } else {
            MIN_REBIND_INTERVAL_MS
        }
    }

    private fun maybeResetNotificationListenerComponent(reason: String, force: Boolean = false) {
        if (!isServiceManuallyStarted.get()) return
        if (!hasNotificationAccess()) return

        val now = SystemClock.elapsedRealtime()
        if (force) {
            lastListenerComponentResetAttemptMs.set(now)
        } else {
            val unboundSince = listenerUnboundSinceMs.get()
            if (unboundSince == 0L) return

            if (now - unboundSince < LISTENER_COMPONENT_RESET_DELAY_MS) {
                return
            }

            val lastResetAttempt = lastListenerComponentResetAttemptMs.get()
            if (now - lastResetAttempt < LISTENER_COMPONENT_RESET_INTERVAL_MS) {
                return
            }
            if (!lastListenerComponentResetAttemptMs.compareAndSet(lastResetAttempt, now)) {
                return
            }
        }

        val componentName = ComponentName(this, javaClass)
        val packageManager = packageManager

        Log.w(TAG, "Forcing notification listener component reset. Reason: $reason")

        try {
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable notification listener component during recovery: ${e.message}", e)
            return
        }

        mainHandler.postDelayed({
            try {
                packageManager.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                lastListenerRebindAttempt.set(0L)
                _listenerCurrentlyBound = false
                requestNotificationListenerRebind("Component reset recovery: $reason", force = true)
                tryToConnectToActiveMediaSessions(nextScanRetryDelayMs())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-enable notification listener component during recovery: ${e.message}", e)
            }
        }, LISTENER_COMPONENT_REENABLE_DELAY_MS)
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
            val minIntervalMs = currentListenerRebindIntervalMs()
            if (now - lastAttempt < minIntervalMs) {
                Log.d(TAG, "requestNotificationListenerRebind: Recent attempt ${now - lastAttempt}ms ago. Skipping. Reason: $reason")
                maybeResetNotificationListenerComponent("rebind throttled: $reason")
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
            Log.i(TAG, "requestNotificationListenerRebind: Requesting system rebind. Reason: $reason")
            requestRebind(componentName)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "requestNotificationListenerRebind: requestRebind failed (${e.message}).")
            handleMissingNotificationAccess("requestRebind failed: ${e.message}")
        } catch (e: SecurityException) {
            Log.e(TAG, "requestNotificationListenerRebind: SecurityException during rebind request: ${e.message}", e)
            handleMissingNotificationAccess("requestRebind SecurityException")
        }
    }

    private fun hasNotificationAccess(): Boolean {
        return try {
            val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(this)
            if (enabledPackages.contains(packageName)) {
                return true
            }

            val enabledListeners = Settings.Secure.getString(contentResolver, ENABLED_NOTIFICATION_LISTENERS_KEY)
            if (enabledListeners.isNullOrBlank()) {
                maybeLogNotificationAccessDebug(
                    reason = "hasNotificationAccess=false (enabled_listeners empty)",
                    enabledPackages = enabledPackages,
                    enabledListeners = enabledListeners
                )
                return false
            }

            val componentName = ComponentName(this, javaClass)
            val flat = componentName.flattenToString()
            val short = componentName.flattenToShortString()
            val hasAccess = enabledListeners.contains(flat) || enabledListeners.contains(short)
            if (!hasAccess) {
                maybeLogNotificationAccessDebug(
                    reason = "hasNotificationAccess=false (component not found)",
                    enabledPackages = enabledPackages,
                    enabledListeners = enabledListeners,
                    componentName = componentName
                )
            }
            hasAccess
        } catch (e: Exception) {
            Log.w(TAG, "hasNotificationAccess: Unable to determine notification access state: ${e.message}")
            true
        }
    }


    private fun executeFindActiveMediaSessions() {
        try {
            if (serviceJob.isCancelled || !isServiceManuallyStarted.get()) {
                Log.w(TAG, "executeFindActiveMediaSessions: Aborting. ServiceJob cancelled or service not manually started. isCancelled=${serviceJob.isCancelled}, isManuallyStarted=${isServiceManuallyStarted.get()}")
                return
            }
            Log.d(TAG, "executeFindActiveMediaSessions: Starting media scan. _listenerEverConnected: $_listenerEverConnected. Current token: $currentMediaSessionToken")

            if (!refreshListenerBindingState("executeFindActiveMediaSessions start")) {
                Log.w(TAG, "executeFindActiveMediaSessions: Listener is not currently bound. Requesting recovery before media scan.")
                updatePersistentNotification("Waiting for notification listener...")
                requestNotificationListenerRebind("Listener not bound before media scan")
                maybeResetNotificationListenerComponent("Listener not bound before media scan")
                _isAttemptingConnection = false
                tryToConnectToActiveMediaSessions(nextScanRetryDelayMs())
                if (currentMediaSessionToken != null) clearSongContextAndHideLyrics()
                return
            }

            var activeNotificationsInternal: Array<StatusBarNotification>? = null
            try {
                activeNotificationsInternal = this.activeNotifications
                if (activeNotificationsInternal != null) {
                    if (!_listenerEverConnected) {
                        Log.i(TAG, "executeFindActiveMediaSessions: Got active notifications, considering listener active for this scan.")
                    }
                    markListenerConnected("activeNotifications accessible")
                    scanRetryAttempt.set(0)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException getting active notifications: ${e.message}. Listener permission might be revoked.")
                _listenerEverConnected = false
                updatePersistentNotification("Error: Check Notification Access.")
                clearSongContextAndHideLyrics()
                requestNotificationListenerRebind("SecurityException while querying active notifications", force = true)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Exception getting active notifications: ${e.message}", e)
                updatePersistentNotification("Error accessing notifications. Retrying...")
                _isAttemptingConnection = false
                tryToConnectToActiveMediaSessions(nextScanRetryDelayMs())
                return
            }

            if (activeNotificationsInternal == null) {
                Log.w(TAG, "activeNotifications API returned null. Listener might not be fully bound or permission issue.")
                updatePersistentNotification(if (_listenerEverConnected) "Listener error. Retrying..." else "Waiting for listener connection...")
                if (!_listenerEverConnected) {
                    requestNotificationListenerRebind("activeNotifications returned null")
                }
                _isAttemptingConnection = false
                tryToConnectToActiveMediaSessions(nextScanRetryDelayMs(slowMultiplier = 2))
                if (currentMediaSessionToken != null) clearSongContextAndHideLyrics()
                return
            }

            if (activeNotificationsInternal.isEmpty()) {
                val listenerStillBound = refreshListenerBindingState("activeNotifications empty")
                if (!listenerStillBound) {
                    Log.w(TAG, "executeFindActiveMediaSessions: activeNotifications was empty because the listener is no longer bound.")
                    updatePersistentNotification("Waiting for notification listener...")
                    requestNotificationListenerRebind("activeNotifications empty while listener unbound")
                    maybeResetNotificationListenerComponent("activeNotifications empty while listener unbound")
                    _isAttemptingConnection = false
                    tryToConnectToActiveMediaSessions(nextScanRetryDelayMs())
                    if (currentMediaSessionToken != null) clearSongContextAndHideLyrics()
                    return
                }

                Log.d(TAG, "No active media notifications found (list is empty).")
                updatePersistentNotification(buildStatusNotificationText())

                if (currentMediaSessionToken != null) {
                    Log.d(TAG, "executeFindActiveMediaSessions: activeNotifications empty, scheduling delayed clear for token $currentMediaSessionToken.")
                    scheduleSongContextClearAndHideLyrics("activeNotifications empty while listener still bound")
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
                updatePersistentNotification(buildStatusNotificationText())

                if (currentMediaSessionToken != null) {
                    clearSongContextAndHideLyrics()
                }
            }
        } finally {
            _isAttemptingConnection = false
            maybeResetDebugNotification("executeFindActiveMediaSessions finished")
        }
    }


    private fun setupMediaController(token: MediaSession.Token) {
        if (serviceJob.isCancelled || !isServiceManuallyStarted.get()) { Log.w(TAG, "setupMediaController: Aborting. ServiceJob cancelled or service not manually started."); return }
        cancelPendingSongContextClear("setupMediaController($token)")
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
                    registerActivity()
                    if (newController.sessionToken != currentMediaSessionToken) {
                        Log.w(TAG, "onPlaybackStateChanged for a stale session (${newController.sessionToken}, pkg: ${newController.packageName}). Current active token is $currentMediaSessionToken. Ignoring.")
                        return
                    }
                    val oldStateValue = this@LyricService.currentPlaybackState?.state
                    this@LyricService.currentPlaybackState = state
                    Log.d(TAG, "onPlaybackStateChanged (for $currentMediaSessionToken): ${stateToString(state)}, Pos: ${state?.position}, Speed: ${state?.playbackSpeed}")

                    val nowPlaying = state?.state == PlaybackState.STATE_PLAYING

                    // Update adapter playing state so instrumental visualizers animate correctly
                    mainHandler.post {
                        lyricsAdapter?.setPlayingState(nowPlaying)
                    }

                    // Hide/show lyrics window on pause if the preference is enabled
                    if (oldStateValue != state?.state) {
                        val hideOnPause = try {
                            applicationContext.getSharedPreferences(FLUTTER_SHARED_PREFERENCES, Context.MODE_PRIVATE)
                                .getBoolean(PREF_HIDE_WINDOW_ON_PAUSE, false)
                        } catch (_: Exception) { false }

                        if (!nowPlaying && hideOnPause && lyricsView != null) {
                            Log.d(TAG, "Hide-on-pause: hiding lyrics window (state=${stateToString(state)})")
                            wasHiddenByPausePreference = true
                            hideLyricsWindow()
                        } else if (nowPlaying && lyricsView == null && (hideOnPause || wasHiddenByPausePreference)) {
                            Log.d(TAG, "Hide-on-pause: re-showing lyrics window (state=${stateToString(state)}, hiddenByPausePref=$wasHiddenByPausePreference)")
                            currentLyricsData?.let {
                                showLyricsWindow(it)
                                wasHiddenByPausePreference = false
                            }
                        }
                    }

                    if (currentLyricsData is LyricsData.Synced) {
                        if (nowPlaying) {
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
                    registerActivity()
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
                        Log.i(TAG, "MediaSession Destroyed for active token $currentMediaSessionToken (pkg: ${newController.packageName}). Scheduling delayed cleanup.")
                        scheduleSongContextClearAndHideLyrics("active session destroyed for ${newController.packageName}")
                    } else {
                        Log.w(TAG, "MediaSession Destroyed for a token ${newController.sessionToken} (pkg: ${newController.packageName}) that is NOT the current active token ($currentMediaSessionToken).")
                         if (activeMediaController != null && activeMediaController?.sessionToken == newController.sessionToken) {
                            Log.d(TAG, "The destroyed session's controller was indeed our activeMediaController. Cleaning it up, but not clearing full song context unless currentMediaSessionToken matches.")
                            cleanupMediaController()
                        }
                    }
                }
            }
            newController.registerCallback(mediaControllerCallback!!, mainHandler)

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
        if (!newTitle.isNullOrBlank()) {
            cancelPendingSongContextClear("metadata update for '$newTitle'")
        }

        if (newTitle.isNullOrBlank() && metadata != null) {
            Log.d(TAG, "Media metadata ($source) missing title. Not processing as new song.")
            if (lastDetectedSongTitle != null && (activeMediaController?.sessionToken == currentMediaSessionToken || currentMediaSessionToken == null)) {
                 Log.d(TAG, "Title became null/blank for current session $currentMediaSessionToken, previously was '$lastDetectedSongTitle'. Scheduling delayed clear.")
                 scheduleSongContextClearAndHideLyrics("metadata title became blank")
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

            // Re-apply colors when metadata changes (e.g., album art loads after cached lyrics shown)
            if (lyricsView != null && currentLyricsData != null && metadata != null) {
                val albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                if (albumArt != null) {
                    Log.d(TAG, "Album art available, re-applying colors for already-shown lyrics")
                    updateLyricsWindowColors()
                }
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
        cancelPendingSongContextClear("notification posted from ${sbn.packageName}")
        markListenerConnected("onNotificationPosted from ${sbn.packageName}")
        registerActivity()
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

                val newSbnIsActive = newSbnPlaybackState?.state == PlaybackState.STATE_PLAYING ||
                        newSbnPlaybackState?.state == PlaybackState.STATE_BUFFERING
                val currentServiceIsActive = this.currentPlaybackState?.state == PlaybackState.STATE_PLAYING ||
                        this.currentPlaybackState?.state == PlaybackState.STATE_BUFFERING

                val shouldSwitch = when {
                    newSbnIsActive -> true
                    currentServiceIsActive -> false
                    currentMediaSessionToken == null -> true
                    else -> false
                }


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
        cancelPendingSongContextClear("clearSongContextAndHideLyrics immediate")
        val tokenThatWasActive = currentMediaSessionToken
        Log.i(TAG, "clearSongContextAndHideLyrics: Starting. Was for token: $tokenThatWasActive, Title: $lastDetectedSongTitle")

        wasHiddenByPausePreference = false
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
            updatePersistentNotification(buildStatusNotificationText())
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

    private fun extractPrimaryArtistForSearch(artist: String): String {
        return artist
            .split(
                Regex(
                    ",|feat\\.?|ft\\.?|featuring|&|\\||\\s+x\\s+|\\band\\b|\\bwith\\b|\\bvs\\.?\\b",
                    RegexOption.IGNORE_CASE
                )
            )
            .firstOrNull()
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()
    }


    private fun isValidInitialProviderResult(data: LyricsData?): Boolean {
        return when (data) {
            null -> false
            is LyricsData.Plain -> data.lyrics.isNotBlank()
            is LyricsData.Synced -> data.lines.any { it.timestamp != ATTRIBUTION_TIMESTAMP && it.text.isNotBlank() }
            is LyricsData.MismatchInfo -> isValidInitialProviderResult(data.originalLyricsData)
            is LyricsData.Info -> data.message.contains("instrumental", ignoreCase = true)
        }
    }

    private fun stripAttributionLines(lines: List<TimedLyricLine>?): List<TimedLyricLine>? {
        return lines?.filter { it.timestamp != ATTRIBUTION_TIMESTAMP }
    }

    private fun formatLyricsAttribution(source: String?, cached: Boolean = false): String {
        val sourceLabel = when (source?.trim()?.lowercase()) {
            "musixmatch" -> "Musixmatch"
            "lrclib" -> "LRCLib"
            null, "" -> "Unknown"
            else -> source!!.trim()
        }
        return if (cached) {
            "Lyrics provided by $sourceLabel (cached)"
        } else {
            "Lyrics provided by $sourceLabel"
        }
    }

    private fun cacheConcurrentWinnerIfEligible(
        providerResult: ProviderLyricsResult,
        requestedTitle: String,
        requestedArtist: String,
        requestedDurationMs: Long
    ) {
        when (providerResult.provider) {
            LyricsProvider.MUSIXMATCH -> {
                when (val data = providerResult.data) {
                    is LyricsData.Synced -> {
                        val linesToCache = stripAttributionLines(data.lines).orEmpty()
                        if (linesToCache.isNotEmpty()) {
                            val translatedLinesToCache = stripAttributionLines(data.translatedLines)
                            val syncedToCache = LyricsData.Synced(
                                title = requestedTitle,
                                artist = requestedArtist,
                                lines = linesToCache,
                                translatedLines = translatedLinesToCache,
                                durationMs = requestedDurationMs
                            )
                            cacheManager.put(
                                requestedArtist,
                                requestedTitle,
                                requestedDurationMs,
                                syncedToCache,
                                providerResult.cacheSource ?: "musixmatch"
                            )
                        }
                    }
                    is LyricsData.Plain -> {
                        if (data.lyrics.isNotBlank()) {
                            val plainToCache = LyricsData.Plain(
                                title = requestedTitle,
                                artist = requestedArtist,
                                lyrics = data.lyrics,
                                durationMs = requestedDurationMs
                            )
                            cacheManager.put(
                                requestedArtist,
                                requestedTitle,
                                requestedDurationMs,
                                plainToCache,
                                providerResult.cacheSource ?: "musixmatch"
                            )
                        }
                    }
                    else -> Unit
                }
            }
            LyricsProvider.CUSTOM -> {
                when (val data = providerResult.data) {
                    is LyricsData.Synced -> {
                        val linesToCache = stripAttributionLines(data.lines).orEmpty()
                        if (linesToCache.isNotEmpty()) {
                            val durationToCache = if (data.durationMs > 0) data.durationMs else requestedDurationMs
                            val syncedToCache = LyricsData.Synced(
                                title = requestedTitle,
                                artist = requestedArtist.ifEmpty { null },
                                lines = linesToCache,
                                translatedLines = null,
                                durationMs = durationToCache
                            )
                            cacheManager.put(
                                requestedArtist.ifEmpty { null },
                                requestedTitle,
                                durationToCache,
                                syncedToCache,
                                providerResult.cacheSource ?: "custom"
                            )
                        }
                    }
                    else -> Unit
                }
            }
            LyricsProvider.LRCLIB -> {
                when (val data = providerResult.data) {
                    is LyricsData.Synced -> {
                        val linesToCache = stripAttributionLines(data.lines).orEmpty()
                        if (linesToCache.isNotEmpty()) {
                            val durationToCache = if (data.durationMs > 0) data.durationMs else requestedDurationMs
                            val syncedToCache = LyricsData.Synced(
                                title = requestedTitle,
                                artist = requestedArtist.ifEmpty { null },
                                lines = linesToCache,
                                translatedLines = null,
                                durationMs = durationToCache
                            )
                            cacheManager.put(
                                requestedArtist.ifEmpty { null },
                                requestedTitle,
                                durationToCache,
                                syncedToCache,
                                providerResult.cacheSource ?: "lrclib"
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun fetchFirstValidLyricsFromConcurrentProviders(
        title: String,
        artist: String,
        durationFromMediaMs: Long
    ): LyricsData? = coroutineScope {
        val pendingRequests = mutableSetOf<Deferred<ProviderLyricsResult>>()

        if (musixmatchUserToken != null) {
            pendingRequests.add(
                async(Dispatchers.IO) {
                    ProviderLyricsResult(
                        provider = LyricsProvider.MUSIXMATCH,
                        data = searchWithMusixmatch(
                            title = title,
                            artist = artist,
                            durationFromMediaMs = durationFromMediaMs,
                            allowCacheWrite = false
                        ),
                        cacheSource = "musixmatch"
                    )
                }
            )
        }

        pendingRequests.add(
            async(Dispatchers.IO) {
                searchWithCustomLyricsApi(
                    title = title,
                    artist = artist,
                    durationFromMediaMs = durationFromMediaMs,
                    allowCacheWrite = false
                )
            }
        )

        pendingRequests.add(async(Dispatchers.IO) {
            ProviderLyricsResult(
                provider = LyricsProvider.LRCLIB,
                data = searchWithLrcLib(
                    title = title,
                    artist = artist,
                    durationFromMediaMs = durationFromMediaMs,
                    enableFallbackRetries = false,
                    allowCacheWrite = false
                ),
                cacheSource = "lrclib"
            )
        })
        // Plain (unsynced) lyrics are only used if no provider delivers a synced result.
        var heldPlainResult: ProviderLyricsResult? = null

        while (pendingRequests.isNotEmpty()) {
            val completedRequestAndResult = select<Pair<Deferred<ProviderLyricsResult>, ProviderLyricsResult>> {
                pendingRequests.forEach { request ->
                    request.onAwait { result -> request to result }
                }
            }

            val completedRequest = completedRequestAndResult.first
            val providerResult = completedRequestAndResult.second
            pendingRequests.remove(completedRequest)

            if (!isValidInitialProviderResult(providerResult.data)) {
                Log.d(
                    TAG,
                    "Initial concurrent fetch from ${providerResult.provider} was not valid. Waiting for remaining providers."
                )
                continue
            }

            if (providerResult.data is LyricsData.Plain) {
                if (heldPlainResult == null) {
                    Log.d(
                        TAG,
                        "Initial concurrent fetch: ${providerResult.provider} returned plain lyrics. Holding them while waiting for a synced result."
                    )
                    heldPlainResult = providerResult
                }
                continue
            }

            Log.d(
                TAG,
                "Initial concurrent fetch winner: ${providerResult.provider}. Discarding slower provider requests."
            )
            pendingRequests.forEach { request ->
                if (request.isActive) {
                    request.cancel("Discarding slower provider after ${providerResult.provider} returned a valid result.")
                }
            }
            cacheConcurrentWinnerIfEligible(
                providerResult = providerResult,
                requestedTitle = title,
                requestedArtist = artist,
                requestedDurationMs = durationFromMediaMs
            )
            return@coroutineScope providerResult.data
        }

        heldPlainResult?.let { plainResult ->
            Log.d(TAG, "Initial concurrent fetch: no synced result. Using plain lyrics from ${plainResult.provider}.")
            cacheConcurrentWinnerIfEligible(
                providerResult = plainResult,
                requestedTitle = title,
                requestedArtist = artist,
                requestedDurationMs = durationFromMediaMs
            )
            return@coroutineScope plainResult.data
        }

        Log.d(TAG, "Initial concurrent fetch did not produce a valid result from any provider.")
        null
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
                // Check cache first (only for non-YouTube players)
                    if (!useYouTubeLogic) {
                    val cachedLyrics = cacheManager.get(artistForThisFetch, titleForThisFetch, durationFromMediaMs)
                    if (cachedLyrics != null) {
                        Log.d(TAG, "Cache hit for '$titleForThisFetch' by '$artistForThisFetch'")
                        // Update attribution to include "(cached)" and keep artist aligned with current request
                        val preparedCachedLyrics = updateCachedLyricsForDisplay(cachedLyrics, artistForThisFetch)
                        val cachedTitle = when (preparedCachedLyrics) {
                            is LyricsData.Synced -> preparedCachedLyrics.title
                            is LyricsData.Plain -> preparedCachedLyrics.title
                            is LyricsData.Info -> preparedCachedLyrics.title
                            is LyricsData.MismatchInfo -> preparedCachedLyrics.title
                        }
                        val cachedArtist = when (preparedCachedLyrics) {
                            is LyricsData.Synced -> preparedCachedLyrics.artist
                            is LyricsData.Plain -> preparedCachedLyrics.artist
                            is LyricsData.Info -> preparedCachedLyrics.artist
                            is LyricsData.MismatchInfo -> preparedCachedLyrics.artist
                        }

                        if (matchesCurrentSongContextForLyrics(preparedCachedLyrics)) {
                            fetchedLyricsDataLocal = preparedCachedLyrics
                        } else {
                            Log.w(
                                TAG,
                                "Cache hit for '$titleForThisFetch'/'$artistForThisFetch' produced cached payload '$cachedTitle'/'$cachedArtist' that failed current song guard '$lastDetectedSongTitle'/'$lastDetectedSongArtist'. Skipping cache and falling back to network."
                            )
                        }
                    }
                    }

                if (fetchedLyricsDataLocal == null) {
                    if (useYouTubeLogic) {
                        Log.d(TAG, "Using LRCLib for YouTube-based player.")
                        fetchedLyricsDataLocal = searchWithLrcLib(
                            title = titleForThisFetch,
                            artist = artistForThisFetch,
                            durationFromMediaMs = durationFromMediaMs
                        )
                    } else {
                        if (musixmatchUserToken != null) {
                            Log.d(TAG, "Starting concurrent first-pass fetch (Musixmatch + Custom + LRCLIB without retries).")
                        } else {
                            Log.d(TAG, "Musixmatch token unavailable. Starting concurrent first-pass fetch (Custom + LRCLIB without retries).")
                        }
                        fetchedLyricsDataLocal = fetchFirstValidLyricsFromConcurrentProviders(
                            title = titleForThisFetch,
                            artist = artistForThisFetch,
                            durationFromMediaMs = durationFromMediaMs
                        )

                        if (fetchedLyricsDataLocal == null) {
                            Log.d(TAG, "Initial concurrent fetch had no valid winner. Running retry mechanisms (LRCLIB fallback retries).")
                            fetchedLyricsDataLocal = searchWithLrcLib(
                                title = titleForThisFetch,
                                artist = artistForThisFetch,
                                durationFromMediaMs = durationFromMediaMs,
                                enableFallbackRetries = true
                            )
                        }
                    }
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

    private suspend fun searchWithMusixmatch(
        title: String,
        artist: String,
        durationFromMediaMs: Long,
        allowCacheWrite: Boolean = true
    ): LyricsData? {
        val token = musixmatchUserToken ?: return null
        if (title.isBlank() || artist.isBlank()) {
            Log.d(TAG, "Musixmatch search skipped: title or artist is blank.")
            return null
        }

        try {
            val encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8.toString())
            val encodedArtist = URLEncoder.encode(artist, StandardCharsets.UTF_8.toString())
            val url = "$MUSIXMATCH_API_BASE_URL?usertoken=$token&q_track=$encodedTitle&q_artist=$encodedArtist&app_id=mac-ios-v2.0&subtitle_format=json&selected_language=en&part=subtitle_translated"

            Log.d(TAG, "Fetching from Musixmatch: ${url.replace(token, maskMusixmatchToken(token))}")
            val response: MusixmatchLyricsResponse = httpClient.get(url) {
                    header("User-Agent", MUSIXMATCH_USER_AGENT)
                    header("Cookie", MUSIXMATCH_COOKIE)
                }.body()

            val macroCalls = response.message.body.macro_calls
            val subtitlesGet = macroCalls.trackSubtitlesGet?.message?.body
            val lyricsGet = macroCalls.trackLyricsGet?.message?.body
            val subtitleHolder = subtitlesGet?.subtitle_list?.firstOrNull()?.subtitle

            // track_length is the guard: a matched track must report a length that agrees with the player.
            val trackLengthSec = macroCalls.matcherTrackGet?.message?.body?.track?.track_length ?: 0
            if (durationFromMediaMs > 0) {
                if (trackLengthSec <= 0) {
                    Log.d(TAG, "Musixmatch: no track length in response for '$title'. Discarding result.")
                    return null
                }
                if (kotlin.math.abs(trackLengthSec * 1000L - durationFromMediaMs) > PROVIDER_DURATION_TOLERANCE_MS) {
                    Log.d(TAG, "Musixmatch: track length ${trackLengthSec}s does not match media duration ${durationFromMediaMs / 1000}s for '$title'. Discarding result.")
                    return null
                }
            }

            if (lyricsGet?.lyrics?.instrumental == 1) {
                return LyricsData.Info(title, artist, "This is an instrumental song... 🎵", durationFromMediaMs)
            }

            val syncedLrc = subtitleHolder?.subtitle_body
            val translatedLrc = subtitleHolder?.subtitle_translated?.subtitle_body

            if (!syncedLrc.isNullOrBlank()) {
                val timedLines = parseSyncedLyrics(syncedLrc)
                val translatedLines = if (!translatedLrc.isNullOrBlank()) parseSyncedLyrics(translatedLrc) else null

                if (timedLines.isNotEmpty()) {
                    val linesWithAttribution = timedLines.toMutableList().apply {
                        add(TimedLyricLine(ATTRIBUTION_TIMESTAMP, MUSIXMATCH_ATTRIBUTION))
                    }
                    val translatedWithAttribution = translatedLines?.toMutableList()?.apply {
                        add(TimedLyricLine(ATTRIBUTION_TIMESTAMP, MUSIXMATCH_ATTRIBUTION))
                    }
                    Log.d(TAG, "Found ${timedLines.size} synced lines from Musixmatch for '$title'. Translation available: ${translatedLines != null}")
                    val syncedData = LyricsData.Synced(title, artist, linesWithAttribution, translatedWithAttribution, durationFromMediaMs)

                    // Cache the synced lyrics (without attribution for storage)
                    if (allowCacheWrite) {
                        val lyricsToCache = LyricsData.Synced(title, artist, timedLines, translatedLines, durationFromMediaMs)
                        cacheManager.put(artist, title, durationFromMediaMs, lyricsToCache, "musixmatch")
                    }

                    return syncedData
                } else {
                    return null
                }
            }

            val plainLyrics = lyricsGet?.lyrics?.lyrics_body
            if (!plainLyrics.isNullOrBlank()) {
                Log.d(TAG, "Found plain lyrics from Musixmatch for '$title'")
                val plainData = LyricsData.Plain(title, artist, plainLyrics, durationFromMediaMs)

                // Cache the plain lyrics
                if (allowCacheWrite) {
                    cacheManager.put(artist, title, durationFromMediaMs, plainData, "musixmatch")
                }

                return plainData
            }

            Log.d(TAG, "No lyrics found on Musixmatch for '$title'")
            return LyricsData.Info(title, artist, "Lyrics not found.", durationFromMediaMs)

        } catch (e: CancellationException) {
            Log.d(TAG, "Musixmatch search cancelled for '$title'.")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Musixmatch search for '$title'", e)
            return null
        }
    }
    
    private suspend fun searchWithLrcLib(
        title: String,
        artist: String,
        durationFromMediaMs: Long,
        enableFallbackRetries: Boolean = true,
        allowCacheWrite: Boolean = true
    ): LyricsData {
        var potentialMismatch = false
        var results: List<LyricResult>? = null
        val isFromYouTube = isYouTubeBasedPlayer(activeMediaController?.packageName)

        try {
            if (isFromYouTube) {
                val initialQuery = cleanYouTubeTitleForSearch(title)
                results = searchLrcLib(initialQuery)
            } else {
                results = searchLrcLibByTrackAndArtist(title, artist)
            }
            val attemptedQueries = mutableSetOf("$title $artist".trim().lowercase())

            if (results.isNullOrEmpty() && !isFromYouTube && enableFallbackRetries) {
                val primaryArtist = extractPrimaryArtistForSearch(artist)
                if (primaryArtist.isNotBlank()) {
                    val delimiterQuery = "$title $primaryArtist".trim()
                    val shouldTryDelimiterQuery = delimiterQuery.isNotBlank() &&
                            attemptedQueries.add(delimiterQuery.lowercase())
                    if (shouldTryDelimiterQuery) {
                        Log.d(TAG, "LRCLib: Initial search failed for '$title'. Retrying with primary artist '$primaryArtist'.")
                        results = searchLrcLib(delimiterQuery)
                        if (!results.isNullOrEmpty()) potentialMismatch = true
                    }
                }


                if (results.isNullOrEmpty() && attemptedQueries.size == 1) {
                    Log.d(TAG, "LRCLib: No usable fallback query for '$title'. Keeping initial search result.")
                }
            } else if (results.isNullOrEmpty() && !isFromYouTube && !enableFallbackRetries) {
                Log.d(TAG, "LRCLib: Initial no-retry fetch failed for '$title'. Skipping fallback retries.")
            }

            if (results.isNullOrEmpty() && !isFromYouTube) {
                if (enableFallbackRetries && attemptedQueries.size > 1) {
                    Log.d(TAG, "LRCLib: Exhausted fallback retries for '$title'.")
                } else {
                    Log.d(TAG, "LRCLib: Initial search failed and no additional fallback retries applied for '$title'.")
                }
            }

            val chosenLyricResult: LyricResult?
            if (!results.isNullOrEmpty()) {
                val mediaDurationSec = if (durationFromMediaMs > 0) durationFromMediaMs / 1000.0 else -1.0
                val durationToleranceSec = 3.0
                chosenLyricResult = if (mediaDurationSec > 0) {
                    results.firstOrNull { r ->
                        !r.instrumental &&
                            !r.syncedLyrics.isNullOrBlank() &&
                            kotlin.math.abs(r.duration - mediaDurationSec) <= durationToleranceSec
                    } ?: results.firstOrNull { r ->
                        r.instrumental &&
                            kotlin.math.abs(r.duration - mediaDurationSec) <= durationToleranceSec
                    }
                } else {
                    Log.d(TAG, "LRCLib: Media duration unavailable for '$title'. Skipping LRCLib result selection because strict duration matching is required.")
                    null
                }
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

                        // Cache synced lyrics from LRCLib (only on full match first try, not potential mismatches)
                        if (allowCacheWrite && !potentialMismatch) {
                            val lyricsToCache = LyricsData.Synced(title, artist.ifEmpty { null }, timedLines, null, finalDurationMs)
                            cacheManager.put(artist.ifEmpty { null }, title, finalDurationMs, lyricsToCache, "lrclib")
                        }

                        LyricsData.Synced(title, artist.ifEmpty { null }, linesWithAttribution, null, finalDurationMs)
                    } else {
                        Log.w(TAG, "LRCLib: Synced lyrics payload for '$title' could not be parsed into timed lines. Discarding result.")
                        LyricsData.Info(title, artist.ifEmpty { null }, "Lyrics not found.", finalDurationMs)
                    }
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
        } catch (e: CancellationException) {
            Log.d(TAG, "LRCLib searchWithLrcLib cancelled for '$title'.")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Exception in LRCLib search for '$title'", e)
            return LyricsData.Info(title, artist.ifEmpty { null }, "Could not load lyrics.", durationFromMediaMs.takeIf { it > 0 } ?: 0L)
        }
    }

    private suspend fun searchWithCustomLyricsApi(
        title: String,
        artist: String,
        durationFromMediaMs: Long,
        allowCacheWrite: Boolean = true
    ): ProviderLyricsResult {
        if (title.isBlank()) {
            Log.d(TAG, "Custom lyrics API search skipped: title is blank.")
            return ProviderLyricsResult(LyricsProvider.CUSTOM, null, "custom")
        }

        return try {
            val encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8.toString())
            val encodedArtist = URLEncoder.encode(artist, StandardCharsets.UTF_8.toString())
            val durationParam = if (durationFromMediaMs > 0) "&durationMs=$durationFromMediaMs" else ""
            val url = "$CUSTOM_LYRIC_API_BASE_URL?title=$encodedTitle&artist=$encodedArtist$durationParam"
            Log.d(TAG, "Fetching from custom lyrics API: $url")

            val response: CustomLyricsResponse = httpClient.get(url).body()
            val source = response.source?.trim().takeUnless { it.isNullOrEmpty() } ?: "custom"

            // Compare durations only when both sides report one; custom lyrics may carry none.
            val responseDurationMs = response.durationMs ?: 0L
            if (durationFromMediaMs > 0 && responseDurationMs > 0 &&
                kotlin.math.abs(responseDurationMs - durationFromMediaMs) > PROVIDER_DURATION_TOLERANCE_MS
            ) {
                Log.d(TAG, "Custom lyrics API: duration ${responseDurationMs / 1000}s does not match media duration ${durationFromMediaMs / 1000}s for '$title'. Discarding result.")
                return ProviderLyricsResult(LyricsProvider.CUSTOM, null, source)
            }

            val sortedLines = response.lrc
                .orEmpty()
                .mapNotNull { line ->
                    val timestamp = line.timeMs
                    if (timestamp == null || timestamp < 0) {
                        null
                    } else {
                        val text = line.text?.trim().takeUnless { it.isNullOrBlank() } ?: "🎶 ... 🎶"
                        TimedLyricLine(timestamp, text)
                    }
                }
                .sortedBy { it.timestamp }

            if (sortedLines.isEmpty()) {
                Log.d(TAG, "Custom lyrics API returned no usable synced lines for '$title'.")
                return ProviderLyricsResult(LyricsProvider.CUSTOM, null, source)
            }

            val finalDurationMs = when {
                durationFromMediaMs > 0 -> durationFromMediaMs
                (response.durationMs ?: 0L) > 0 -> response.durationMs!!
                else -> 0L
            }

            val linesWithAttribution = sortedLines.toMutableList().apply {
                add(TimedLyricLine(ATTRIBUTION_TIMESTAMP, formatLyricsAttribution(source)))
            }
            val syncedData = LyricsData.Synced(
                title = title,
                artist = artist.ifEmpty { null },
                lines = linesWithAttribution,
                translatedLines = null,
                durationMs = finalDurationMs
            )

            if (allowCacheWrite) {
                val lyricsToCache = LyricsData.Synced(
                    title = title,
                    artist = artist.ifEmpty { null },
                    lines = sortedLines,
                    translatedLines = null,
                    durationMs = finalDurationMs
                )
                cacheManager.put(artist.ifEmpty { null }, title, finalDurationMs, lyricsToCache, source)
            }

            ProviderLyricsResult(LyricsProvider.CUSTOM, syncedData, source)
        } catch (e: CancellationException) {
            Log.d(TAG, "Custom lyrics API search cancelled for '$title'.")
            throw e
        } catch (e: ResponseException) {
            Log.d(TAG, "Custom lyrics API returned ${e.response.status.value} for '$title' (treated as no result).")
            ProviderLyricsResult(LyricsProvider.CUSTOM, null, "custom")
        } catch (e: Exception) {
            Log.e(TAG, "Exception in custom lyrics API search for '$title'", e)
            ProviderLyricsResult(LyricsProvider.CUSTOM, null, "custom")
        }
    }
    
    private suspend fun searchLrcLibByTrackAndArtist(trackName: String, artistName: String): List<LyricResult>? {
        if (trackName.isBlank()) return null
        val encodedTrack = URLEncoder.encode(trackName, StandardCharsets.UTF_8.toString())
        val encodedArtist = URLEncoder.encode(artistName, StandardCharsets.UTF_8.toString())
        val url = "$LYRIC_API_BASE_URL?track_name=$encodedTrack&artist_name=$encodedArtist"
        Log.d(TAG, "Fetching lyrics from LRCLib (structured): $url")
        return try {
            if (!currentCoroutineContext().isActive) {
                Log.w(TAG, "LRCLib: Coroutine no longer active before network call for '$trackName'.")
                return null
            }
            httpClient.get(url).body<List<LyricResult>>().also { Log.d(TAG, "LRCLib structured search for '$trackName' by '$artistName' returned ${it.size} results.") }
        } catch (e: CancellationException) {
            Log.d(TAG, "LRCLib structured search cancelled for '$trackName'.")
            throw e
        }
        catch (e: Exception) {
            Log.e(TAG, "LRCLib structured search exception for '$trackName': ${e.message}")
            null
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

    private fun bindLyricsWindowView(view: View) {
        lyricsView = view
        knownLyricsOverlayViews.add(view)

        songInfoTextView = view.findViewById(R.id.songInfoTextView)
        lyricsRecyclerView = view.findViewById(R.id.lyricsRecyclerView)
        expandCollapseButton = view.findViewById(R.id.expandCollapseButton)
        translateButton = view.findViewById(R.id.translateButton)
        overlayAlbumArtImageView = view.findViewById(R.id.overlayAlbumArtImageView)
        overlayScrimView = view.findViewById(R.id.overlayScrimView)
        headerDivider = view.findViewById(R.id.headerDivider)
        overlayContentLayout = view.findViewById(R.id.lyricsOverlayContent)
        val closeButton = view.findViewById<ImageButton>(R.id.closeButton)

        val existingLayoutManager = lyricsRecyclerView?.layoutManager as? LinearLayoutManager
        linearLayoutManager = existingLayoutManager ?: LinearLayoutManager(this).also {
            lyricsRecyclerView?.layoutManager = it
        }

        val existingAdapter = lyricsRecyclerView?.adapter as? LyricsAdapter
        lyricsAdapter = existingAdapter ?: LyricsAdapter(this, emptyList<TimedLyricLine>()).also {
            lyricsRecyclerView?.adapter = it
        }
        lyricsRecyclerView?.itemAnimator = null

        closeButton?.setOnClickListener {
            if (handleLyricsOverlayInteraction(view, "close button")) {
                return@setOnClickListener
            }
            wasHiddenByPausePreference = false
            hideLyricsWindow()
        }
        expandCollapseButton?.setOnClickListener {
            if (handleLyricsOverlayInteraction(view, "expand button")) {
                return@setOnClickListener
            }
            cycleLyricsWindowMode()
        }
        translateButton?.setOnClickListener {
            if (handleLyricsOverlayInteraction(view, "translate button")) {
                return@setOnClickListener
            }
            toggleTranslation()
        }
        viewMover = ViewMover(view)
        view.setOnTouchListener(viewMover)
    }

    private fun clearLyricsWindowReferences(clearTrackedView: Boolean = true) {
        if (clearTrackedView) {
            lyricsView = null
            params = null
            windowManager = null
        }
        songInfoTextView = null
        lyricsRecyclerView = null
        expandCollapseButton = null
        translateButton = null
        overlayAlbumArtImageView = null
        overlayScrimView = null
        headerDivider = null
        overlayContentLayout = null
        miniTouchInterceptor?.let { lyricsRecyclerView?.removeOnItemTouchListener(it) }
        miniTouchInterceptor = null
        viewMover = null
        lyricsAdapter = null
    }

    private fun knownLyricsOverlayViewSnapshot(): List<View> {
        val views = mutableListOf<View>()
        lyricsView?.let { views.add(it) }
        views.addAll(knownLyricsOverlayViews)
        return views.distinct()
    }

    private fun syncLyricsOverlayBinding(view: View) {
        lyricsView = view
        knownLyricsOverlayViews.add(view)
        if (windowManager == null) {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        params = view.layoutParams as? WindowManager.LayoutParams ?: params
    }

    private fun detachLyricsOverlayView(view: View, reason: String): Boolean {
        if (!view.isAttachedToWindow) {
            knownLyricsOverlayViews.remove(view)
            return true
        }

        val currentWindowManager = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also {
            windowManager = it
        }

        try {
            currentWindowManager.removeViewImmediate(view)
            Log.d(TAG, "Detached lyrics overlay for $reason.")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Lyrics overlay was already detached during $reason: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error detaching lyrics overlay during $reason", e)
        }

        if (!view.isAttachedToWindow) {
            knownLyricsOverlayViews.remove(view)
            return true
        }

        return false
    }

    private fun pruneDuplicateLyricsOverlayViews(reason: String, preferredView: View? = lyricsView): View? {
        val attachedViews = knownLyricsOverlayViewSnapshot().filter { it.isAttachedToWindow }
        if (attachedViews.isEmpty()) {
            knownLyricsOverlayViews.removeAll { !it.isAttachedToWindow }
            return null
        }

        val survivingView = when {
            preferredView?.isAttachedToWindow == true -> preferredView
            else -> attachedViews.lastOrNull()
        } ?: return null

        val duplicateViews = attachedViews.filter { it !== survivingView }
        if (duplicateViews.isNotEmpty()) {
            Log.w(TAG, "Pruning ${duplicateViews.size} duplicate lyrics overlay(s) during $reason.")
        }

        var attachedDuplicateRemains = false
        duplicateViews.forEach { duplicateView ->
            if (!detachLyricsOverlayView(duplicateView, "$reason duplicate cleanup")) {
                attachedDuplicateRemains = true
            }
        }

        if (
            lyricsView !== survivingView ||
            songInfoTextView == null ||
            lyricsRecyclerView == null ||
            expandCollapseButton == null ||
            translateButton == null ||
            overlayAlbumArtImageView == null ||
            overlayScrimView == null ||
            lyricsAdapter == null
        ) {
            Log.w(TAG, "Rebinding lyrics window references to surviving overlay for $reason.")
            bindLyricsWindowView(survivingView)
        } else {
            syncLyricsOverlayBinding(survivingView)
        }

        if (attachedDuplicateRemains) {
            Log.w(TAG, "At least one duplicate lyrics overlay remained attached during $reason.")
        }

        return survivingView
    }

    private fun handleLyricsOverlayInteraction(view: View, reason: String): Boolean {
        knownLyricsOverlayViews.add(view)

        if (!view.isAttachedToWindow) {
            knownLyricsOverlayViews.remove(view)
            if (lyricsView === view) {
                clearLyricsWindowReferences()
            }
            Log.d(TAG, "Ignoring lyrics overlay interaction for detached view during $reason.")
            return true
        }

        if (lyricsView !== view) {
            Log.w(TAG, "Interaction came from a stale lyrics overlay during $reason. Removing stale view.")
            detachLyricsOverlayView(view, "stale interaction: $reason")
            ensureLyricsViewBoundToAttachedOverlay("after stale interaction: $reason")
            return true
        }

        syncLyricsOverlayBinding(view)
        ensureLyricsViewBoundToAttachedOverlay(reason)
        return false
    }

    private fun songContextForLyricsData(data: LyricsData): Pair<String?, String?> {
        return when (data) {
            is LyricsData.Plain -> data.title to data.artist
            is LyricsData.Synced -> data.title to data.artist
            is LyricsData.Info -> data.title to data.artist
            is LyricsData.MismatchInfo -> data.title to data.artist
        }
    }

    private fun shouldApplyAsyncOverlayTheme(
        targetView: View?,
        expectedToken: MediaSession.Token?,
        expectedTitle: String?,
        expectedArtist: String?,
        reason: String
    ): Boolean {
        if (targetView == null) {
            Log.d(TAG, "$reason: overlay view reference cleared before async theme update.")
            return false
        }

        if (!targetView.isAttachedToWindow) {
            knownLyricsOverlayViews.remove(targetView)
            Log.d(TAG, "$reason: overlay view detached before async theme update.")
            return false
        }

        if (lyricsView !== targetView) {
            Log.d(TAG, "$reason: async theme result belongs to a stale overlay instance. Skipping.")
            return false
        }

        if (expectedToken != currentMediaSessionToken) {
            Log.d(TAG, "$reason: media session changed from $expectedToken to $currentMediaSessionToken. Skipping.")
            return false
        }

        if (!matchesCurrentSongContext(expectedTitle, expectedArtist)) {
            Log.d(
                TAG,
                "$reason: song context changed from '$expectedTitle'/'$expectedArtist' to '$lastDetectedSongTitle'/'$lastDetectedSongArtist'. Skipping."
            )
            return false
        }

        return true
    }

    private fun ensureLyricsViewBoundToAttachedOverlay(reason: String): Boolean {
        pruneDuplicateLyricsOverlayViews(reason, lyricsView)?.let { survivingView ->
            syncLyricsOverlayBinding(survivingView)
            return true
        }

        val currentView = lyricsView
        val attachedView = knownLyricsOverlayViewSnapshot().lastOrNull { it.isAttachedToWindow }
        if (attachedView != null) {
            Log.w(TAG, "Rebinding lyrics window references to an attached overlay for $reason.")
            syncLyricsOverlayBinding(attachedView)
            bindLyricsWindowView(attachedView)
            return true
        }

        currentView?.let { knownLyricsOverlayViews.remove(it) }
        knownLyricsOverlayViews.removeAll { !it.isAttachedToWindow }
        return false
    }

    private fun createLyricsWindowLayoutParams(): WindowManager.LayoutParams {
        val overlayFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val savedWindowPosition = loadSavedWindowPosition()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            x = savedWindowPosition?.first ?: 0
            y = savedWindowPosition?.second ?: 100
        }
    }

    private fun getCurrentAlbumArtBitmap(): Bitmap? {
        val currentActiveMc = activeMediaController
        if (currentActiveMc == null || currentActiveMc.sessionToken != currentMediaSessionToken) {
            return null
        }

        val metadata = currentActiveMc.metadata ?: return null
        return metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
    }

    private fun areDynamicLyricsWindowColorsEnabled(): Boolean {
        return applicationContext
            .getSharedPreferences(FLUTTER_SHARED_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(PREF_DYNAMIC_LYRICS_WINDOW_COLORS, true)
    }

    private fun isBlurOverlayEffectActive(): Boolean {
        if (!areDynamicLyricsWindowColorsEnabled()) {
            return false
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }

        val simulateLegacyOverlay = applicationContext
            .getSharedPreferences(FLUTTER_SHARED_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(PREF_SIMULATE_LEGACY_OVERLAY, false)
        return !simulateLegacyOverlay
    }

    private fun applyOverlayArtwork(bitmap: Bitmap?) {
        overlayAlbumArtImageView?.let { imageView ->
            if (bitmap == null || !areDynamicLyricsWindowColorsEnabled()) {
                imageView.setImageDrawable(null)
                imageView.imageMatrix = Matrix()
                imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                imageView.isVisible = false
                overlayScrimView?.isVisible = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    imageView.setRenderEffect(null)
                }
                return
            }

            imageView.setImageBitmap(bitmap)
            imageView.scaleType = ImageView.ScaleType.MATRIX
            applyOverlayArtworkTransform(imageView)
            imageView.colorFilter = overlayArtworkColorFilter
            imageView.isVisible = true

            val blurActive = isBlurOverlayEffectActive()
            if (blurActive) {
                imageView.setRenderEffect(
                    RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP)
                )
                // Skip the dark scrim if the album art is already dark — the blur +
                // brightness-halving color filter already produce a sufficiently dim background.
                val avgLuminance = estimateBitmapLuminance(bitmap)
                overlayScrimView?.isVisible = avgLuminance > 0.3
            } else {
                overlayScrimView?.isVisible = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    imageView.setRenderEffect(null)
                }
            }
        }
    }

    /** Sample a down-scaled copy to cheaply estimate average perceptual luminance (0..1). */
    private fun estimateBitmapLuminance(bitmap: Bitmap): Double {
        val small = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        var totalLuminance = 0.0
        val px = small.width * small.height
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                totalLuminance += ColorUtils.calculateLuminance(small.getPixel(x, y))
            }
        }
        if (small !== bitmap) small.recycle()
        return totalLuminance / px
    }

    private fun applyOverlayArtworkTransform(imageView: ImageView) {
        val drawable = imageView.drawable ?: return
        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()
        if (drawableWidth <= 0f || drawableHeight <= 0f) {
            return
        }

        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) {
            imageView.post {
                if (overlayAlbumArtImageView === imageView && imageView.drawable != null) {
                    applyOverlayArtworkTransform(imageView)
                }
            }
            return
        }

        val baseScale = maxOf(viewWidth / drawableWidth, viewHeight / drawableHeight)
        val scale = baseScale * OVERLAY_ARTWORK_ZOOM
        val scaledWidth = drawableWidth * scale
        val scaledHeight = drawableHeight * scale
        val horizontalExcess = (scaledWidth - viewWidth).coerceAtLeast(0f)
        val verticalExcess = (scaledHeight - viewHeight).coerceAtLeast(0f)

        val dx = -horizontalExcess / 2f
        val centeredDy = -verticalExcess / 2f
        val desiredDy = centeredDy - (verticalExcess * OVERLAY_ARTWORK_VERTICAL_BIAS)
        val dy = desiredDy.coerceIn(-verticalExcess, 0f)

        imageView.imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
    }

    private fun detachKnownLyricsOverlayViews(reason: String): Boolean {
        val knownViews = knownLyricsOverlayViewSnapshot()
        if (knownViews.isEmpty()) {
            return true
        }

        var allDetached = true
        val stillAttachedViews = mutableListOf<View>()

        for (view in knownViews) {
            if (!detachLyricsOverlayView(view, reason)) {
                allDetached = false
                stillAttachedViews.add(view)
            }
        }

        if (!allDetached) {
            stillAttachedViews.firstOrNull()?.let { attachedView ->
                Log.w(TAG, "A lyrics overlay is still attached after $reason. Keeping it bound so later actions target the real window.")
                params = params ?: (attachedView.layoutParams as? WindowManager.LayoutParams)
                bindLyricsWindowView(attachedView)
            }
        }

        return allDetached
    }

    private fun showLyricsWindow(data: LyricsData) {
        if (serviceJob.isCancelled || !isServiceManuallyStarted.get()) { Log.w(TAG, "showLyricsWindow: Aborting. ServiceJob cancelled or service not manually started."); return }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { if (!serviceJob.isCancelled && isServiceManuallyStarted.get()) showLyricsWindow(data) }
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

        if (data !is LyricsData.Info && !matchesCurrentSongContextForLyrics(data)) {
             Log.w(TAG, "showLyricsWindow (MainThread): Called for '$dataTitle'/'$dataArtist', but current song is '$lastDetectedSongTitle'/'$lastDetectedSongArtist'. Aborting show.")
             if (lyricsView != null && lyricsView?.isAttachedToWindow == true) {
                 val actualCurrentData = currentLyricsData
                 if (actualCurrentData != null && actualCurrentData != data) {
                    showLyricsWindow(actualCurrentData)
                 } else if (actualCurrentData == null) {
                    showLyricsWindow(LyricsData.Info(null, null, if (refreshListenerBindingState("showLyricsWindow waiting state")) "Waiting for song..." else "Connecting listener... If stuck here, see the help section in the app", 0L))
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

        ensureLyricsViewBoundToAttachedOverlay("showLyricsWindow")
        if (lyricsView != null && lyricsView?.isAttachedToWindow != true) {
            Log.w(TAG, "showLyricsWindow: Found a stale detached overlay reference. Clearing it before recreating the window.")
            lyricsView?.let { knownLyricsOverlayViews.remove(it) }
            clearLyricsWindowReferences()
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
            val inflatedLyricsView: View
            try {
                inflatedLyricsView = inflater.inflate(R.layout.lyrics_overlay, null)
            } catch (e: Exception) {
                 Log.e(TAG, "Error inflating R.layout.lyrics_overlay: ${e.message}", e)
                 clearLyricsWindowReferences()
                 return
            }
            bindLyricsWindowView(inflatedLyricsView)
            this.params = createLyricsWindowLayoutParams()

            try {
                val currentLyricsView = lyricsView ?: return
                if (!currentLyricsView.isAttachedToWindow) {
                    this.windowManager?.addView(currentLyricsView, this.params)
                    Log.d(TAG, "Lyrics window added to WindowManager.")
                } else {
                    Log.w(TAG, "LyricsView was unexpectedly already attached before initial addView.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding lyrics view to WindowManager: ${e.message}", e)
                lyricsView?.let { knownLyricsOverlayViews.remove(it) }
                clearLyricsWindowReferences()
                return
            }
        } else {
            Log.d(TAG, "Lyrics window already exists. Updating content. Attached: ${lyricsView?.isAttachedToWindow}")
            if (this.windowManager == null) {
                this.windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            }
            if (this.params == null) {
                this.params = (lyricsView?.layoutParams as? WindowManager.LayoutParams) ?: createLyricsWindowLayoutParams()
            }
        }

        defaultLyricsWindowMode = loadDefaultLyricsWindowMode()
        lyricsWindowMode = defaultLyricsWindowMode
        lyricsWindowModeExpanding = true
        translateButton?.isVisible = currentLyricsHasTranslation && lyricsWindowMode != LyricsWindowMode.MINI
        applyOverlayArtwork(getCurrentAlbumArtBitmap())

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
        val themeContext = songContextForLyricsData(data)
        val overlayViewForTheme = lyricsView
        val sessionTokenForTheme = currentMediaSessionToken

        if (!dynamicColoursEnabled) {
            applyOverlayArtwork(null)
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
                        // Apply default colors immediately to avoid flash/uninitialized state
                        applyThemeToOverlayElements(finalOverlayBackgroundColor, finalTitleAndIconColor, finalLyricsTextColor, finalLyricsHighlightBgColor)

                        // Extract palette colors asynchronously and update when ready
                        Palette.from(albumArtBitmap).generate { palette ->
                            if (!shouldApplyAsyncOverlayTheme(
                                    targetView = overlayViewForTheme,
                                    expectedToken = sessionTokenForTheme,
                                    expectedTitle = themeContext.first,
                                    expectedArtist = themeContext.second,
                                    reason = "showLyricsWindow palette"
                                )
                            ) {
                                return@generate
                            }

                            palette?.let { p ->
                                var selectedBackgroundColorRgb: Int? = p.dominantSwatch?.rgb
                                if (selectedBackgroundColorRgb == null) {
                                    val fallbackBgSwatch = p.darkVibrantSwatch ?: p.vibrantSwatch ?: p.darkMutedSwatch ?: p.mutedSwatch
                                    selectedBackgroundColorRgb = fallbackBgSwatch?.rgb
                                }

                                val opaqueChosenBgColor = selectedBackgroundColorRgb ?: Color.parseColor("#FF212121")

                                val paletteOverlayBgColor = selectedBackgroundColorRgb?.let {
                                    ColorUtils.setAlphaComponent(it, 221)
                                } ?: DEFAULT_STATIC_BACKGROUND_COLOR

                                val isBackgroundLight = ColorUtils.calculateLuminance(opaqueChosenBgColor) > 0.5
                                val paletteTitleColor = DEFAULT_STATIC_TITLE_COLOR

                                val contrastTitleBg = ColorUtils.calculateContrast(paletteTitleColor, opaqueChosenBgColor)
                                val finalPaletteTitleColor = if (contrastTitleBg < 5.0) {
                                    Log.w(TAG, "Low contrast ($contrastTitleBg) between title color (${Integer.toHexString(paletteTitleColor)}) and OPAQUE BG (${Integer.toHexString(opaqueChosenBgColor)}). Keeping default bright title color for blurred artwork treatment.")
                                    DEFAULT_STATIC_TITLE_COLOR
                                } else {
                                    paletteTitleColor
                                }

                                val highlightSwatch = if (isBackgroundLight) {
                                    p.darkMutedSwatch ?: p.darkVibrantSwatch ?: p.mutedSwatch ?: p.vibrantSwatch
                                } else {
                                    p.lightMutedSwatch ?: p.lightVibrantSwatch ?: p.vibrantSwatch ?: p.mutedSwatch
                                }
                                val paletteHighlightColor = highlightSwatch?.rgb?.let {
                                    ColorUtils.setAlphaComponent(it, 85)
                                } ?: finalLyricsHighlightBgColor

                                Log.d(TAG, "Palette applied. BG Light: $isBackgroundLight. OverlayBG (translucent): #${Integer.toHexString(paletteOverlayBgColor)}, Title/Icon: #${Integer.toHexString(finalPaletteTitleColor)}, LyricHighlightBG: #${Integer.toHexString(paletteHighlightColor)}")

                                // Update with palette colors (this will smoothly transition from defaults to palette colors)
                                applyThemeToOverlayElements(paletteOverlayBgColor, finalPaletteTitleColor, finalLyricsTextColor, paletteHighlightColor)
                            } ?: Log.d(TAG, "Palette object was null. Keeping defaults.")
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
                val linesWithIntro = prependIntroInstrumentalIfNeeded(linesToShow)
                Log.d(TAG, "Displaying Synced lyrics: ${linesWithIntro.size} lines. Translated: $isShowingTranslatedLyrics")
                lyricsAdapter?.updateLyrics(linesWithIntro, true)
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
                if (dataToDisplayForAdapter.message.contains("instrumental", ignoreCase = true)) {
                    Log.d(TAG, "Displaying instrumental visualizer placeholder for info response.")
                    lyricsAdapter?.updateLyrics(listOf(TimedLyricLine(0, "🎶 ... 🎶")), true)
                    val isCurrentlyPlaying =
                        activeMediaController?.sessionToken == currentMediaSessionToken &&
                            activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
                    lyricsAdapter?.setPlayingState(isCurrentlyPlaying)
                    lyricsAdapter?.setHighlight(0)
                } else {
                    Log.d(TAG, "Displaying Info: $finalMessageForInfo")
                    lyricsAdapter?.updateLyrics(listOf(TimedLyricLine(0, finalMessageForInfo)), false)
                }
            }
            is LyricsData.MismatchInfo -> {
                 Log.e(TAG, "Internal error: MismatchInfo was not unwrapped. Displaying error.")
                 lyricsAdapter?.updateLyrics(listOf(TimedLyricLine(0,"Error displaying lyrics (mismatch type).")), false)
            }
        }
        lyricsRecyclerView?.isVisible = true
        wasHiddenByPausePreference = false

        applyLyricsWindowMode()
        if (dataToDisplayForAdapter is LyricsData.Synced &&
            activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING &&
            activeMediaController?.sessionToken == currentMediaSessionToken) {
            lyricsAdapter?.setPlayingState(true)
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

            // Update the header divider to match the title color at low alpha
            view.findViewById<View>(R.id.headerDivider)?.setBackgroundColor(
                ColorUtils.setAlphaComponent(titleIconColor, 26)
            )
        }

        songInfoTextView?.setTextColor(titleIconColor)

        translateButton?.setColorFilter(titleIconColor, PorterDuff.Mode.SRC_IN)
        expandCollapseButton?.setColorFilter(titleIconColor, PorterDuff.Mode.SRC_IN)
        lyricsView?.findViewById<ImageButton>(R.id.closeButton)?.setColorFilter(titleIconColor, PorterDuff.Mode.SRC_IN)

        lyricsAdapter?.updateThemeColors(
            normalLineTextColor = lyricTextColor,
            highlightedLineTextColor = lyricTextColor,
            highlightedLineBackgroundColor = lyricHighlightBgColor,
            showHighlightedLineBackground = !areDynamicLyricsWindowColorsEnabled()
        )
    }

    /**
     * Updates lyrics window colors from current metadata album art.
     * Used to apply colors when album art loads after lyrics are already showing (e.g., cached lyrics).
     */
    private fun updateLyricsWindowColors() {
        if (lyricsView == null) return

        val prefs = applicationContext.getSharedPreferences(FLUTTER_SHARED_PREFERENCES, Context.MODE_PRIVATE)
        val dynamicColoursEnabled = prefs.getBoolean(PREF_DYNAMIC_LYRICS_WINDOW_COLORS, true)
        val overlayViewForTheme = lyricsView
        val sessionTokenForTheme = currentMediaSessionToken
        val titleForTheme = lastDetectedSongTitle
        val artistForTheme = lastDetectedSongArtist

        if (!dynamicColoursEnabled) {
            Log.d(TAG, "Dynamic colours disabled, skipping color update")
            applyOverlayArtwork(null)
            // Apply the stored static colors when dynamic colors are disabled
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
            applyThemeToOverlayElements(storedBackgroundColor, storedTitleColor, Color.WHITE, storedHighlightColor)
            return
        }

        val currentActiveMc = activeMediaController
        if (currentActiveMc == null || currentActiveMc.sessionToken != currentMediaSessionToken) {
            Log.d(TAG, "No active media controller or token mismatch, skipping color update")
            return
        }

        currentActiveMc.metadata?.let { metadata ->
            val albumArtBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            applyOverlayArtwork(albumArtBitmap)

            if (albumArtBitmap != null) {
                val finalLyricsTextColor = Color.WHITE

                // Extract palette colors asynchronously and update when ready
                Palette.from(albumArtBitmap).generate { palette ->
                    if (!shouldApplyAsyncOverlayTheme(
                            targetView = overlayViewForTheme,
                            expectedToken = sessionTokenForTheme,
                            expectedTitle = titleForTheme,
                            expectedArtist = artistForTheme,
                            reason = "updateLyricsWindowColors palette"
                        )
                    ) {
                        return@generate
                    }

                    palette?.let { p ->
                        var selectedBackgroundColorRgb: Int? = p.dominantSwatch?.rgb
                        if (selectedBackgroundColorRgb == null) {
                            val fallbackBgSwatch = p.darkVibrantSwatch ?: p.vibrantSwatch ?: p.darkMutedSwatch ?: p.mutedSwatch
                            selectedBackgroundColorRgb = fallbackBgSwatch?.rgb
                        }

                        val opaqueChosenBgColor = selectedBackgroundColorRgb ?: Color.parseColor("#FF212121")

                        val paletteOverlayBgColor = selectedBackgroundColorRgb?.let {
                            ColorUtils.setAlphaComponent(it, 221)
                        } ?: DEFAULT_STATIC_BACKGROUND_COLOR

                        val isBackgroundLight = ColorUtils.calculateLuminance(opaqueChosenBgColor) > 0.5
                        val paletteTitleColor = DEFAULT_STATIC_TITLE_COLOR

                        val contrastTitleBg = ColorUtils.calculateContrast(paletteTitleColor, opaqueChosenBgColor)
                        val finalPaletteTitleColor = if (contrastTitleBg < 5.0) {
                            Log.w(TAG, "updateLyricsWindowColors: Low contrast ($contrastTitleBg). Keeping default bright title color for blurred artwork treatment.")
                            DEFAULT_STATIC_TITLE_COLOR
                        } else {
                            paletteTitleColor
                        }

                        val highlightSwatch = if (isBackgroundLight) {
                            p.darkMutedSwatch ?: p.darkVibrantSwatch ?: p.mutedSwatch ?: p.vibrantSwatch
                        } else {
                            p.lightMutedSwatch ?: p.lightVibrantSwatch ?: p.vibrantSwatch ?: p.mutedSwatch
                        }
                        val paletteHighlightColor = highlightSwatch?.rgb?.let {
                            ColorUtils.setAlphaComponent(it, 85)
                        } ?: DEFAULT_STATIC_HIGHLIGHT_COLOR

                        Log.d(TAG, "updateLyricsWindowColors: Palette colors extracted and applied. BG: #${Integer.toHexString(paletteOverlayBgColor)}, Title: #${Integer.toHexString(finalPaletteTitleColor)}, Highlight: #${Integer.toHexString(paletteHighlightColor)}")

                        // Update with palette colors
                        applyThemeToOverlayElements(paletteOverlayBgColor, finalPaletteTitleColor, finalLyricsTextColor, paletteHighlightColor)
                    } ?: Log.d(TAG, "updateLyricsWindowColors: Palette object was null")
                }
            } else {
                Log.d(TAG, "updateLyricsWindowColors: No album art bitmap available")
                applyOverlayArtwork(null)
            }
        } ?: run {
            applyOverlayArtwork(null)
            Log.d(TAG, "updateLyricsWindowColors: No metadata available")
        }
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
    
    private fun cycleLyricsWindowMode() {
        when (lyricsWindowMode) {
            LyricsWindowMode.MINI -> {
                lyricsWindowMode = LyricsWindowMode.COMPACT
                lyricsWindowModeExpanding = true
            }
            LyricsWindowMode.EXPANDED -> {
                lyricsWindowMode = LyricsWindowMode.COMPACT
                lyricsWindowModeExpanding = false
            }
            LyricsWindowMode.COMPACT -> {
                if (lyricsWindowModeExpanding) {
                    lyricsWindowMode = LyricsWindowMode.EXPANDED
                } else {
                    lyricsWindowMode = LyricsWindowMode.MINI
                }
            }
        }
        applyLyricsWindowMode()
    }

    /** If the first real lyric line starts after 1s, prepend an instrumental visualiser placeholder at t=0. */
    private fun prependIntroInstrumentalIfNeeded(lines: List<TimedLyricLine>): List<TimedLyricLine> {
        if (lines.isEmpty()) return lines
        val firstNonAttribution = lines.firstOrNull { it.timestamp != ATTRIBUTION_TIMESTAMP } ?: return lines
        if (firstNonAttribution.text == "🎶 ... 🎶") return lines // already has one
        if (firstNonAttribution.timestamp <= 1000L) return lines
        return listOf(TimedLyricLine(0L, "🎶 ... 🎶")) + lines
    }

    private fun loadDefaultLyricsWindowMode(): LyricsWindowMode {
        val value = applicationContext
            .getSharedPreferences(FLUTTER_SHARED_PREFERENCES, Context.MODE_PRIVATE)
            .getString(PREF_LYRICS_WINDOW_SIZE, "compact")
        return when (value) {
            "mini" -> LyricsWindowMode.MINI
            "expanded" -> LyricsWindowMode.EXPANDED
            else -> LyricsWindowMode.COMPACT
        }
    }

    private fun applyLyricsWindowMode() {
        val isMini = lyricsWindowMode == LyricsWindowMode.MINI
        val isSynced = lyricsAdapter?.isSynced() ?: false
        // Mini mode only applies to synced lyrics — fall back to compact for plain
        val effectiveMode = if (isMini && !isSynced) LyricsWindowMode.COMPACT else lyricsWindowMode
        val isMiniEffective = effectiveMode == LyricsWindowMode.MINI

        // Header visibility — in mini mode, hide song info, translate, and divider entirely
        songInfoTextView?.visibility = if (isMiniEffective) View.GONE else View.VISIBLE
        translateButton?.visibility = if (!isMiniEffective && currentLyricsHasTranslation) View.VISIBLE else View.GONE
        headerDivider?.visibility = if (isMiniEffective) View.GONE else View.VISIBLE

        // Reposition buttons and RecyclerView via ConstraintSet for mini mode
        overlayContentLayout?.let { layout ->
            val cs = ConstraintSet()
            cs.clone(layout)
            if (isMiniEffective) {
                // Move buttons to be vertically centered alongside the RecyclerView
                cs.clear(R.id.expandCollapseButton, ConstraintSet.TOP)
                cs.connect(R.id.expandCollapseButton, ConstraintSet.TOP, R.id.lyricsRecyclerView, ConstraintSet.TOP)
                cs.connect(R.id.expandCollapseButton, ConstraintSet.BOTTOM, R.id.lyricsRecyclerView, ConstraintSet.BOTTOM)
                cs.clear(R.id.closeButton, ConstraintSet.TOP)
                cs.connect(R.id.closeButton, ConstraintSet.TOP, R.id.lyricsRecyclerView, ConstraintSet.TOP)
                cs.connect(R.id.closeButton, ConstraintSet.BOTTOM, R.id.lyricsRecyclerView, ConstraintSet.BOTTOM)
                // RecyclerView starts at parent top, ends before buttons so text doesn't overlap
                cs.connect(R.id.lyricsRecyclerView, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                cs.setMargin(R.id.lyricsRecyclerView, ConstraintSet.TOP, 0)
                cs.connect(R.id.lyricsRecyclerView, ConstraintSet.END, R.id.expandCollapseButton, ConstraintSet.START)
                cs.setMargin(R.id.lyricsRecyclerView, ConstraintSet.END, 4.dpToPx())
                // Tighter padding
                layout.setPadding(layout.paddingStart, 8.dpToPx(), layout.paddingEnd, 6.dpToPx())
            } else {
                // Restore buttons to parent top
                cs.clear(R.id.expandCollapseButton, ConstraintSet.BOTTOM)
                cs.connect(R.id.expandCollapseButton, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                cs.clear(R.id.closeButton, ConstraintSet.BOTTOM)
                cs.connect(R.id.closeButton, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                // RecyclerView below divider, full width
                cs.connect(R.id.lyricsRecyclerView, ConstraintSet.TOP, R.id.headerDivider, ConstraintSet.BOTTOM)
                cs.setMargin(R.id.lyricsRecyclerView, ConstraintSet.TOP, 6.dpToPx())
                cs.connect(R.id.lyricsRecyclerView, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                cs.setMargin(R.id.lyricsRecyclerView, ConstraintSet.END, 0)
                // Restore padding
                layout.setPadding(layout.paddingStart, 14.dpToPx(), layout.paddingEnd, 12.dpToPx())
            }
            cs.applyTo(layout)
        }

        // In mini mode, disable RV scrolling so touch events fall through to ViewMover for drag
        if (isMiniEffective) {
            if (miniTouchInterceptor == null) {
                miniTouchInterceptor = object : RecyclerView.OnItemTouchListener {
                    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent) = true
                    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                        // Forward to ViewMover directly for window dragging
                        lyricsView?.let { viewMover?.onTouch(it, e) }
                    }
                    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
                }
            }
            miniTouchInterceptor?.let { listener ->
                lyricsRecyclerView?.removeOnItemTouchListener(listener)
                lyricsRecyclerView?.addOnItemTouchListener(listener)
            }
        } else {
            miniTouchInterceptor?.let { lyricsRecyclerView?.removeOnItemTouchListener(it) }
        }

        // RecyclerView height
        lyricsRecyclerView?.let { rv ->
            val targetHeight = when (effectiveMode) {
                LyricsWindowMode.MINI -> ViewGroup.LayoutParams.WRAP_CONTENT
                LyricsWindowMode.COMPACT -> COLLAPSED_LYRICS_MAX_HEIGHT_DP.dpToPx()
                LyricsWindowMode.EXPANDED -> EXPANDED_LYRICS_MAX_HEIGHT_DP.dpToPx()
            }
            val currentParams = rv.layoutParams
            if (currentParams.height != targetHeight) {
                currentParams.height = targetHeight
                rv.layoutParams = currentParams
            }
        }

        // Expand/collapse button icon — reflects what the NEXT tap will do
        val willExpand = effectiveMode == LyricsWindowMode.MINI ||
            (effectiveMode == LyricsWindowMode.COMPACT && lyricsWindowModeExpanding)
        expandCollapseButton?.setImageResource(
            if (willExpand) R.drawable.ic_round_keyboard_arrow_down_24
            else R.drawable.ic_round_keyboard_arrow_up_24
        )

        // Update adapter mode
        lyricsAdapter?.setWindowMode(isMiniEffective, effectiveMode == LyricsWindowMode.EXPANDED)

        applyOverlayArtwork(getCurrentAlbumArtBitmap())
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
        if (!matchesCurrentSongContextForLyrics(dataForHighlighting)) {
            Log.w(TAG, "Highlighting attempted for '${dataForHighlighting.title}/${dataForHighlighting.artist}' but current song context is '$lastDetectedSongTitle/$lastDetectedSongArtist'. Aborting.")
            return
        }

        val rawLines = if (isShowingTranslatedLyrics) {
            dataForHighlighting.translatedLines ?: dataForHighlighting.lines
        } else {
            dataForHighlighting.lines
        }
        val lines = prependIntroInstrumentalIfNeeded(rawLines)
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
                    // Ensure playing state is in sync so instrumental visualizers animate
                    lyricsAdapter?.setPlayingState(true)
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
                                // Slower speed for smoother, more fluid scrolling motion
                                return 150f / displayMetrics.densityDpi
                            }

                            override fun calculateTimeForDeceleration(dx: Int): Int {
                                // Longer deceleration for a gentler stop
                                return (super.calculateTimeForDeceleration(dx) * 1.4).toInt()
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
            mainHandler.post { hideLyricsWindow() }
            return
        }
        Log.d(TAG, "hideLyricsWindow() called. LyricsView Null: ${lyricsView == null}, Attached: ${lyricsView?.isAttachedToWindow}")
        lyricsHighlightingJob?.cancel(); lyricsHighlightingJob = null

        persistWindowPositionIfEnabled()

        if (detachKnownLyricsOverlayViews("hideLyricsWindow")) {
            clearLyricsWindowReferences()
            Log.d(TAG, "Lyrics UI components cleared after hide.")
        } else {
            Log.w(TAG, "hideLyricsWindow: At least one overlay could not be detached yet. Keeping references for retry to avoid orphaned duplicates.")
        }

        if (isServiceManuallyStarted.get()) {
            updatePersistentNotification(buildStatusNotificationText())
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
    /**
     * Updates cached lyrics for display: changes artist to match current request and adds "(cache)" attribution.
     * This prevents artist mismatch rejections when fuzzy matching finds cached lyrics with different artist variants.
     */
    private fun updateCachedLyricsForDisplay(lyrics: LyricsData, currentArtist: String): LyricsData {
        // First update the artist to match current request
        val lyricsWithUpdatedArtist = when (lyrics) {
            is LyricsData.Synced -> lyrics.copy(artist = currentArtist.ifEmpty { null })
            is LyricsData.Plain -> lyrics.copy(artist = currentArtist.ifEmpty { null })
            is LyricsData.Info -> lyrics.copy(artist = currentArtist.ifEmpty { null })
            else -> lyrics
        }

        // Then add cache attribution
        return updateAttributionForCache(lyricsWithUpdatedArtist)
    }

    /**
     * Adds attribution text to cached lyrics with "(cache)" suffix.
     */
    private fun updateAttributionForCache(lyrics: LyricsData): LyricsData {
        return when (lyrics) {
            is LyricsData.Synced -> {
                // Check if attribution already exists (shouldn't happen, but be safe)
                val hasAttribution = lyrics.lines.any { it.timestamp == ATTRIBUTION_TIMESTAMP }

                val updatedLines = if (hasAttribution) {
                    // Update existing attribution
                    lyrics.lines.map { line ->
                        if (line.timestamp == ATTRIBUTION_TIMESTAMP) {
                            when {
                                line.text == MUSIXMATCH_ATTRIBUTION -> line.copy(text = MUSIXMATCH_ATTRIBUTION_CACHE)
                                line.text.startsWith("Lyrics provided by ") && !line.text.contains("(cached)", ignoreCase = true) ->
                                    line.copy(text = "${line.text} (cached)")
                                else -> line
                            }
                        } else {
                            line
                        }
                    }
                } else {
                    // Add new attribution line
                    lyrics.lines.toMutableList().apply {
                        add(TimedLyricLine(ATTRIBUTION_TIMESTAMP, "Lyrics provided by cache"))
                    }
                }

                val updatedTranslatedLines = lyrics.translatedLines?.let { translatedLines ->
                    val hasTranslatedAttribution = translatedLines.any { it.timestamp == ATTRIBUTION_TIMESTAMP }
                    if (hasTranslatedAttribution) {
                        translatedLines.map { line ->
                            if (line.timestamp == ATTRIBUTION_TIMESTAMP) {
                                when {
                                    line.text == MUSIXMATCH_ATTRIBUTION -> line.copy(text = MUSIXMATCH_ATTRIBUTION_CACHE)
                                    line.text.startsWith("Lyrics provided by ") && !line.text.contains("(cached)", ignoreCase = true) ->
                                        line.copy(text = "${line.text} (cached)")
                                    else -> line
                                }
                            } else {
                                line
                            }
                        }
                    } else {
                        translatedLines.toMutableList().apply {
                            add(TimedLyricLine(ATTRIBUTION_TIMESTAMP, "Lyrics provided by cache"))
                        }
                    }
                }

                lyrics.copy(lines = updatedLines, translatedLines = updatedTranslatedLines)
            }
            is LyricsData.Plain -> {
                // Plain lyrics don't have attribution lines in the same way
                lyrics
            }
            else -> lyrics
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
        if (currentNotificationText != text) {
            currentNotificationText = text
            Log.d(TAG, "Updating persistent notification: $text")
            notificationManager.notify(NOTIFICATION_ID, createPersistentNotification(text))
        }
    }

    private fun registerActivity() {
        lastListenerHeartbeatMs.set(SystemClock.elapsedRealtime())
        consecutiveListenerRecoveryAttempts.set(0)

        // If the notification shows a stale/error state but we have active song info, restore it.
        val currentText = currentNotificationText
        if (currentText == "Waiting for songs.." ||
            currentText == "Waiting for notification listener..." ||
            currentText == "Error accessing notifications. Retrying..." ||
            currentText == "Listener disconnected. Check permissions.") {

            if (!lastDetectedSongTitle.isNullOrBlank()) {
                Log.i(TAG, "registerActivity: Restoring notification from stale state '$currentText' to active song info.")
                updatePersistentNotification(buildStatusNotificationText())
            }
        }
    }

    private fun buildStatusNotificationText(): String {
        if (!hasNotificationAccess()) {
            return "Notification access missing. Tap to fix."
        }

        if (!refreshListenerBindingState("buildStatusNotificationText")) {
            return "Waiting for notification listener..."
        }

        val title = lastDetectedSongTitle
        val artist = lastDetectedSongArtist
        return if (title.isNullOrBlank()) {
            "Waiting for song..."
        } else {
            val songInfoForDisplay = artist?.takeIf { it.isNotBlank() }?.let { "$title by $it" } ?: title
            "Lyrics for: $songInfoForDisplay"
        }
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
        } else if (!refreshListenerBindingState("evaluateListenerHealth")) {
            // Don't show floating window for status messages - only use persistent notification
            // maybeShowStatusInfo("Waiting for notification listener connection… If stuck here, Go to the app's help and support section and follow the steps under question 1. ")
            updatePersistentNotification("Waiting for notification listener...")
            requestNotificationListenerRebind("Health check: listener never connected")
            maybeResetNotificationListenerComponent("Health check: listener never connected")
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
                // maybeShowStatusInfo("Refreshing notification listener… Feel free to close this window")
                updatePersistentNotification("Waiting for songs..")
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
        maybeLogNotificationAccessDebug("handleMissingNotificationAccess: $reason")
        updatePersistentNotification("Notification access missing. Tap to fix.")
        //maybeShowStatusInfo("Notification access missing. If stuck here, Go to the app's help and support section and follow the steps under question 1. ")
    }

    private fun maybeLogNotificationAccessDebug(
        reason: String,
        enabledPackages: Set<String>? = null,
        enabledListeners: String? = null,
        componentName: ComponentName? = null
    ) {
        val now = SystemClock.elapsedRealtime()
        val last = lastNotificationAccessDebugLogMs.get()
        if (now - last < TimeUnit.SECONDS.toMillis(20)) {
            return
        }
        if (!lastNotificationAccessDebugLogMs.compareAndSet(last, now)) {
            return
        }

        val resolvedComponent = componentName ?: ComponentName(this, javaClass)
        val resolvedPackages = enabledPackages ?: NotificationManagerCompat.getEnabledListenerPackages(this)
        val resolvedListeners = enabledListeners ?: Settings.Secure.getString(
            contentResolver,
            ENABLED_NOTIFICATION_LISTENERS_KEY
        )
        val flat = resolvedComponent.flattenToString()
        val short = resolvedComponent.flattenToShortString()

        Log.d(
            TAG,
            "Notification access debug ($reason): enabledPackages=$resolvedPackages, enabledListeners=$resolvedListeners, componentFlat=$flat, componentShort=$short"
        )
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

        mainHandler.post {
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
        // Capture restart decision state FIRST, before any cleanup
        val wasManuallyStarted = isServiceManuallyStarted.get()
        Log.i(TAG, "Service onDestroy(). Instance: ${this.hashCode()}. isServiceManuallyStarted: $wasManuallyStarted. Current token: $currentMediaSessionToken. ServiceJob Active: ${serviceJob.isActive}")
        
        // Schedule restart BEFORE cleanup and super.onDestroy() to ensure reliable restart
        // on all Android builds, including OEM-modified ones that may clear state early
        if (wasManuallyStarted) {
            Log.i(TAG, "onDestroy: Service was manually started, scheduling restart before cleanup.")
            scheduleServiceRestart("Service destroyed while marked as manually started")
        }
        
        // Now proceed with cleanup
        isServiceManuallyStarted.set(false)
        cancelPendingSongContextClear("onDestroy")

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
        _listenerCurrentlyBound = false

        mainHandler.removeCallbacks(executeFindActiveMediaSessionsRunnable)
        cancelListenerHealthChecks("onDestroy")
        runCatching { httpClient.close() }
            .onFailure { Log.w(TAG, "onDestroy: Failed to close HTTP client cleanly: ${it.message}") }
        Log.i(TAG, "Service fully destroyed. Instance: ${this.hashCode()}")
        super.onDestroy()
    }

    private inner class ViewMover(private val targetView: View) : View.OnTouchListener {
        private var initialX: Int = 0; private var initialY: Int = 0
        private var initialTouchX: Float = 0f; private var initialTouchY: Float = 0f
        private val touchSlop by lazy { android.view.ViewConfiguration.get(applicationContext).scaledTouchSlop }
        private var isDragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN || lyricsView !== targetView) {
                if (handleLyricsOverlayInteraction(targetView, "drag")) {
                    return true
                }
            }

            val currentParams = this@LyricService.params ?: return false
            val currentWindowManager = this@LyricService.windowManager ?: return false
            if (!targetView.isAttachedToWindow) return false

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
                        try { currentWindowManager.updateViewLayout(targetView, currentParams) }
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
