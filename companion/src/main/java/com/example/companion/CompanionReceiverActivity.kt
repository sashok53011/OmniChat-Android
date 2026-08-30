package com.example.companion

import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.text.format.Formatter
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CompanionReceiverActivity : ComponentActivity() {

    data class ReceivedMessage(
        val type: String,
        val content: String,
        val timestamp: String,
        val sender: String
    )

    private var udpSocket: DatagramSocket? = null
    @Volatile private var listening = false
    private var mediaSession: MediaSession? = null

    private val messages = mutableStateListOf<ReceivedMessage>()
    private var statusText = mutableStateOf("Tap Start")
    private var isRunning = mutableStateOf(false)
    private var ipAddress = mutableStateOf("")
    private var scrollDirection = mutableIntStateOf(0)
    private var fontSize = mutableIntStateOf(14)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ipAddress.value = getIp()
        fontSize.intValue = getSharedPreferences("companion_settings", MODE_PRIVATE).getInt("font_size", 14)
        handleIntent(intent)
        setupMediaSession()

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1565C0),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFD1E4FF),
                    surface = Color(0xFFFBFBFB)
                )
            ) {
                CompanionScreen(
                    messages = messages,
                    statusText = statusText.value,
                    isRunning = isRunning.value,
                    ipAddress = ipAddress.value,
                    scrollDirection = scrollDirection.intValue,
                    fontSize = fontSize.intValue,
                    onStart = { startReceiver() },
                    onStop = { stopReceiver() },
                    onClearMessages = { messages.clear() },
                    onFontSizeChange = { newSize ->
                        fontSize.intValue = newSize
                        getSharedPreferences("companion_settings", MODE_PRIVATE).edit().putInt("font_size", newSize).apply()
                    }
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { scrollDirection.intValue = -1; true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { scrollDirection.intValue = 1; true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> { scrollDirection.intValue = 0; true }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    private fun setupMediaSession() {
        mediaSession?.release()
        mediaSession = MediaSession(this, "OmniChatCompanion").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { scrollDirection.intValue = 1 }
                override fun onPause() { scrollDirection.intValue = 0 }
                override fun onSkipToNext() { scrollDirection.intValue = 1 }
                override fun onSkipToPrevious() { scrollDirection.intValue = -1 }
                override fun onStop() { scrollDirection.intValue = 0 }
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build()
            )
            isActive = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        listening = false
        try { udpSocket?.close() } catch (e: Exception) { }
        udpSocket = null
        try { mediaSession?.release() } catch (e: Exception) { }
        mediaSession = null
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val raw = intent?.getStringExtra("raw_message") ?: return
        val parsed = parseMessage(raw)
        if (parsed != null) {
            messages.add(parsed)
        }
    }

    private fun getIp(): String {
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip != 0) return Formatter.formatIpAddress(ip)
        } catch (e: Exception) { }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (e: Exception) { }
        return "Not connected"
    }

    private fun startReceiver() {
        ipAddress.value = getIp()
        if (ipAddress.value == "0.0.0.0" || ipAddress.value == "Not connected") {
            Toast.makeText(this, "Connect to WiFi first", Toast.LENGTH_LONG).show()
            statusText.value = "ERROR: No WiFi"
            return
        }
        listening = true
        isRunning.value = true
        statusText.value = "Listening on $ipAddress:12345"

        Thread {
            try {
                udpSocket = DatagramSocket(12345)
                udpSocket?.soTimeout = 1000
                val buffer = ByteArray(65535)
                while (listening) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udpSocket?.receive(packet)
                        val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        if (data.isNotBlank()) {
                            val parsed = parseMessage(data)
                            if (parsed != null) {
                                runOnUiThread { messages.add(parsed) }
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) { }
                }
            } catch (e: Exception) {
                if (listening) {
                    statusText.value = "ERROR: ${e.message}"
                }
            }
        }.start()
    }

    private fun stopReceiver() {
        listening = false
        isRunning.value = false
        try { udpSocket?.close() } catch (e: Exception) { }
        udpSocket = null
        statusText.value = "Stopped"
    }

    private fun parseMessage(raw: String): ReceivedMessage? {
        return try {
            val json = JSONObject(raw)
            ReceivedMessage(
                type = json.optString("type", "text"),
                content = json.optString("content", ""),
                timestamp = json.optString("timestamp", SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())),
                sender = json.optString("sender", "OmniChat")
            )
        } catch (e: Exception) {
            ReceivedMessage("text", raw, SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()), "Unknown")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionScreen(
    messages: List<CompanionReceiverActivity.ReceivedMessage>,
    statusText: String,
    isRunning: Boolean,
    ipAddress: String,
    scrollDirection: Int = 0,
    fontSize: Int = 14,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearMessages: () -> Unit,
    onFontSizeChange: (Int) -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(scrollDirection) {
        if (scrollDirection != 0 && messages.isNotEmpty()) {
            while (scrollDirection != 0) {
                delay(150)
                val current = listState.firstVisibleItemIndex
                val next = (current + scrollDirection).coerceIn(0, messages.size - 1)
                if (next != current) {
                    listState.animateScrollToItem(next)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OmniChat", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (isRunning) "Active" else "Inactive",
                            fontSize = 10.sp,
                            color = if (isRunning) Color(0xFF4CAF50) else Color.Gray
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    IconButton(onClick = onClearMessages) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Messages list - full screen
            if (messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (isRunning) "Waiting for messages..." else "Tap menu to start",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(messages) { msg ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(msg.sender, fontSize = (fontSize * 0.75f).sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text(msg.timestamp, fontSize = (fontSize * 0.65f).sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                MarkdownText(msg.content, modifier = Modifier.fillMaxWidth(), baseFontSize = fontSize.sp)
                            }
                        }
                    }
                }
            }

            // Dropdown menu
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("IP: $ipAddress", fontSize = 12.sp) },
                    onClick = { showMenu = false }
                )
                DropdownMenuItem(
                    text = { Text(statusText, fontSize = 11.sp, color = if (isRunning) Color(0xFF4CAF50) else Color.Gray) },
                    onClick = { showMenu = false }
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onFontSizeChange((fontSize - 1).coerceAtLeast(10)) }, modifier = Modifier.size(36.dp)) {
                        Text("A-", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("${fontSize}sp", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { onFontSizeChange((fontSize + 1).coerceAtMost(24)) }, modifier = Modifier.size(36.dp)) {
                        Text("A+", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(if (isRunning) "Stop" else "Start", fontSize = 12.sp) },
                    leadingIcon = { Icon(if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        showMenu = false
                        if (isRunning) onStop() else onStart()
                    }
                )
            }
        }
    }
}
