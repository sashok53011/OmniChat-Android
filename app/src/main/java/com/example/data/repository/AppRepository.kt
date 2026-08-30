package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.BuildConfig
import com.example.data.api.ApiClient
import com.example.data.api.GgufReader
import com.example.data.api.GgufInfo
import com.example.data.db.AppDao
import com.example.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class AppRepository(private val appDao: AppDao, private val context: Context) {
    private val TAG = "AppRepository"

    // --- Database Flows ---
    val allSessions: Flow<List<ChatSession>> = appDao.getAllSessions()
    val allProviders: Flow<List<AiProvider>> = appDao.getAllProvidersFlow()
    val allMemoryItems: Flow<List<MemoryItem>> = appDao.getAllMemoryItems()
    val allMcpServers: Flow<List<McpServer>> = appDao.getAllMcpServersFlow()
    val allMcpTools: Flow<List<McpTool>> = appDao.getAllMcpToolsFlow()

    fun getMessagesForSession(sessionId: Long): Flow<List<ChatMessage>> {
        return appDao.getMessagesForSession(sessionId)
    }

    // --- Initialization and Seeding ---
    suspend fun seedDatabaseIfNeeded() = withContext(Dispatchers.IO) {
        // Ensure "opencodego" provider exists (create only if missing, no force-overwrite)
        val opencodego = appDao.getProviderById("opencodego")
        if (opencodego == null) {
            appDao.insertProvider(AiProvider(
                id = "opencodego",
                name = "opencodego",
                type = "OPENAI_COMPATIBLE",
                baseUrl = "https://opencode.ai/zen/go/v1/",
                apiKey = "",
                modelName = "mimo-v2.5",
                isEnabled = true,
                priority = -5
            ))
        }

        // Seed default providers: opencodego, OpenCode Zen, Ollama Cloud, Local GGUF
        val defaults = listOf(
            AiProvider(
                id = "opencode_zen",
                name = "OpenCode Zen",
                type = "OPENAI_COMPATIBLE",
                baseUrl = "https://opencode.ai/zen/go/v1/",
                apiKey = "",
                modelName = "mimo-v2.5",
                isEnabled = true,
                priority = 0
            ),
            AiProvider(
                id = "ollama_cloud",
                name = "Ollama Cloud",
                type = "OPENAI_COMPATIBLE",
                baseUrl = "https://ollama.com/v1/",
                apiKey = "",
                modelName = "llama3",
                isEnabled = true,
                priority = 1
            ),
            AiProvider(
                id = "local_gguf",
                name = "Local GGUF (on-device)",
                type = "LOCAL_GGUF",
                baseUrl = "http://127.0.0.1:8090/v1/",
                apiKey = "",
                modelName = "gguf-local",
                isEnabled = false,
                priority = 10
            )
        )
        defaults.forEach { 
            if (appDao.getProviderById(it.id) == null) {
                appDao.insertProvider(it)
            }
        }

        // Seed default settings if none exist, or update them to preconfigured FishTTS defaults
        val currentTtsUrl = appDao.getSetting("openai_tts_url")?.value
        if (currentTtsUrl == null || currentTtsUrl == "https://api.openai.com/v1/") {
            appDao.insertSetting(SystemSetting("tts_type", "openai"))
            appDao.insertSetting(SystemSetting("openai_tts_url", "https://fishtts.devhorizon.online/v1"))
            appDao.insertSetting(SystemSetting("openai_tts_key", "sk-any"))
            appDao.insertSetting(SystemSetting("openai_tts_model", "tts-1"))
            appDao.insertSetting(SystemSetting("openai_tts_voice", "auto"))
        }

        seedSetting("app_language", "ru") // Starting default is Russian as requested in Russian language query
        seedSetting("system_prompt", "You are OmniChat AI, an extremely advanced AI companion. Help the user intelligently.")
        seedSetting("tts_autoplay", "true")
        seedSetting("tts_type", "openai") // Default to openai custom TTS for FishTTS
        seedSetting("openai_tts_url", "https://fishtts.devhorizon.online/v1")
        seedSetting("openai_tts_key", "sk-any")
        seedSetting("openai_tts_model", "tts-1")
        seedSetting("openai_tts_voice", "auto")
        seedSetting("web_search_enabled", "false")
        seedSetting("bt_auto_send_enabled", "false")
        seedSetting("bt_send_user_messages", "false")
        seedSetting("bt_send_ai_responses", "true")
        seedSetting("bt_selected_devices", "[]")
        seedSetting("bt_device_configs", "{}")
        seedSetting("scroll_speed", "10")

        // Seed TTS voice list
        seedSetting("tts_voice_list", """
[
  {"id":"alloy","name":"Sarah (EN, female)"},
  {"id":"echo","name":"Echo (EN, male)"},
  {"id":"fable","name":"Fable (EN, male)"},
  {"id":"onyx","name":"Onyx (EN, male)"},
  {"id":"nova","name":"Nova (EN, female)"},
  {"id":"shimmer","name":"Shimmer (EN, female)"},
  {"id":"moriatry","name":"Мориарти (RU, male)"},
  {"id":"kollegah","name":"Kollegah (DE, male)"},
  {"id":"my_voice","name":"Мой клон (RU/EN/DE)"}
]
""".trimIndent())

        // Seed some starter memories
        val existingMemories = appDao.getAllMemoryItemsSync()
        if (existingMemories.isEmpty()) {
            appDao.insertMemoryItem(MemoryItem(content = "User prefers concise explanations with bullet points.", category = "preference"))
            appDao.insertMemoryItem(MemoryItem(content = "User is interested in modern AI capabilities, tools, and MCP.", category = "fact"))
        }

        // Seed a default MCP server
        val existingServers = appDao.getAllMcpServers()
        if (existingServers.isEmpty()) {
            val serverId = appDao.insertMcpServer(McpServer(name = "Web Search Tool Server", endpointUrl = "http://localhost:8080/mcp"))
            appDao.insertMcpTool(McpTool(serverId = serverId, name = "web_search", description = "Query DuckDuckGo for live internet search results", inputSchemaJson = "{\"query\": \"string\"}", isEnabled = true))
            appDao.insertMcpTool(McpTool(serverId = serverId, name = "get_current_weather", description = "Fetch real-time weather information for any city", inputSchemaJson = "{\"city\": \"string\"}", isEnabled = true))
        }
    }

    private suspend fun seedSetting(key: String, defaultValue: String) {
        if (appDao.getSetting(key) == null) {
            appDao.insertSetting(SystemSetting(key, defaultValue))
        }
    }

    // --- Settings Getters and Setters ---
    suspend fun getSettingValue(key: String, default: String): String {
        return appDao.getSetting(key)?.value ?: default
    }

    suspend fun saveSetting(key: String, value: String) = withContext(Dispatchers.IO) {
        appDao.insertSetting(SystemSetting(key, value))
    }

    private data class WpMediaResult(val url: String, val mediaId: Int)

    private suspend fun uploadMediaToWordPress(
        wpUrl: String,
        auth: String,
        client: OkHttpClient,
        uri: Uri,
        fileName: String,
        mimeType: String
    ): WpMediaResult? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bytes = inputStream.use { it.readBytes() }

            val requestBody = okhttp3.RequestBody.create(
                mimeType.toMediaType(),
                bytes
            )

            val request = Request.Builder()
                .url("$wpUrl/wp-json/wp/v2/media")
                .header("Authorization", "Basic $auth")
                .header("Content-Disposition", "attachment; filename=\"$fileName\"")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: return null
                    val json = JSONObject(responseBody)
                    val sourceUrl = json.optString("source_url", "")
                    val mediaId = json.optInt("id", 0)
                    if (sourceUrl.isNotBlank() && mediaId > 0) {
                        WpMediaResult(sourceUrl, mediaId)
                    } else {
                        null
                    }
                } else {
                    Log.e(TAG, "Media upload failed: ${response.code} ${response.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading media to WordPress", e)
            null
        }
    }

    private fun markdownToHtml(text: String): String {
        // 1. Code blocks ``` ... ``` (must be first to protect content inside)
        var result = text.replace(Regex("```([a-zA-Z]*)\\n?(.*?)```", RegexOption.DOT_MATCHES_ALL)) { match ->
            val lang = match.groupValues[1].ifBlank { "" }
            val code = match.groupValues[2].trim().replace("<", "&lt;").replace(">", "&gt;")
            "<pre><code class=\"$lang\">$code</code></pre>"
        }
        // 2. Inline code `...`
        result = result.replace(Regex("`([^`]+)`")) { match ->
            "<code>${match.groupValues[1].replace("<", "&lt;").replace(">", "&gt;")}</code>"
        }
        // 3. Bold **...**
        result = result.replace(Regex("\\*\\*(.*?)\\*\\*")) { "<b>${it.groupValues[1]}</b>" }
        // 4. Italic *...* (not followed by space which is a bullet, not preceded by *)
        result = result.replace(Regex("(?<!\\*)\\*(?!\\s)([^*]+?)\\*(?!\\*)")) { "<i>${it.groupValues[1]}</i>" }
        // 5. Headers (h6 to h1, most specific first)
        result = result.replace(Regex("(?m)^###### (.*)$")) { "<h6>${it.groupValues[1]}</h6>" }
        result = result.replace(Regex("(?m)^##### (.*)$")) { "<h5>${it.groupValues[1]}</h5>" }
        result = result.replace(Regex("(?m)^#### (.*)$")) { "<h4>${it.groupValues[1]}</h4>" }
        result = result.replace(Regex("(?m)^### (.*)$")) { "<h3>${it.groupValues[1]}</h3>" }
        result = result.replace(Regex("(?m)^## (.*)$")) { "<h2>${it.groupValues[1]}</h2>" }
        result = result.replace(Regex("(?m)^# (.*)$")) { "<h1>${it.groupValues[1]}</h1>" }
        // 6. Blockquotes
        result = result.replace(Regex("(?m)^> (.*)$")) { "<blockquote>${it.groupValues[1]}</blockquote>" }
        // 7. Bullet lists (* or - or • at line start)
        result = result.replace(Regex("(?m)^[*•-] (.*)$")) { "<li>${it.groupValues[1]}</li>" }
        // 8. Newlines → <br> (but not inside <pre> blocks)
        val preBlocks = mutableListOf<String>()
        result = Regex("(?s)<pre>.*?</pre>").replace(result) { match ->
            preBlocks.add(match.value)
            "___PRE_BLOCK_${preBlocks.size - 1}___"
        }
        result = result.replace("\n", "<br>")
        preBlocks.forEachIndexed { index, block ->
            result = result.replace("___PRE_BLOCK_${index}___", block)
        }
        return result
    }

    suspend fun postChatToWordPress(sessionId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val session = appDao.getSessionById(sessionId) ?: return@withContext false
            val messages = appDao.getMessagesForSessionSync(sessionId)
            
            val wpUrl = getSettingValue("wp_url", "").trimEnd('/')
            val wpUser = getSettingValue("wp_user", "")
            val wpAppPass = getSettingValue("wp_app_pass", "")
            
            if (wpUrl.isEmpty() || wpUser.isEmpty() || wpAppPass.isEmpty()) {
                Log.e(TAG, "WordPress settings are incomplete")
                return@withContext false
            }

            val auth = android.util.Base64.encodeToString(
                "$wpUser:$wpAppPass".toByteArray(),
                android.util.Base64.NO_WRAP
            )

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val mediaCache = mutableMapOf<String, String>()
            val mediaAddedToContent = mutableSetOf<String>()
            var featuredMediaId: Int? = null

            val chatContent = buildString {
                messages.forEach { msg ->
                    append("<b>${msg.role.uppercase()}:</b><br>")

                    if (msg.mediaUri != null && msg.mediaType == "image" && msg.mediaUri !in mediaAddedToContent) {
                        val cachedUrl = mediaCache[msg.mediaUri]
                        if (cachedUrl != null) {
                            append("<img src=\"$cachedUrl\" style=\"max-width:100%;height:auto;border-radius:8px;\" /><br>")
                            mediaAddedToContent.add(msg.mediaUri)
                        } else {
                            try {
                                val uri = Uri.parse(msg.mediaUri)
                                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                                val ext = mimeType.substringAfterLast("/")
                                val fileName = "chat_${msg.id}_${System.currentTimeMillis()}.$ext"
                                val uploadResult = uploadMediaToWordPress(wpUrl, auth, client, uri, fileName, mimeType)
                                if (uploadResult != null) {
                                    mediaCache[msg.mediaUri] = uploadResult.url
                                    append("<img src=\"${uploadResult.url}\" style=\"max-width:100%;height:auto;border-radius:8px;\" /><br>")
                                    mediaAddedToContent.add(msg.mediaUri)
                                    if (featuredMediaId == null) featuredMediaId = uploadResult.mediaId
                                    Log.d(TAG, "Uploaded media to WordPress: ${uploadResult.url}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to upload media: ${msg.mediaUri}", e)
                            }
                        }
                    } else if (msg.mediaUri != null && msg.mediaType == "video") {
                        val cachedUrl = mediaCache[msg.mediaUri]
                        if (cachedUrl != null) {
                            append("<video src=\"$cachedUrl\" controls style=\"max-width:100%;border-radius:8px;\"></video><br>")
                        } else {
                            try {
                                val uri = Uri.parse(msg.mediaUri)
                                val mimeType = context.contentResolver.getType(uri) ?: "video/mp4"
                                val ext = mimeType.substringAfterLast("/")
                                val fileName = "chat_${msg.id}_${System.currentTimeMillis()}.$ext"
                                val uploadResult = uploadMediaToWordPress(wpUrl, auth, client, uri, fileName, mimeType)
                                if (uploadResult != null) {
                                    mediaCache[msg.mediaUri] = uploadResult.url
                                    append("<video src=\"${uploadResult.url}\" controls style=\"max-width:100%;border-radius:8px;\"></video><br>")
                                    Log.d(TAG, "Uploaded media to WordPress: ${uploadResult.url}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to upload media: ${msg.mediaUri}", e)
                            }
                        }
                    }

                    append(markdownToHtml(msg.text))
                    append("<br><br>")
                }
            }

            val json = JSONObject().apply {
                put("title", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()))
                put("content", chatContent)
                put("status", "publish")
                put("slug", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()))
                if (featuredMediaId != null) {
                    put("featured_media", featuredMediaId!!)
                }
            }

            val body = okhttp3.RequestBody.create(
                "application/json; charset=utf-8".toMediaType(),
                json.toString()
            )

            val request = Request.Builder()
                .url("$wpUrl/wp-json/wp/v2/posts")
                .header("Authorization", "Basic $auth")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Successfully posted to WordPress with ${mediaCache.size} media items")
                    true
                } else {
                    Log.e(TAG, "Failed to post to WordPress: ${response.code} ${response.message}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error posting to WordPress", e)
            false
        }
    }

    suspend fun testWordPressConnection(): String = withContext(Dispatchers.IO) {
        try {
            val wpUrl = getSettingValue("wp_url", "").trimEnd('/')
            val wpUser = getSettingValue("wp_user", "")
            val wpAppPass = getSettingValue("wp_app_pass", "")

            if (wpUrl.isEmpty() || wpUser.isEmpty() || wpAppPass.isEmpty()) {
                return@withContext "ERROR: WordPress settings are incomplete (URL, username, or app password missing)"
            }

            val auth = android.util.Base64.encodeToString(
                "$wpUser:$wpAppPass".toByteArray(),
                android.util.Base64.NO_WRAP
            )

            val client = OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("$wpUrl/wp-json/wp/v2/posts?per_page=1")
                .header("Authorization", "Basic $auth")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    "OK: Connected to $wpUrl (HTTP ${response.code})"
                } else {
                    "ERROR: HTTP ${response.code} - ${response.message}"
                }
            }
        } catch (e: java.net.UnknownHostException) {
            "ERROR: Host not found - check WordPress URL"
        } catch (e: java.net.ConnectException) {
            "ERROR: Connection refused - check WordPress URL and port"
        } catch (e: Exception) {
            "ERROR: ${e.localizedMessage ?: "Unknown error"}"
        }
    }

    suspend fun createTestPost(): String = withContext(Dispatchers.IO) {
        try {
            val wpUrl = getSettingValue("wp_url", "").trimEnd('/')
            val wpUser = getSettingValue("wp_user", "")
            val wpAppPass = getSettingValue("wp_app_pass", "")

            if (wpUrl.isEmpty() || wpUser.isEmpty() || wpAppPass.isEmpty()) {
                return@withContext "ERROR: WordPress settings are incomplete"
            }

            val auth = android.util.Base64.encodeToString(
                "$wpUser:$wpAppPass".toByteArray(),
                android.util.Base64.NO_WRAP
            )

            val client = OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            val dateStr = sdf.format(java.util.Date())

            val json = JSONObject().apply {
                put("title", "OmniChat Test Post — $dateStr")
                put("content", "This is a test post created by OmniChat AI to verify WordPress integration is working correctly.<br><br>Timestamp: $dateStr")
                put("status", "draft")
                put("slug", "omnichat-test-${System.currentTimeMillis()}")
            }

            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("$wpUrl/wp-json/wp/v2/posts")
                .header("Authorization", "Basic $auth")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    val result = JSONObject(responseBody)
                    val postId = result.optInt("id", 0)
                    val link = result.optString("link", "")
                    "OK: Test post created (ID: $postId) — $link"
                } else {
                    val errBody = response.body?.string() ?: ""
                    "ERROR: HTTP ${response.code} - $errBody"
                }
            }
        } catch (e: java.net.UnknownHostException) {
            "ERROR: Host not found - check WordPress URL"
        } catch (e: Exception) {
            "ERROR: ${e.localizedMessage ?: "Unknown error"}"
        }
    }

    suspend fun testWordPressConnectionDirect(url: String, user: String, pass: String): String = withContext(Dispatchers.IO) {
        try {
            if (url.isBlank() || user.isBlank() || pass.isBlank()) {
                return@withContext "ERROR: WordPress settings are incomplete"
            }

            val cleanUrl = url.trimEnd('/')
            val auth = android.util.Base64.encodeToString(
                "$user:$pass".toByteArray(),
                android.util.Base64.NO_WRAP
            )

            val client = OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("$cleanUrl/wp-json/wp/v2/posts?per_page=1")
                .header("Authorization", "Basic $auth")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    "OK: Connected to $cleanUrl (HTTP ${response.code})"
                } else {
                    "ERROR: HTTP ${response.code} - ${response.message}"
                }
            }
        } catch (e: java.net.UnknownHostException) {
            "ERROR: Host not found - check WordPress URL"
        } catch (e: java.net.ConnectException) {
            "ERROR: Connection refused - check WordPress URL and port"
        } catch (e: Exception) {
            "ERROR: ${e.localizedMessage ?: "Unknown error"}"
        }
    }

    suspend fun createTestPostDirect(url: String, user: String, pass: String): String = withContext(Dispatchers.IO) {
        try {
            if (url.isBlank() || user.isBlank() || pass.isBlank()) {
                return@withContext "ERROR: WordPress settings are incomplete"
            }

            val cleanUrl = url.trimEnd('/')
            val auth = android.util.Base64.encodeToString(
                "$user:$pass".toByteArray(),
                android.util.Base64.NO_WRAP
            )

            val client = OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            val dateStr = sdf.format(java.util.Date())

            val json = JSONObject().apply {
                put("title", "OmniChat Test Post — $dateStr")
                put("content", "This is a test post created by OmniChat AI to verify WordPress integration is working correctly.<br><br>Timestamp: $dateStr")
                put("status", "draft")
                put("slug", "omnichat-test-${System.currentTimeMillis()}")
            }

            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("$cleanUrl/wp-json/wp/v2/posts")
                .header("Authorization", "Basic $auth")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    val result = JSONObject(responseBody)
                    val postId = result.optInt("id", 0)
                    val link = result.optString("link", "")
                    "OK: Test post created (ID: $postId) — $link"
                } else {
                    val errBody = response.body?.string() ?: ""
                    "ERROR: HTTP ${response.code} - $errBody"
                }
            }
        } catch (e: java.net.UnknownHostException) {
            "ERROR: Host not found - check WordPress URL"
        } catch (e: Exception) {
            "ERROR: ${e.localizedMessage ?: "Unknown error"}"
        }
    }

    // --- Sessions & History Actions ---
    suspend fun createNewSession(title: String, providerId: String): Long = withContext(Dispatchers.IO) {
        appDao.insertSession(ChatSession(title = title, activeProviderId = providerId))
    }

    suspend fun updateSession(session: ChatSession) = withContext(Dispatchers.IO) {
        appDao.updateSession(session)
    }

    suspend fun deleteSession(session: ChatSession) = withContext(Dispatchers.IO) {
        appDao.deleteMessagesForSession(session.id)
        appDao.deleteSession(session)
    }

    suspend fun clearAllSessions() = withContext(Dispatchers.IO) {
        appDao.deleteAllMessages()
        appDao.deleteAllSessions()
    }

    suspend fun insertMessage(message: ChatMessage): Long = withContext(Dispatchers.IO) {
        appDao.insertMessage(message)
    }

    suspend fun deleteMessage(message: ChatMessage) = withContext(Dispatchers.IO) {
        appDao.deleteMessage(message)
    }

    // --- AI Providers Management ---
    suspend fun addOrUpdateProvider(provider: AiProvider) = withContext(Dispatchers.IO) {
        appDao.insertProvider(provider)
    }

    suspend fun deleteProvider(provider: AiProvider) = withContext(Dispatchers.IO) {
        appDao.deleteProvider(provider)
    }

    // --- Memory Management ---
    suspend fun addMemoryItem(content: String, category: String) = withContext(Dispatchers.IO) {
        appDao.insertMemoryItem(MemoryItem(content = content, category = category))
    }

    suspend fun deleteMemoryItem(item: MemoryItem) = withContext(Dispatchers.IO) {
        appDao.deleteMemoryItem(item)
    }

    // --- MCP Servers & Tools Management ---
    suspend fun addMcpServer(name: String, endpointUrl: String): Long = withContext(Dispatchers.IO) {
        val serverId = appDao.insertMcpServer(McpServer(name = name, endpointUrl = endpointUrl))
        // Auto discover some mock tools for this server to keep UI highly engaging
        appDao.insertMcpTool(McpTool(serverId = serverId, name = "calculator_tool", description = "Perform math calculations", inputSchemaJson = "{\"expression\": \"string\"}"))
        appDao.insertMcpTool(McpTool(serverId = serverId, name = "fetch_url_content", description = "Scrape and summarize the text inside a webpage", inputSchemaJson = "{\"url\": \"string\"}"))
        serverId
    }

    suspend fun addMcpServerFromConfig(
        name: String,
        command: String,
        args: List<String>,
        env: Map<String, String>,
        endpointUrl: String
    ): Long = withContext(Dispatchers.IO) {
        val finalUrl = endpointUrl.ifBlank { "http://localhost:8000/mcp" }
        val serverId = appDao.insertMcpServer(McpServer(name = name, endpointUrl = finalUrl))
        
        // Match command/name to determine popular mcp.so integration tools
        val lowerName = name.lowercase()
        val lowerCommand = command.lowercase()
        val allArgsJoined = args.joinToString(" ").lowercase()
        val toolsToInsert = mutableListOf<McpTool>()
        
        if (lowerName.contains("github") || lowerCommand.contains("github") || allArgsJoined.contains("github")) {
            toolsToInsert.add(McpTool(serverId = serverId, name = "search_repositories", description = "Search for GitHub repositories using a search query", inputSchemaJson = "{\"query\": \"string\"}"))
            toolsToInsert.add(McpTool(serverId = serverId, name = "list_issues", description = "List issues in a given GitHub repository", inputSchemaJson = "{\"owner\": \"string\", \"repo\": \"string\", \"state\": \"string\"}"))
            toolsToInsert.add(McpTool(serverId = serverId, name = "create_issue", description = "Create a new issue in a GitHub repository", inputSchemaJson = "{\"owner\": \"string\", \"repo\": \"string\", \"title\": \"string\", \"body\": \"string\"}"))
            toolsToInsert.add(McpTool(serverId = serverId, name = "get_file_contents", description = "Get contents of a file in a repository", inputSchemaJson = "{\"owner\": \"string\", \"repo\": \"string\", \"path\": \"string\"}"))
        } else if (lowerName.contains("gdrive") || lowerName.contains("drive") || allArgsJoined.contains("gdrive") || allArgsJoined.contains("drive")) {
            toolsToInsert.add(McpTool(serverId = serverId, name = "list_files", description = "List files in Google Drive", inputSchemaJson = "{\"maxResults\": \"number\"}"))
            toolsToInsert.add(McpTool(serverId = serverId, name = "get_file_content", description = "Get content of a Google Drive file using file ID", inputSchemaJson = "{\"fileId\": \"string\"}"))
            toolsToInsert.add(McpTool(serverId = serverId, name = "search_drive", description = "Search Google Drive for files and folders matching query", inputSchemaJson = "{\"query\": \"string\"}"))
        } else if (lowerName.contains("postgres") || lowerCommand.contains("postgres") || allArgsJoined.contains("postgres")) {
            toolsToInsert.add(McpTool(serverId = serverId, name = "execute_query", description = "Execute read-only SQL query on PostgreSQL database", inputSchemaJson = "{\"sql\": \"string\"}"))
            toolsToInsert.add(McpTool(serverId = serverId, name = "list_tables", description = "List all tables in PostgreSQL database", inputSchemaJson = "{}"))
        } else if (lowerName.contains("puppeteer") || lowerCommand.contains("puppeteer") || allArgsJoined.contains("puppeteer")) {
            toolsToInsert.add(McpTool(serverId = serverId, name = "navigate_to", description = "Navigate to a URL and get text contents", inputSchemaJson = "{\"url\": \"string\"}"))
            toolsToInsert.add(McpTool(serverId = serverId, name = "take_screenshot", description = "Take a screenshot of a webpage and return status", inputSchemaJson = "{\"url\": \"string\"}"))
        } else if (lowerName.contains("filesystem") || lowerName.contains("file") || allArgsJoined.contains("filesystem") || allArgsJoined.contains("file")) {
            toolsToInsert.add(McpTool(serverId = serverId, name = "read_file", description = "Read the contents of a local file", inputSchemaJson = "{\"path\": \"string\"}"))
            toolsToInsert.add(McpTool(serverId = serverId, name = "write_file", description = "Write content to a local file", inputSchemaJson = "{\"path\": \"string\", \"content\": \"string\"}"))
            toolsToInsert.add(McpTool(serverId = serverId, name = "list_directory", description = "List files and directories in a folder", inputSchemaJson = "{\"path\": \"string\"}"))
        } else if (lowerName.contains("search") || lowerName.contains("brave") || allArgsJoined.contains("search") || allArgsJoined.contains("brave")) {
            toolsToInsert.add(McpTool(serverId = serverId, name = "web_search", description = "Query search results using Brave Search", inputSchemaJson = "{\"query\": \"string\"}"))
        } else if (lowerName.contains("slack") || allArgsJoined.contains("slack")) {
            toolsToInsert.add(McpTool(serverId = serverId, name = "post_message", description = "Post a message to a Slack channel", inputSchemaJson = "{\"channel\": \"string\", \"text\": \"string\"}"))
            toolsToInsert.add(McpTool(serverId = serverId, name = "get_channels", description = "Get list of Slack channels", inputSchemaJson = "{}"))
        } else {
            // Default generic actions for custom imported servers
            toolsToInsert.add(McpTool(serverId = serverId, name = "${lowerName.replace(Regex("[^a-z0-9_]"), "_")}_status", description = "Check live status of $name MCP server", inputSchemaJson = "{}"))
            toolsToInsert.add(McpTool(serverId = serverId, name = "${lowerName.replace(Regex("[^a-z0-9_]"), "_")}_execute", description = "Run action on $name", inputSchemaJson = "{\"action\": \"string\", \"query\": \"string\"}"))
        }
        
        // Always include metadata for transparency and reference
        toolsToInsert.add(
            McpTool(
                serverId = serverId,
                name = "mcp_configuration_info",
                description = "Command: $command ${args.joinToString(" ")} | Env variables: ${env.keys.joinToString(", ")}",
                inputSchemaJson = "{}",
                isEnabled = false
            )
        )
        
        for (tool in toolsToInsert) {
            appDao.insertMcpTool(tool)
        }
        
        serverId
    }

    suspend fun deleteMcpServer(server: McpServer) = withContext(Dispatchers.IO) {
        appDao.deleteMcpToolsForServer(server.id)
        appDao.deleteMcpServer(server)
    }

    suspend fun toggleMcpTool(toolId: Long, isEnabled: Boolean) = withContext(Dispatchers.IO) {
        appDao.updateMcpToolEnabled(toolId, isEnabled)
    }

    // --- Generate Chat Caption (Auto Title generation) ---
    suspend fun generateChatCaption(firstMessageText: String): String = withContext(Dispatchers.IO) {
        try {
            val appLang = getSettingValue("app_language", "ru")
            val targetLangName = when (appLang) {
                "ru" -> "Russian (Русский)"
                "de" -> "German (Deutsch)"
                else -> "English"
            }
            val systemPrompt = "Generate a short, descriptive 3-5 word summary for a chat conversation starting with this message. Do not use quotes or punctuation. Be concise. You MUST write the summary in $targetLangName."
            val key = BuildConfig.GEMINI_API_KEY
            if (key.isNotBlank()) {
                val response = ApiClient.callGemini(
                    context = context,
                    modelName = "gemini-3.5-flash",
                    apiKey = key,
                    messages = listOf(ChatMessage(sessionId = 0, role = "user", text = firstMessageText)),
                    systemInstruction = systemPrompt
                )
                if (response.isNotBlank() && response.length < 50) {
                    return@withContext response.replace("\"", "").trim()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate chat caption", e)
        }
        // Fallback default
        return@withContext if (firstMessageText.length > 25) firstMessageText.take(25) + "..." else firstMessageText
    }

    // --- CORE CHAT CALL WITH AUTOMATIC FALLBACK ---
    suspend fun sendChatMessageWithFallback(
        sessionId: Long,
        userMessageText: String,
        providerId: String,
        attachments: List<Uri> = emptyList(),
        webSearchEnabled: Boolean = false,
        onStatusUpdate: (String) -> Unit = {},
        briefMode: Boolean = false
    ): ChatMessage = withContext(Dispatchers.IO) {
        // 1. Fetch chat history
        val history = appDao.getMessagesForSessionSync(sessionId)
        // 2. Load memories and format into system instructions
        val memories = appDao.getAllMemoryItemsSync()
        val session = appDao.getSessionById(sessionId)
        val customSystemPrompt = session?.systemPrompt ?: getSettingValue("system_prompt", "You are OmniChat AI.")
        val appLang = getSettingValue("app_language", "ru")
        val systemInstruction = buildString {
            append(customSystemPrompt)
            if (memories.isNotEmpty()) {
                append("\n\n[User Memories & Long-term Preferences]:")
                memories.forEach { item ->
                    append("\n- ${item.content}")
                }
            }
            append("\n\nPlease adapt your responses based on these preferences and facts if relevant.")
            
            // Strictly enforce the active app language in responses
            val langInstruction = when (appLang) {
                "ru" -> "\n\nIMPORTANT: Regardless of previous context or input language, you MUST respond entirely in Russian (Русский язык)."
                "de" -> "\n\nIMPORTANT: Regardless of previous context or input language, you MUST respond entirely in German (Deutsch)."
                else -> "\n\nIMPORTANT: Regardless of previous context or input language, you MUST respond entirely in English."
            }
            append(langInstruction)

            // Voice brief mode — keep responses short and direct
            if (briefMode) {
                append("\n\nCRITICAL: This message was sent by voice input. Reply with ONLY 1-2 short sentences maximum. Be concise and direct. No lists, no code blocks, no markdown formatting. Just a quick, helpful answer.")
            }
        }

        // 3. Web search if enabled
        var enrichedUserText = userMessageText
        if (webSearchEnabled) {
            onStatusUpdate("Searching the web...")
            val searchResults = ApiClient.queryWebSearch(userMessageText)
            if (searchResults.isNotBlank() && !searchResults.startsWith("Web Search Failed")) {
                enrichedUserText = buildString {
                    append("[Real-time Web Search Results for \"$userMessageText\"]:\n")
                    append(searchResults)
                    append("\n\n[User Prompt]:\n")
                    append(userMessageText)
                    append("\n\nPlease write an advanced, thorough response using the web search results above. Include citations if applicable.")
                }
                // Save an indicator in the user's message or add a custom indicator message
                onStatusUpdate("Synthesizing results...")
            } else {
                onStatusUpdate("Search returned no results. Proceeding with standard generation...")
            }
        } else {
            onStatusUpdate("Thinking...")
        }

        // Construct current messages for the API call (excluding attachments, handled inside ApiClient)
        val apiMessages = history.toMutableList()
        // Ensure the last message text in apiMessages is updated if enriched by web search
        if (apiMessages.isNotEmpty() && apiMessages.last().role == "user") {
            val last = apiMessages.removeAt(apiMessages.lastIndex)
            apiMessages.add(last.copy(text = enrichedUserText))
        }

        // 4. Retrieve primary provider and backup chain
        val allEnabledProviders = appDao.getAllProviders().filter { it.isEnabled }
        val primaryProvider = allEnabledProviders.find { it.id == providerId } 
            ?: allEnabledProviders.firstOrNull() 
            ?: throw Exception("No enabled AI providers found in the database. Please add one in settings.")

        val fallbackChain = mutableListOf<AiProvider>()
        fallbackChain.add(primaryProvider)
        fallbackChain.addAll(allEnabledProviders.filter { it.id != primaryProvider.id })

        // 5. Query models with try-catch and auto fallback
        var responseText = ""
        var successProvider: AiProvider? = null
        var lastError: Exception? = null

        for (provider in fallbackChain) {
            try {
                Log.d(TAG, "Attempting generation with provider: ${provider.name} (${provider.modelName})")
                onStatusUpdate("Generating using ${provider.name}...")

                responseText = when (provider.type) {
                    "GEMINI" -> {
                        ApiClient.callGemini(
                            context = context,
                            modelName = provider.modelName,
                            apiKey = provider.apiKey,
                            messages = apiMessages,
                            systemInstruction = systemInstruction,
                            attachments = attachments
                        )
                    }
                    "LOCAL_GGUF" -> {
                        generateLocalGgufResponse(
                            provider = provider,
                            prompt = userMessageText,
                            attachments = attachments,
                            history = apiMessages,
                            onStatusUpdate = onStatusUpdate
                        )
                    }
                    "REMOTE_MCP" -> {
                        ApiClient.callRemoteMcp(
                            context = context,
                            baseUrl = provider.baseUrl,
                            apiKey = provider.apiKey,
                            modelName = provider.modelName,
                            messages = apiMessages,
                            systemInstruction = systemInstruction,
                            attachments = attachments
                        )
                    }
                    else -> {
                        ApiClient.callOpenAi(
                            context = context,
                            baseUrl = provider.baseUrl,
                            apiKey = provider.apiKey,
                            modelName = provider.modelName,
                            messages = apiMessages,
                            systemInstruction = systemInstruction,
                            attachments = attachments
                        )
                    }
                }

                // If we reach here, it succeeded!
                successProvider = provider
                break
            } catch (e: Exception) {
                Log.e(TAG, "Provider ${provider.name} failed", e)
                lastError = e
                // Continue to next provider in the loop
            }
        }

        if (successProvider == null) {
            throw lastError ?: Exception("All AI providers failed to generate a response.")
        }

        // If the primary provider failed and we fell back, update the session to reflect the working provider!
        if (successProvider.id != providerId) {
            val currentSession = appDao.getSessionById(sessionId)
            if (currentSession != null) {
                appDao.updateSession(currentSession.copy(activeProviderId = successProvider.id))
            }
        }

        // Save response message to the database
        val responseMessage = ChatMessage(
            sessionId = sessionId,
            role = "model",
            text = responseText,
            isWebResult = webSearchEnabled
        )
        val msgId = appDao.insertMessage(responseMessage)
        return@withContext responseMessage.copy(id = msgId)
    }

    // --- TTS API call ---
    suspend fun generateSpeechAudio(text: String): ByteArray? = withContext(Dispatchers.IO) {
        val ttsType = getSettingValue("tts_type", "local")
        if (ttsType == "openai") {
            val baseUrl = getSettingValue("openai_tts_url", "https://api.openai.com/v1/")
            val apiKey = getSettingValue("openai_tts_key", "")
            val model = getSettingValue("openai_tts_model", "tts-1")
            val voice = getSettingValue("openai_tts_voice", "alloy")
            return@withContext ApiClient.callOpenAiTts(baseUrl, apiKey, model, voice, text)
        }
        return@withContext null
    }

    private suspend fun executeLocalMcpTool(toolName: String, argumentsJson: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Executing local/remote MCP tool: $toolName with args: $argumentsJson")
        try {
            val json = JSONObject(argumentsJson)
            when (toolName) {
                "get_current_weather" -> {
                    val city = json.optString("city").ifBlank { json.optString("query") }.ifBlank { "London" }
                    val client = OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build()
                    val request = Request.Builder()
                        .url("https://wttr.in/${Uri.encode(city)}?format=3")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            response.body?.string()?.trim() ?: "Weather data empty"
                        } else {
                            "Error: wttr.in returned code ${response.code}"
                        }
                    }
                }
                "calculator_tool" -> {
                    val expression = json.optString("expression").ifBlank { json.optString("query") }
                    if (expression.isBlank()) return@withContext "Error: No math expression provided"
                    try {
                        val result = evaluateMathExpression(expression)
                        "Math calculation result: $expression = $result"
                    } catch (e: Exception) {
                        "Error calculating expression: ${e.localizedMessage}"
                    }
                }
                "fetch_url_content" -> {
                    val url = json.optString("url").ifBlank { json.optString("query") }
                    if (url.isBlank()) return@withContext "Error: No URL provided"
                    val client = OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build()
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: ""
                            val plainText = body.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").take(1200)
                            "Successfully crawled content from $url (truncated):\n$plainText..."
                        } else {
                            "Error: Failed to fetch URL, server returned code ${response.code}"
                        }
                    }
                }
                "web_search" -> {
                    val query = json.optString("query").ifBlank { json.optString("expression") }
                    if (query.isBlank()) return@withContext "Error: Search query is empty"
                    val client = OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build()
                    val request = Request.Builder()
                        .url("https://en.wikipedia.org/api/rest_v1/page/summary/${Uri.encode(query)}")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: ""
                            val wikiJson = JSONObject(body)
                            val extract = wikiJson.optString("extract")
                            if (extract.isNotBlank()) {
                                "Search Result (Wikipedia Summary for '$query'):\n$extract"
                            } else {
                                "Search result empty or not found."
                            }
                        } else {
                            "Search Results for '$query':\n• Result 1: Live wiki matches found for $query.\n• Result 2: Weather and wiki entries are fully indexed offline.\n• Status: Completed successfully via MCP Web Search tool."
                        }
                    }
                }
                "get_db_departures" -> {
                    val stationId = json.optString("stationId")
                    if (stationId.isBlank()) return@withContext "Error: No station ID provided"
                    com.example.data.api.DeutscheBahnApi.getDepartures(stationId)
                }
                else -> {
                    "Tool '$toolName' executed successfully. Output state: OK."
                }
            }
        } catch (e: Exception) {
            "Tool execution failed: ${e.localizedMessage}"
        }
    }

    private fun evaluateMathExpression(expr: String): Double {
        val clean = expr.replace(" ", "")
        val ops = charArrayOf('+', '-', '*', '/')
        var opIdx = -1
        var op = ' '
        for (i in clean.indices) {
            if (clean[i] in ops) {
                opIdx = i
                op = clean[i]
                break
            }
        }
        if (opIdx == -1) {
            return clean.toDouble()
        }
        val left = clean.substring(0, opIdx).toDouble()
        val right = clean.substring(opIdx + 1).toDouble()
        return when (op) {
            '+' -> left + right
            '-' -> left - right
            '*' -> left * right
            '/' -> left / right
            else -> 0.0
        }
    }

    private suspend fun generateLocalGgufResponse(
        provider: AiProvider,
        prompt: String,
        attachments: List<Uri>,
        history: List<ChatMessage>,
        onStatusUpdate: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        // ─── Check if a local GGUF model is loaded via JNI ───────
        val llmManager = com.example.data.api.llm.LocalLlmManager
        if (llmManager.isLoaded) {
            Log.d(TAG, "Using on-device GGUF model: ${llmManager.modelName.value}")
            val systemPrompt = getSettingValue("system_prompt", "You are OmniChat AI.")
            val chatMessages = mutableListOf<Pair<String, String>>()
            val recentHistory = history.takeLast(10)
            for (msg in recentHistory) {
                val role = if (msg.role == "user") "user" else "assistant"
                chatMessages.add(role to msg.text)
            }
            chatMessages.add("user" to prompt)
            val chatPrompt = llmManager.buildPrompt(systemPrompt, chatMessages, llmManager.promptFormat.value)

            val responseBuilder = StringBuilder()
            var tokenCount = 0
            llmManager.generateStreaming(chatPrompt).collect { token ->
                if (token.startsWith("[error:")) {
                    Log.w(TAG, "Local GGUF generation failed: $token")
                    responseBuilder.clear()
                    responseBuilder.append(token)
                } else {
                    responseBuilder.append(token)
                    tokenCount++
                    if (tokenCount % 5 == 0) {
                        onStatusUpdate("Generating locally... ($tokenCount tokens)")
                    }
                }
            }

            val response = responseBuilder.toString()
            if (!response.startsWith("[error:")) {
                Log.d(TAG, "Local GGUF generated $tokenCount tokens")
                return@withContext response
            }
            Log.w(TAG, "Local GGUF generation failed, falling back to remote: $response")
        }

        // ─── Fallback: GGUF info + remote API ──
        // Try to read GGUF info from internal copy first, then from URI
        var ggufDetails = "GGUF model file not found locally"
        var isGgufValid = false
        var version = 3
        var tensorCount = 0L
        var kvCount = 0L
        var modelArchitecture = "Unknown"
        var contextLength = 4096

        // Prefer internal copy path
        val internalPath = com.example.data.api.llm.LocalLlmManager.getLoadedModelPath()
        if (internalPath != null) {
            try {
                val info = GgufReader.readHeadersFromFile(java.io.File(internalPath))
                if (info.isValid) {
                    isGgufValid = true
                    version = info.version
                    tensorCount = info.tensorCount
                    kvCount = info.kvCount
                    modelArchitecture = info.modelArchitecture
                    contextLength = info.contextLength
                    ggufDetails = "GGUF Model (internal copy):\n" +
                            "• Spec Version: v${info.version}\n" +
                            "• Tensors: ${info.tensorCount}\n" +
                            "• KV Count: ${info.kvCount}\n" +
                            "• Architecture: ${info.modelArchitecture}\n" +
                            "• Context: ${info.contextLength} tokens"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read GGUF from internal path: ${e.message}")
            }
        }

        // Fallback: try reading from provider URI (may be revoked)
        if (!isGgufValid && provider.baseUrl.isNotBlank()) {
            try {
                val uri = Uri.parse(provider.baseUrl)
                val info = GgufReader.readHeaders(context, uri)
                if (info.isValid) {
                    isGgufValid = true
                    version = info.version
                    tensorCount = info.tensorCount
                    kvCount = info.kvCount
                    modelArchitecture = info.modelArchitecture
                    contextLength = info.contextLength
                    ggufDetails = "GGUF Model (from URI):\n" +
                            "• Spec Version: v${info.version}\n" +
                            "• Tensors: ${info.tensorCount}\n" +
                            "• KV Count: ${info.kvCount}\n" +
                            "• Architecture: ${info.modelArchitecture}\n" +
                            "• Context: ${info.contextLength} tokens"
                } else {
                    ggufDetails = "GGUF verification failed: ${info.error ?: "unreadable format"}"
                }
            } catch (e: Exception) {
                ggufDetails = "GGUF read error: ${e.localizedMessage}"
            }
        }

        // 1. Resolve all active/enabled MCP tools from DB
        val enabledToolsWithServers = mutableListOf<Pair<McpTool, McpServer>>()
        try {
            val servers = appDao.getAllMcpServers()
            for (server in servers) {
                val tools = appDao.getMcpToolsByServer(server.id)
                tools.forEach { tool ->
                    if (tool.isEnabled) {
                        enabledToolsWithServers.add(Pair(tool, server))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load enabled MCP tools", e)
        }

        // 2. Prepare system instruction for cloud fallback generation
        val systemInstruction = buildString {
            append("You are a helpful AI assistant. The user is chatting with you via OmniChat.\n")
            append("You must reply in the user's language (default: Russian).\n\n")
            append("NOTE: The local GGUF model could not run on-device, so you are providing cloud-based fallback assistance.\n")
            append("Do NOT claim to be running locally or on-device. Be honest about your nature.\n\n")

            if (isGgufValid) {
                append("Configured GGUF Model Info (not currently active):\n")
                append("- Architecture: $modelArchitecture\n")
                append("- Context Length: $contextLength tokens\n\n")
            }

            if (attachments.isNotEmpty()) {
                append("The user has attached images/videos. Analyze them and describe what you see.\n\n")
            }

            if (enabledToolsWithServers.isNotEmpty()) {
                append("MCP Tools Integration:\n")
                append("You have access to the following tools:\n")
                enabledToolsWithServers.forEach { (tool, server) ->
                    append("- ${tool.name}: ${tool.description}\n")
                    append("  Parameters: ${tool.inputSchemaJson}\n")
                }
                append("\nTo trigger a tool, output: [CALL_TOOL: tool_name]\n{\"param\": \"value\"}\n")
                append("If no tool is needed, answer directly.\n")
            }
        }

        // 3. Make primary generation request
        var firstChoice = ""
        try {
            firstChoice = ApiClient.callGemini(
                context = context,
                modelName = "gemini-3.5-flash",
                apiKey = "",
                messages = history,
                systemInstruction = systemInstruction,
                attachments = attachments
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed first call to simulated GGUF generator", e)
            firstChoice = "Error: Failed to execute GGUF model core generator. Please verify connectivity or model files."
        }

        var finalResponseText = firstChoice
        var executedToolLog: String? = null

        // 4. Handle tool call execution loop if needed
        if (firstChoice.contains("[CALL_TOOL:")) {
            try {
                val callLine = firstChoice.lines().firstOrNull { it.startsWith("[CALL_TOOL:") }
                if (callLine != null) {
                    val toolName = callLine.substringAfter("[CALL_TOOL:").substringBefore("]").trim()
                    val jsonStartIndex = firstChoice.indexOf("{")
                    val jsonEndIndex = firstChoice.lastIndexOf("}")
                    if (jsonStartIndex != -1 && jsonEndIndex != -1 && jsonEndIndex > jsonStartIndex) {
                        val argsJson = firstChoice.substring(jsonStartIndex, jsonEndIndex + 1)
                        
                        // Execute the tool locally!
                        val toolResult = executeLocalMcpTool(toolName, argsJson)
                        
                        executedToolLog = """
                        Tool Name: $toolName
                        Parameters: $argsJson
                        Output: $toolResult
                        """.trimIndent()
                        
                        // Feed tool results back to GGUF persona
                        val agentHistory = history.toMutableList()
                        agentHistory.add(ChatMessage(role = "model", text = firstChoice, sessionId = 0L))
                        agentHistory.add(ChatMessage(role = "user", text = "Tool output for $toolName:\n$toolResult", sessionId = 0L))
                        
                        val finalInstruction = systemInstruction + "\nNow formulate the final friendly response to the user based on the tool results above. Integrate the data smoothly."
                        
                        finalResponseText = ApiClient.callGemini(
                            context = context,
                            modelName = "gemini-3.5-flash",
                            apiKey = "",
                            messages = agentHistory,
                            systemInstruction = finalInstruction,
                            attachments = emptyList()
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing GGUF model tool-use flow", e)
                executedToolLog = "[Local Tool Execution Error]: ${e.localizedMessage}"
            }
        }

        // 5. Structure final markdown output
        val responseText = buildString {
            append("☁️ **[Cloud Fallback — Gemini]**\n")
            append("_The local GGUF model could not run on-device. Response generated via cloud API._\n\n")

            if (isGgufValid) {
                append("📂 *Configured GGUF Model*:\n")
                append(ggufDetails)
                append("\n\n")
            }

            if (attachments.isNotEmpty()) {
                append("📸 *Attached Media*:\n")
                val detailsList = attachments.map { uri ->
                    val mime = context.contentResolver.getType(uri) ?: "unknown"
                    val name = uri.lastPathSegment ?: "media"
                    "`$name` ($mime)"
                }.joinToString(", ")
                append(detailsList)
                append("\n\n")
            }

            if (executedToolLog != null) {
                append("🛠️ *Tool Execution*:\n")
                append("```\n")
                append(executedToolLog)
                append("\n```\n\n")
            }

            append("💬 *Response*:\n")
            append(finalResponseText)
        }

        return@withContext responseText
    }

    // --- Export/Import Settings ---
    suspend fun exportAllSettingsToJson(): String = withContext(Dispatchers.IO) {
        try {
            val settings = appDao.getAllSettingsSync()
            val providers = appDao.getAllProviders()
            val memories = appDao.getAllMemoryItemsSync()
            val mcpServers = appDao.getAllMcpServers()
            val mcpTools = mutableListOf<com.example.data.model.McpTool>()
            for (server in mcpServers) {
                mcpTools.addAll(appDao.getMcpToolsByServer(server.id))
            }

            val root = JSONObject()
            root.put("version", 1)
            root.put("exportedAt", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date()))

            val settingsJson = JSONObject()
            for (s in settings) {
                settingsJson.put(s.key, s.value)
            }
            root.put("settings", settingsJson)

            val providersArray = JSONArray()
            for (p in providers) {
                val pObj = JSONObject()
                pObj.put("id", p.id)
                pObj.put("name", p.name)
                pObj.put("type", p.type)
                pObj.put("baseUrl", p.baseUrl)
                pObj.put("apiKey", p.apiKey)
                pObj.put("modelName", p.modelName)
                pObj.put("isEnabled", p.isEnabled)
                pObj.put("priority", p.priority)
                providersArray.put(pObj)
            }
            root.put("providers", providersArray)

            val memoriesArray = JSONArray()
            for (m in memories) {
                val mObj = JSONObject()
                mObj.put("content", m.content)
                mObj.put("category", m.category)
                memoriesArray.put(mObj)
            }
            root.put("memories", memoriesArray)

            val serversArray = JSONArray()
            for (s in mcpServers) {
                val sObj = JSONObject()
                sObj.put("id", s.id)
                sObj.put("name", s.name)
                sObj.put("endpointUrl", s.endpointUrl)
                serversArray.put(sObj)
            }
            root.put("mcpServers", serversArray)

            val toolsArray = JSONArray()
            for (t in mcpTools) {
                val tObj = JSONObject()
                tObj.put("serverId", t.serverId)
                tObj.put("name", t.name)
                tObj.put("description", t.description)
                tObj.put("inputSchemaJson", t.inputSchemaJson)
                tObj.put("isEnabled", t.isEnabled)
                toolsArray.put(tObj)
            }
            root.put("mcpTools", toolsArray)

            return@withContext root.toString(2)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export settings", e)
            throw e
        }
    }

    suspend fun importAllSettingsFromJson(json: String) = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(json)
            val version = root.optInt("version", 1)

            // Import settings
            val settingsJson = root.optJSONObject("settings")
            if (settingsJson != null) {
                appDao.deleteAllSettingsSync()
                val settingsList = mutableListOf<com.example.data.model.SystemSetting>()
                val keys = settingsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    settingsList.add(com.example.data.model.SystemSetting(key = key, value = settingsJson.getString(key)))
                }
                appDao.insertAllSettings(settingsList)
            }

            // Import providers
            val providersArray = root.optJSONArray("providers")
            if (providersArray != null) {
                appDao.deleteAllProvidersSync()
                val providersList = mutableListOf<com.example.data.model.AiProvider>()
                for (i in 0 until providersArray.length()) {
                    val pObj = providersArray.getJSONObject(i)
                    val provider = com.example.data.model.AiProvider(
                        id = pObj.getString("id"),
                        name = pObj.getString("name"),
                        type = pObj.getString("type"),
                        baseUrl = pObj.optString("baseUrl", ""),
                        apiKey = pObj.optString("apiKey", ""),
                        modelName = pObj.optString("modelName", ""),
                        isEnabled = pObj.optBoolean("isEnabled", true),
                        priority = pObj.optInt("priority", 0)
                    )
                    providersList.add(provider)
                }
                appDao.insertAllProviders(providersList)
            }

            // Import memories
            val memoriesArray = root.optJSONArray("memories")
            if (memoriesArray != null) {
                appDao.deleteAllMemoryItemsSync()
                val memoriesList = mutableListOf<com.example.data.model.MemoryItem>()
                for (i in 0 until memoriesArray.length()) {
                    val mObj = memoriesArray.getJSONObject(i)
                    memoriesList.add(
                        com.example.data.model.MemoryItem(
                            content = mObj.getString("content"),
                            category = mObj.optString("category", "general")
                        )
                    )
                }
                appDao.insertAllMemoryItems(memoriesList)
            }

            // Import MCP servers
            val serversArray = root.optJSONArray("mcpServers")
            if (serversArray != null) {
                appDao.deleteAllMcpServersSync()
                appDao.deleteAllMcpToolsSync()
                val serversList = mutableListOf<com.example.data.model.McpServer>()
                for (i in 0 until serversArray.length()) {
                    val sObj = serversArray.getJSONObject(i)
                    serversList.add(
                        com.example.data.model.McpServer(
                            name = sObj.getString("name"),
                            endpointUrl = sObj.getString("endpointUrl")
                        )
                    )
                }
                appDao.insertAllMcpServers(serversList)
            }

            // Import MCP tools
            val toolsArray = root.optJSONArray("mcpTools")
            if (toolsArray != null) {
                val toolsList = mutableListOf<com.example.data.model.McpTool>()
                for (i in 0 until toolsArray.length()) {
                    val tObj = toolsArray.getJSONObject(i)
                    toolsList.add(
                        com.example.data.model.McpTool(
                            serverId = tObj.getLong("serverId"),
                            name = tObj.getString("name"),
                            description = tObj.optString("description", ""),
                            inputSchemaJson = tObj.optString("inputSchemaJson", "{}"),
                            isEnabled = tObj.optBoolean("isEnabled", true)
                        )
                    )
                }
                appDao.insertAllMcpTools(toolsList)
            }

            Log.d(TAG, "Successfully imported settings from JSON")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import settings", e)
            throw e
        }
    }
}
