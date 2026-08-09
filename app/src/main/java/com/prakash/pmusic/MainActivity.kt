package com.prakash.phub

import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import androidx.core.app.ActivityCompat
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import android.os.Bundle
import android.os.Environment
import android.app.DownloadManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import android.provider.Settings
import com.prakash.phub.R
import com.prakash.phub.ui.theme.PHubTheme

class MainActivity : ComponentActivity() {

    private val client = OkHttpClient()
    private val selectedFiles = mutableStateListOf<Uri>()
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris?.let {
            selectedFiles.clear()
            selectedFiles.addAll(it)
            FileSharingRegistry.selectedFiles.clear()
            FileSharingRegistry.selectedFiles.addAll(it)
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress
                        if (ip != null && !ip.contains(":")) return ip
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "Unknown"
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val wifi = getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("PHubMulticastLock").apply {
            setReferenceCounted(true)
            acquire()
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = Uri.parse("package:${packageName}")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent()
                    intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    startActivity(intent)
                }
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val permissions = mutableListOf(Manifest.permission.POST_NOTIFICATIONS)
            
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            
            if (permissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 100)
            }
        }
        setContent {
            var isServerMode by remember { mutableStateOf(false) }
            var serverIp by remember { mutableStateOf("192.168.1.1") }
            var status by remember { mutableStateOf("Not Connected") }
            var currentFile by remember { mutableStateOf("") }
            var currentFileProgress by remember { mutableStateOf(0f) }
            var overallProgress by remember { mutableStateOf(0f) }
            var downloadedCount by remember { mutableStateOf(0) }
            var skippedCount by remember { mutableStateOf(0) }
            var fileCounter by remember { mutableStateOf("0 / 0") }
            var transferSpeed by remember { mutableStateOf("") }
            var timeRemaining by remember { mutableStateOf("") }

            var showFileBrowser by remember { mutableStateOf(false) }
            var showQrDialog by remember { mutableStateOf(false) }
            var serverFileList by remember { mutableStateOf<List<String>>(emptyList()) }
            val selectedServerFiles = remember { mutableStateListOf<String>() }

            // Server-side transfer state
            val serverTransferProgress = remember { mutableStateMapOf<String, Float>() }

            // Security States
            var showAuthChallenge by remember { mutableStateOf(false) }
            var authOptions by remember { mutableStateOf<List<String>>(emptyList()) }
            var selectedAuthCode by remember { mutableStateOf("") }

            val context = androidx.compose.ui.platform.LocalContext.current

            // NSD Discovery
            DisposableEffect(isServerMode) {
                if (!isServerMode) {
                    startDiscovery { ip ->
                        serverIp = ip
                        status = "Server Discovered!"
                    }
                }
                onDispose { stopDiscovery() }
            }

            DisposableEffect(Unit) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        intent?.let {
                            if (it.action == SyncService.ACTION_SYNC_PROGRESS) {
                                val cf = it.getStringExtra("currentFile") ?: ""
                                val cfp = it.getFloatExtra("currentFileProgress", -1f)
                                val dc = it.getIntExtra("downloadedCount", 0)
                                val sc = it.getIntExtra("skippedCount", 0)
                                val tf = it.getIntExtra("totalFiles", 0)
                                val st = it.getStringExtra("status")
                                val sp = it.getStringExtra("speed") ?: ""
                                val tr = it.getStringExtra("timeRemaining") ?: ""

                                if (cf.isNotBlank()) currentFile = cf
                                if (cfp >= 0) currentFileProgress = cfp
                                downloadedCount = dc
                                skippedCount = sc
                                transferSpeed = sp
                                timeRemaining = tr
                                if (tf > 0) {
                                    fileCounter = "${dc + sc} / $tf"
                                    overallProgress = (dc + sc).toFloat() / tf.toFloat()
                                }
                                st?.let { s -> status = s }
                            } else if (it.action == ServerService.ACTION_SERVER_PROGRESS) {
                                val fileName = it.getStringExtra("fileName") ?: ""
                                val progress = it.getFloatExtra("progress", 0f)
                                if (fileName.isNotEmpty()) {
                                    serverTransferProgress[fileName] = progress
                                }
                            }
                        }
                    }
                }
                val filter = IntentFilter()
                filter.addAction(SyncService.ACTION_SYNC_PROGRESS)
                filter.addAction(ServerService.ACTION_SERVER_PROGRESS)
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                onDispose { context.unregisterReceiver(receiver) }
            }

            PHubTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    Image(
                        painter = painterResource(id = R.drawable.background),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(15.dp))
                        Text("P-Hub", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Client", color = Color.White)
                            Switch(checked = isServerMode, onCheckedChange = { isServerMode = it })
                            Text("Server", color = Color.White)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (isServerMode) {
                            ServerSection(
                                ip = getLocalIpAddress(),
                                selectedFiles = selectedFiles,
                                serverTransferProgress = serverTransferProgress,
                                onShowQr = { showQrDialog = true },
                                onPickFiles = { filePickerLauncher.launch("*/*") },
                                getFileName = { getFileName(it) }
                            )
                        } else {
                            ClientSection(
                                serverIp = serverIp,
                                onIpChange = { serverIp = it },
                                status = status,
                                onTest = {
                                    status = "Checking..."
                                    CoroutineScope(Dispatchers.IO).launch {
                                        testConnection(serverIp, selectedAuthCode) { result -> status = result }
                                    }
                                },
                                onSync = {
                                    if (selectedAuthCode.isEmpty()) {
                                        status = "Authenticate first"
                                        return@ClientSection
                                    }
                                    val intent = Intent(this@MainActivity, SyncService::class.java)
                                    intent.putExtra("SERVER_IP", serverIp)
                                    intent.putExtra("AUTH_CODE", selectedAuthCode)
                                    if (selectedServerFiles.isNotEmpty()) {
                                        intent.putStringArrayListExtra("SELECTED_FILES", ArrayList(selectedServerFiles))
                                    }
                                    startForegroundService(intent)
                                    status = "Sync Started"
                                },
                                onBrowse = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        if (selectedAuthCode.isEmpty()) {
                                            val options = fetchAuthChallenge(serverIp)
                                            if (options.isNotEmpty()) {
                                                withContext(Dispatchers.Main) {
                                                    authOptions = options
                                                    showAuthChallenge = true
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) { status = "Server Offline" }
                                            }
                                        } else {
                                            val files = fetchServerFileList(serverIp, selectedAuthCode)
                                            withContext(Dispatchers.Main) {
                                                serverFileList = files
                                                selectedServerFiles.clear()
                                                selectedServerFiles.addAll(files)
                                                showFileBrowser = true
                                            }
                                        }
                                    }
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (!isServerMode) {
                            ProgressCard(
                                status, currentFile, currentFileProgress,
                                fileCounter, overallProgress, downloadedCount, skippedCount, transferSpeed, timeRemaining
                            )
                            
                            if (status.contains("Completed")) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { openDownloadsFolder() },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                                ) {
                                    Text("Open Downloads Folder")
                                }
                            }
                        }
                    }

                    if (showQrDialog) {
                        val ip = getLocalIpAddress()
                        AlertDialog(
                            onDismissRequest = { showQrDialog = false },
                            title = { Text("Server QR Code") },
                            text = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                    Text("Scan to connect to $ip")
                                    Spacer(modifier = Modifier.height(10.dp))
                                    val bitmap = generateQrCode("http://$ip:8080")
                                    if (bitmap != null) {
                                        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "QR Code", modifier = Modifier.size(200.dp))
                                    }
                                }
                            },
                            confirmButton = { Button(onClick = { showQrDialog = false }) { Text("Close") } }
                        )
                    }

                    if (showAuthChallenge) {
                        AlertDialog(
                            onDismissRequest = { showAuthChallenge = false },
                            title = { Text("Security Verification") },
                            text = { Text("Select the correct verification code from the server.") },
                            confirmButton = {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    authOptions.chunked(2).forEach { row ->
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                            row.forEach { code ->
                                                Button(
                                                    onClick = {
                                                        selectedAuthCode = code
                                                        showAuthChallenge = false
                                                    },
                                                    modifier = Modifier.padding(4.dp)
                                                ) { Text(code) }
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }

                    if (showFileBrowser) {
                        AlertDialog(
                            onDismissRequest = { showFileBrowser = false },
                            title = { Text("Browse Server Files") },
                            text = {
                                Column(modifier = Modifier.heightIn(max = 400.dp)) {
                                    if (serverFileList.isEmpty()) {
                                        Text("No files found or server offline")
                                    } else {
                                        LazyColumn {
                                            items(serverFileList) { fileName ->
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                                ) {
                                                    Checkbox(
                                                        checked = selectedServerFiles.contains(fileName),
                                                        onCheckedChange = { checked ->
                                                            if (checked) selectedServerFiles.add(fileName)
                                                            else selectedServerFiles.remove(fileName)
                                                        }
                                                    )
                                                    Text(fileName, modifier = Modifier.padding(start = 8.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                Button(onClick = { showFileBrowser = false }) {
                                    Text("Done (${selectedServerFiles.size})")
                                }
                            }
                        )
                    }

                    // Developer Credit
                    Text(
                        text = "<<<Prakash>>>",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(10.dp)
                    )
                }
            }
        }
    }

    private fun generateQrCode(text: String): android.graphics.Bitmap? {
        return try {
            val encoder = BarcodeEncoder()
            encoder.encodeBitmap(text, BarcodeFormat.QR_CODE, 400, 400)
        } catch (e: Exception) {
            null
        }
    }

    private fun openDownloadsFolder() {
        val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/P-Hub"
        val intent = Intent(Intent.ACTION_VIEW)
        val uri = Uri.parse(path)
        intent.setDataAndType(uri, "resource/folder")
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            // Fallback: Open general downloads
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        }
    }

    private fun startDiscovery(onDiscovered: (String) -> Unit) {
        nsdManager = (getSystemService(Context.NSD_SERVICE) as NsdManager)
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains(ServerService.SERVICE_TYPE)) {
                    nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            onDiscovered(serviceInfo.host.hostAddress ?: "")
                        }
                    })
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { nsdManager?.stopServiceDiscovery(this) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { nsdManager?.stopServiceDiscovery(this) }
        }
        nsdManager?.discoverServices(ServerService.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun stopDiscovery() {
        discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        discoveryListener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        multicastLock?.let { if (it.isHeld) it.release() }
    }

    @Composable
    fun ServerSection(
        ip: String,
        selectedFiles: List<Uri>,
        serverTransferProgress: Map<String, Float>,
        onShowQr: () -> Unit,
        onPickFiles: () -> Unit,
        getFileName: (Uri) -> String
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Your IP: $ip", color = Color.Cyan, fontWeight = FontWeight.Bold)
                IconButton(onClick = onShowQr) {
                    Icon(painter = painterResource(id = android.R.drawable.ic_menu_share), contentDescription = "QR Code", tint = Color.Cyan)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val intent = Intent(this@MainActivity, ServerService::class.java)
                        startForegroundService(intent)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("Start Server")
                }

                Button(
                    onClick = {
                        val intent = Intent(this@MainActivity, ServerService::class.java)
                        stopService(intent)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                ) {
                    Text("Stop Server")
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Selective Share", color = Color.White)
                Switch(
                    checked = FileSharingRegistry.fullAccessMode,
                    onCheckedChange = { FileSharingRegistry.fullAccessMode = it }
                )
                Text("Full Storage Access", color = Color.White)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(onClick = onPickFiles, modifier = Modifier.fillMaxWidth()) {
                Text("Select Files to Share")
            }
            Text("Files selected: ${selectedFiles.size}", color = Color.White, fontSize = 12.sp)

            Spacer(modifier = Modifier.height(12.dp))

            // Display Codes
            if (FileSharingRegistry.allCodes.isNotEmpty()) {
                Text("Verification Codes (Key is Bold):", color = Color.White, fontSize = 14.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    FileSharingRegistry.allCodes.forEach { code ->
                        Text(
                            text = code,
                            color = if (code == FileSharingRegistry.secretCode) Color.Green else Color.White,
                            fontWeight = if (code == FileSharingRegistry.secretCode) FontWeight.ExtraBold else FontWeight.Normal,
                            fontSize = 24.sp
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))

            // Scrollable list of files with progress background
            if (selectedFiles.isNotEmpty()) {
                Text("Sharing List:", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp).fillMaxWidth()) {
                    items(selectedFiles) { uri ->
                        val fileName = getFileName(uri)
                        val progress = serverTransferProgress[fileName] ?: 0f
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .height(40.dp)
                                .background(Color.White.copy(alpha = 0.1f))
                        ) {
                            // Progress background "moving thing"
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(progress)
                                    .background(Color.Green.copy(alpha = 0.3f))
                            )
                            
                            Text(
                                text = fileName,
                                color = Color.White,
                                modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                                maxLines = 1,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ClientSection(
        serverIp: String,
        onIpChange: (String) -> Unit,
        status: String,
        onTest: () -> Unit,
        onSync: () -> Unit,
        onBrowse: () -> Unit
    ) {
        Column {
            OutlinedTextField(
                value = serverIp,
                onValueChange = onIpChange,
                label = { Text("Server IP (Auto-Discovery Active)", color = Color.White) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = Color.White,
                    focusedLabelColor = Color.White,
                    unfocusedLabelColor = Color.LightGray,
                    cursorColor = Color.White
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onTest, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E57C2))) {
                    Text("Test")
                }
                Button(onClick = onBrowse, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E57C2))) {
                    Text("Browse")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onSync, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) {
                Text("Sync Selected Files")
            }
        }
    }

    @Composable
    fun ProgressCard(
        status: String,
        currentFile: String,
        currentFileProgress: Float,
        fileCounter: String,
        overallProgress: Float,
        downloadedCount: Int,
        skippedCount: Int,
        speed: String,
        timeLeft: String
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.25f)),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Status", fontSize = 20.sp, color = Color.White)
                    if (speed.isNotEmpty()) {
                        Text(speed, color = Color.Cyan, fontWeight = FontWeight.Bold)
                    }
                }
                if (status != "Not Connected") {
                    Text(status, color = when {
                        status.contains("Completed") -> Color(0xFF00C853)
                        status.contains("Online") -> Color(0xFF2196F3)
                        status.contains("Error") -> Color.Red
                        else -> Color.White
                    }, fontWeight = FontWeight.Bold)
                }
                if (timeLeft.isNotEmpty() && !status.contains("Completed")) {
                    Text("Est. Time: $timeLeft", color = Color.LightGray, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text("Current: $currentFile", color = Color.White, maxLines = 1)
                LinearProgressIndicator(progress = { currentFileProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp))
                Spacer(modifier = Modifier.height(10.dp))
                Text("Overall: $fileCounter", color = Color.White)
                LinearProgressIndicator(progress = { overallProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp))
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Done: $downloadedCount", color = Color.White)
                    Text("Skipped: $skippedCount", color = Color.White)
                }
            }
        }
    }

    private suspend fun testConnection(serverIp: String, authCode: String, updateStatus: (String) -> Unit) {
        try {
            val url = if (authCode.isNotEmpty()) "http://$serverIp:8080/files?auth=$authCode" else "http://$serverIp:8080/challenge"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                withContext(Dispatchers.Main) {
                    updateStatus(if (response.isSuccessful) "Online" else "Error: ${response.code}")
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { updateStatus("Offline") }
        }
    }

    private suspend fun fetchAuthChallenge(serverIp: String): List<String> {
        return try {
            val request = Request.Builder().url("http://$serverIp:8080/challenge").build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val array = JSONArray(body)
                    val list = mutableListOf<String>()
                    for (i in 0 until array.length()) {
                        list.add(array.getString(i))
                    }
                    list
                } else emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchServerFileList(serverIp: String, authCode: String): List<String> {
        return try {
            val request = Request.Builder().url("http://$serverIp:8080/files?auth=$authCode").build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val array = JSONArray(body)
                    val list = mutableListOf<String>()
                    for (i in 0 until array.length()) {
                        list.add(array.getJSONObject(i).getString("name"))
                    }
                    list
                } else emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
