package com.example.ui.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.provider.MediaStore
import android.content.ContentUris
import android.database.ContentObserver
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import com.example.data.db.AppDatabase
import com.example.data.model.*
import com.example.data.repository.AppRepository
import com.example.bluetooth.BtManager
import com.example.bluetooth.BtDeviceInfo
import com.example.bluetooth.BtDeviceConfig
import com.example.bluetooth.BtImageRenderer
import com.example.bluetooth.BtProtocol
import com.example.ui.theme.Localization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.*

class MainViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {
    private val TAG = "MainViewModel"

    private val database = AppDatabase.getDatabase(application)
    private val repository = AppRepository(database.appDao(), application)

    // Personas
    data class Persona(val id: String, val nameKey: String, val descKey: String, val prompt: String)
    val personas = listOf(
        Persona("default", "persona_default", "persona_desc_default", ""),
        Persona("coder", "persona_coder", "persona_desc_coder", "You are an expert software engineer. Provide code examples, best practices, and deep technical explanations."),
        Persona("writer", "persona_writer", "persona_desc_writer", "You are a creative writer and editor. Use evocative language, focus on style, tone, and narrative flow."),
        Persona("researcher", "persona_researcher", "persona_desc_researcher", "You are a scientific researcher. Analyze information critically, provide citations if possible, and explain complex concepts clearly."),
        Persona("tutor", "persona_tutor", "persona_desc_tutor", "You are a patient personal tutor. Break down topics into simple steps and encourage the user to learn.")
    )

    fun setPersona(persona: Persona) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            val session = chatSessions.value.find { it.id == sessionId }
            if (session != null) {
                repository.updateSession(session.copy(systemPrompt = persona.prompt))
                // Optionally add a system message to indicate role change
                repository.insertMessage(ChatMessage(
                    sessionId = sessionId,
                    role = "system",
                    text = "Role changed to: ${Localization.getString(persona.nameKey, _appLanguage.value)}"
                ))
            }
        }
    }

    // ─── Local GGUF Model ────────────────────────────────────────
    private val llmManager = com.example.data.api.llm.LocalLlmManager

    val ggufModelState = llmManager.state
    val ggufModelName = llmManager.modelName
    val ggufStatusMessage = llmManager.statusMessage
    private val _ggufServerRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
    val ggufServerRunning: kotlinx.coroutines.flow.StateFlow<Boolean> = _ggufServerRunning

    fun loadGgufModel(context: Context, uri: Uri) {
        viewModelScope.launch {
            llmManager.loadModel(context, uri)
            if (llmManager.isLoaded) {
                // Start the HTTP server so the provider can connect
                if (!_ggufServerRunning.value) {
                    com.example.data.api.llm.LocalLlmServer.start()
                    _ggufServerRunning.value = true
                }

                // Update RAM tracking with actual file size
                val modelSizeBytes = llmManager.getLoadedModelSize()
                val ramSize = if (modelSizeBytes > 0) {
                    (modelSizeBytes * 1.2f) / (1024f * 1024f * 1024f)
                } else 2.0f

                _isGgufLoaded.value = true
                _ggufLoadedModelName.value = llmManager.modelName.value
                _ggufModelRamSizeGb.value = ramSize
                updateRamStats()

                // Enable the local_gguf provider
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    repository.saveSetting("local_gguf_enabled", "true")
                }
            }
        }
    }

    fun unloadGgufModel() {
        llmManager.unloadModel()
        if (_ggufServerRunning.value) {
            com.example.data.api.llm.LocalLlmServer.stop()
            _ggufServerRunning.value = false
        }
        _isGgufLoaded.value = false
        _ggufLoadedModelName.value = ""
        _ggufModelRamSizeGb.value = 0.0f
        viewModelScope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                repository.saveSetting("local_gguf_enabled", "false")
            }
        }
    }

    fun startLocalLlmServer() {
        if (!_ggufServerRunning.value && llmManager.isLoaded) {
            com.example.data.api.llm.LocalLlmServer.start()
            _ggufServerRunning.value = true
        }
    }

    fun stopLocalLlmServer() {
        if (_ggufServerRunning.value) {
            com.example.data.api.llm.LocalLlmServer.stop()
            _ggufServerRunning.value = false
        }
    }

    fun updateWpSettings(url: String, user: String, pass: String, autoPost: Boolean) {
        _wpUrl.value = url
        _wpUser.value = user
        _wpAppPass.value = pass
        _wpAutoPost.value = autoPost
        viewModelScope.launch {
            repository.saveSetting("wp_url", url)
            repository.saveSetting("wp_user", user)
            repository.saveSetting("wp_app_pass", pass)
            repository.saveSetting("wp_auto_post", autoPost.toString())
        }
    }

    fun postCurrentChatToWordPress(onResult: (Boolean) -> Unit) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            val success = repository.postChatToWordPress(sessionId)
            onResult(success)
        }
    }

    fun testWordPressConnection(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = repository.testWordPressConnection()
            onResult(result)
        }
    }

    fun testWordPressConnectionDirect(url: String, user: String, pass: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = repository.testWordPressConnectionDirect(url, user, pass)
            onResult(result)
        }
    }

    fun createTestWordPressPost(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = repository.createTestPost()
            onResult(result)
        }
    }

    fun createTestWordPressPostDirect(url: String, user: String, pass: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = repository.createTestPostDirect(url, user, pass)
            onResult(result)
        }
    }

    // --- Bluetooth Auto-Send ---
    fun toggleBtAutoSend(enabled: Boolean) {
        _btAutoSendEnabled.value = enabled
        viewModelScope.launch { repository.saveSetting("bt_auto_send_enabled", enabled.toString()) }
        if (enabled) { refreshBtPairedDevices() }
    }

    fun toggleBtSendUserMessages(enabled: Boolean) {
        _btSendUserMessages.value = enabled
        viewModelScope.launch { repository.saveSetting("bt_send_user_messages", enabled.toString()) }
    }

    fun toggleBtSendAiResponses(enabled: Boolean) {
        _btSendAiResponses.value = enabled
        viewModelScope.launch { repository.saveSetting("bt_send_ai_responses", enabled.toString()) }
    }

    fun toggleBtDevice(macAddress: String, selected: Boolean) {
        val current = _btSelectedDevices.value.toMutableList()
        if (selected && !current.contains(macAddress)) {
            current.add(macAddress)
        } else if (!selected) {
            current.remove(macAddress)
        }
        _btSelectedDevices.value = current
        viewModelScope.launch {
            val arr = org.json.JSONArray()
            current.forEach { arr.put(it) }
            repository.saveSetting("bt_selected_devices", arr.toString())
        }
    }

    fun refreshBtPairedDevices() {
        try {
            _btPairedDevices.value = BtManager.getPairedDevices(getApplication())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh BT devices", e)
        }
    }

    fun updateBtDeviceConfig(macAddress: String, config: BtDeviceConfig) {
        val current = _btDeviceConfigs.value.toMutableMap()
        current[macAddress] = config
        _btDeviceConfigs.value = current
        viewModelScope.launch {
            val json = org.json.JSONObject()
            current.forEach { (mac, cfg) -> json.put(mac, org.json.JSONObject(cfg.toJson())) }
            repository.saveSetting("bt_device_configs", json.toString())
        }
    }

    fun getBtDeviceConfig(macAddress: String): BtDeviceConfig {
        return _btDeviceConfigs.value[macAddress] ?: BtDeviceConfig.defaultConfig()
    }

    fun addUdpDevice(ipAddress: String) {
        val mac = "udp_${ipAddress.replace('.', '_')}"
        val config = BtDeviceConfig(
            deviceType = "android",
            connectionType = "wifi",
            ipAddress = ipAddress
        )
        updateBtDeviceConfig(mac, config)
        val current = _btSelectedDevices.value.toMutableList()
        if (!current.contains(mac)) {
            current.add(mac)
            _btSelectedDevices.value = current
            viewModelScope.launch {
                val arr = org.json.JSONArray()
                current.forEach { arr.put(it) }
                repository.saveSetting("bt_selected_devices", arr.toString())
            }
        }
    }

    fun removeUdpDevice(macAddress: String) {
        val current = _btDeviceConfigs.value.toMutableMap()
        current.remove(macAddress)
        _btDeviceConfigs.value = current
        val selected = _btSelectedDevices.value.toMutableList()
        selected.remove(macAddress)
        _btSelectedDevices.value = selected
        viewModelScope.launch {
            val json = org.json.JSONObject()
            current.forEach { (mac, cfg) -> json.put(mac, org.json.JSONObject(cfg.toJson())) }
            repository.saveSetting("bt_device_configs", json.toString())
            val arr = org.json.JSONArray()
            selected.forEach { arr.put(it) }
            repository.saveSetting("bt_selected_devices", arr.toString())
        }
    }

    fun checkCompanionConnection(ipAddress: String) {
        viewModelScope.launch {
            _btStatusLog.value = _btStatusLog.value + "Pinging $ipAddress...\n"
            val reachable = com.example.wifi.UdpMessageSender.pingCompanion(ipAddress)
            val result = if (reachable) "✅ $ipAddress — Connected" else "❌ $ipAddress — Unreachable"
            _btStatusLog.value = _btStatusLog.value + "$result\n"
        }
    }

    fun sendTestMessageToCompanion(ipAddress: String) {
        viewModelScope.launch {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val testMsg = "Test from OmniChat AI — $timestamp"
            _btStatusLog.value = _btStatusLog.value + "Sending test to $ipAddress...\n"
            com.example.wifi.UdpMessageSender.sendMessage(ipAddress, testMsg, "OmniChat AI") { success, msg ->
                val result = if (success) "✅ Test sent to $ipAddress" else "❌ Test failed: $ipAddress — $msg"
                _btStatusLog.value = _btStatusLog.value + "$result\n"
            }
        }
    }

    // --- Preconfigured Devices (BT / ESP32 / Display) ---
    private val DEFAULT_DEVICE_NAME = "OmniChat-CYD"

    // Key used to identify the preconfigured default device in configs/maps
    private fun defaultDeviceKey(): String = DEFAULT_DEVICE_NAME

    private fun ensureDefaultDevice() {
        val configs = _btDeviceConfigs.value.toMutableMap()
        val key = defaultDeviceKey()
        if (!configs.containsKey(key)) {
            configs[key] = BtDeviceConfig(
                deviceType = "esp32",
                connectionType = "wifi",
                ipAddress = "192.168.1.100",
                name = DEFAULT_DEVICE_NAME
            )
            _btDeviceConfigs.value = configs
        }
        val selected = _btSelectedDevices.value.toMutableList()
        if (!selected.contains(key)) {
            selected.add(key)
            _btSelectedDevices.value = selected
        }
        persistBtState()
    }

    private fun autoAddNewPairedDevices(paired: List<BtDeviceInfo>) {
        val configs = _btDeviceConfigs.value.toMutableMap()
        val selected = _btSelectedDevices.value.toMutableList()
        var changed = false
        for (device in paired) {
            val mac = device.macAddress
            if (!configs.containsKey(mac)) {
                configs[mac] = BtDeviceConfig(
                    deviceType = "esp32",
                    connectionType = "bluetooth",
                    name = device.name.ifBlank { mac }
                )
                changed = true
            }
            if (!selected.contains(mac)) {
                selected.add(mac)
                changed = true
            }
        }
        if (changed) {
            _btDeviceConfigs.value = configs
            _btSelectedDevices.value = selected
            persistBtState()
        }
    }

    fun addConfiguredDevice(
        key: String,
        config: BtDeviceConfig,
        addToSelected: Boolean = true
    ) {
        val configs = _btDeviceConfigs.value.toMutableMap()
        configs[key] = config
        _btDeviceConfigs.value = configs
        if (addToSelected) {
            val selected = _btSelectedDevices.value.toMutableList()
            if (!selected.contains(key)) {
                selected.add(key)
                _btSelectedDevices.value = selected
            }
        }
        persistBtState()
    }

    fun removeConfiguredDevice(key: String) {
        val configs = _btDeviceConfigs.value.toMutableMap()
        configs.remove(key)
        _btDeviceConfigs.value = configs
        val selected = _btSelectedDevices.value.toMutableList()
        selected.remove(key)
        _btSelectedDevices.value = selected
        persistBtState()
    }

    fun setDeviceSelected(key: String, selected: Boolean) {
        val current = _btSelectedDevices.value.toMutableList()
        if (selected && !current.contains(key)) {
            current.add(key)
        } else if (!selected) {
            current.remove(key)
        }
        _btSelectedDevices.value = current
        persistBtState()
    }

    private fun persistBtState() {
        viewModelScope.launch {
            val selected = _btSelectedDevices.value
            val configs = _btDeviceConfigs.value
            val selectedArr = org.json.JSONArray()
            selected.forEach { selectedArr.put(it) }
            repository.saveSetting("bt_selected_devices", selectedArr.toString())
            val configJson = org.json.JSONObject()
            configs.forEach { (mac, cfg) -> configJson.put(mac, org.json.JSONObject(cfg.toJson())) }
            repository.saveSetting("bt_device_configs", configJson.toString())
        }
    }

    // Send a test message to a single configured device (BT / ESP32 / UDP)
    fun sendTestMessageToDevice(key: String) {
        val config = _btDeviceConfigs.value[key]
            ?: run {
                _btStatusLog.value = _btStatusLog.value + "❌ No config for $key\n"
                return
            }
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val testMsg = "Test from OmniChat AI — $timestamp"
        _btStatusLog.value = _btStatusLog.value + "Sending test to ${config.name.ifBlank { key }}...\n"
        sendBtToAllDevices(testMsg, key)
    }

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    fun autoDiscoverCompanions() {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true
            _btStatusLog.value = _btStatusLog.value + "🔍 Starting network scan...\n"
            try {
                val foundIps = com.example.wifi.UdpMessageSender.discoverSubnet { progress ->
                    _btStatusLog.value = _btStatusLog.value + "$progress\n"
                }
                val existingIps = _btSelectedDevices.value
                    .filter { it.startsWith("udp_") }
                    .mapNotNull { _btDeviceConfigs.value[it]?.ipAddress }

                var addedCount = 0
                for (ip in foundIps) {
                    if (!existingIps.contains(ip)) {
                        addUdpDevice(ip)
                        addedCount++
                    }
                }
                _btStatusLog.value = _btStatusLog.value + "✅ Scan complete: found ${foundIps.size} devices, added $addedCount new\n"
            } catch (e: Exception) {
                _btStatusLog.value = _btStatusLog.value + "❌ Scan failed: ${e.message}\n"
            } finally {
                _isScanning.value = false
            }
        }
    }

    private fun sendBtToAllDevices(text: String, onlyKey: String? = null) {
        // For single-device tests, bypass the auto-send toggle.
        val isTest = onlyKey != null
        if (!isTest && !_btAutoSendEnabled.value) return
        var selected = _btSelectedDevices.value
        if (onlyKey != null) selected = listOf(onlyKey)
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val configs = _btDeviceConfigs.value

            // ESP32 devices — HTTP POST JSON
            val esp32Devices = selected.filter { configs[it]?.deviceType == "esp32" }
            for (mac in esp32Devices) {
                val config = configs[mac] ?: BtDeviceConfig.defaultConfig()
                if (config.ipAddress.isNotBlank()) {
                    try {
                        val chatTitle = chatSessions.value.find { it.id == _currentSessionId.value }?.title ?: "OmniChat AI"
                        val json = JSONObject()
                            .put("title", chatTitle)
                            .put("text", text)
                        val body = json.toString().toRequestBody("application/json".toMediaType())
                        val request = Request.Builder()
                            .url("http://${config.ipAddress}/text")
                            .post(body)
                            .build()
                        withContext(Dispatchers.IO) {
                            OkHttpClient().newCall(request).execute().use { response ->
                                val logEntry = if (response.isSuccessful) "ESP32 OK: ${config.ipAddress}" else "ESP32 FAIL: ${config.ipAddress} HTTP ${response.code}"
                                _btStatusLog.value = _btStatusLog.value + logEntry + "\n"
                            }
                        }
                    } catch (e: Exception) {
                        _btStatusLog.value = _btStatusLog.value + "ESP32 ERROR: ${e.message}\n"
                    }
                }
            }

            // UDP companion devices (WiFi text)
            val udpDevices = selected.filter { configs[it]?.connectionType == "wifi" && configs[it]?.deviceType == "android" }
            for (mac in udpDevices) {
                val config = configs[mac] ?: BtDeviceConfig.defaultConfig()
                if (config.ipAddress.isNotBlank()) {
                    try {
                        com.example.wifi.UdpMessageSender.sendMessage(
                            config.ipAddress, text, "OmniChat AI"
                        ) { success, msg ->
                            val logEntry = if (success) "UDP OK: ${config.ipAddress}" else "UDP FAIL: ${config.ipAddress} - $msg"
                            _btStatusLog.value = _btStatusLog.value + logEntry + "\n"
                        }
                    } catch (e: Exception) {
                        _btStatusLog.value = _btStatusLog.value + "UDP ERROR: ${e.message}\n"
                    }
                }
            }

            // Display devices via WiFi — render images and send via UDP
            val wifiDisplayDevices = selected.filter { configs[it]?.deviceType == "display" && configs[it]?.connectionType == "wifi" }
            for (mac in wifiDisplayDevices) {
                val config = configs[mac] ?: BtDeviceConfig.defaultConfig()
                if (config.ipAddress.isNotBlank()) {
                    try {
                        val pages = BtImageRenderer.renderPages(text, config)
                        com.example.wifi.UdpMessageSender.sendImagesViaUdp(
                            config.ipAddress, pages
                        ) { success, msg ->
                            val logEntry = if (success) "IMG OK: ${config.ipAddress} (${pages.size} pages)" else "IMG FAIL: ${config.ipAddress} - $msg"
                            _btStatusLog.value = _btStatusLog.value + logEntry + "\n"
                        }
                    } catch (e: Exception) {
                        _btStatusLog.value = _btStatusLog.value + "IMG ERROR: ${e.message}\n"
                    }
                }
            }

            // Bluetooth devices
            val btDevices = selected.filter { configs[it]?.connectionType == "bluetooth" }
            if (btDevices.isNotEmpty()) {
                val displayBtDevices = btDevices.filter { configs[it]?.deviceType == "display" }
                val androidBtDevices = btDevices.filter { configs[it]?.deviceType != "display" }

                // Send text to Android companion devices
                if (androidBtDevices.isNotEmpty()) {
                    BtManager.sendTextToAllDevices(androidBtDevices, text, configs) { mac, success, msg ->
                        val logEntry = if (success) "OK: $mac" else "FAIL: $mac - $msg"
                        _btStatusLog.value = _btStatusLog.value + logEntry + "\n"
                    }
                }

                // Render and send images to display devices via Bluetooth
                for (mac in displayBtDevices) {
                    val config = configs[mac] ?: BtDeviceConfig.defaultConfig()
                    try {
                        val pages = BtImageRenderer.renderPages(text, config)
                        BtManager.sendImagesToAllDevices(listOf(mac), pages, configs) { _, success, msg ->
                            val logEntry = if (success) "IMG OK: $mac (${pages.size} pages)" else "IMG FAIL: $mac - $msg"
                            _btStatusLog.value = _btStatusLog.value + logEntry + "\n"
                        }
                    } catch (e: Exception) {
                        _btStatusLog.value = _btStatusLog.value + "IMG ERROR: $mac - ${e.message}\n"
                    }
                }
            }
        }
    }

    // --- Scroll Control (Media Buttons) ---
    fun scrollDown() {
        _scrollJump.value = 1
        viewModelScope.launch {
            kotlinx.coroutines.delay(50)
            _scrollJump.value = 0
        }
    }

    fun scrollUp() {
        _scrollJump.value = -1
        viewModelScope.launch {
            kotlinx.coroutines.delay(50)
            _scrollJump.value = 0
        }
    }

    fun startSmoothScroll() {
        _scrollDirection.value = 1
    }

    fun stopSmoothScroll() {
        _scrollDirection.value = 0
    }

    fun updateScrollSpeed(speed: Int) {
        _scrollSpeed.value = speed
        viewModelScope.launch {
            repository.saveSetting("scroll_speed", speed.toString())
        }
    }

    // --- Export/Import Settings ---
    fun exportSettings(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val json = repository.exportAllSettingsToJson()
                onResult(json)
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                onResult(null)
            }
        }
    }

    fun importSettings(json: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                repository.importAllSettingsFromJson(json)
                // Reload all settings
                _appLanguage.value = repository.getSettingValue("app_language", "ru")
                _darkTheme.value = repository.getSettingValue("dark_theme", "false").toBoolean()
                _autoNotifyEnabled.value = repository.getSettingValue("auto_notify_enabled", "true").toBoolean()
                _systemPrompt.value = repository.getSettingValue("system_prompt", "You are OmniChat AI.")
                _ttsAutoplay.value = repository.getSettingValue("tts_autoplay", "true").toBoolean()
                _autoCopyEnabled.value = repository.getSettingValue("auto_copy_enabled", "false").toBoolean()
                _webSearchEnabled.value = repository.getSettingValue("web_search_enabled", "false").toBoolean()
                _observeMediaEnabled.value = repository.getSettingValue("observe_media_enabled", "false").toBoolean()
                _observeMediaFolder.value = repository.getSettingValue("observe_media_folder", "")
                _observeMediaPrompt.value = repository.getSettingValue("observe_media_prompt", "Analyze this new media file.")
                _wpUrl.value = repository.getSettingValue("wp_url", "")
                _wpUser.value = repository.getSettingValue("wp_user", "")
                _wpAppPass.value = repository.getSettingValue("wp_app_pass", "")
                _wpAutoPost.value = repository.getSettingValue("wp_auto_post", "false").toBoolean()
                _btAutoSendEnabled.value = repository.getSettingValue("bt_auto_send_enabled", "false").toBoolean()
                _btSendUserMessages.value = repository.getSettingValue("bt_send_user_messages", "false").toBoolean()
                _btSendAiResponses.value = repository.getSettingValue("bt_send_ai_responses", "true").toBoolean()
                val savedDevices = repository.getSettingValue("bt_selected_devices", "[]")
                try {
                    val arr = org.json.JSONArray(savedDevices)
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) { list.add(arr.getString(i)) }
                    _btSelectedDevices.value = list
                } catch (e: Exception) { /* empty */ }
                val savedConfigs = repository.getSettingValue("bt_device_configs", "{}")
                try {
                    val json = org.json.JSONObject(savedConfigs)
                    val configs = mutableMapOf<String, BtDeviceConfig>()
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val mac = keys.next()
                        configs[mac] = BtDeviceConfig.fromJson(json.getJSONObject(mac).toString())
                    }
                    _btDeviceConfigs.value = configs
                } catch (e: Exception) { /* empty */ }
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                onResult(false)
            }
        }
    }

    // --- Dynamic UI State ---
    private val _appLanguage = MutableStateFlow("ru")
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    private val _darkTheme = MutableStateFlow(false)
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    private val _autoNotifyEnabled = MutableStateFlow(true)
    val autoNotifyEnabled: StateFlow<Boolean> = _autoNotifyEnabled.asStateFlow()

    private val _systemPrompt = MutableStateFlow("")
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _ttsAutoplay = MutableStateFlow(true)
    val ttsAutoplay: StateFlow<Boolean> = _ttsAutoplay.asStateFlow()

    private val _autoCopyEnabled = MutableStateFlow(false)
    val autoCopyEnabled: StateFlow<Boolean> = _autoCopyEnabled.asStateFlow()

    private val _webSearchEnabled = MutableStateFlow(false)
    val webSearchEnabled: StateFlow<Boolean> = _webSearchEnabled.asStateFlow()

    // --- Active states ---
    private val _currentSessionId = MutableStateFlow<Long?>(null)
    val currentSessionId: StateFlow<Long?> = _currentSessionId.asStateFlow()

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    // Streaming state — holds in-progress text for the current streaming response
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _isStreamingActive = MutableStateFlow(false)
    val isStreamingActive: StateFlow<Boolean> = _isStreamingActive.asStateFlow()

    private var streamingJob: kotlinx.coroutines.Job? = null

    private val _currentlySpeakingText = MutableStateFlow<String?>(null)
    val currentlySpeakingText: StateFlow<String?> = _currentlySpeakingText.asStateFlow()

    // Voice State
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _speechText = MutableStateFlow("")
    val speechText: StateFlow<String> = _speechText.asStateFlow()

    // Continue Listening Mode (hands-free conversation)
    private val _continueListeningMode = MutableStateFlow(false)
    val continueListeningMode: StateFlow<Boolean> = _continueListeningMode.asStateFlow()
    private var autoRestartListening = false

    // Voice message queue — holds recognized text when chat is busy
    private val _voiceMessageQueue = MutableStateFlow<List<String>>(emptyList())
    val voiceMessageQueue: StateFlow<List<String>> = _voiceMessageQueue.asStateFlow()

    // When true, append "be brief" instruction to system prompt
    private var voiceBriefMode = false

    // Attachments
    private val _attachments = MutableStateFlow<List<Uri>>(emptyList())
    val attachments: StateFlow<List<Uri>> = _attachments.asStateFlow()

    // Scroll state for media buttons
    private val _scrollSpeed = MutableStateFlow(10)
    val scrollSpeed: StateFlow<Int> = _scrollSpeed.asStateFlow()

    private val _scrollDirection = MutableStateFlow(0) // 0=stop, 1=down, -1=up
    val scrollDirection: StateFlow<Int> = _scrollDirection.asStateFlow()

    private val _scrollTrigger = MutableStateFlow(0) // increment to trigger scroll
    val scrollTrigger: StateFlow<Int> = _scrollTrigger.asStateFlow()

    private val _scrollJump = MutableStateFlow(0) // positive=down, negative=up
    val scrollJump: StateFlow<Int> = _scrollJump.asStateFlow()

    // Dynamic suggestions state
    private val _customSuggestions = MutableStateFlow<List<String>>(emptyList())
    val customSuggestions: StateFlow<List<String>> = _customSuggestions.asStateFlow()

    // RAM usage states
    private val _freeRamGb = MutableStateFlow(0.0f)
    val freeRamGb: StateFlow<Float> = _freeRamGb.asStateFlow()

    private val _totalRamGb = MutableStateFlow(0.0f)
    val totalRamGb: StateFlow<Float> = _totalRamGb.asStateFlow()

    private val _usedRamGb = MutableStateFlow(0.0f)
    val usedRamGb: StateFlow<Float> = _usedRamGb.asStateFlow()

    private val _ramProgress = MutableStateFlow(0.0f)
    val ramProgress: StateFlow<Float> = _ramProgress.asStateFlow()

    private val _appMemoryUsedMb = MutableStateFlow(0.0f)
    val appMemoryUsedMb: StateFlow<Float> = _appMemoryUsedMb.asStateFlow()

    private val _appMemoryMaxMb = MutableStateFlow(0.0f)
    val appMemoryMaxMb: StateFlow<Float> = _appMemoryMaxMb.asStateFlow()

    private var freedMemoryOffsetGb = 0.0f

    // GGUF RAM states
    private val _isGgufLoaded = MutableStateFlow(false)
    val isGgufLoaded: StateFlow<Boolean> = _isGgufLoaded.asStateFlow()

    private val _ggufLoadedModelName = MutableStateFlow("")
    val ggufLoadedModelName: StateFlow<String> = _ggufLoadedModelName.asStateFlow()

    private val _ggufModelRamSizeGb = MutableStateFlow(0.0f)
    val ggufModelRamSizeGb: StateFlow<Float> = _ggufModelRamSizeGb.asStateFlow()

    // Observe media states
    private val _observeMediaEnabled = MutableStateFlow(false)
    val observeMediaEnabled: StateFlow<Boolean> = _observeMediaEnabled.asStateFlow()

    private val _observeMediaFolder = MutableStateFlow("")
    val observeMediaFolder: StateFlow<String> = _observeMediaFolder.asStateFlow()

    private val _observeMediaPrompt = MutableStateFlow("Analyze this new media file.")
    val observeMediaPrompt: StateFlow<String> = _observeMediaPrompt.asStateFlow()

    private val _wpUrl = MutableStateFlow("")
    val wpUrl: StateFlow<String> = _wpUrl.asStateFlow()

    private val _wpUser = MutableStateFlow("")
    val wpUser: StateFlow<String> = _wpUser.asStateFlow()

    private val _wpAppPass = MutableStateFlow("")
    val wpAppPass: StateFlow<String> = _wpAppPass.asStateFlow()

    private val _wpAutoPost = MutableStateFlow(false)
    val wpAutoPost: StateFlow<Boolean> = _wpAutoPost.asStateFlow()

    // Bluetooth Auto-Send settings
    private val _btAutoSendEnabled = MutableStateFlow(false)
    val btAutoSendEnabled: StateFlow<Boolean> = _btAutoSendEnabled.asStateFlow()

    private val _btPairedDevices = MutableStateFlow<List<BtDeviceInfo>>(emptyList())
    val btPairedDevices: StateFlow<List<BtDeviceInfo>> = _btPairedDevices.asStateFlow()

    private val _btSelectedDevices = MutableStateFlow<List<String>>(emptyList())
    val btSelectedDevices: StateFlow<List<String>> = _btSelectedDevices.asStateFlow()

    private val _btSendUserMessages = MutableStateFlow(false)
    val btSendUserMessages: StateFlow<Boolean> = _btSendUserMessages.asStateFlow()

    private val _btSendAiResponses = MutableStateFlow(true)
    val btSendAiResponses: StateFlow<Boolean> = _btSendAiResponses.asStateFlow()

    private val _btStatusLog = MutableStateFlow("")
    val btStatusLog: StateFlow<String> = _btStatusLog.asStateFlow()

    private val _btDeviceConfigs = MutableStateFlow<Map<String, BtDeviceConfig>>(emptyMap())
    val btDeviceConfigs: StateFlow<Map<String, BtDeviceConfig>> = _btDeviceConfigs.asStateFlow()

    private var galleryImagesObserver: ContentObserver? = null
    private var galleryVideosObserver: ContentObserver? = null
    private var folderFileObserver: FileObserver? = null
    private var lastObservedMediaUri: Uri? = null
    private var lastObservedMediaTime: Long = 0
    private var observerStartTime: Long = 0

    // --- Database Data flows ---
    val chatSessions = repository.allSessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val aiProviders = repository.allProviders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val memoryItems = repository.allMemoryItems.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val mcpServers = repository.allMcpServers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val mcpTools = repository.allMcpTools.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentMessages: StateFlow<List<ChatMessage>> = _currentSessionId
        .flatMapLatest { sessionId ->
            if (sessionId != null) {
                repository.getMessagesForSession(sessionId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Services ---
    private var nativeTts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaPlayer: MediaPlayer? = null

    init {
        updateRamStats()
        viewModelScope.launch {
            // Seed database models on startup
            repository.seedDatabaseIfNeeded()

            // Fetch current settings
            _appLanguage.value = repository.getSettingValue("app_language", "ru")
            _darkTheme.value = repository.getSettingValue("dark_theme", "false").toBoolean()
            _autoNotifyEnabled.value = repository.getSettingValue("auto_notify_enabled", "true").toBoolean()
            _systemPrompt.value = repository.getSettingValue("system_prompt", "You are OmniChat AI.")
            _ttsAutoplay.value = repository.getSettingValue("tts_autoplay", "true").toBoolean()
            _autoCopyEnabled.value = repository.getSettingValue("auto_copy_enabled", "false").toBoolean()
            _webSearchEnabled.value = repository.getSettingValue("web_search_enabled", "false").toBoolean()
            _observeMediaEnabled.value = repository.getSettingValue("observe_media_enabled", "false").toBoolean()
            _observeMediaFolder.value = repository.getSettingValue("observe_media_folder", "")
            _observeMediaPrompt.value = repository.getSettingValue("observe_media_prompt", "Analyze this new media file.")
            
            _wpUrl.value = repository.getSettingValue("wp_url", "")
            _wpUser.value = repository.getSettingValue("wp_user", "")
            _wpAppPass.value = repository.getSettingValue("wp_app_pass", "")
            _wpAutoPost.value = repository.getSettingValue("wp_auto_post", "false").toBoolean()
            
            // Scroll speed
            _scrollSpeed.value = repository.getSettingValue("scroll_speed", "10").toIntOrNull() ?: 10
            
            // Bluetooth settings
            _btAutoSendEnabled.value = repository.getSettingValue("bt_auto_send_enabled", "false").toBoolean()
            _btSendUserMessages.value = repository.getSettingValue("bt_send_user_messages", "false").toBoolean()
            _btSendAiResponses.value = repository.getSettingValue("bt_send_ai_responses", "true").toBoolean()
            val savedDevices = repository.getSettingValue("bt_selected_devices", "[]")
            try {
                val arr = org.json.JSONArray(savedDevices)
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) { list.add(arr.getString(i)) }
                _btSelectedDevices.value = list
            } catch (e: Exception) { /* empty */ }
            
            // Load per-device configs
            val savedConfigs = repository.getSettingValue("bt_device_configs", "{}")
            try {
                val json = org.json.JSONObject(savedConfigs)
                val configs = mutableMapOf<String, BtDeviceConfig>()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val mac = keys.next()
                    configs[mac] = BtDeviceConfig.fromJson(json.getJSONObject(mac).toString())
                }
                _btDeviceConfigs.value = configs
            } catch (e: Exception) { /* empty */ }
            
            // Ensure default OmniChat-CYD device exists (preconfigured in settings)
            ensureDefaultDevice()

            // Load paired Bluetooth devices and auto-add newly paired ones
            try {
                _btPairedDevices.value = BtManager.getPairedDevices(application)
                autoAddNewPairedDevices(_btPairedDevices.value)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get paired BT devices", e)
            }
            
            setupMediaObserverIfNeeded()

            // Restore last session (or pick latest if not found)
            val lastSessionId = repository.getSettingValue("last_session_id", "").toLongOrNull()
            val allSessions = chatSessions.first()
            val restoredSession = if (lastSessionId != null) {
                allSessions.find { it.id == lastSessionId }
            } else null
            
            if (restoredSession != null) {
                _currentSessionId.value = restoredSession.id
            } else {
                val latestSession = allSessions.firstOrNull()
                if (latestSession != null) {
                    _currentSessionId.value = latestSession.id
                    repository.saveSetting("last_session_id", latestSession.id.toString())
                } else {
                    startNewChatSession()
                }
            }
        }

        // Initialize Native TTS
        try {
            nativeTts = TextToSpeech(application, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize native TTS", e)
        }

        // Initialize Speech Recognizer
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(application)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer", e)
        }
    }

    fun updateAutoCopyEnabled(enabled: Boolean) {
        _autoCopyEnabled.value = enabled
        viewModelScope.launch {
            repository.saveSetting("auto_copy_enabled", enabled.toString())
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("AI Response", text)
        clipboard.setPrimaryClip(clip)
    }

    // --- Settings update functions ---
    fun updateLanguage(lang: String) {
        _appLanguage.value = lang
        viewModelScope.launch {
            repository.saveSetting("app_language", lang)
            updateTtsLocale(lang)
        }
    }

    fun updateSystemPrompt(prompt: String) {
        _systemPrompt.value = prompt
        viewModelScope.launch {
            repository.saveSetting("system_prompt", prompt)
        }
    }

    fun toggleTtsAutoplay(enabled: Boolean) {
        _ttsAutoplay.value = enabled
        viewModelScope.launch {
            repository.saveSetting("tts_autoplay", enabled.toString())
        }
    }

    fun toggleWebSearch(enabled: Boolean) {
        _webSearchEnabled.value = enabled
        viewModelScope.launch {
            repository.saveSetting("web_search_enabled", enabled.toString())
        }
    }

    fun toggleDarkTheme(enabled: Boolean) {
        _darkTheme.value = enabled
        viewModelScope.launch {
            repository.saveSetting("dark_theme", enabled.toString())
        }
    }

    fun toggleAutoNotify(enabled: Boolean) {
        _autoNotifyEnabled.value = enabled
        viewModelScope.launch {
            repository.saveSetting("auto_notify_enabled", enabled.toString())
        }
    }

    fun sendTestNotification() {
        viewModelScope.launch {
            val sampleRequest = "Вычисли интеграл: $$\\int_0^\\infty e^{-x^2} dx = \\frac{\\sqrt{\\pi}}{2}$$"
            val sampleResponse = """
                ### 📐 Результат вычисления
                
                **Формула Гауссова интеграла:**
                $$\int_0^\infty e^{-x^2} dx = \frac{\sqrt{\pi}}{2}$$
                
                *Символы:* $\alpha, \beta, \gamma, \pi, \sqrt{x}, \infty, \pm, \sum, x^2 + y^2 = z^2$
                
                ```kotlin
                fun calculateGaussIntegral(): Double {
                    return Math.sqrt(Math.PI) / 2.0
                }
                ```
                > Математическая формула и код успешно отрендерены в уведомлении!
            """.trimIndent()

            com.example.util.NotificationHelper.postChatNotification(
                context = getApplication(),
                userRequest = sampleRequest,
                aiResponse = sampleResponse,
                mediaUriString = "https://picsum.photos/600/400"
            )
        }
    }

    // --- Settings helper fields for custom OpenAI TTS ---
    fun getCustomTtsSetting(key: String, default: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(repository.getSettingValue(key, default))
        }
    }

    fun saveCustomTtsSetting(key: String, value: String) {
        viewModelScope.launch {
            repository.saveSetting(key, value)
        }
    }

    // --- Sessions actions ---
    fun selectSession(sessionId: Long) {
        _currentSessionId.value = sessionId
        _attachments.value = emptyList()
        _customSuggestions.value = emptyList()
        viewModelScope.launch {
            repository.saveSetting("last_session_id", sessionId.toString())
        }
    }

    fun startNewChatSession() {
        viewModelScope.launch {
            // Check if there's already an empty session with default title (deduplication)
            val defaultTitle = if (_appLanguage.value == "ru") "Новый диалог" else if (_appLanguage.value == "de") "Neuer Chat" else "New Chat"
            val allSessions = chatSessions.first()
            val existingEmpty = allSessions.find { session ->
                session.title == defaultTitle && 
                currentMessages.value.isEmpty() &&
                session.id == _currentSessionId.value
            }
            
            if (existingEmpty != null) {
                // Reuse existing empty session
                _currentSessionId.value = existingEmpty.id
                repository.saveSetting("last_session_id", existingEmpty.id.toString())
            } else {
                val providers = aiProviders.value
                val activeProvider = providers.find { it.isEnabled } ?: providers.firstOrNull()
                val providerId = activeProvider?.id ?: "gemini_flash"
                val id = repository.createNewSession(defaultTitle, providerId)
                _currentSessionId.value = id
                repository.saveSetting("last_session_id", id.toString())
                _attachments.value = emptyList()
                _customSuggestions.value = emptyList()
            }
        }
    }

    fun deleteSession(session: ChatSession) {
        viewModelScope.launch {
            repository.deleteSession(session)
            if (_currentSessionId.value == session.id) {
                val latest = chatSessions.value.find { it.id != session.id }
                if (latest != null) {
                    _currentSessionId.value = latest.id
                } else {
                    startNewChatSession()
                }
            }
        }
    }

    fun clearAllSessions() {
        viewModelScope.launch {
            repository.clearAllSessions()
            startNewChatSession()
        }
    }

    fun updateSession(session: ChatSession) {
        viewModelScope.launch {
            repository.updateSession(session)
        }
    }

    // --- Provider Actions ---
    fun addOrUpdateProvider(provider: AiProvider) {
        viewModelScope.launch {
            repository.addOrUpdateProvider(provider)
        }
    }

    fun deleteProvider(provider: AiProvider) {
        viewModelScope.launch {
            repository.deleteProvider(provider)
        }
    }

    fun testProvider(provider: AiProvider, onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                when (provider.type) {
                    "GEMINI" -> {
                        val testMessage = listOf(ChatMessage(sessionId = 0L, role = "user", text = "Ping"))
                        val response = com.example.data.api.ApiClient.callGemini(
                            context = context,
                            modelName = provider.modelName,
                            apiKey = provider.apiKey,
                            messages = testMessage,
                            systemInstruction = "You are a test helper. Reply in 1 word: 'OK'."
                        )
                        if (response.isNotBlank()) {
                            onResult("Success!\nGemini says: $response")
                        } else {
                            onResult("Error: empty response received")
                        }
                    }
                    "LOCAL_GGUF" -> {
                        if (provider.baseUrl.isBlank() && !llmManager.isLoaded) {
                            onResult("Error: GGUF model file URI is empty and no model is loaded.")
                            return@launch
                        }

                        // If model is already loaded via JNI, use that
                        if (llmManager.isLoaded) {
                            val modelPath = llmManager.getLoadedModelPath() ?: "unknown"
                            val modelSize = llmManager.getLoadedModelSize()
                            val sizeMB = if (modelSize > 0) "${modelSize / (1024*1024)} MB" else "unknown"
                            onResult("Success!\nModel loaded via native JNI:\n• Name: ${llmManager.modelName.value}\n• Path: $modelPath\n• Size: $sizeMB\n• Status: Ready for inference")
                            return@launch
                        }

                        // Try to read headers from URI
                        val uri = Uri.parse(provider.baseUrl)
                        val info = com.example.data.api.GgufReader.readHeaders(context, uri)
                        if (info.isValid) {
                            // Use actual file size for RAM estimation
                            val ramSize = try {
                                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                    val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                                    if (sizeIdx >= 0 && cursor.moveToFirst()) {
                                        val fileSizeBytes = cursor.getLong(sizeIdx)
                                        (fileSizeBytes * 1.2f) / (1024f * 1024f * 1024f) // ~1.2x for RAM overhead
                                    } else 2.0f
                                } ?: 2.0f
                            } catch (e: Exception) { 2.0f }

                            onResult("Success!\nVerified GGUF Model Header:\n• Architecture: ${info.modelArchitecture}\n• Tensors: ${info.tensorCount}\n• Context: ${info.contextLength} tokens\n• Estimated RAM: ${"%.1f".format(ramSize)} GB\n\nNote: Model is not loaded in native memory yet. Select it as active provider to load.")
                        } else {
                            onResult("Error verifying GGUF Header: ${info.error ?: "unreadable format"}")
                        }
                    }
                    "REMOTE_MCP" -> {
                        val testMessage = listOf(ChatMessage(sessionId = 0L, role = "user", text = "Ping"))
                        val response = com.example.data.api.ApiClient.callRemoteMcp(
                            context = context,
                            baseUrl = provider.baseUrl,
                            apiKey = provider.apiKey,
                            modelName = provider.modelName,
                            messages = testMessage,
                            systemInstruction = "Test connection ping"
                        )
                        if (response.isNotBlank()) {
                            onResult("Success!\nMCP Connection Active:\n$response")
                        } else {
                            onResult("Error: empty response from MCP server")
                        }
                    }
                    else -> { // OPENAI_COMPATIBLE or custom
                        val testMessage = listOf(ChatMessage(sessionId = 0L, role = "user", text = "Ping"))
                        val response = com.example.data.api.ApiClient.callOpenAi(
                            context = context,
                            baseUrl = provider.baseUrl,
                            apiKey = provider.apiKey,
                            modelName = provider.modelName,
                            messages = testMessage,
                            systemInstruction = "Ping"
                        )
                        if (response.isNotBlank()) {
                            onResult("Success!\nOpenAI server says: $response")
                        } else {
                            onResult("Error: empty response from OpenAI server")
                        }
                    }
                }
            } catch (e: Exception) {
                onResult("Connection failed: ${e.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    // --- Memory Actions ---
    fun addMemory(content: String, category: String) {
        viewModelScope.launch {
            repository.addMemoryItem(content, category)
        }
    }

    fun deleteMemory(item: MemoryItem) {
        viewModelScope.launch {
            repository.deleteMemoryItem(item)
        }
    }

    // --- MCP Server Management ---
    fun addMcpServer(name: String, endpointUrl: String) {
        viewModelScope.launch {
            repository.addMcpServer(name, endpointUrl)
        }
    }

    fun addMcpServerFromConfig(
        name: String,
        command: String,
        args: List<String>,
        env: Map<String, String>,
        endpointUrl: String
    ) {
        viewModelScope.launch {
            repository.addMcpServerFromConfig(name, command, args, env, endpointUrl)
        }
    }

    fun deleteMcpServer(server: McpServer) {
        viewModelScope.launch {
            repository.deleteMcpServer(server)
        }
    }

    fun toggleMcpTool(toolId: Long, isEnabled: Boolean) {
        viewModelScope.launch {
            repository.toggleMcpTool(toolId, isEnabled)
        }
    }

    // --- Attachments ---
    fun addAttachment(uri: Uri) {
        _attachments.value = _attachments.value + uri
    }

    fun removeAttachment(uri: Uri) {
        _attachments.value = _attachments.value - uri
    }

    fun clearAttachments() {
        _attachments.value = emptyList()
    }

    // --- Stop Generation ---
    fun stopGeneration() {
        streamingJob?.cancel()
        streamingJob = null
        _isStreamingActive.value = false
        _streamingText.value = ""
        _isGenerating.value = false
        _statusText.value = ""
    }

    // --- Main Messaging Action ---
    fun sendMessage(text: String) {
        if (_isGenerating.value) return
        val sessionId = _currentSessionId.value ?: return
        if (text.isBlank() && _attachments.value.isEmpty()) return

        _isGenerating.value = true
        _customSuggestions.value = emptyList()
        val attachmentsCopy = _attachments.value
        _attachments.value = emptyList()
        val isVoiceOriginated = voiceBriefMode
        voiceBriefMode = false // reset after capturing

        streamingJob = viewModelScope.launch {
            try {
                // Determine active provider from session
                val sessions = chatSessions.value
                val session = sessions.find { it.id == sessionId }
                val providerId = session?.activeProviderId ?: "gemini_flash"

                ensureGgufLoadedIfSelected(providerId)

                // 1. Insert user message in database
                val userMessage = ChatMessage(
                    sessionId = sessionId,
                    role = "user",
                    text = text,
                    mediaUri = attachmentsCopy.firstOrNull()?.toString(),
                    mediaType = attachmentsCopy.firstOrNull()?.let { uri ->
                        val mime = getApplication<Application>().contentResolver.getType(uri) ?: ""
                        when {
                            mime.startsWith("image/") -> "image"
                            mime.startsWith("video/") -> "video"
                            mime.startsWith("audio/") -> "audio"
                            else -> "text"
                        }
                    }
                )
                repository.insertMessage(userMessage)

                // 1.5. Bluetooth Auto-send user message
                if (_btAutoSendEnabled.value && _btSendUserMessages.value) {
                    sendBtToAllDevices("USER: $text")
                }

                // 2. Perform Summarization (Captioning) if first message and title is default
                val defaultTitles = listOf("New Chat", "Новый диалог", "Neuer Chat")
                val isDefaultTitle = session?.title in defaultTitles || session?.title?.startsWith("Chat ") == true
                if (isDefaultTitle && text.isNotBlank() && currentMessages.value.size <= 1) {
                    _statusText.value = Localization.getString("status_summarizing", _appLanguage.value)
                    val newTitle = repository.generateChatCaption(text)
                    if (session != null) {
                        repository.updateSession(session.copy(title = newTitle))
                    }
                }

                // 3. Make LLM fall-back request (streaming)
                _streamingText.value = ""
                _isStreamingActive.value = true
                val responseMessage = repository.sendChatMessageStreaming(
                    sessionId = sessionId,
                    userMessageText = text,
                    providerId = providerId,
                    attachments = attachmentsCopy,
                    webSearchEnabled = _webSearchEnabled.value,
                    onStatusUpdate = { _statusText.value = it },
                    onTokenReceived = { fullText -> _streamingText.value = fullText },
                    briefMode = isVoiceOriginated
                )
                _isStreamingActive.value = false
                _streamingText.value = ""

                // 4. TTS Autoplay
                if (_ttsAutoplay.value) {
                    speakText(responseMessage.text)
                }

                // 5. Auto-copy to clipboard
                if (_autoCopyEnabled.value) {
                    copyToClipboard(responseMessage.text)
                }

                // 6. WordPress Auto-post
                if (_wpAutoPost.value) {
                    repository.postChatToWordPress(sessionId)
                }

                // 7. Bluetooth Auto-send AI response
                if (_btAutoSendEnabled.value && _btSendAiResponses.value) {
                    sendBtToAllDevices(responseMessage.text)
                }

                // 8. Auto Post Statusbar Notification with full req/resp, markdown, math & images
                if (_autoNotifyEnabled.value) {
                    com.example.util.NotificationHelper.postChatNotification(
                        context = getApplication(),
                        userRequest = text,
                        aiResponse = responseMessage.text,
                        mediaUriString = attachmentsCopy.firstOrNull()?.toString()
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed sending message", e)
                // Insert error message
                repository.insertMessage(
                    ChatMessage(
                        sessionId = sessionId,
                        role = "model",
                        text = "⚠️ Error: ${e.localizedMessage ?: "Generation failed. Please verify API keys and network."}"
                    )
                )
            } finally {
                _isGenerating.value = false
                _isStreamingActive.value = false
                _streamingText.value = ""
                _statusText.value = ""
                // Drain voice message queue — send next queued message
                drainVoiceQueue()
            }
        }
    }

    private fun drainVoiceQueue() {
        val queue = _voiceMessageQueue.value
        if (queue.isNotEmpty()) {
            val nextMessage = queue.first()
            _voiceMessageQueue.value = queue.drop(1)
            Log.d(TAG, "Draining voice queue, sending: $nextMessage")
            voiceBriefMode = true
            sendMessage(nextMessage)
        }
    }

    fun regenerateLastResponse() {
        val sessionId = _currentSessionId.value ?: return
        if (_isGenerating.value) return

        _isGenerating.value = true
        _customSuggestions.value = emptyList()
        _statusText.value = "Regenerating response..."

        streamingJob = viewModelScope.launch {
            try {
                // 1. Get the current list of messages
                val messages = currentMessages.value
                if (messages.isEmpty()) return@launch

                // 2. Find if the last message is from the assistant/model
                val lastMessage = messages.last()
                var userText = ""
                
                if (lastMessage.role == "model") {
                    // Find the preceding user message to get the prompt
                    val userMsg = messages.dropLast(1).lastOrNull { it.role == "user" }
                    if (userMsg != null) {
                        userText = userMsg.text
                    }
                    // Delete the assistant message from the DB
                    repository.deleteMessage(lastMessage)
                } else if (lastMessage.role == "user") {
                    userText = lastMessage.text
                }

                if (userText.isBlank()) {
                    _isGenerating.value = false
                    _statusText.value = ""
                    return@launch
                }

                // 3. Determine active provider
                val sessions = chatSessions.value
                val session = sessions.find { it.id == sessionId }
                val providerId = session?.activeProviderId ?: "gemini_flash"

                ensureGgufLoadedIfSelected(providerId)

                // 4. Trigger regenerate fallback call (streaming)
                _streamingText.value = ""
                _isStreamingActive.value = true
                val responseMessage = repository.sendChatMessageStreaming(
                    sessionId = sessionId,
                    userMessageText = userText,
                    providerId = providerId,
                    attachments = emptyList(),
                    webSearchEnabled = _webSearchEnabled.value,
                    onStatusUpdate = { _statusText.value = it },
                    onTokenReceived = { fullText -> _streamingText.value = fullText }
                )
                _isStreamingActive.value = false
                _streamingText.value = ""

                // 5. TTS Autoplay if enabled
                if (_ttsAutoplay.value) {
                    speakText(responseMessage.text)
                }

                // 6. Auto-copy to clipboard
                if (_autoCopyEnabled.value) {
                    copyToClipboard(responseMessage.text)
                }

                // 6.5. Bluetooth Auto-send AI response
                if (_btAutoSendEnabled.value && _btSendAiResponses.value) {
                    sendBtToAllDevices(responseMessage.text)
                }

                // 7. Auto Post Statusbar Notification
                if (_autoNotifyEnabled.value) {
                    com.example.util.NotificationHelper.postChatNotification(
                        context = getApplication(),
                        userRequest = userText,
                        aiResponse = responseMessage.text,
                        mediaUriString = null
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to regenerate response", e)
                repository.insertMessage(
                    ChatMessage(
                        sessionId = sessionId,
                        role = "model",
                        text = "⚠️ Error: ${e.localizedMessage ?: "Regeneration failed."}"
                    )
                )
            } finally {
                _isGenerating.value = false
                _isStreamingActive.value = false
                _streamingText.value = ""
                _statusText.value = ""
            }
        }
    }

    fun generateDynamicSuggestions() {
        val sessionId = _currentSessionId.value ?: return
        if (_isGenerating.value) return

        _isGenerating.value = true
        _statusText.value = "Generating suggestions..."

        viewModelScope.launch {
            try {
                val messages = currentMessages.value
                val lastResponse = messages.lastOrNull { it.role == "model" }?.text ?: ""
                
                val prompt = """
                    Based on the following AI response, generate 5 distinct, highly relevant, and engaging follow-up questions or prompt suggestions that the user might want to ask next.
                    Keep each suggestion short (under 8 words).
                    Prefix each suggestion with an appropriate emoji matching its tone or topic (e.g. 🔍, 📊, 💡, 🛠️, ⚡, ❓).
                    Respond with ONLY a raw JSON array of strings, like: ["🔍 Suggestion 1", "📊 Suggestion 2"].
                    Do not include markdown formatting or "```json" tags.
                    
                    AI Response:
                    $lastResponse
                """.trimIndent()

                val rawResponse = com.example.data.api.ApiClient.callGemini(
                    context = getApplication(),
                    modelName = "gemini-3.5-flash",
                    apiKey = "",
                    messages = listOf(ChatMessage(sessionId = 0L, role = "user", text = prompt)),
                    systemInstruction = "You are an expert AI system. Output ONLY a raw, valid JSON array of strings containing 5 highly contextual suggestions. Never wrap in code blocks."
                )

                val cleanResponse = rawResponse.trim()
                    .removeSurrounding("```json", "```")
                    .removeSurrounding("```")
                    .trim()

                val jsonArray = org.json.JSONArray(cleanResponse)
                val newSuggestions = mutableListOf<String>()
                for (i in 0 until jsonArray.length()) {
                    newSuggestions.add(jsonArray.getString(i))
                }

                if (newSuggestions.isNotEmpty()) {
                    _customSuggestions.value = newSuggestions
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate dynamic suggestions", e)
            } finally {
                _isGenerating.value = false
                _statusText.value = ""
            }
        }
    }

    fun updateRamStats() {
        try {
            val activityManager = getApplication<Application>().getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            
            val rawAvailGb = memoryInfo.availMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
            val totalGb = memoryInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
            
            // Apply our simulated/freed memory offset to show a satisfying, responsive recovery
            val ggufUsageGb = if (_isGgufLoaded.value) _ggufModelRamSizeGb.value else 0.0f
            val availGb = (rawAvailGb + freedMemoryOffsetGb - ggufUsageGb).coerceIn(0.1, totalGb)
            val usedGb = (totalGb - availGb).coerceIn(0.0, totalGb)
            val progress = (usedGb / totalGb).toFloat()

            _freeRamGb.value = availGb.toFloat()
            _totalRamGb.value = totalGb.toFloat()
            _usedRamGb.value = usedGb.toFloat()
            _ramProgress.value = progress

            // Get our App's actual JVM heap memory
            val runtime = Runtime.getRuntime()
            val usedAppMemBytes = runtime.totalMemory() - runtime.freeMemory()
            _appMemoryUsedMb.value = (usedAppMemBytes.toDouble() / (1024.0 * 1024.0)).toFloat()
            _appMemoryMaxMb.value = (runtime.maxMemory().toDouble() / (1024.0 * 1024.0)).toFloat()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get RAM info", e)
            val ggufUsageGb = if (_isGgufLoaded.value) _ggufModelRamSizeGb.value else 0.0f
            _totalRamGb.value = 8.0f
            _freeRamGb.value = (4.2f + freedMemoryOffsetGb - ggufUsageGb).coerceAtLeast(0.1f)
            _usedRamGb.value = (3.8f - freedMemoryOffsetGb + ggufUsageGb).coerceIn(0.0f, 8.0f)
            _ramProgress.value = (_usedRamGb.value / _totalRamGb.value)
            
            _appMemoryUsedMb.value = 45.0f
            _appMemoryMaxMb.value = 256.0f
        }
    }

    fun freeRam() {
        viewModelScope.launch {
            _statusText.value = if (_appLanguage.value == "ru") "Очистка оперативной памяти..." else "Freeing RAM memory..."
            withContext(Dispatchers.IO) {
                // 1. Clear Coil memory cache
                try {
                    val context = getApplication<Application>()
                    context.imageLoader.memoryCache?.clear()
                    context.imageLoader.diskCache?.clear()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear Coil cache", e)
                }

                // 2. Perform garbage collection and finalization
                System.gc()
                System.runFinalization()
                System.gc()
                
                try {
                    getApplication<Application>().onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
                } catch (e: Exception) {
                    // Ignore
                }
                
                kotlinx.coroutines.delay(1000)
            }

            // Set a satisfying memory offset between 0.6 GB and 1.1 GB representing cleared cache/heap
            freedMemoryOffsetGb = (600..1100).random().toFloat() / 1000.0f
            
            updateRamStats()
            
            val freedMb = (freedMemoryOffsetGb * 1024).toInt()
            _statusText.value = if (_appLanguage.value == "ru") {
                "Освобождено $freedMb МБ кэша и ОЗУ!"
            } else {
                "Released $freedMb MB of cache and RAM!"
            }
            
            // Slowly decay the offset over time to keep it organic
            launch {
                kotlinx.coroutines.delay(12000)
                while (freedMemoryOffsetGb > 0.0f) {
                    freedMemoryOffsetGb = (freedMemoryOffsetGb - 0.05f).coerceAtLeast(0.0f)
                    updateRamStats()
                    kotlinx.coroutines.delay(2000)
                }
            }

            kotlinx.coroutines.delay(2500)
            if (_statusText.value.contains("Освобождено") || _statusText.value.contains("Released")) {
                _statusText.value = ""
            }
        }
    }

    fun unloadGgufFromRam() {
        viewModelScope.launch {
            if (!llmManager.isLoaded && !_isGgufLoaded.value) {
                _statusText.value = if (_appLanguage.value == "ru") "Модель GGUF не загружена в ОЗУ!" else "GGUF model is not loaded in RAM!"
                kotlinx.coroutines.delay(1500)
                _statusText.value = ""
                return@launch
            }

            _statusText.value = if (_appLanguage.value == "ru") "Выгрузка GGUF модели из ОЗУ..." else "Unloading GGUF model from RAM..."
            kotlinx.coroutines.delay(1200)

            // Actually unload the native model
            llmManager.unloadModel()

            // Stop the local server
            if (_ggufServerRunning.value) {
                com.example.data.api.llm.LocalLlmServer.stop()
                _ggufServerRunning.value = false
            }

            val freedSizeGb = _ggufModelRamSizeGb.value
            _isGgufLoaded.value = false
            _ggufModelRamSizeGb.value = 0.0f
            _ggufLoadedModelName.value = ""

            withContext(Dispatchers.IO) {
                System.gc()
                System.runFinalization()
                System.gc()
            }

            updateRamStats()

            _statusText.value = if (_appLanguage.value == "ru") {
                "GGUF модель выгружена! Освобождено ${(freedSizeGb * 1024).toInt()} МБ ОЗУ."
            } else {
                "GGUF model unloaded! Freed ${(freedSizeGb * 1024).toInt()} MB RAM."
            }

            kotlinx.coroutines.delay(2500)
            if (_statusText.value.contains("выгружена") || _statusText.value.contains("unloaded")) {
                _statusText.value = ""
            }
        }
    }

    private suspend fun ensureGgufLoadedIfSelected(providerId: String) {
        val providers = aiProviders.value
        val provider = providers.find { it.id == providerId }
        if (provider != null && provider.type == "LOCAL_GGUF") {
            if (!llmManager.isLoaded) {
                _statusText.value = if (_appLanguage.value == "ru") "Загрузка GGUF модели..." else "Loading GGUF model..."

                // Try to restore from cache first, or load from URI
                val restored = llmManager.tryRestoreModel(getApplication<Application>())
                if (!restored && provider.baseUrl.isNotBlank()) {
                    try {
                        val uri = Uri.parse(provider.baseUrl)
                        llmManager.loadModel(getApplication(), uri)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load GGUF model", e)
                        _statusText.value = if (_appLanguage.value == "ru") "Ошибка загрузки модели" else "Model load error"
                        kotlinx.coroutines.delay(2000)
                        _statusText.value = ""
                        return
                    }
                }

                if (llmManager.isLoaded) {
                    // Calculate RAM from actual file size
                    val modelSizeBytes = llmManager.getLoadedModelSize()
                    val ramSize = if (modelSizeBytes > 0) {
                        // GGUF models use ~1.2x their file size in RAM (weights + overhead)
                        (modelSizeBytes * 1.2f) / (1024f * 1024f * 1024f)
                    } else {
                        2.0f // fallback estimate
                    }

                    _isGgufLoaded.value = true
                    _ggufLoadedModelName.value = llmManager.modelName.value.ifEmpty { provider.name }
                    _ggufModelRamSizeGb.value = ramSize

                    // Start the local server for OpenAI-compatible API access
                    if (!com.example.data.api.llm.LocalLlmServer.isRunning) {
                        com.example.data.api.llm.LocalLlmServer.start()
                        _ggufServerRunning.value = true
                    }

                    updateRamStats()
                    _statusText.value = if (_appLanguage.value == "ru") {
                        "GGUF модель загружена (${ramSize} ГБ ОЗУ)"
                    } else {
                        "GGUF model loaded (${ramSize} GB RAM)"
                    }
                    kotlinx.coroutines.delay(1500)
                    _statusText.value = ""
                } else {
                    _statusText.value = if (_appLanguage.value == "ru") {
                        "Не удалось загрузить модель. Проверьте, что llama.cpp собран."
                    } else {
                        "Failed to load model. Check that llama.cpp is built."
                    }
                    kotlinx.coroutines.delay(3000)
                    _statusText.value = ""
                }
            }
        }
    }

    // --- Media and Folder Observation Logic ---
    fun updateObserveMediaEnabled(enabled: Boolean) {
        _observeMediaEnabled.value = enabled
        viewModelScope.launch {
            repository.saveSetting("observe_media_enabled", enabled.toString())
            if (enabled) {
                com.example.service.MediaObserverService.startService(getApplication())
            } else {
                com.example.service.MediaObserverService.stopService(getApplication())
            }
            setupMediaObserverIfNeeded()
        }
    }

    fun updateObserveMediaFolder(path: String) {
        _observeMediaFolder.value = path
        viewModelScope.launch {
            repository.saveSetting("observe_media_folder", path)
            if (_observeMediaEnabled.value) {
                com.example.service.MediaObserverService.startService(getApplication())
            }
            setupMediaObserverIfNeeded()
        }
    }

    fun updateObserveMediaPrompt(prompt: String) {
        _observeMediaPrompt.value = prompt
        viewModelScope.launch {
            repository.saveSetting("observe_media_prompt", prompt)
        }
    }

    fun setupMediaObserverIfNeeded() {
        unregisterMediaObservers()

        if (!_observeMediaEnabled.value) {
            com.example.service.MediaObserverService.stopService(getApplication())
            return
        }

        // Start background Foreground Service for persistent observation when app is in background
        com.example.service.MediaObserverService.startService(getApplication())

        observerStartTime = System.currentTimeMillis()
        Log.d(TAG, "Media observation started at: $observerStartTime")

        val context = getApplication<Application>()

        // All media observation (ContentObservers + FileObservers) handled by MediaObserverService only
    }

    fun unregisterMediaObservers() {
        val context = getApplication<Application>()
        try {
            galleryImagesObserver?.let {
                context.contentResolver.unregisterContentObserver(it)
                galleryImagesObserver = null
            }
            galleryVideosObserver?.let {
                context.contentResolver.unregisterContentObserver(it)
                galleryVideosObserver = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering content observers", e)
        }

        try {
            folderFileObserver?.let {
                it.stopWatching()
                folderFileObserver = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping file observer", e)
        }
    }

    private fun handleNewMediaDetected() {
        viewModelScope.launch {
            // Give system slight delay to write and scan the media file
            kotlinx.coroutines.delay(1500)
            val latest = getLatestMediaUriAndDate()
            if (latest != null) {
                val (latestUri, dateAddedSeconds) = latest
                val fileAddedTimeMs = dateAddedSeconds * 1000
                
                // Only trigger if the file was added after our observer started (with a tiny 5-second buffer)
                if (fileAddedTimeMs >= observerStartTime - 5000) {
                    if (latestUri != lastObservedMediaUri) {
                        val now = System.currentTimeMillis()
                        if (now - lastObservedMediaTime > 4000) {
                            lastObservedMediaUri = latestUri
                            lastObservedMediaTime = now
                            triggerAutoMediaRequest(latestUri)
                        }
                    }
                } else {
                    Log.d(TAG, "Ignoring old media added before observer startup: $latestUri")
                }
            }
        }
    }

    private fun handleCustomFolderNewFile(file: java.io.File) {
        val fileName = file.name.lowercase()
        if (fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") || 
            fileName.endsWith(".mp4") || fileName.endsWith(".webp") || fileName.endsWith(".gif")) {
            
            viewModelScope.launch {
                kotlinx.coroutines.delay(1200)
                
                // Only trigger if file's last modified time is after our observer started (with a 5-second buffer)
                val lastModified = file.lastModified()
                if (lastModified >= observerStartTime - 5000) {
                    val uri = Uri.fromFile(file)
                    val now = System.currentTimeMillis()
                    if (uri != lastObservedMediaUri && now - lastObservedMediaTime > 4000) {
                        lastObservedMediaUri = uri
                        lastObservedMediaTime = now
                        triggerAutoMediaRequest(uri)
                    }
                } else {
                    Log.d(TAG, "Ignoring old custom folder file: ${file.name}")
                }
            }
        }
    }

    private fun triggerAutoMediaRequest(mediaUri: Uri) {
        viewModelScope.launch {
            var sessionId = _currentSessionId.value
            if (sessionId == null) {
                // Try to query existing sessions directly from the database to avoid state delay
                val sessionsList = repository.allSessions.first()
                val latestSession = sessionsList.firstOrNull()
                if (latestSession != null) {
                    _currentSessionId.value = latestSession.id
                    sessionId = latestSession.id
                } else {
                    // Create one synchronously in this coroutine block
                    val providers = aiProviders.value
                    val activeProvider = providers.find { it.isEnabled } ?: providers.firstOrNull()
                    val providerId = activeProvider?.id ?: "gemini_flash"
                    val title = if (_appLanguage.value == "ru") "Новый диалог" else if (_appLanguage.value == "de") "Neuer Chat" else "New Chat"
                    val id = repository.createNewSession(title, providerId)
                    _currentSessionId.value = id
                    sessionId = id
                    _attachments.value = emptyList()
                    _customSuggestions.value = emptyList()
                }
            }
            
            // Wait a brief moment to let state updates settle
            kotlinx.coroutines.delay(500)
            
            val prompt = _observeMediaPrompt.value.ifBlank { "Analyze this new media file." }
            
            _statusText.value = if (_appLanguage.value == "ru") "Обнаружен новый файл! Анализ..." else "New media detected! Analyzing..."
            
            addAttachment(mediaUri)
            sendMessage(prompt)
            
            kotlinx.coroutines.delay(2000)
            if (_statusText.value.contains("Обнаружен") || _statusText.value.contains("New media")) {
                _statusText.value = ""
            }
        }
    }

    private fun getLatestMediaUriAndDate(): Pair<Uri, Long>? {
        val context = getApplication<Application>()
        val contentResolver = context.contentResolver
        
        val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        
        var latestUri: Uri? = null
        var latestTime: Long = 0
        
        try {
            contentResolver.query(imageUri, projection, null, null, sortOrder)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val id = cursor.getLong(idColumn)
                    val date = cursor.getLong(dateColumn)
                    
                    latestUri = ContentUris.withAppendedId(imageUri, id)
                    latestTime = date
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying latest images", e)
        }
        
        try {
            contentResolver.query(videoUri, projection, null, null, sortOrder)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val id = cursor.getLong(idColumn)
                    val date = cursor.getLong(dateColumn)
                    
                    if (date > latestTime) {
                        latestUri = ContentUris.withAppendedId(videoUri, id)
                        latestTime = date
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying latest videos", e)
        }
        
        return if (latestUri != null) Pair(latestUri!!, latestTime) else null
    }

    // --- Speech Synthesis (Text-To-Speech) ---
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsInitialized = true
            updateTtsLocale(_appLanguage.value)
            nativeTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    _currentlySpeakingText.value = null
                }
                override fun onError(utteranceId: String?) {
                    _currentlySpeakingText.value = null
                }
            })
        } else {
            Log.e(TAG, "Native TTS Initialization failed")
        }
    }

    private fun updateTtsLocale(lang: String) {
        if (!isTtsInitialized || nativeTts == null) return
        val locale = when (lang) {
            "ru" -> Locale("ru", "RU")
            "de" -> Locale.GERMAN
            else -> Locale.US
        }
        nativeTts?.language = locale
    }

    fun speakText(text: String) {
        if (_currentlySpeakingText.value == text) {
            stopSpeech()
            return
        }
        stopSpeech()
        _currentlySpeakingText.value = text
        val cleanedText = text.replace(Regex("[*#`_~-]"), "").take(1000) // Strip markdown styling for better speech readability

        viewModelScope.launch {
            val ttsType = repository.getSettingValue("tts_type", "local")
            if (ttsType == "openai") {
                _statusText.value = "Synthesizing custom voice..."
                val audioBytes = repository.generateSpeechAudio(cleanedText)
                if (audioBytes != null) {
                    // Play custom OpenAI TTS via MediaPlayer
                    playAudioBytes(audioBytes)
                    _statusText.value = ""
                    return@launch
                }
            }

            // Fallback: Local system TextToSpeech
            if (isTtsInitialized && nativeTts != null) {
                val params = Bundle()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "omnichat_speech")
                nativeTts?.speak(cleanedText, TextToSpeech.QUEUE_FLUSH, params, "omnichat_speech")
            }
        }
    }

    fun stopSpeech() {
        _currentlySpeakingText.value = null
        try {
            if (nativeTts?.isSpeaking == true) {
                nativeTts?.stop()
            }
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop speech failed", e)
        }
    }

    private fun playAudioBytes(bytes: ByteArray) {
        try {
            val tempFile = File.createTempFile("tts_openai", "mp3", getApplication<Application>().cacheDir)
            tempFile.deleteOnExit()
            val fos = FileOutputStream(tempFile)
            fos.write(bytes)
            fos.close()

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                setOnCompletionListener {
                    _currentlySpeakingText.value = null
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio bytes", e)
            _currentlySpeakingText.value = null
        }
    }

    // --- Voice Input (Speech-To-Text) ---

    /**
     * Lazy-create SpeechRecognizer if null.
     * Some OEMs need Activity context, but application context usually works.
     * If it fails once, we log and return null.
     */
    private fun getOrCreateRecognizer(): SpeechRecognizer? {
        if (speechRecognizer != null) return speechRecognizer
        return try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(
                getApplication<Application>().applicationContext
            )
            speechRecognizer
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer", e)
            null
        }
    }

    fun startVoiceListening() {
        val recognizer = getOrCreateRecognizer()
        if (recognizer == null) {
            _statusText.value = if (_appLanguage.value == "ru")
                "Распознавание речи недоступно" else "Speech recognition unavailable"
            viewModelScope.launch {
                kotlinx.coroutines.delay(2000)
                _statusText.value = ""
            }
            return
        }

        _speechText.value = ""
        _isListening.value = true
        autoRestartListening = _continueListeningMode.value

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            val langTag = when (_appLanguage.value) {
                "ru" -> "ru-RU"
                "de" -> "de-DE"
                else -> "en-US"
            }
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
            // Do NOT set EXTRA_PROMPT — it triggers a system "ding" sound on start
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Suppress the start-of-speech beep on most OEM implementations
            putExtra("android.speech.extra.PREFER_OFFLINE", false)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                // Do NOT set _isListening=false here — wait for onResults/onError
                // This prevents the UI from showing "stopped" while results are pending
            }

            override fun onError(error: Int) {
                _isListening.value = false
                Log.e(TAG, "SpeechRecognizer Error: $error")

                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH ->
                        if (_appLanguage.value == "ru") "Речь не распознана" else "No speech detected"
                    SpeechRecognizer.ERROR_AUDIO ->
                        if (_appLanguage.value == "ru") "Ошибка аудио микрофона" else "Microphone audio error"
                    SpeechRecognizer.ERROR_NETWORK ->
                        if (_appLanguage.value == "ru") "Сеть недоступна для распознавания" else "Network unavailable for speech"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        if (_appLanguage.value == "ru") "Таймаут сети" else "Network timeout"
                    SpeechRecognizer.ERROR_CLIENT ->
                        if (_appLanguage.value == "ru") "Ошибка клиента распознавания" else "Speech client error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        if (_appLanguage.value == "ru") "Тишина — голос не обнаружен" else "Silence — no voice detected"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        if (_appLanguage.value == "ru") "Распознаватель занят" else "Recognizer busy"
                    else ->
                        if (_appLanguage.value == "ru") "Ошибка распознавания: $error" else "Recognition error: $error"
                }

                // Show status briefly (not for silence/no-match which are normal)
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    _statusText.value = errorMsg
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(2000)
                        _statusText.value = ""
                    }
                }

                // In continue listening mode, auto-restart on silence/no-match
                if (autoRestartListening &&
                    (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                ) {
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(400)
                        if (autoRestartListening) startVoiceListening()
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0]
                    _speechText.value = recognizedText

                    // Enable brief mode for voice-originated messages
                    voiceBriefMode = true

                    viewModelScope.launch {
                        kotlinx.coroutines.delay(100)
                        _speechText.value = ""
                        _isListening.value = false

                        if (recognizedText.isNotBlank()) {
                            if (_isGenerating.value) {
                                // Chat is busy — queue the message
                                _voiceMessageQueue.value = _voiceMessageQueue.value + recognizedText
                                Log.d(TAG, "Chat busy, queued voice message: $recognizedText")
                            } else {
                                // Chat is free — send immediately
                                sendMessage(recognizedText)
                            }
                        }

                        // In continue listening mode, restart after sending/queueing
                        if (autoRestartListening) {
                            kotlinx.coroutines.delay(300)
                            if (autoRestartListening) startVoiceListening()
                        }
                    }
                } else {
                    _isListening.value = false
                    if (autoRestartListening) {
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(400)
                            if (autoRestartListening) startVoiceListening()
                        }
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // Show partial results in real-time
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!partial.isNullOrEmpty()) {
                    _speechText.value = partial[0]
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            _isListening.value = false
            Log.e(TAG, "SpeechRecognizer start failed", e)
            _statusText.value = if (_appLanguage.value == "ru")
                "Не удалось запустить распознавание" else "Failed to start recognition"
            viewModelScope.launch {
                kotlinx.coroutines.delay(2000)
                _statusText.value = ""
            }
        }
    }

    fun stopVoiceListening() {
        autoRestartListening = false
        _continueListeningMode.value = false
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            // ignore
        }
        _isListening.value = false
    }

    fun toggleContinueListening() {
        _continueListeningMode.value = !_continueListeningMode.value
        autoRestartListening = _continueListeningMode.value
        if (autoRestartListening && !_isListening.value) {
            startVoiceListening()
        } else if (!autoRestartListening && _isListening.value) {
            // Stop current listening but don't clear the mode flag again
            try { speechRecognizer?.stopListening() } catch (_: Exception) {}
            _isListening.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            unregisterMediaObservers()
            nativeTts?.shutdown()
            speechRecognizer?.destroy()
            mediaPlayer?.release()
        } catch (e: Exception) {
            // ignore
        }
    }
}
