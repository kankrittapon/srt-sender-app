package com.example.srtsender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BootReceiver - Auto-start app on device boot
 * Only works when "Module Mode" is enabled in settings
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val PREF_MODULE_MODE = "module_mode_enabled"
        const val PREF_AUTO_STREAM = "auto_stream_enabled"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed received")

            val prefs = context.getSharedPreferences("SrtSenderPrefs", Context.MODE_PRIVATE)
            val isModuleMode = prefs.getBoolean(PREF_MODULE_MODE, false)
            val autoStream = prefs.getBoolean(PREF_AUTO_STREAM, false)

            if (isModuleMode) {
                Log.d(TAG, "Module mode enabled - starting app")
                
                // Get saved credentials
                val boatId = prefs.getString("boatId", null)
                val serverIp = prefs.getString("serverIp", null)
                val deviceRole = prefs.getString("deviceRole", "racing_boat")
                val hasVideo = prefs.getBoolean("hasVideo", true)
                val hasGps = prefs.getBoolean("hasGps", true)

                if (boatId != null && serverIp != null) {
                    // Start MainActivity directly
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("SERVER_IP", serverIp)
                        putExtra("BOAT_ID", boatId)
                        putExtra("DEVICE_ROLE", deviceRole)
                        putExtra("HAS_VIDEO", hasVideo)
                        putExtra("HAS_GPS", hasGps)
                        putExtra("AUTO_START", autoStream) // Signal to auto-start streaming
                    }
                    context.startActivity(launchIntent)
                    Log.d(TAG, "Launched MainActivity with boatId=$boatId, autoStream=$autoStream")
                } else {
                    // No saved credentials, open login
                    val launchIntent = Intent(context, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(launchIntent)
                    Log.d(TAG, "No credentials found, launching LoginActivity")
                }
            } else {
                Log.d(TAG, "Module mode disabled - not starting automatically")
            }
        }
    }
}
