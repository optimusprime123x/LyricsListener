package dev.optimus.lyricslistener 

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager // For Battery Optimization
import android.provider.Settings
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.app.NotificationManagerCompat // For checking POST_NOTIFICATIONS
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "dev.optimus.lyricslistener/permissions"
    private val POST_NOTIFICATIONS_REQUEST_CODE = 101 // Define request code

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
                        // Optional: Add flag if you want to ensure this activity is brought to front if already running in task.
                        // intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13 (API 33)
                        result.success(NotificationManagerCompat.from(this).areNotificationsEnabled())
                    } else {
                        result.success(true)
                    }
                }
                "requestPostNotifications" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // Check if permission is already granted
                        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                            result.success(true) // Already granted
                        } else {
                            // Request permission. The result will be handled in onRequestPermissionsResult.
                            // Flutter side should re-check on resume.
                            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), POST_NOTIFICATIONS_REQUEST_CODE)
                            // We don't call result.success() here immediately because the request is async.
                            // The Dart side will re-query the status when the app resumes.
                            // For now, just acknowledge the call if needed, or rely on resume.
                            // result.success(null) // Or let it be handled by onResume
                        }
                    } else {
                        result.success(true) // Not needed for older versions
                    }
                }
                "startLyricService" -> {
                    try {
                        val serviceIntent = Intent(this, LyricService::class.java)
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
                "isLyricServiceRunning" -> {
                    result.success(isServiceRunning(LyricService::class.java))
                }
                // --- BATTERY OPTIMIZATION METHODS ---
                "isIgnoringBatteryOptimizations" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                        result.success(powerManager.isIgnoringBatteryOptimizations(packageName))
                    } else {
                        result.success(true) // Not applicable before Marshmallow
                    }
                }
                "requestDisableBatteryOptimization" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            val intent = Intent()
                            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                            if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                                Log.d("MainActivity", "App already ignoring battery optimizations.")
                                result.success(true) // Indicate it's already done
                                return@setMethodCallHandler
                            }
                            intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                            intent.data = Uri.parse("package:$packageName")
                            startActivity(intent)
                            result.success(null) // Acknowledge request was made
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Could not open specific battery optimization request: ${e.message}")
                            // Fallback to general battery settings if specific request fails
                            try {
                                val generalIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                startActivity(generalIntent)
                                result.success(null) // Acknowledge fallback request was made
                            } catch (se: Exception) {
                                Log.e("MainActivity", "Could not open general battery optimization settings: ${se.message}")
                                result.error("BATTERY_OPTIMIZATION_SETTINGS_UNAVAILABLE", "Battery optimization settings could not be opened.", null)
                            }
                        }
                    } else {
                        result.success(true) // Not applicable before Marshmallow
                    }
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    // Helper function to check if a service is running
    @Suppress("DEPRECATION") // Needed for getRunningServices on older Android versions
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
            manager?.getRunningServices(Integer.MAX_VALUE)?.forEach { service ->
                if (serviceClass.name == service.service.className) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error checking service status: ${e.message}")
            return false // Assume not running if an error occurs
        }
        return false
    }

    // Handle the result of runtime permissions requests (e.g., POST_NOTIFICATIONS)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == POST_NOTIFICATIONS_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("MainActivity", "POST_NOTIFICATIONS permission granted.")
                // Optionally, you could send an event back to Flutter here,
                // but the current Dart logic re-checks on resume, which is usually sufficient.
            } else {
                Log.d("MainActivity", "POST_NOTIFICATIONS permission denied.")
            }
        }
        // You can add more request codes here if you request other runtime permissions directly
    }

    // Optional: If you want to send an event to Dart when the activity resumes,
    // for example, after a permission is granted/denied from settings.
    // However, the Dart side's didChangeAppLifecycleState already handles re-checking.
    // override fun onResume() {
    //     super.onResume()
    //     // Example: MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod("activityResumed", null)
    // }
}