package com.example.srtsender

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.srtsender.BuildConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class UpdateManager(private val activity: Activity) {

    private val TAG = "UpdateManager"
    private var downloadId: Long = -1
    private val UPDATE_URL = "https://srtm.kankrittapon.online/version.json"

    data class AppVersion(
        val versionCode: Int = 0,
        val versionName: String = "",
        val downloadUrl: String = "",
        val releaseNotes: String = ""
    )

    fun checkUpdate(isManualCheck: Boolean = false) {
        thread {
            try {
                val url = URL(UPDATE_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    val json = JSONObject(response.toString())
                    val remoteVersion = AppVersion(
                        versionCode = json.optInt("versionCode", 0),
                        versionName = json.optString("versionName", ""),
                        downloadUrl = json.optString("downloadUrl", ""),
                        releaseNotes = json.optString("releaseNotes", "")
                    )

                    activity.runOnUiThread {
                        processUpdate(remoteVersion, isManualCheck)
                    }
                } else {
                    Log.e(TAG, "Failed to fetch version: Code ${conn.responseCode}")
                    if (isManualCheck) {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Failed to check updates (HTTP ${conn.responseCode})", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking update", e)
                if (isManualCheck) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun processUpdate(remoteVersion: AppVersion, isManualCheck: Boolean) {
        val currentVersionCode = BuildConfig.VERSION_CODE
        Log.d(TAG, "Current: $currentVersionCode, Remote: ${remoteVersion.versionCode}")

        if (remoteVersion.versionCode > currentVersionCode) {
            showUpdateDialog(remoteVersion)
        } else if (isManualCheck) {
            Toast.makeText(activity, "You are using the latest version.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showUpdateDialog(version: AppVersion) {
        if (activity.isFinishing) return

        AlertDialog.Builder(activity)
            .setTitle("New Update Available 🚀")
            .setMessage("Version: ${version.versionName}\n\n${version.releaseNotes}\n\nUpdate now?")
            .setPositiveButton("Update") { _, _ ->
                downloadApk(version.downloadUrl)
            }
            .setNegativeButton("Later", null)
            .setCancelable(false)
            .show()
    }

    private fun downloadApk(url: String) {
        if (url.isEmpty() || !url.startsWith("http")) {
            Toast.makeText(activity, "Invalid download URL", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(activity, "Downloading update...", Toast.LENGTH_SHORT).show()

        val fileName = "app-release.apk"
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Downloading Update")
                .setDescription("Downloading latest version of SRT Sender")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = downloadManager.enqueue(request)

            // Register receiver for download complete
            activity.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor = downloadManager.query(query)
                        if (cursor.moveToFirst()) {
                            val statusColumnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            if (statusColumnIndex != -1) {
                                val status = cursor.getInt(statusColumnIndex)
                                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                    installApk(fileName)
                                }
                            }
                        }
                        cursor.close()
                        try {
                            activity.unregisterReceiver(this)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        } catch (e: Exception) {
            Toast.makeText(activity, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun installApk(fileName: String) {
        try {
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
            if (!file.exists()) {
                Log.e(TAG, "APK file not found")
                Toast.makeText(activity, "Update failed: File not found", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.provider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            Toast.makeText(activity, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
