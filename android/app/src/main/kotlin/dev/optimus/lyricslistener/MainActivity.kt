package dev.optimus.lyricslistener

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.app.NotificationManagerCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class MainActivity : FlutterActivity() {
    private val CHANNEL = "dev.optimus.lyricslistener/permissions"
    private val DEBUG_LOG_CHANNEL = "dev.optimus.lyricslistener/debugLogs"
    private val POST_NOTIFICATIONS_REQUEST_CODE = 101
    private val ENABLED_NOTIFICATION_LISTENERS_KEY = "enabled_notification_listeners"
    private val customLrcLinePattern: Pattern =
        Pattern.compile("^\\[(\\d{1,}):(\\d{1,2})(?:([.:])(\\d{1,3}))?\\](.*)$")
    private val timestampLikeTagBodyPattern: Pattern =
        Pattern.compile("^\\d{1,3}:\\d{1,3}(?:[.:]\\d{0,4})?$")
    private val lrcMetadataTagPattern: Pattern =
        Pattern.compile("^\\[[A-Za-z]{1,12}\\s*:[^\\]]*]$")
    private val lrcMetadataTagPrefixPattern: Pattern =
        Pattern.compile("^\\[([A-Za-z]{1,12})\\s*:[^\\]]*\\](.*)$")
    private val supportedLrcMetadataKeys =
        setOf(
            "ar",
            "artist",
            "ti",
            "title",
            "al",
            "album",
            "au",
            "by",
            "offset",
            "length",
            "total",
            "re",
            "ve",
            "la",
            "language",
            "kana",
            "id",
            "hash",
            "sign",
            "qq",
            "encoding"
        )

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, DEBUG_LOG_CHANNEL)
            .setStreamHandler(LogStreamHandler())
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getAndroidVersion" -> {
                    result.success(Build.VERSION.SDK_INT)
                }
                "isNotificationAccessGranted" -> {
                    try {
                        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(this)
                        if (enabledPackages.contains(packageName)) {
                            result.success(true)
                            return@setMethodCallHandler
                        }

                        val enabledListeners = Settings.Secure.getString(contentResolver, ENABLED_NOTIFICATION_LISTENERS_KEY)
                        val notificationListener = ComponentName(this, LyricService::class.java)
                        val flat = notificationListener.flattenToString()
                        val short = notificationListener.flattenToShortString()

                        if (enabledListeners.isNullOrBlank()) {
                            Log.d(
                                "MainActivity",
                                "Notification access debug (isNotificationAccessGranted): enabledPackages=$enabledPackages, enabledListeners=$enabledListeners, componentFlat=$flat, componentShort=$short"
                            )
                            result.success(false)
                            return@setMethodCallHandler
                        }

                        val hasAccess = enabledListeners.contains(flat) || enabledListeners.contains(short)
                        if (!hasAccess) {
                            Log.d(
                                "MainActivity",
                                "Notification access debug (isNotificationAccessGranted): enabledPackages=$enabledPackages, enabledListeners=$enabledListeners, componentFlat=$flat, componentShort=$short"
                            )
                        }
                        result.success(hasAccess)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error checking notification access: ${e.message}")
                        result.error("ERROR_NOTIFICATION_ACCESS", e.message, null)
                    }
                }
                "requestNotificationAccess" -> { // This method already opens the settings page
                    try {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        startActivity(intent)
                        result.success(null)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error opening notification settings: ${e.message}")
                        result.error("ERROR_OPEN_NOTIFICATION_SETTINGS", e.message, null)
                    }
                }
                "openNotificationSettings" -> { // Added method to explicitly open notification listener settings
                    try {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        startActivity(intent)
                        result.success(null)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error opening notification listener settings page: ${e.message}")
                        result.error("ERROR_OPEN_NOTIFICATION_LISTENER_SETTINGS", e.message, null)
                    }
                }
                "canDrawOverlays" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        result.success(Settings.canDrawOverlays(this))
                    } else {
                        result.success(true)
                    }
                }
                "requestOverlayPermission" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                            startActivity(intent)
                            result.success(null)
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error opening overlay settings: ${e.message}")
                            result.error("ERROR_OPEN_OVERLAY_SETTINGS", e.message, null)
                        }
                    } else {
                        result.success(null)
                    }
                }
                "isPostNotificationsGranted" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        result.success(NotificationManagerCompat.from(this).areNotificationsEnabled())
                    } else {
                        result.success(true)
                    }
                }
                "requestPostNotifications" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                            result.success(true)
                        } else {
                            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), POST_NOTIFICATIONS_REQUEST_CODE)
                            // Result for this will be handled by onRequestPermissionsResult and Flutter side will re-check status on resume
                        }
                    } else {
                        result.success(true)
                    }
                }
                "startLyricService" -> {
                    try {
                        val serviceIntent = Intent(this, LyricService::class.java).apply {
                            action = LyricService.ACTION_USER_INITIATED_START
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
                        result.success(null)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error starting LyricService: ${e.message}")
                        result.error("ERROR_START_SERVICE", e.message, null)
                    }
                }
                "stopLyricService" -> {
                    try {
                        val serviceIntent = Intent(this, LyricService::class.java).apply {
                            action = LyricService.ACTION_USER_INITIATED_STOP
                        }
                         // We still need to call startService/startForegroundService to deliver the stop intent
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
                        // LyricService itself will call stopSelf() or stopForeground(true)
                        result.success(null)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error stopping LyricService: ${e.message}")
                        result.error("ERROR_STOP_SERVICE", e.message, null)
                    }
                }
                "isLyricServiceRunning" -> {
                    val isManuallyStarted = LyricService.isServiceManuallyStarted.get()
                    val isActuallyRunning = isServiceProcessRunning(LyricService::class.java)
                    Log.d("MainActivity", "isLyricServiceRunning: ManuallyStarted=$isManuallyStarted, ProcessRunning=$isActuallyRunning")
                    result.success(isManuallyStarted && isActuallyRunning)
                }
                "isIgnoringBatteryOptimizations" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                        result.success(powerManager.isIgnoringBatteryOptimizations(packageName))
                    } else {
                        result.success(true)
                    }
                }
                "requestDisableBatteryOptimization" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            val intent = Intent()
                            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                            if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                                result.success(true) // Already ignoring
                                return@setMethodCallHandler
                            }
                            intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                            intent.data = Uri.parse("package:$packageName")
                            startActivity(intent)
                            result.success(null) // User will be taken to settings
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Could not open specific battery optimization request: ${e.message}")
                            try {
                                // Fallback to general battery optimization settings if specific one fails
                                val generalIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                startActivity(generalIntent)
                                result.success(null) // User will be taken to settings
                            } catch (se: Exception) {
                                Log.e("MainActivity", "Could not open general battery optimization settings: ${se.message}")
                                result.error("BATTERY_OPTIMIZATION_SETTINGS_UNAVAILABLE", "Battery optimization settings could not be opened.", null)
                            }
                        }
                    } else {
                        result.success(true) // Not applicable for older versions
                    }
                }
                "clearLyricsCache" -> {
                    try {
                        val cacheManager = LyricsCacheManager(this)
                        val clearedCount = cacheManager.clearAll()
                        Log.d("MainActivity", "Cleared $clearedCount cached lyrics")
                        result.success(clearedCount)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error clearing lyrics cache: ${e.message}")
                        result.error("ERROR_CLEAR_CACHE", e.message, null)
                    }
                }
                "getCachedLyricsList" -> {
                    try {
                        val cacheManager = LyricsCacheManager(this)
                        val entries = cacheManager.getAllEntries()
                        result.success(entries)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error getting cached lyrics list: ${e.message}")
                        result.error("ERROR_GET_CACHE_LIST", e.message, null)
                    }
                }
                "getCachedLyricsContent" -> {
                    try {
                        val cacheKey = call.argument<String>("cacheKey")
                        if (cacheKey == null) {
                            result.error("ERROR_MISSING_PARAM", "cacheKey is required", null)
                            return@setMethodCallHandler
                        }
                        val cacheManager = LyricsCacheManager(this)
                        val entry = cacheManager.getEntry(cacheKey)
                        if (entry != null) {
                            result.success(entry)
                        } else {
                            result.error("ERROR_NOT_FOUND", "Cache entry not found", null)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error getting cached lyrics content: ${e.message}")
                        result.error("ERROR_GET_CACHE_CONTENT", e.message, null)
                    }
                }
                "updateCachedLyrics" -> {
                    try {
                        val cacheKey = call.argument<String>("cacheKey")
                        val updatedData = call.argument<Map<String, Any?>>("data")
                        if (cacheKey == null || updatedData == null) {
                            result.error("ERROR_MISSING_PARAM", "cacheKey and data are required", null)
                            return@setMethodCallHandler
                        }
                        val cacheManager = LyricsCacheManager(this)
                        val success = cacheManager.updateEntry(cacheKey, updatedData)
                        result.success(success)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error updating cached lyrics: ${e.message}")
                        result.error("ERROR_UPDATE_CACHE", e.message, null)
                    }
                }
                "deleteCachedLyrics" -> {
                    try {
                        val cacheKey = call.argument<String>("cacheKey")
                        if (cacheKey == null) {
                            result.error("ERROR_MISSING_PARAM", "cacheKey is required", null)
                            return@setMethodCallHandler
                        }
                        val cacheManager = LyricsCacheManager(this)
                        val success = cacheManager.deleteEntry(cacheKey)
                        result.success(success)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error deleting cached lyrics: ${e.message}")
                        result.error("ERROR_DELETE_CACHE", e.message, null)
                    }
                }
                "addCustomLyrics" -> {
                    try {
                        val title = call.argument<String>("title")?.trim().orEmpty()
                        val artist = call.argument<String>("artist")?.trim().orEmpty()
                        val lrc = call.argument<String>("lrc").orEmpty()
                        val overwriteExisting = call.argument<Boolean>("overwriteExisting") ?: false

                        if (title.isBlank()) {
                            result.error("ERROR_INVALID_INPUT", "Title is required.", null)
                            return@setMethodCallHandler
                        }
                        if (artist.isBlank()) {
                            result.error("ERROR_INVALID_INPUT", "Artist is required.", null)
                            return@setMethodCallHandler
                        }
                        if (lrc.isBlank()) {
                            result.error("ERROR_INVALID_INPUT", "LRC is required.", null)
                            return@setMethodCallHandler
                        }

                        val parsedLines = parseAndValidateCustomLrc(lrc)
                        val cacheManager = LyricsCacheManager(this)
                        val exactMatches = cacheManager.findExactEntries(artist, title)

                        if (exactMatches.isNotEmpty() && !overwriteExisting) {
                            result.success(
                                mapOf(
                                    "status" to "conflict",
                                    "matchCount" to exactMatches.size,
                                    "matches" to exactMatches
                                )
                            )
                            return@setMethodCallHandler
                        }

                        val overwrittenCount = if (overwriteExisting) {
                            cacheManager.deleteExactEntries(artist, title)
                        } else {
                            0
                        }

                        val customLyrics = LyricsData.Synced(
                            title = title,
                            artist = artist,
                            lines = parsedLines,
                            translatedLines = null,
                            durationMs = 0L
                        )
                        cacheManager.put(
                            artist = artist,
                            title = title,
                            durationMs = 0L,
                            lyrics = customLyrics,
                            source = "custom"
                        )

                        result.success(
                            mapOf(
                                "status" to "saved",
                                "lineCount" to parsedLines.size,
                                "overwrittenCount" to overwrittenCount
                            )
                        )
                    } catch (e: IllegalArgumentException) {
                        result.error("ERROR_INVALID_LRC", e.message ?: "Invalid LRC.", null)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error adding custom lyrics: ${e.message}", e)
                        result.error("ERROR_ADD_CUSTOM_LYRICS", e.message, null)
                    }
                }
                "startDebugActiveMediaNotification" -> {
                    try {
                        val intent = Intent(this, LyricService::class.java).apply {
                            action = LyricService.ACTION_DEBUG_ACTIVE_NOTIFICATION
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        result.success(null)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error starting debug action: ${e.message}")
                        result.error("ERROR_START_DEBUG", e.message, null)
                    }
                }
                "getMusixmatchTokenAvailable" -> {
                    result.success(LyricService.isMusixmatchTokenAvailableForDebug.get())
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private fun parseAndValidateCustomLrc(rawLrc: String): List<TimedLyricLine> {
        val lrcText = normalizeCustomLrcInput(rawLrc)
        if (lrcText.isBlank()) {
            throw IllegalArgumentException("LRC is empty.")
        }

        val parsedLines = mutableListOf<TimedLyricLine>()
        var parsedTimestampCount = 0

        lrcText.lines().forEachIndexed { index, originalLine ->
            val lineNumber = index + 1
            var currentTextSegment = originalLine.trim()
            if (currentTextSegment.isBlank()) {
                return@forEachIndexed
            }

            currentTextSegment = stripLeadingSupportedLrcMetadataTags(currentTextSegment)
            if (currentTextSegment.isBlank()) {
                return@forEachIndexed
            }

            while (currentTextSegment.isNotBlank()) {
                val timedPlaceholders = mutableListOf<TimedLyricLine>()

                while (currentTextSegment.startsWith("[")) {
                    val matcher = customLrcLinePattern.matcher(currentTextSegment)
                    if (matcher.matches()) {
                        val minutes = matcher.group(1)!!.toIntOrNull()
                        val seconds = matcher.group(2)!!.toIntOrNull()
                        val millisRaw = matcher.group(4)
                        if (minutes == null || seconds == null) {
                            throw IllegalArgumentException("Invalid timestamp number on line $lineNumber.")
                        }
                        if (seconds !in 0..59) {
                            throw IllegalArgumentException("Invalid seconds value on line $lineNumber. Use 00-59.")
                        }

                        val milliseconds = when (millisRaw?.length ?: 0) {
                            0 -> 0
                            1 -> millisRaw?.toIntOrNull()?.times(100)
                            2 -> millisRaw?.toIntOrNull()?.times(10)
                            3 -> millisRaw?.toIntOrNull()
                            else -> null
                        } ?: throw IllegalArgumentException("Invalid milliseconds value on line $lineNumber.")

                        if (milliseconds !in 0..999) {
                            throw IllegalArgumentException("Invalid milliseconds value on line $lineNumber.")
                        }

                        val timestampMs =
                            TimeUnit.MINUTES.toMillis(minutes.toLong()) +
                                TimeUnit.SECONDS.toMillis(seconds.toLong()) +
                                milliseconds.toLong()

                        timedPlaceholders.add(TimedLyricLine(timestampMs, ""))
                        parsedTimestampCount++
                        currentTextSegment = matcher.group(5)?.trimStart().orEmpty()
                    } else {
                        break
                    }
                }

                if (currentTextSegment.startsWith("[")) {
                    throw IllegalArgumentException(
                        "Invalid LRC tag on line $lineNumber. Use [m:ss], [m:ss.xx], or [m:ss.xxx]."
                    )
                }

                if (timedPlaceholders.isEmpty()) {
                    throw IllegalArgumentException(
                        "Line $lineNumber is missing a timestamp. Each lyric line must start with [m:ss], [m:ss.xx], or [m:ss.xxx]."
                    )
                }

                val nextTimestampIndex = findNextTimestampStartOrThrow(currentTextSegment, lineNumber)
                val lyricTextForTimestamps =
                    if (nextTimestampIndex >= 0) {
                        currentTextSegment.substring(0, nextTimestampIndex)
                    } else {
                        currentTextSegment
                    }

                val displayText = lyricTextForTimestamps.trim().ifBlank { "🎶 ... 🎶" }
                timedPlaceholders.forEach { placeholder ->
                    parsedLines.add(placeholder.copy(text = displayText))
                }

                currentTextSegment =
                    if (nextTimestampIndex >= 0) {
                        currentTextSegment.substring(nextTimestampIndex).trimStart()
                    } else {
                        ""
                    }
            }
        }

        if (parsedTimestampCount == 0 || parsedLines.isEmpty()) {
            throw IllegalArgumentException(
                "No valid timed LRC lines were found. Add at least one line like [0:12], [0:12.34], or [0:12.345] Hello."
            )
        }

        parsedLines.sortBy { it.timestamp }
        return parsedLines
    }

    private fun findNextTimestampStartOrThrow(text: String, lineNumber: Int): Int {
        var searchIndex = 0
        while (true) {
            val bracketIndex = text.indexOf('[', searchIndex)
            if (bracketIndex < 0) {
                return -1
            }

            val candidate = text.substring(bracketIndex)
            val matcher = customLrcLinePattern.matcher(candidate)
            if (matcher.matches()) {
                return bracketIndex
            }

            if (looksLikeMalformedTimestampTag(candidate)) {
                throw IllegalArgumentException(
                    "Invalid LRC tag on line $lineNumber. Use [m:ss], [m:ss.xx], or [m:ss.xxx]."
                )
            }

            searchIndex = bracketIndex + 1
        }
    }

    private fun looksLikeMalformedTimestampTag(text: String): Boolean {
        if (!text.startsWith("[")) {
            return false
        }

        val closingBracketIndex = text.indexOf(']')
        if (closingBracketIndex !in 2..16) {
            return false
        }

        val tagBody = text.substring(1, closingBracketIndex)
        return timestampLikeTagBodyPattern.matcher(tagBody).matches()
    }

    private fun normalizeCustomLrcInput(rawLrc: String): String {
        return rawLrc
            .replace("\uFEFF", "")
            .replace('\uFF3B', '[') // full-width [
            .replace('\uFF3D', ']') // full-width ]
            .replace('\uFF1A', ':') // full-width :
            .replace('\uFF0E', '.') // full-width .
            .replace('\u3000', ' ') // ideographic space
            .replace('\u00A0', ' ') // non-breaking space
    }

    private fun stripLeadingSupportedLrcMetadataTags(text: String): String {
        var remaining = text.trim()
        while (remaining.startsWith("[")) {
            val matcher = lrcMetadataTagPrefixPattern.matcher(remaining)
            if (!matcher.matches()) {
                break
            }

            val key = matcher.group(1)?.lowercase() ?: break
            if (!supportedLrcMetadataKeys.contains(key)) {
                break
            }

            remaining = matcher.group(2)?.trimStart().orEmpty()
        }

        return remaining
    }

    private fun isSupportedLrcMetadataTag(line: String): Boolean {
        val trimmed = line.trim()
        if (!lrcMetadataTagPattern.matcher(trimmed).matches()) {
            return false
        }

        val matcher = lrcMetadataTagPrefixPattern.matcher(trimmed)
        if (!matcher.matches()) {
            return false
        }

        val key = matcher.group(1)?.lowercase() ?: return false
        return supportedLrcMetadataKeys.contains(key)
    }

    private class LogStreamHandler : EventChannel.StreamHandler {
        private var logcatProcess: Process? = null
        private var readerThread: Thread? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            try {
                ProcessBuilder("logcat", "-c").start().waitFor()
            } catch (ignored: Exception) {
            }

            val processBuilder = ProcessBuilder(
                "logcat",
                "-v",
                "time",
                "LyricService:D",
                "MainActivity:D",
                "*:S"
            )

            readerThread = Thread {
                try {
                    logcatProcess = processBuilder.start()
                    val process = logcatProcess ?: return@Thread
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val message = line
                            if (message != null) {
                                mainHandler.post {
                                    events?.success(message)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        events?.error("LOG_STREAM_ERROR", e.message, null)
                    }
                }
            }.also { it.start() }
        }

        override fun onCancel(arguments: Any?) {
            try {
                logcatProcess?.destroy()
            } catch (ignored: Exception) {
            }
            readerThread?.interrupt()
            logcatProcess = null
            readerThread = null
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceProcessRunning(serviceClass: Class<*>): Boolean {
        try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
            // On newer Android versions, getRunningServices is restricted for 3rd party apps.
            // This method might not be reliable for determining if the service *process* is running.
            // Relying on LyricService.isServiceManuallyStarted and LyricService.isServiceRunningInternal
            // is generally better for the service's own state.
            // However, this check can still be a fallback or supplemental information.
            manager?.getRunningServices(Integer.MAX_VALUE)?.forEach { service ->
                if (serviceClass.name == service.service.className) {
                    Log.d("MainActivity", "Service process ${serviceClass.name} found in getRunningServices.")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error checking service process status: ${e.message}")
            return false // If error, assume not running via this check
        }
        Log.d("MainActivity", "Service process ${serviceClass.name} NOT found in getRunningServices.")
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == POST_NOTIFICATIONS_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("MainActivity", "POST_NOTIFICATIONS permission granted.")
                // Flutter side will re-check on resume via didChangeAppLifecycleState
            } else {
                Log.d("MainActivity", "POST_NOTIFICATIONS permission denied.")
                // Flutter side will re-check on resume
            }
        }
    }
}
