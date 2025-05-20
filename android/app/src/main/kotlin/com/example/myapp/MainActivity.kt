package com.example.myapp

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.Manifest
import android.app.ActivityManager
import android.content.Context // Import Context


class MainActivity : FlutterActivity() {
    private val TAG = "MainActivity"
    private val PERMISSIONS_CHANNEL = "com.example.myapp/permissions"
    private lateinit var permissionsMethodChannel: MethodChannel

    private val POST_NOTIFICATIONS_PERMISSION_REQUEST_CODE = 100 // Define request code

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        permissionsMethodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, PERMISSIONS_CHANNEL)
        permissionsMethodChannel.setMethodCallHandler {
            call, result ->
            when (call.method) {
                "requestNotificationAccess" -> {
                    openNotificationListenerSettings()
                    result.success(null)
                }
                "requestOverlayPermission" -> {
                    openDrawOverOtherAppsSettings()
                    result.success(null)
                }
                "getAndroidVersion" -> {
                     result.success(Build.VERSION.SDK_INT)
                }
                "isNotificationAccessGranted" -> {
                    result.success(isNotificationServiceEnabled())
                }
                 "canDrawOverlays" -> {
                    result.success(canDrawOverlays())
                }
                 "isPostNotificationsGranted" -> {
                    result.success(isPostNotificationsGranted())
                 }
                 "requestPostNotifications" -> {
                     requestPostNotificationsPermission()
                     result.success(null)
                 }
                 "startLyricService" -> {
                     startLyricService()
                     result.success(null)
                 }
                 "isLyricServiceRunning" -> {
                    result.success(isServiceRunning(LyricService::class.java))
                 }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private fun isPostNotificationsGranted(): Boolean {
         return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestPostNotificationsPermission(){
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                 ActivityCompat.requestPermissions(this,
                     arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                     POST_NOTIFICATIONS_PERMISSION_REQUEST_CODE)
             }
         }
    }

    private fun openNotificationListenerSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
            Log.d(TAG, "Opening Notification Listener Settings.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Notification Listener Settings: ${e.message}")
            Toast.makeText(this, "Could not open notification access settings.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver,
            "enabled_notification_listeners")
        if (!TextUtils.isEmpty(flat)) {
            val names = flat.split(":").toTypedArray()
            for (name in names) {
                val cn = android.content.ComponentName.unflattenFromString(name)
                if (cn != null) {
                    if (TextUtils.equals(pkgName, cn.packageName)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun openDrawOverOtherAppsSettings() {
        try {
            // On Android M and above, the user must grant this permission specifically for the app
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                     val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + packageName))
                    startActivity(intent)
                     Log.d(TAG, "Opening Draw Over Other Apps Settings.")
                } else {
                    Log.d(TAG, "Draw Over Other Apps permission already granted.")
                    Toast.makeText(this, "Overlay permission already granted.", Toast.LENGTH_SHORT).show()
                }
            } else {
                 Log.d(TAG, "Draw Over Other Apps permission not needed on this Android version.")
                 Toast.makeText(this, "Overlay permission not needed on this Android version.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Draw Over Other Apps Settings: ${e.message}")
            Toast.makeText(this, "Could not open display over other apps settings.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun canDrawOverlays(): Boolean {
         return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

     private fun startLyricService() {
        Log.d(TAG, "Attempting to start LyricService.")
        // Use startForegroundService for services that perform foreground operations
        // This requires the FOREGROUND_SERVICE permission in the Manifest
        try {
            val serviceIntent = Intent(this, LyricService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            Log.d(TAG, "LyricService started.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting LyricService: ${e.message}")
             Toast.makeText(this, "Error starting lyric service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

     @Suppress("DEPRECATION") // Use deprecated method for broader compatibility check
     private fun isServiceRunning(serviceClass: Class<*>): Boolean {
         val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
         // Note: getRunningServices is deprecated and may not return all running services
         // on recent Android versions due to privacy and security restrictions.
         // A more reliable method would involve the service reporting its status.
         for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
             if (serviceClass.name == service.service.className) {
                 Log.d(TAG, "Service ${serviceClass.simpleName} is running.")
                 return true
             }
         }
         Log.d(TAG, "Service ${serviceClass.simpleName} is not running.")
         return false
     }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            POST_NOTIFICATIONS_PERMISSION_REQUEST_CODE -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    Log.d(TAG, "POST_NOTIFICATIONS permission granted.")
                     permissionsMethodChannel.invokeMethod("postNotificationsGranted", true)
                } else {
                    Log.w(TAG, "POST_NOTIFICATIONS permission denied.")
                     permissionsMethodChannel.invokeMethod("postNotificationsGranted", false)
                }
                return
            }
        }
    }

}
