package com.prakash.phub

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class SyncService : Service() {
    private var serverIp = ""
    private var authCode = ""
    private val client = OkHttpClient()
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var wakeLock: PowerManager.WakeLock? = null

    private var isSyncing = false
    private var totalFiles = 0
    private var downloadedCount = 0
    private var skippedCount = 0

    companion object {
        const val CHANNEL_ID = "P_HUB_SYNC"
        const val NOTIFICATION_ID = 1001
        const val ACTION_SYNC_PROGRESS = "com.prakash.pmusic.SYNC_PROGRESS"
    }

    private fun sendProgressBroadcast(
        currentFile: String = "",
        currentFileProgress: Float = -1f,
        status: String? = null,
        speed: String = "",
        timeRemaining: String = ""
    ) {
        val intent = Intent(ACTION_SYNC_PROGRESS).apply {
            setPackage(packageName)
            putExtra("currentFile", currentFile)
            putExtra("currentFileProgress", currentFileProgress)
            putExtra("downloadedCount", downloadedCount)
            putExtra("skippedCount", skippedCount)
            putExtra("totalFiles", totalFiles)
            putExtra("speed", speed)
            putExtra("timeRemaining", timeRemaining)
            status?.let { putExtra("status", it) }
        }
        sendBroadcast(intent)
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification("P-Hub Sync", text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun loadFileList(selectedFiles: List<String>?) {
        if (isSyncing) return
        isSyncing = true

        serviceScope.launch {
            try {
                val filesToDownload = if (selectedFiles != null && selectedFiles.isNotEmpty()) {
                    selectedFiles
                } else {
                    val request = Request.Builder()
                        .url("http://$serverIp:8080/files?auth=$authCode")
                        .build()

                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) throw Exception("Auth Failed")
                    
                    val body = response.body?.string() ?: "[]"
                    val array = JSONArray(body)
                    val list = mutableListOf<String>()
                    for (i in 0 until array.length()) {
                        list.add(array.getJSONObject(i).getString("name"))
                    }
                    list
                }

                totalFiles = filesToDownload.size
                downloadedCount = 0
                skippedCount = 0

                updateNotification("$totalFiles file(s) ready")
                sendProgressBroadcast(status = "Syncing $totalFiles files")

                for (i in 0 until totalFiles) {
                    val filename = filesToDownload[i]
                    updateNotification("Downloading ${i + 1}/$totalFiles : $filename")
                    downloadFile(filename)
                }

                updateNotification("Sync Completed")
                sendProgressBroadcast(status = "Sync Completed")
            } catch (e: Exception) {
                android.util.Log.e("PHUB", "Error in loadFileList: ${e.message}")
                updateNotification("Sync Error")
                sendProgressBroadcast(status = "Error: ${e.message}")
            } finally {
                isSyncing = false
            }
        }
    }

    private suspend fun markCompleted(filename: String) {
        withContext(Dispatchers.IO) {
            try {
                val json = """{"filename":"$filename"}"""
                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("http://$serverIp:8080/complete?auth=$authCode")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    android.util.Log.d("PHUB", "Marked Complete = $filename (Code: ${response.code})")
                }
            } catch (e: Exception) {
                android.util.Log.e("PHUB", "Error in markCompleted: ${e.message}")
            }
        }
    }

    private suspend fun downloadFile(filename: String) {
        withContext(Dispatchers.IO) {
            try {
                val folder = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "P-Hub"
                )
                if (!folder.exists()) folder.mkdirs()

                val targetFile = File(folder, filename)
                
                if (targetFile.exists()) {
                    skippedCount++
                    sendProgressBroadcast(currentFile = filename, currentFileProgress = 1f)
                    markCompleted(filename)
                    return@withContext
                }

                sendProgressBroadcast(currentFile = filename, currentFileProgress = 0f)

                val request = Request.Builder()
                    .url("http://$serverIp:8080/download/$filename?auth=$authCode")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        skippedCount++
                        sendProgressBroadcast(status = "Failed to download $filename")
                        return@withContext
                    }

                    response.body?.let { body ->
                        val totalBytes = body.contentLength()
                        var downloadedBytes = 0L

                        body.byteStream().use { input ->
                            FileOutputStream(targetFile).use { output ->
                                val buffer = ByteArray(16384)
                                var bytesRead: Int
                                var lastPercent = -1
                                var lastSpeedUpdateTime = System.currentTimeMillis()
                                var lastDownloadedBytes = 0L

                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    downloadedBytes += bytesRead

                                    val currentTime = System.currentTimeMillis()
                                    if (currentTime - lastSpeedUpdateTime >= 1000) {
                                        val speedBytesPerSec = (downloadedBytes - lastDownloadedBytes)
                                        val speedMbps = (speedBytesPerSec * 8.0) / (1024 * 1024)
                                        val speedText = "%.2f Mbps".format(speedMbps)
                                        
                                        // Est. Time Remaining
                                        val remainingBytes = totalBytes - downloadedBytes
                                        val timeLeftSec = if (speedBytesPerSec > 0) remainingBytes / speedBytesPerSec else 0L
                                        val timeLeftText = if (timeLeftSec > 0) {
                                            if (timeLeftSec > 60) "${timeLeftSec / 60}m ${timeLeftSec % 60}s"
                                            else "${timeLeftSec}s"
                                        } else ""

                                        val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                                        sendProgressBroadcast(
                                            currentFile = filename, 
                                            currentFileProgress = progress, 
                                            speed = speedText,
                                            timeRemaining = timeLeftText
                                        )
                                        
                                        lastSpeedUpdateTime = currentTime
                                        lastDownloadedBytes = downloadedBytes
                                    }

                                    val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                                    val percent = (progress * 100).toInt()

                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        updateNotification("$percent% - $filename")
                                    }
                                }
                            }
                        }
                    }
                }

                downloadedCount++
                sendProgressBroadcast(currentFile = filename, currentFileProgress = 1f, speed = "Done")
                markCompleted(filename)
            } catch (e: Exception) {
                android.util.Log.e("PHUB", "Error downloading $filename: ${e.message}")
                skippedCount++
                sendProgressBroadcast(status = "Error downloading $filename")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        createNotificationChannel()
        
        val notification = buildNotification("P-Hub Sync", "Preparing sync...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PHub::SyncWakeLock").apply {
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serverIp = intent?.getStringExtra("SERVER_IP") ?: ""
        authCode = intent?.getStringExtra("AUTH_CODE") ?: ""
        val selectedFiles = intent?.getStringArrayListExtra("SELECTED_FILES")
        if (serverIp.isNotBlank()) {
            loadFileList(selectedFiles)
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "P-Hub Sync",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
