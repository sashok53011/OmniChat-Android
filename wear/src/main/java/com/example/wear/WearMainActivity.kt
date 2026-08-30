package com.example.wear

import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.wear.compose.material.MaterialTheme
import com.example.wear.screen.WearScreenManager
import com.example.wear.ui.WearMessage
import com.example.wear.ui.WearOmniChatScreen
import com.example.wear.ui.theme.WearOmniChatTheme
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WearMainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WearMainActivity"
        private const val UDP_PORT = 12345
    }

    private var screenManager: WearScreenManager? = null
    private var udpSocket: DatagramSocket? = null
    @Volatile private var listening = false

    private var currentChatDate = mutableStateOf("")
    private val messages = mutableStateListOf<WearMessage>()
    private val seenTimestamps = mutableSetOf<String>()
    private var statusText = mutableStateOf("Tap Start")
    private var isRunning = mutableStateOf(false)
    private var ipAddress = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            screenManager = WearScreenManager(this)
            screenManager?.keepScreenOn()
        } catch (e: Exception) { Log.e(TAG, "Screen manager failed", e) }

        loadMessages()
        ipAddress.value = getIp()

        setContent {
            WearOmniChatTheme {
                WearOmniChatScreen(
                    messages = messages,
                    isConnected = isRunning.value,
                    statusMessage = statusText.value,
                    errorMessage = null,
                    onConnect = { startListening() },
                    onDisconnect = { stopListening() },
                    onClearMessages = {
                        messages.clear()
                        seenTimestamps.clear()
                        currentChatDate.value = ""
                        getSharedPreferences("wear_chat", MODE_PRIVATE).edit().clear().apply()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        listening = false
        try { udpSocket?.close() } catch (e: Exception) { }
        try { screenManager?.releaseWakeLock() } catch (e: Exception) { }
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

    private fun getTodayString(): String {
        return SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date())
    }

    private fun startListening() {
        stopListening()

        ipAddress.value = getIp()
        if (ipAddress.value == "0.0.0.0" || ipAddress.value == "Not connected") {
            statusText.value = "ERROR: No WiFi"
            return
        }

        val today = getTodayString()
        if (currentChatDate.value != today) {
            messages.clear()
            seenTimestamps.clear()
            currentChatDate.value = today
        }

        listening = true
        isRunning.value = true
        statusText.value = "Listening on $ipAddress:$UDP_PORT"

        Thread {
            try {
                udpSocket = DatagramSocket(UDP_PORT)
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
                                val key = "${parsed.timestamp}_${parsed.content.hashCode()}"
                                if (seenTimestamps.add(key)) {
                                    runOnUiThread {
                                        messages.add(parsed)
                                        saveMessages()
                                        screenManager?.activateScreen()
                                    }
                                }
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) { }
                }
            } catch (e: Exception) {
                if (listening) {
                    statusText.value = "ERROR: ${e.message}"
                    Log.e(TAG, "UDP error", e)
                }
            }
        }.start()
    }

    private fun stopListening() {
        listening = false
        isRunning.value = false
        try { udpSocket?.close() } catch (e: Exception) { }
        udpSocket = null
        statusText.value = "Stopped"
    }

    private fun saveMessages() {
        try {
            val prefs = getSharedPreferences("wear_chat", MODE_PRIVATE)
            val arr = org.json.JSONArray()
            for (m in messages) {
                val obj = org.json.JSONObject()
                obj.put("type", m.type)
                obj.put("content", m.content)
                obj.put("timestamp", m.timestamp)
                obj.put("sender", m.sender)
                arr.put(obj)
            }
            prefs.edit()
                .putString("chat_date", currentChatDate.value)
                .putString("messages", arr.toString())
                .apply()
        } catch (e: Exception) { Log.e(TAG, "Save failed", e) }
    }

    private fun loadMessages() {
        try {
            val prefs = getSharedPreferences("wear_chat", MODE_PRIVATE)
            val savedDate = prefs.getString("chat_date", "") ?: ""
            val today = getTodayString()
            if (savedDate == today) {
                currentChatDate.value = savedDate
                val json = prefs.getString("messages", "[]") ?: "[]"
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val msg = WearMessage(
                        type = obj.optString("type", "text"),
                        content = obj.optString("content", ""),
                        timestamp = obj.optString("timestamp", ""),
                        sender = obj.optString("sender", "OmniChat")
                    )
                    val key = "${msg.timestamp}_${msg.content.hashCode()}"
                    if (seenTimestamps.add(key)) {
                        messages.add(msg)
                    }
                }
            } else {
                prefs.edit().clear().apply()
            }
        } catch (e: Exception) { Log.e(TAG, "Load failed", e) }
    }

    private fun parseMessage(raw: String): WearMessage? {
        return try {
            val json = JSONObject(raw)
            WearMessage(
                type = json.optString("type", "text"),
                content = json.optString("content", ""),
                timestamp = json.optString("timestamp", SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())),
                sender = json.optString("sender", "OmniChat")
            )
        } catch (e: Exception) {
            WearMessage("text", raw, SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()), "Unknown")
        }
    }
}
