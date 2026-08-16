package com.prakash.phub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.prakash.phub.R
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class ServerService : Service() {

    private class ZipItem(val name: String, val open: () -> InputStream?)

    private var server: ApplicationEngine? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var wakeLock: PowerManager.WakeLock? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    companion object {
        const val CHANNEL_ID = "P_HUB_SERVER"
        const val NOTIFICATION_ID = 2001
        const val SERVICE_TYPE = "_phub._tcp"
        const val SERVICE_NAME = "P-Hub Server"
        const val ACTION_SERVER_PROGRESS = "com.prakash.pmusic.SERVER_PROGRESS"
    }

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        generateCodes()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                buildNotification("P-Hub Server", "Server is running..."),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("P-Hub Server", "Server is running..."))
        }
        startServer()
        registerService(8080)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PHub::ServerWakeLock").apply {
            acquire()
        }
    }

    private fun generateCodes() {
        val secret = Random.nextInt(10, 99).toString()
        val codes = mutableListOf(secret)
        while (codes.size < 4) {
            val fake = Random.nextInt(10, 99).toString()
            if (!codes.contains(fake)) codes.add(fake)
        }
        FileSharingRegistry.secretCode = secret
        FileSharingRegistry.allCodes = codes.shuffled()
    }

    private fun sendProgressBroadcast(fileName: String, progress: Float) {
        val intent = Intent(ACTION_SERVER_PROGRESS).apply {
            setPackage(packageName)
            putExtra("fileName", fileName)
            putExtra("progress", progress)
        }
        sendBroadcast(intent)
    }

    private fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        nsdManager = (getSystemService(Context.NSD_SERVICE) as NsdManager).apply {
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                    android.util.Log.d("PHUB", "Service registered: ${NsdServiceInfo.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    android.util.Log.e("PHUB", "Registration failed: $errorCode")
                }
                override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
            registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }
    }

    private fun getStorageRoots(): List<File> {
        val roots = mutableListOf<File>()
        roots.add(Environment.getExternalStorageDirectory())
        val externalDirs = ContextCompat.getExternalFilesDirs(this, null)
        for (dir in externalDirs) {
            if (dir != null) {
                val path = dir.absolutePath
                if (path.contains("/Android/data/")) {
                    val rootPath = path.substring(0, path.indexOf("/Android/data/"))
                    val root = File(rootPath)
                    if (root.exists() && root !in roots) {
                        roots.add(root)
                    }
                }
            }
        }
        return roots
    }

    private fun startServer() {
        server = embeddedServer(Netty, port = 8080) {
            install(ContentNegotiation) {
                json()
            }
            routing {
                get("/") {
                    call.respondText(getWebHtml(), ContentType.Text.Html)
                }

                get("/background.png") {
                    val inputStream = resources.openRawResource(R.drawable.background)
                    call.respondOutputStream(ContentType.Image.PNG) {
                        inputStream.copyTo(this)
                        inputStream.close()
                    }
                }

                get("/preview") {
                    val auth = call.request.queryParameters["auth"]
                    val path = call.request.queryParameters["path"] ?: ""
                    if (auth != FileSharingRegistry.secretCode) {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                    val file = File(path)
                    if (file.exists() && file.isFile) {
                        call.respondFile(file)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }

                post("/upload") {
                    val auth = call.request.queryParameters["auth"]
                    val path = call.request.queryParameters["path"] ?: ""
                    val senderName = call.request.queryParameters["name"]?.trim()?.ifEmpty { "Guest" } ?: "Guest"
                    
                    if (auth != FileSharingRegistry.secretCode) {
                        call.respond(HttpStatusCode.Unauthorized, "Invalid Key")
                        return@post
                    }

                    // Logic Change: Conditional subfolder creation
                    var targetDir = if (path == "selective") {
                        // Selective mode: Create subfolder for user
                        val base = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "P-Hub")
                        File(base, senderName)
                    } else {
                        // Full access mode: Use current directory directly, or internal storage root if empty
                        if (path.isEmpty()) Environment.getExternalStorageDirectory() else File(path)
                    }
                    
                    if (!targetDir.exists()) targetDir.mkdirs()

                    val multipart = call.receiveMultipart()
                    multipart.forEachPart { part ->
                        if (part is PartData.FileItem) {
                            val rawName = part.originalFileName ?: "file_${System.currentTimeMillis()}"
                            val cleanName = rawName.replace('\\', '/')
                                .split('/')
                                .filter { it.isNotEmpty() && it != "." && it != ".." && !it.matches(Regex("^[A-Za-z]:$")) }
                                .joinToString("/")
                            if (cleanName.isNotEmpty()) {
                                val file = File(targetDir, cleanName)
                                file.parentFile?.mkdirs()
                                part.streamProvider().use { input ->
                                    file.outputStream().buffered().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                        }
                        part.dispose()
                    }
                    call.respondText("Success")
                }

                get("/challenge") {
                    val array = JSONArray()
                    FileSharingRegistry.allCodes.forEach { array.put(it) }
                    call.respondText(array.toString(), ContentType.Application.Json)
                }

                // List roots (drives)
                get("/roots") {
                    val auth = call.request.queryParameters["auth"]
                    if (auth != FileSharingRegistry.secretCode) {
                        call.respond(HttpStatusCode.Unauthorized, "Invalid Key")
                        return@get
                    }
                    val array = JSONArray()
                    if (FileSharingRegistry.fullAccessMode) {
                        getStorageRoots().forEach { root ->
                            val obj = JSONObject()
                            obj.put("name", if (root == Environment.getExternalStorageDirectory()) "Internal Storage" else root.name)
                            obj.put("path", root.absolutePath)
                            array.put(obj)
                        }
                    } else {
                        val obj = JSONObject()
                        obj.put("name", "Shared Files")
                        obj.put("path", "selective")
                        array.put(obj)
                    }
                    call.respondText(array.toString(), ContentType.Application.Json)
                }

                // Detailed directory listing
                get("/ls") {
                    val auth = call.request.queryParameters["auth"]
                    val path = call.request.queryParameters["path"]
                    if (auth != FileSharingRegistry.secretCode) {
                        call.respond(HttpStatusCode.Unauthorized, "Invalid Key")
                        return@get
                    }
                    
                    val array = JSONArray()
                    if (path == "selective") {
                        FileSharingRegistry.selectedFiles.forEach { uri ->
                            val name = getFileName(uri)
                            val obj = JSONObject()
                            obj.put("name", name)
                            obj.put("path", "shared://$name")
                            obj.put("isDir", false)
                            obj.put("size", contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: 0L)
                            obj.put("time", System.currentTimeMillis())
                            array.put(obj)
                        }
                    } else {
                        val dir = if (path.isNullOrEmpty()) Environment.getExternalStorageDirectory() else File(path)
                        if (!dir.exists() || !dir.isDirectory) {
                            call.respond(HttpStatusCode.NotFound, "Directory not found")
                            return@get
                        }

                        dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.forEach { file ->
                            val obj = JSONObject()
                            obj.put("name", file.name)
                            obj.put("path", file.absolutePath)
                            obj.put("isDir", file.isDirectory)
                            obj.put("size", if (file.isDirectory) 0 else file.length())
                            obj.put("time", file.lastModified())
                            array.put(obj)
                        }
                    }
                    call.respondText(array.toString(), ContentType.Application.Json)
                }

                // File Operations: mkdir, rm, mv, cp
                post("/action") {
                    val auth = call.request.queryParameters["auth"]
                    if (auth != FileSharingRegistry.secretCode) {
                        call.respond(HttpStatusCode.Unauthorized, "Invalid Key")
                        return@post
                    }

                    if (!FileSharingRegistry.fullAccessMode) {
                        call.respond(HttpStatusCode.Forbidden, "Action not allowed in Selective Share mode")
                        return@post
                    }

                    val params = call.receiveParameters()
                    val action = params["action"]
                    val path = params["path"] ?: ""
                    val target = params["target"] ?: ""

                    val file = File(path)
                    when (action) {
                        "mkdir" -> {
                            val newDir = File(file, target)
                            if (newDir.mkdirs()) call.respondText("Created") else call.respond(HttpStatusCode.InternalServerError, "Failed")
                        }
                        "rm" -> {
                            if (file.deleteRecursively()) call.respondText("Deleted") else call.respond(HttpStatusCode.InternalServerError, "Failed")
                        }
                        "mv" -> {
                            val dest = File(target)
                            if (file.renameTo(dest)) call.respondText("Moved") else call.respond(HttpStatusCode.InternalServerError, "Failed")
                        }
                        "cp" -> {
                            try {
                                val dest = File(target)
                                if (file.isDirectory) file.copyRecursively(dest, overwrite = true)
                                else file.copyTo(dest, overwrite = true)
                                call.respondText("Copied")
                            } catch (e: Exception) {
                                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error")
                            }
                        }
                        else -> call.respond(HttpStatusCode.BadRequest, "Unknown action")
                    }
                }

                get("/download") {
                    val auth = call.request.queryParameters["auth"]
                    val path = call.request.queryParameters["path"] ?: ""
                    if (auth != FileSharingRegistry.secretCode) {
                        call.respond(HttpStatusCode.Unauthorized, "Invalid Key")
                        return@get
                    }

                    if (path.startsWith("shared://")) {
                        val name = path.removePrefix("shared://")
                        val uri = FileSharingRegistry.selectedFiles.find { getFileName(it) == name }
                        if (uri != null) {
                            streamUri(uri, call, name)
                        } else {
                            call.respond(HttpStatusCode.NotFound)
                        }
                        return@get
                    }

                    val file = File(path)
                    if (file.exists() && file.isFile) {
                        streamFile(file, call, file.name)
                    } else {
                        call.respond(HttpStatusCode.NotFound, "File not found")
                    }
                }

                // Download multiple files as a single ZIP archive
                get("/download-zip") {
                    val auth = call.request.queryParameters["auth"]
                    if (auth != FileSharingRegistry.secretCode) {
                        call.respond(HttpStatusCode.Unauthorized, "Invalid Key")
                        return@get
                    }

                    val paths = call.request.queryParameters.getAll("path") ?: emptyList()
                    val zipItems = paths.mapNotNull { path ->
                        if (path.startsWith("shared://")) {
                            val name = path.removePrefix("shared://")
                            val uri = FileSharingRegistry.selectedFiles.find { getFileName(it) == name }
                            uri?.let { ZipItem(name) { contentResolver.openInputStream(it) } }
                        } else {
                            val file = File(path)
                            if (file.isFile) ZipItem(file.name) { FileInputStream(file) } else null
                        }
                    }

                    if (zipItems.isEmpty()) {
                        call.respond(HttpStatusCode.NotFound, "No downloadable files")
                        return@get
                    }

                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "P-Hub-${zipItems.size}-files.zip").toString()
                    )
                    call.respondOutputStream(ContentType.Application.OctetStream) {
                        val zip = ZipOutputStream(this)
                        val usedNames = mutableSetOf<String>()
                        try {
                            zipItems.forEach { item ->
                                val base = item.name.substringBeforeLast('.')
                                val ext = item.name.substringAfterLast('.', "")
                                var entryName = item.name
                                var counter = 1
                                while (entryName in usedNames) {
                                    entryName = if (ext.isEmpty() || ext == item.name) "$base ($counter)" else "$base ($counter).$ext"
                                    counter++
                                }
                                usedNames.add(entryName)

                                val input = item.open()
                                if (input != null) {
                                    input.use {
                                        zip.putNextEntry(ZipEntry(entryName))
                                        it.copyTo(zip, 65536)
                                        zip.closeEntry()
                                    }
                                }
                            }
                        } finally {
                            zip.close()
                        }
                    }
                }
                
                // Keep the old /files and /download/{name} for Client App compatibility
                get("/files") {
                    val code = call.request.queryParameters["auth"]
                    if (code != FileSharingRegistry.secretCode) {
                        call.respond(HttpStatusCode.Unauthorized, "Wrong Code")
                        return@get
                    }
                    val array = JSONArray()
                    if (FileSharingRegistry.fullAccessMode) {
                        listAllFiles(Environment.getExternalStorageDirectory(), array)
                    } else {
                        FileSharingRegistry.selectedFiles.forEach { uri ->
                            val obj = JSONObject()
                            obj.put("name", getFileName(uri))
                            array.put(obj)
                        }
                    }
                    call.respondText(array.toString(), ContentType.Application.Json)
                }

                get("/download/{name}") {
                    val code = call.request.queryParameters["auth"]
                    if (code != FileSharingRegistry.secretCode) {
                        call.respond(HttpStatusCode.Unauthorized, "Wrong Code")
                        return@get
                    }
                    val name = call.parameters["name"]
                    if (FileSharingRegistry.fullAccessMode) {
                        val file = File(Environment.getExternalStorageDirectory(), name ?: "")
                        if (file.exists() && file.isFile) streamFile(file, call, name ?: "unknown")
                        else call.respond(HttpStatusCode.NotFound)
                    } else {
                        val uri = FileSharingRegistry.selectedFiles.find { getFileName(it) == name }
                        if (uri != null) streamUri(uri, call, name ?: "unknown")
                        else call.respond(HttpStatusCode.NotFound)
                    }
                }
            }
        }.start(wait = false)
    }

    private fun listAllFiles(dir: File, array: JSONArray) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile) {
                val obj = JSONObject()
                obj.put("name", file.absolutePath.removePrefix(Environment.getExternalStorageDirectory().absolutePath + "/"))
                array.put(obj)
            } else if (file.isDirectory && !file.isHidden) {
                if (file.name in listOf("Download", "Music", "DCIM", "Documents")) {
                    listAllFiles(file, array)
                }
            }
        }
    }

    private suspend fun streamFile(file: File, call: ApplicationCall, fileName: String) {
        val totalSize = file.length()
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString())
        call.respondOutputStream(ContentType.Application.OctetStream) {
            val buffer = ByteArray(16384)
            var bytesRead: Int
            var totalSent = 0L
            var lastUpdateProgress = -1f
            FileInputStream(file).use { input ->
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    write(buffer, 0, bytesRead)
                    totalSent += bytesRead
                    if (totalSize > 0) {
                        val currentProgress = totalSent.toFloat() / totalSize.toFloat()
                        if (currentProgress - lastUpdateProgress >= 0.01f || currentProgress >= 0.99f) {
                            sendProgressBroadcast(fileName, currentProgress)
                            lastUpdateProgress = currentProgress
                        }
                    }
                }
            }
            sendProgressBroadcast(fileName, 1.0f)
        }
    }

    private suspend fun streamUri(uri: Uri, call: ApplicationCall, fileName: String) {
        val inputStream = contentResolver.openInputStream(uri)
        val totalSize = contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L
        if (inputStream != null) {
            call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString())
            call.respondOutputStream(ContentType.Application.OctetStream) {
                val buffer = ByteArray(16384)
                var bytesRead: Int
                var totalSent = 0L
                var lastUpdateProgress = -1f
                inputStream.use { input ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        write(buffer, 0, bytesRead)
                        totalSent += bytesRead
                        if (totalSize > 0) {
                            val currentProgress = totalSent.toFloat() / totalSize.toFloat()
                            if (currentProgress - lastUpdateProgress >= 0.01f || currentProgress >= 0.99f) {
                                sendProgressBroadcast(fileName, currentProgress)
                                lastUpdateProgress = currentProgress
                            }
                        }
                    }
                }
                sendProgressBroadcast(fileName, 1.0f)
            }
        } else {
            call.respond(HttpStatusCode.NotFound, "File not found")
        }
    }

    private fun getWebHtml(): String {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>P-Hub Web Explorer</title>
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body {
                        font-family: 'Segoe UI', sans-serif;
                        height: 100vh;
                        background: url('/background.png') no-repeat center center fixed;
                        background-size: cover;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        color: white;
                    }
                    .overlay { position: absolute; width: 100%; height: 100%; background: rgba(0,0,0,0.5); backdrop-filter: blur(10px); z-index: 1; }
                    .container {
                        position: relative; z-index: 2; width: 98%; max-width: 1000px; height: 95vh;
                        background: rgba(255, 255, 255, 0.1);
                        border: 1px solid rgba(255, 255, 255, 0.2);
                        border-radius: 20px;
                        box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.5);
                        display: flex; flex-direction: column; overflow: hidden;
                    }
                    header { padding: 8px; border-bottom: 1px solid rgba(255,255,255,0.1); text-align: center; }
                    h1 { color: #00e676; font-size: 18px; text-shadow: 0 0 10px rgba(0, 230, 118, 0.5); margin: 0; }
                    .credits { font-size: 9px; opacity: 0.6; }
                    
                    .top-bar { display: flex; align-items: center; gap: 8px; padding: 8px 15px; border-bottom: 1px solid rgba(255,255,255,0.05); background: rgba(0,0,0,0.1); }
                    .top-input { flex: 0 1 200px; padding: 8px; background: rgba(0,0,0,0.3); border: 1px solid rgba(255,255,255,0.3); color: white; border-radius: 5px; font-size: 14px; }
                    
                    .toolbar { padding: 8px 15px; background: rgba(0,0,0,0.2); display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
                    .input-key { width: 40px; padding: 5px; background: rgba(0,0,0,0.3); border: 1px solid rgba(255,255,255,0.3); color: white; border-radius: 5px; text-align: center; font-size: 14px; }
                    .breadcrumb { flex: 1; font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 100px; }
                    .view-options { display: flex; gap: 4px; }
                    
                    .main-content { flex: 1; display: flex; overflow: hidden; }
                    .sidebar { width: 220px; border-right: 1px solid rgba(255,255,255,0.1); padding: 10px; overflow-y: auto; transition: 0.3s; background: rgba(0,0,0,0.2); }
                    .sidebar-header { font-size: 10px; opacity: 0.5; margin-bottom: 10px; font-weight: bold; }
                    .tree-item { display: flex; align-items: center; padding: 6px; cursor: pointer; border-radius: 5px; transition: 0.2s; font-size: 13px; white-space: nowrap; }
                    .tree-item:hover { background: rgba(255,255,255,0.1); }
                    .tree-item.active > .tree-label { color: #00e676; font-weight: bold; }
                    .tree-toggle { width: 20px; text-align: center; font-size: 10px; opacity: 0.5; }
                    .tree-toggle.expanded { transform: rotate(90deg); }
                    .tree-children { margin-left: 15px; display: none; }
                    .tree-children.visible { display: block; }
                    .tree-label { flex: 1; overflow: hidden; text-overflow: ellipsis; }
                    
                    .file-view { flex: 1; overflow-y: auto; padding: 5px; }
                    .file-list-mode .file-table { width: 100%; border-collapse: collapse; }
                    .file-list-mode .file-table th { text-align: left; padding: 8px; font-size: 11px; opacity: 0.7; border-bottom: 1px solid rgba(255,255,255,0.1); cursor: pointer; }
                    .file-list-mode .file-table th:hover { opacity: 1; background: rgba(255,255,255,0.05); }
                    .file-list-mode .file-row { border-bottom: 1px solid rgba(255,255,255,0.05); transition: 0.2s; cursor: pointer; }
                    .file-list-mode .file-row:hover { background: rgba(255,255,255,0.05); }
                    .file-list-mode .file-row.selected { background: rgba(0, 230, 118, 0.2); }
                    .file-list-mode .file-row td { padding: 8px; font-size: 13px; vertical-align: middle; }
                    
                    .file-grid-mode { 
                        display: grid !important; 
                        grid-template-columns: repeat(auto-fill, minmax(90px, 1fr)); 
                        gap: 10px; 
                        width: 100%;
                    }
                    .file-item { 
                        display: flex; flex-direction: column; align-items: center; 
                        padding: 8px; border-radius: 10px; cursor: pointer; 
                        transition: 0.2s; text-align: center; background: rgba(255, 255, 255, 0.03);
                    }
                    .file-item:hover { background: rgba(255, 255, 255, 0.05); }
                    .file-item.selected { background: rgba(0, 230, 118, 0.2); }
                    .file-item .icon { font-size: 32px; margin-bottom: 4px; height: 50px; display: flex; align-items: center; justify-content: center; }
                    .file-item .name { font-size: 11px; word-break: break-all; max-height: 30px; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; line-height: 1.2; }
                    .file-item .preview-img { width: 100%; height: 100%; object-fit: cover; border-radius: 5px; }

                    .upload-sidebar { 
                        position: fixed; right: 20px; top: 2.5vh; 
                        width: 220px; height: 95vh; 
                        background: rgba(255, 255, 255, 0.1); 
                        backdrop-filter: blur(15px); 
                        border: 1px solid rgba(255, 255, 255, 0.2); 
                        border-radius: 20px; 
                        padding: 15px; 
                        overflow-y: auto; 
                        z-index: 10;
                        box-shadow: 0 4px 15px rgba(0,0,0,0.3);
                    }
                    .upload-item { margin-bottom: 12px; font-size: 12px; background: rgba(0,0,0,0.2); padding: 8px; border-radius: 8px; border: 1px solid rgba(255,255,255,0.05); }
                    .upload-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-bottom: 5px; font-weight: bold; }
                    .upload-progress { width: 100%; height: 6px; background: rgba(255,255,255,0.1); border-radius: 3px; overflow: hidden; }
                    .upload-bar { height: 100%; background: #00e676; width: 0%; transition: 0.1s; }
                    .upload-status { font-size: 10px; margin-top: 4px; opacity: 0.7; display: flex; justify-content: space-between; }

                    .actions { padding: 8px; border-top: 1px solid rgba(255,255,255,0.1); display: flex; gap: 6px; justify-content: center; flex-wrap: wrap; }
                    .btn { padding: 6px 10px; border-radius: 6px; border: none; cursor: pointer; font-size: 11px; font-weight: bold; transition: 0.2s; white-space: nowrap; }
                    .btn-primary { background: #00e676; color: black; }
                    .btn-secondary { background: rgba(255,255,255,0.1); color: white; border: 1px solid rgba(255,255,255,0.2); }
                    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
                    .btn-view { background: transparent; color: white; border: 1px solid rgba(255,255,255,0.2); padding: 4px 8px; }
                    .btn-view.active { background: rgba(0, 230, 118, 0.5); border-color: #00e676; }

                    #btnMenu { display: none; }

                    @media (max-width: 600px) {
                        .sidebar { position: absolute; left: -220px; top: 0; bottom: 0; z-index: 10; background: rgba(30,30,30,0.98); backdrop-filter: blur(20px); border-right: 1px solid rgba(255,255,255,0.2); }
                        .sidebar.open { left: 0; }
                        #btnMenu { display: block; white-space: nowrap; font-size: 10px; padding: 4px 8px; width: auto; }
                        #btnUp { padding: 4px 5px; width: 35px; min-width: 35px; font-size: 9px; }
                        .top-bar { padding: 6px 10px; gap: 6px; }
                        .top-input { font-size: 13px; }
                        .actions { padding: 5px; padding-bottom: 25px; gap: 4px; }
                        .btn { padding: 5px 2px; font-size: 9px; flex: 1 1 18%; }
                        .toolbar { gap: 4px; padding: 6px 10px; }
                        .breadcrumb { font-size: 11px; flex: 3; }
                        .container { height: 100vh; width: 100%; border-radius: 0; border: none; }
                        .upload-sidebar { position: absolute; right: -200px; top: 0; bottom: 0; z-index: 10; background: rgba(30,30,30,0.98); transition: 0.3s; }
                        .upload-sidebar.open { right: 0; }
                    }

                    .modal { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.8); z-index: 100; display: none; align-items: center; justify-content: center; }
                    .modal-content { background: #1e1e1e; padding: 20px; border-radius: 15px; width: 300px; text-align: center; }
                    .modal-input { width: 100%; padding: 10px; margin: 15px 0; background: #333; border: 1px solid #444; color: white; border-radius: 5px; }
                </style>
            </head>
            <body>
                <div class="overlay" onclick="closeSidebar()"></div>
                <div class="container">
                    <header>
                        <h1>P-Hub Explorer</h1>
                        <p class="credits">Designed and developed by &lt;&lt;&lt;Prakash&gt;&gt;&gt;</p>
                    </header>
                    <div class="top-bar">
                        <input type="text" id="senderName" class="top-input" placeholder="Your Name" maxlength="20">
                        <button class="btn btn-secondary" id="btnMenu" onclick="toggleSidebar()">☰ STORAGE</button>
                    </div>
                    <div class="toolbar">
                        <input type="text" id="authKey" class="input-key" placeholder="Key" maxlength="2">
                        <div id="breadcrumb" class="breadcrumb">Select a drive</div>
                        <button class="btn btn-view active" id="btnUp" onclick="goUp()">UP</button>
                        <div class="view-options">
                            <button class="btn btn-view active" id="btnListView" onclick="setViewMode('list')" title="List View">L</button>
                            <button class="btn btn-view" id="btnDetailView" onclick="setViewMode('details')" title="Details View">D</button>
                            <button class="btn btn-view" id="btnPreviewView" onclick="setViewMode('preview')" title="Preview View">P</button>
                        </div>
                    </div>
                    <div class="main-content">
                        <div id="sidebar" class="sidebar">
                            <div class="sidebar-header">STORAGE EXPLORER</div>
                            <div id="treeRoot"></div>
                        </div>
                        <div id="fileView" class="file-view file-list-mode" ondragover="event.preventDefault(); this.style.background='rgba(0,230,118,0.1)'" ondragleave="this.style.background=''" ondrop="handleDrop(event)">
                            <table class="file-table" id="fileTable">
                                <thead>
                                    <tr>
                                        <th onclick="setSort('name')">Name</th>
                                        <th style="width:70px;" onclick="setSort('size')">Size</th>
                                        <th id="thTime" style="width:100px; display:none;" onclick="setSort('time')">Date</th>
                                    </tr>
                                </thead>
                                <tbody id="fileList"></tbody>
                            </table>
                            <div id="fileGrid" class="file-grid-mode" style="display:none;"></div>
                        </div>
                    </div>
                    <div class="actions">
                        <button class="btn btn-secondary" onclick="selectAll()">All</button>
                        <button class="btn btn-primary" id="btnDownload" onclick="downloadFile()" disabled>Get</button>
                        <button class="btn btn-secondary action-btn" onclick="showModal('mkdir')">New</button>
                        <button class="btn btn-secondary action-btn" id="btnCopy" onclick="markSource('cp')" disabled>Copy</button>
                        <button class="btn btn-secondary action-btn" id="btnMove" onclick="markSource('mv')" disabled>Move</button>
                        <button class="btn btn-primary action-btn" id="btnPaste" onclick="paste()" style="display:none;">Paste</button>
                        <button class="btn btn-secondary action-btn" id="btnRename" onclick="showModal('rename')" disabled>Rename</button>
                        <button class="btn btn-secondary action-btn" id="btnDelete" onclick="deleteFile()" style="color:#ff5252;" disabled>Del</button>
                        <button class="btn btn-primary" onclick="document.getElementById('uploadInput').click()">Upload</button>
                        <button class="btn btn-secondary" onclick="document.getElementById('folderInput').click()">Folder</button>
                        <input type="file" id="uploadInput" multiple style="display:none;" onchange="uploadFiles()">
                        <input type="file" id="folderInput" webkitdirectory multiple style="display:none;" onchange="uploadFiles()">
                    </div>
                </div>

                <!-- Moved outside container -->
                <div id="uploadSidebar" class="upload-sidebar">
                    <div class="sidebar-header" style="display:flex; justify-content:space-between; align-items:center;">
                        UPLOAD QUEUE
                        <span onclick="document.getElementById('uploadList').innerHTML=''" style="cursor:pointer; font-size:14px; opacity:0.6;" title="Clear List">🗑</span>
                    </div>
                    <div id="uploadList"></div>
                </div>

                <div style="position:fixed; bottom:10px; right:10px; font-size:10px; opacity:0.5; z-index:100; color:white; pointer-events:none;">&lt;&lt;&lt;Prakash&gt;&gt;&gt;</div>

                <div id="modal" class="modal">
                    <div class="modal-content">
                        <h3 id="modalTitle">New Folder</h3>
                        <input type="text" id="modalInput" class="modal-input">
                        <div style="display:flex; gap:10px;">
                            <button class="btn btn-secondary" style="flex:1;" onclick="closeModal()">Cancel</button>
                            <button class="btn btn-primary" style="flex:1;" onclick="handleModalSubmit()">OK</button>
                        </div>
                    </div>
                </div>

                <script>
                    let currentPath = '';
                    let selectedItems = [];
                    let clipboard = { action: '', sources: [], name: '' };
                    let currentViewMode = 'list';
                    let currentFiles = [];
                    let sortKey = 'name';
                    let sortAsc = true;
                    let lastSelectedIndex = -1;

                    window.onload = () => {
                        const savedKey = localStorage.getItem('phub_key');
                        if (savedKey) {
                            document.getElementById('authKey').value = savedKey;
                            loadRoots();
                        }
                        const savedName = localStorage.getItem('phub_sender_name');
                        if (savedName) {
                            document.getElementById('senderName').value = savedName;
                        }
                    };

                    document.getElementById('senderName').oninput = (e) => {
                        localStorage.setItem('phub_sender_name', e.target.value);
                    };
                    document.getElementById('senderName').onblur = (e) => {
                        localStorage.setItem('phub_sender_name', e.target.value);
                    };

                    document.getElementById('authKey').onchange = (e) => {
                        localStorage.setItem('phub_key', e.target.value);
                        loadRoots();
                    };

                    function toggleSidebar() {
                        document.getElementById('sidebar').classList.toggle('open');
                    }

                    function closeSidebar() {
                        document.getElementById('sidebar').classList.remove('open');
                        document.getElementById('uploadSidebar').classList.remove('open');
                    }

                    function loadRoots() {
                        const key = document.getElementById('authKey').value;
                        if (!key) return;
                        fetch('/roots?auth=' + key)
                            .then(res => res.json())
                            .then(data => {
                                const treeRoot = document.getElementById('treeRoot');
                                treeRoot.innerHTML = '';
                                data.forEach(root => {
                                    createTreeNode(treeRoot, root.name, root.path, true);
                                });
                                const isRestricted = data.length === 1 && data[0].path === 'selective';
                                document.querySelectorAll('.action-btn').forEach(b => b.style.display = isRestricted ? 'none' : 'block');
                                // UP button should be small but visible if not restricted
                                document.getElementById('btnUp').style.display = isRestricted ? 'none' : 'block';
                            });
                    }

                    function createTreeNode(parent, name, path, isDrive = false) {
                        const container = document.createElement('div');
                        const item = document.createElement('div');
                        item.className = 'tree-item';
                        item.dataset.path = path;
                        
                        const toggle = document.createElement('span');
                        toggle.className = 'tree-toggle';
                        toggle.innerText = path === 'selective' ? '' : '▶';
                        toggle.onclick = (e) => {
                            e.stopPropagation();
                            toggleFolder(container, path);
                        };

                        const label = document.createElement('span');
                        label.className = 'tree-label';
                        label.innerText = (isDrive ? '💾 ' : '📁 ') + name;
                        label.onclick = () => { navigate(path); if(window.innerWidth < 600) closeSidebar(); };

                        item.appendChild(toggle);
                        item.appendChild(label);
                        container.appendChild(item);

                        const children = document.createElement('div');
                        children.className = 'tree-children';
                        container.appendChild(children);
                        
                        parent.appendChild(container);
                        return container;
                    }

                    function toggleFolder(container, path) {
                        if (path === 'selective') return;
                        const toggle = container.querySelector('.tree-toggle');
                        const children = container.querySelector('.tree-children');
                        const key = document.getElementById('authKey').value;

                        if (toggle.classList.contains('expanded')) {
                            toggle.classList.remove('expanded');
                            toggle.innerText = '▶';
                            children.classList.remove('visible');
                        } else {
                            toggle.classList.add('expanded');
                            toggle.innerText = '▼';
                            children.classList.add('visible');
                            
                            if (children.innerHTML === '') {
                                fetch('/ls?auth=' + key + '&path=' + encodeURIComponent(path))
                                    .then(res => res.json())
                                    .then(data => {
                                        data.filter(i => i.isDir).forEach(dir => {
                                            createTreeNode(children, dir.name, dir.path);
                                        });
                                    });
                            }
                        }
                    }

                    function navigate(path) {
                        const key = document.getElementById('authKey').value;
                        currentPath = path;
                        selectedItems = [];
                        document.getElementById('breadcrumb').innerText = path === 'selective' ? 'Shared Files' : path;
                        
                        document.querySelectorAll('.tree-item').forEach(el => {
                            el.classList.toggle('active', el.dataset.path === path);
                        });

                        fetch('/ls?auth=' + key + '&path=' + encodeURIComponent(path))
                            .then(res => res.json())
                            .then(data => {
                                currentFiles = data;
                                renderFiles();
                            });
                    }

                    function setViewMode(mode) {
                        currentViewMode = mode;
                        document.querySelectorAll('.btn-view').forEach(b => b.classList.remove('active'));
                        if (mode === 'list') document.getElementById('btnListView').classList.add('active');
                        else if (mode === 'details') document.getElementById('btnDetailView').classList.add('active');
                        else if (mode === 'preview') document.getElementById('btnPreviewView').classList.add('active');
                        renderFiles();
                    }

                    function setSort(key) {
                        if (sortKey === key) sortAsc = !sortAsc;
                        else { sortKey = key; sortAsc = true; }
                        renderFiles();
                    }

                    function renderFiles() {
                        const list = document.getElementById('fileList');
                        const grid = document.getElementById('fileGrid');
                        const view = document.getElementById('fileView');
                        const table = document.getElementById('fileTable');
                        
                        list.innerHTML = '';
                        grid.innerHTML = '';
                        updateButtons();

                        // Sort files
                        currentFiles.sort((a, b) => {
                            if (a.isDir !== b.isDir) return a.isDir ? -1 : 1;
                            let valA = a[sortKey];
                            let valB = b[sortKey];
                            if (typeof valA === 'string') {
                                valA = valA.toLowerCase();
                                valB = valB.toLowerCase();
                            }
                            if (valA < valB) return sortAsc ? -1 : 1;
                            if (valA > valB) return sortAsc ? 1 : -1;
                            return 0;
                        });

                        if (currentViewMode === 'list' || currentViewMode === 'details') {
                            table.style.display = 'table';
                            grid.style.display = 'none';
                            view.className = 'file-view file-list-mode';
                            document.getElementById('thTime').style.display = (currentViewMode === 'details' ? 'table-cell' : 'none');

                            currentFiles.forEach(item => {
                                const row = document.createElement('tr');
                                row.className = 'file-row';
                                if (selectedItems.some(i => i.path === item.path)) row.classList.add('selected');
                                
                                const icon = item.isDir ? '📁' : '📄';
                                const size = item.isDir ? '--' : formatSize(item.size);
                                const time = formatDate(item.time);
                                row.innerHTML = '<td><span class="icon">' + icon + '</span>' + item.name + '</td><td>' + (currentViewMode === 'details' ? size : '') + '</td><td>' + (currentViewMode === 'details' ? time : '') + '</td>';
                                row.onclick = (e) => selectItem(item, row, e);
                                row.ondblclick = () => handleOpen(item);
                                list.appendChild(row);
                            });
                        } else {
                            table.style.display = 'none';
                            grid.style.display = 'grid';
                            view.className = 'file-view';
                            
                            currentFiles.forEach(item => {
                                const div = document.createElement('div');
                                div.className = 'file-item';
                                if (selectedItems.some(i => i.path === item.path)) div.classList.add('selected');

                                let iconContent = '';
                                if (!item.isDir && isImage(item.name)) {
                                    const key = document.getElementById('authKey').value;
                                    iconContent = '<img class="preview-img" src="/preview?auth=' + key + '&path=' + encodeURIComponent(item.path) + '" style="width:60px; height:60px; object-fit:cover;">';
                                } else {
                                    iconContent = '<span style="font-size:32px;">' + (item.isDir ? '📁' : '📄') + '</span>';
                                }
                                
                                div.innerHTML = '<div class="icon">' + iconContent + '</div><div class="name">' + item.name + '</div>';
                                div.onclick = (e) => selectItem(item, div, e);
                                div.ondblclick = () => handleOpen(item);
                                grid.appendChild(div);
                            });
                        }
                    }

                    function isImage(name) {
                        const ext = name.split('.').pop().toLowerCase();
                        return ['jpg', 'jpeg', 'png', 'gif', 'webp'].includes(ext);
                    }

                    function selectItem(item, el, e) {
                        const currentIndex = currentFiles.findIndex(f => f.path === item.path);
                        
                        if (e.shiftKey && lastSelectedIndex !== -1) {
                            const start = Math.min(lastSelectedIndex, currentIndex);
                            const end = Math.max(lastSelectedIndex, currentIndex);
                            
                            if (!e.ctrlKey && !e.metaKey) selectedItems = [];
                            
                            for (let i = start; i <= end; i++) {
                                const file = currentFiles[i];
                                if (!selectedItems.some(si => si.path === file.path)) {
                                    selectedItems.push(file);
                                }
                            }
                        } else if (e.ctrlKey || e.metaKey) {
                            const index = selectedItems.findIndex(i => i.path === item.path);
                            if (index > -1) {
                                selectedItems.splice(index, 1);
                            } else {
                                selectedItems.push(item);
                            }
                            lastSelectedIndex = currentIndex;
                        } else {
                            // Toggle mode for touch/simple click, but support range if Shift used
                            const index = selectedItems.findIndex(i => i.path === item.path);
                            if (index > -1 && selectedItems.length === 1) {
                                selectedItems = [];
                                lastSelectedIndex = -1;
                            } else {
                                selectedItems = [item];
                                lastSelectedIndex = currentIndex;
                            }
                        }
                        renderFiles();
                    }

                    function selectAll() {
                        const allFiles = currentFiles.filter(f => !f.isDir);
                        if (selectedItems.length === allFiles.length) {
                            selectedItems = [];
                        } else {
                            selectedItems = [...allFiles];
                        }
                        renderFiles();
                    }

                    function handleOpen(item) {
                        if (item.isDir) navigate(item.path);
                        else downloadFile([item]);
                    }

                    function goUp() {
                        if (!currentPath || currentPath === 'selective') return;
                        const parts = currentPath.split('/');
                        parts.pop();
                        const parent = parts.join('/') || '/';
                        selectedItems = [];
                        navigate(parent);
                    }

                    function updateButtons() {
                        const hasFiles = selectedItems.some(i => !i.isDir);
                        const hasAny = selectedItems.length > 0;
                        document.getElementById('btnDownload').disabled = !hasFiles;
                        const actionBtns = document.querySelectorAll('.action-btn');
                        actionBtns.forEach(btn => {
                            if (btn.id !== 'btnPaste') btn.disabled = !hasAny;
                        });
                        document.getElementById('btnPaste').style.display = (clipboard.sources && clipboard.sources.length > 0) ? 'inline-block' : 'none';
                    }

                    function formatSize(bytes) {
                        if (bytes === 0) return '0 B';
                        const k = 1024;
                        const sizes = ['B', 'KB', 'MB', 'GB'];
                        const i = Math.floor(Math.log(bytes) / Math.log(k));
                        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
                    }

                    function formatDate(ms) {
                        const d = new Date(ms);
                        return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
                    }

                    function downloadFile(items = selectedItems) {
                        const key = document.getElementById('authKey').value;
                        const files = items.filter(i => !i.isDir);
                        if (files.length === 0) return;
                        const a = document.createElement('a');
                        document.body.appendChild(a);
                        if (files.length === 1) {
                            a.href = '/download?auth=' + key + '&path=' + encodeURIComponent(files[0].path);
                            a.download = files[0].name;
                        } else {
                            const params = files.map(f => 'path=' + encodeURIComponent(f.path)).join('&');
                            a.href = '/download-zip?auth=' + key + '&' + params;
                            a.download = 'P-Hub-' + files.length + '-files.zip';
                        }
                        a.click();
                        document.body.removeChild(a);
                    }

                    function deleteFile() {
                        if (selectedItems.length === 0) return;
                        if (!confirm('Delete ' + selectedItems.length + ' item(s)?')) return;
                        const key = document.getElementById('authKey').value;
                        
                        Promise.all(selectedItems.map(item => {
                            const fd = new URLSearchParams();
                            fd.append('action', 'rm');
                            fd.append('path', item.path);
                            return fetch('/action?auth=' + key, { method: 'POST', body: fd });
                        })).then(() => {
                            selectedItems = [];
                            navigate(currentPath);
                        });
                    }

                    function showModal(type) {
                        const modal = document.getElementById('modal');
                        const title = document.getElementById('modalTitle');
                        const input = document.getElementById('modalInput');
                        modal.dataset.type = type;
                        if (type === 'mkdir') {
                            title.innerText = 'New Folder';
                            input.value = 'New Folder';
                        } else if (type === 'rename') {
                            title.innerText = 'Rename';
                            input.value = selectedItem.name;
                        }
                        modal.style.display = 'flex';
                        input.focus();
                        input.select();
                    }

                    function closeModal() { document.getElementById('modal').style.display = 'none'; }

                    function handleModalSubmit() {
                        const type = document.getElementById('modal').dataset.type;
                        const val = document.getElementById('modalInput').value;
                        const key = document.getElementById('authKey').value;
                        const fd = new URLSearchParams();
                        if (type === 'mkdir') {
                            fd.append('action', 'mkdir');
                            fd.append('path', currentPath);
                            fd.append('target', val);
                        } else if (type === 'rename') {
                            fd.append('action', 'mv');
                            fd.append('path', selectedItem.path);
                            const newPath = selectedItem.path.substring(0, selectedItem.path.lastIndexOf('/') + 1) + val;
                            fd.append('target', newPath);
                        }
                        fetch('/action?auth=' + key, { method: 'POST', body: fd }).then(() => {
                            closeModal();
                            navigate(currentPath);
                        });
                    }

                    function markSource(action) {
                        if (selectedItems.length === 0) return;
                        clipboard = { action: action, sources: selectedItems.map(i => ({ path: i.path, name: i.name })) };
                        updateButtons();
                    }

                    function paste() {
                        const key = document.getElementById('authKey').value;
                        Promise.all(clipboard.sources.map(item => {
                            const fd = new URLSearchParams();
                            fd.append('action', clipboard.action);
                            fd.append('path', item.path);
                            fd.append('target', currentPath + '/' + item.name);
                            return fetch('/action?auth=' + key, { method: 'POST', body: fd });
                        })).then(() => {
                            clipboard = { action: '', sources: [] };
                            navigate(currentPath);
                        });
                    }

                    function traverseEntry(entry, path, items, callback) {
                        if (entry.isFile) {
                            entry.file(file => {
                                items.push({ file: file, relPath: path.concat(file.name).join('/') });
                                callback();
                            }, callback);
                        } else if (entry.isDirectory) {
                            const reader = entry.createReader();
                            const readAll = () => {
                                reader.readEntries(batch => {
                                    if (batch.length === 0) { callback(); return; }
                                    let remaining = batch.length;
                                    batch.forEach(sub => {
                                        traverseEntry(sub, path.concat(entry.name), items, () => {
                                            remaining--;
                                            if (remaining === 0) readAll();
                                        });
                                    });
                                }, callback);
                            };
                            readAll();
                        } else {
                            callback();
                        }
                    }

                    function collectDroppedItems(entries, done) {
                        const items = [];
                        let remaining = entries.length;
                        if (remaining === 0) { done(items); return; }
                        entries.forEach(entry => {
                            traverseEntry(entry, [], items, () => {
                                remaining--;
                                if (remaining === 0) done(items);
                            });
                        });
                    }

                    function handleDrop(e) {
                        e.preventDefault();
                        const view = document.getElementById('fileView');
                        view.style.background = '';
                        const items = e.dataTransfer && e.dataTransfer.items;
                        if (items && items.length && typeof items[0].webkitGetAsEntry === 'function') {
                            const entries = [];
                            for (let i = 0; i < items.length; i++) {
                                const entry = items[i].webkitGetAsEntry();
                                if (entry) entries.push(entry);
                            }
                            if (entries.length) {
                                if (window.innerWidth < 600) document.getElementById('uploadSidebar').classList.add('open');
                                collectDroppedItems(entries, list => uploadItems(list));
                                return;
                            }
                        }
                        if (e.dataTransfer.files.length) {
                            if (window.innerWidth < 600) document.getElementById('uploadSidebar').classList.add('open');
                            uploadFiles(e.dataTransfer.files);
                        }
                    }

                    function uploadFiles(dropped = null) {
                        const source = dropped || document.getElementById('uploadInput').files || document.getElementById('folderInput').files;
                        if (!source || !source.length) return;
                        const items = [];
                        for (let i = 0; i < source.length; i++) {
                            const file = source[i];
                            const rel = (file.webkitRelativePath || '').split('/').filter(s => s && s !== '.').join('/');
                            items.push({ file: file, relPath: rel || file.name });
                        }
                        uploadItems(items);
                    }

                    function uploadItems(items) {
                        const key = document.getElementById('authKey').value;
                        const name = document.getElementById('senderName').value;
                        if (!items.length) return;
                        
                        localStorage.setItem('phub_sender_name', name);
                        
                        items.forEach((item, index) => {
                            const file = item.file;
                            const uploadId = 'upload_' + Date.now() + '_' + index;
                            
                            // Add to sidebar list
                            const list = document.getElementById('uploadList');
                            const div = document.createElement('div');
                            div.className = 'upload-item';
                            div.id = uploadId;
                            div.innerHTML = '<div class="upload-name">' + item.relPath + '</div>' +
                                            '<div class="upload-progress"><div class="upload-bar" id="bar_' + uploadId + '"></div></div>' +
                                            '<div class="upload-status"><span id="txt_' + uploadId + '">Uploading...</span><span id="pct_' + uploadId + '">0%</span></div>';
                            list.prepend(div);
                            
                            const fd = new FormData();
                            fd.append('file', file, item.relPath);
                            
                            const xhr = new XMLHttpRequest();
                            xhr.open('POST', '/upload?auth=' + key + '&path=' + encodeURIComponent(currentPath) + '&name=' + encodeURIComponent(name), true);
                            
                            xhr.upload.onprogress = (e) => {
                                if (e.lengthComputable) {
                                    const percent = Math.round((e.loaded / e.total) * 100);
                                    document.getElementById('bar_' + uploadId).style.width = percent + '%';
                                    document.getElementById('pct_' + uploadId).innerText = percent + '%';
                                }
                            };
                            
                            xhr.onload = () => {
                                if (xhr.status === 200) {
                                    document.getElementById('txt_' + uploadId).innerText = 'Completed';
                                    document.getElementById('txt_' + uploadId).style.color = '#00e676';
                                    document.getElementById('bar_' + uploadId).style.background = '#00e676';
                                    navigate(currentPath);
                                } else {
                                    document.getElementById('txt_' + uploadId).innerText = 'Failed';
                                    document.getElementById('txt_' + uploadId).style.color = '#ff5252';
                                    div.style.border = '1px solid rgba(255, 82, 82, 0.3)';
                                }
                            };
                            xhr.send(fd);
                        });
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "unknown"
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop(1000, 2000)
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
        FileSharingRegistry.secretCode = ""
        FileSharingRegistry.allCodes = emptyList()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "P-Hub Server", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
