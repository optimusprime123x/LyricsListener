package dev.optimus.lyricslistener

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.app.NotificationManagerCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "dev.optimus.lyricslistener/permissions"
    private val POST_NOTIFICATIONS_REQUEST_CODE = 101

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getAndroidVersion" -> {
                    result.success(Build.VERSION.SDK_INT)
                }
                "isNotificationAccessGranted" -> {
                    try {
                        val notificationListener = ComponentName(this, LyricService::class.java).flattenToString()
                        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                        result.success(enabledListeners != null && enabledListeners.contains(notificationListener))
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error checking notification access: ${e.message}")
                        result.error("ERROR_NOTIFICATION_ACCESS", e.message, null)
                    }
                }
                "requestNotificationAccess" -> {
                    try {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        startActivity(intent)
                        result.success(null)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error opening notification settings: ${e.message}")
                        result.error("ERROR_OPEN_NOTIFICATION_SETTINGS", e.message, null)
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
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
                    // The service is considered "running" if the app intends it to be AND its process is found.
                    // After a stop, isManuallyStarted will be false.
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
                                result.success(true)
                                return@setMethodCallHandler
                            }
                            intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                            intent.data = Uri.parse("package:$packageName")
                            startActivity(intent)
                            result.success(null)
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Could not open specific battery optimization request: ${e.message}")
                            try {
                                val generalIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                startActivity(generalIntent)
                                result.success(null)
                            } catch (se: Exception) {
                                Log.e("MainActivity", "Could not open general battery optimization settings: ${se.message}")
                                result.error("BATTERY_OPTIMIZATION_SETTINGS_UNAVAILABLE", "Battery optimization settings could not be opened.", null)
                            }
                        }
                    } else {
                        result.success(true)
                    }
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceProcessRunning(serviceClass: Class<*>): Boolean {
        try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
            manager?.getRunningServices(Integer.MAX_VALUE)?.forEach { service ->
                if (serviceClass.name == service.service.className) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error checking service process status: ${e.message}")
            return false
        }
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == POST_NOTIFICATIONS_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("MainActivity", "POST_NOTIFICATIONS permission granted.")
            } else {
                Log.d("MainActivity", "POST_NOTIFICATIONS permission denied.")
            }
        }
    }
}