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

class MainActivity : FlutterActivity() {
    private val CHANNEL = "dev.optimus.lyricslistener/permissions"
    private val DEBUG_LOG_CHANNEL = "dev.optimus.lyricslistener/debugLogs"
    private val POST_NOTIFICATIONS_REQUEST_CODE = 101
    private val ENABLED_NOTIFICATION_LISTENERS_KEY = "enabled_notification_listeners"

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
                else -> {
                    result.notImplemented()
                }
            }
        }
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
