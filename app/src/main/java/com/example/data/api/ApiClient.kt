package com.example.data.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val TAG = "ApiClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    // Convert Bitmap to Base64 JPEG string
    fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    // Convert Uri to Bitmap
    fun uriToBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode bitmap from uri $uri", e)
            null
        }
    }

    // Extract frames from local video
    fun extractVideoFrames(context: Context, videoUri: Uri, maxFrames: Int = 3): List<Bitmap> {
        val frames = mutableListOf<Bitmap>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            if (durationMs > 0) {
                val intervalMs = durationMs / (maxFrames + 1)
                for (i in 1..maxFrames) {
                    val timeUs = (intervalMs * i) * 1000L
                    val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bitmap != null) {
                        frames.add(bitmap)
                    }
                }
            } else {
                // If duration is unknown, get first frame
                val bitmap = retriever.getFrameAtTime(0L)
                if (bitmap != null) frames.add(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting video frames from $videoUri", e)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignore
            }
        }
        return frames
    }

    // Call Gemini REST API
    suspend fun callGemini(
        context: Context,
        modelName: String,
        apiKey: String,
        messages: List<ChatMessage>,
        systemInstruction: String?,
        attachments: List<Uri> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val resolvedKey = apiKey.ifBlank { BuildConfig.GEMINI_API_KEY }
        val finalModelName = modelName.ifBlank { "gemini-3.5-flash" }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$finalModelName:generateContent?key=$resolvedKey"

        try {
            val root = JSONObject()
            val contentsArray = JSONArray()

            // Map standard chat history to Gemini API contents
            messages.forEach { msg ->
                val contentObj = JSONObject()
                contentObj.put("role", if (msg.role == "user") "user" else "model")
                val partsArray = JSONArray()
                val textPart = JSONObject().put("text", msg.text)
                partsArray.put(textPart)
                contentObj.put("parts", partsArray)
                contentsArray.put(contentObj)
            }

            // Append any active attachment as a new part to the very last message (which is usually the user's current message)
            if (attachments.isNotEmpty() && contentsArray.length() > 0) {
                val lastMessageObj = contentsArray.getJSONObject(contentsArray.length() - 1)
                val partsArray = lastMessageObj.getJSONArray("parts")

                attachments.forEach { uri ->
                    val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                    if (mimeType.startsWith("image/")) {
                        uriToBitmap(context, uri)?.let { bitmap ->
                            val base64 = bitmapToBase64(bitmap)
                            val inlineData = JSONObject()
                                .put("mimeType", "image/jpeg")
                                .put("data", base64)
                            val imgPart = JSONObject().put("inlineData", inlineData)
                            partsArray.put(imgPart)
                        }
                    } else if (mimeType.startsWith("video/")) {
                        // Extract video frames
                        val frames = extractVideoFrames(context, uri, 3)
                        frames.forEach { bitmap ->
                            val base64 = bitmapToBase64(bitmap)
                            val inlineData = JSONObject()
                                .put("mimeType", "image/jpeg")
                                .put("data", base64)
                            val imgPart = JSONObject().put("inlineData", inlineData)
                            partsArray.put(imgPart)
                        }
                    } else if (mimeType.startsWith("audio/")) {
                        // Audio: read as base64 and send as inline data
                        try {
                            val inputStream = context.contentResolver.openInputStream(uri)
                            val audioBytes = inputStream?.use { it.readBytes() }
                            if (audioBytes != null && audioBytes.isNotEmpty()) {
                                val base64 = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
                                val inlineData = JSONObject()
                                    .put("mimeType", mimeType)
                                    .put("data", base64)
                                val audioPart = JSONObject().put("inlineData", inlineData)
                                partsArray.put(audioPart)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to read audio file $uri", e)
                        }
                    } else {
                        // Local text files: Read text and append to the text prompt
                        try {
                            val inputStream = context.contentResolver.openInputStream(uri)
                            val textContent = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                            if (textContent.isNotBlank()) {
                                val currentText = partsArray.getJSONObject(0).getString("text")
                                partsArray.getJSONObject(0).put(
                                    "text",
                                    "$currentText\n\n[Attached File Contents]:\n$textContent"
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to read attached file $uri", e)
                        }
                    }
                }
            }

            root.put("contents", contentsArray)

            // System instructions
            if (!systemInstruction.isNullOrBlank()) {
                val systemObj = JSONObject()
                val systemParts = JSONArray().put(JSONObject().put("text", systemInstruction))
                systemObj.put("parts", systemParts)
                root.put("systemInstruction", systemObj)
            }

            val requestBodyJson = root.toString()
            val requestBody = requestBodyJson.toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errCode = response.code
                    throw Exception("Gemini API Error $errCode: $responseBody")
                }

                val jsonResponse = JSONObject(responseBody)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCand = candidates.getJSONObject(0)
                    val content = firstCand.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        return@withContext parts.getJSONObject(0).optString("text", "No text response")
                    }
                }
                throw Exception("Unexpected empty response from Gemini")
            }
        } catch (e: Exception) {
            Log.e(TAG, "callGemini failed", e)
            throw e
        }
    }

    // Call OpenAI Compatible API
    suspend fun callOpenAi(
        context: Context,
        baseUrl: String,
        apiKey: String,
        modelName: String,
        messages: List<ChatMessage>,
        systemInstruction: String?,
        attachments: List<Uri> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val trimmed = baseUrl.trim()
        val cleanUrl = if (trimmed.endsWith("chat/completions")) {
            trimmed
        } else if (trimmed.endsWith("chat/completions/")) {
            trimmed.removeSuffix("/")
        } else {
            val base = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
            base + "chat/completions"
        }

        try {
            val root = JSONObject()
            root.put("model", modelName.ifBlank { "gpt-4o-mini" })

            val messagesArray = JSONArray()

            // System prompt
            if (!systemInstruction.isNullOrBlank()) {
                messagesArray.put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", systemInstruction)
                )
            }

            // Chat history
            messages.forEachIndexed { index, msg ->
                val msgObj = JSONObject()
                val roleName = if (msg.role == "model") "assistant" else msg.role
                msgObj.put("role", roleName)

                // If this is the last user message and we have image/video attachments, make it a content array
                if (msg.role == "user" && index == messages.lastIndex && attachments.isNotEmpty()) {
                    val contentArray = JSONArray()
                    contentArray.put(JSONObject().put("type", "text").put("text", msg.text))

                    attachments.forEach { uri ->
                        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                        if (mimeType.startsWith("image/")) {
                            uriToBitmap(context, uri)?.let { bitmap ->
                                val base64 = bitmapToBase64(bitmap)
                                val imgUrlObj = JSONObject().put("url", "data:image/jpeg;base64,$base64")
                                val item = JSONObject().put("type", "image_url").put("image_url", imgUrlObj)
                                contentArray.put(item)
                            }
                        } else if (mimeType.startsWith("video/")) {
                            // Video: extract frames and send as image urls
                            val frames = extractVideoFrames(context, uri, 3)
                            frames.forEach { bitmap ->
                                val base64 = bitmapToBase64(bitmap)
                                val imgUrlObj = JSONObject().put("url", "data:image/jpeg;base64,$base64")
                                val item = JSONObject().put("type", "image_url").put("image_url", imgUrlObj)
                                contentArray.put(item)
                            }
                        } else if (mimeType.startsWith("audio/")) {
                            // Audio: send as input_audio in OpenAI format
                            try {
                                val inputStream = context.contentResolver.openInputStream(uri)
                                val audioBytes = inputStream?.use { it.readBytes() }
                                if (audioBytes != null && audioBytes.isNotEmpty()) {
                                    val base64 = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
                                    val format = when {
                                        mimeType.contains("wav") -> "wav"
                                        mimeType.contains("mp3") -> "mp3"
                                        mimeType.contains("ogg") -> "ogg"
                                        mimeType.contains("flac") -> "flac"
                                        else -> "mp3"
                                    }
                                    val audioObj = JSONObject()
                                        .put("data", base64)
                                        .put("format", format)
                                    val item = JSONObject().put("type", "input_audio").put("input_audio", audioObj)
                                    contentArray.put(item)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed reading audio attachment in OpenAI payload", e)
                            }
                        } else {
                            // Local files
                            try {
                                val inputStream = context.contentResolver.openInputStream(uri)
                                val textContent = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                                if (textContent.isNotBlank()) {
                                    contentArray.put(
                                        JSONObject().put("type", "text").put(
                                            "text",
                                            "\n\n[Attached File]:\n$textContent"
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed reading text attachment in OpenAI payload", e)
                            }
                        }
                    }
                    msgObj.put("content", contentArray)
                } else {
                    msgObj.put("content", msg.text)
                }

                messagesArray.put(msgObj)
            }

            root.put("messages", messagesArray)

            val requestBodyJson = root.toString()
            val requestBody = requestBodyJson.toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url(cleanUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw Exception("OpenAI compatible API Error ${response.code}: $responseBody")
                }

                val jsonResponse = JSONObject(responseBody)
                val choices = jsonResponse.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val choice = choices.getJSONObject(0)
                    val message = choice.optJSONObject("message")
                    if (message != null) {
                        return@withContext message.optString("content", "No content")
                    }
                }
                throw Exception("Unexpected empty response from OpenAI-compatible API")
            }
        } catch (e: Exception) {
            Log.e(TAG, "callOpenAi failed on URL $cleanUrl", e)
            throw e
        }
    }

    // Real-time DuckDuckGo Web Search Scraper (Serverless, fast, client-side!)
    suspend fun queryWebSearch(query: String): String = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext ""
        val urlEncodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://html.duckduckgo.com/html/?q=$urlEncodedQuery"

        try {
            val request = Request.Builder()
                .url(searchUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext "Web Search Failed (Status Code: ${response.code})"
                }
                val html = response.body?.string() ?: ""
                if (html.isBlank()) return@withContext "No search results found"

                // A very lightweight, fast string-scraping parser to avoid dependency bloated libraries like jsoup.
                val searchResults = mutableListOf<String>()
                var index = 0
                while (true) {
                    val resultStart = html.indexOf("<div class=\"result__body\">", index)
                    if (resultStart == -1) break

                    val resultEnd = html.indexOf("</div>", resultStart)
                    if (resultEnd == -1) break

                    val bodyFragment = html.substring(resultStart, resultEnd + 200) // fetch a safety margin

                    // Extract Title and URL
                    val titleLinkStart = bodyFragment.indexOf("<a class=\"result__url\"")
                    val titleTextStart = bodyFragment.indexOf(">", titleLinkStart)
                    val titleTextEnd = bodyFragment.indexOf("</a>", titleTextStart)

                    // Extract snippet
                    val snippetStart = bodyFragment.indexOf("<a class=\"result__snippet\"")
                    val snippetTextStart = bodyFragment.indexOf(">", snippetStart)
                    val snippetTextEnd = bodyFragment.indexOf("</a>", snippetTextStart)

                    if (titleTextStart != -1 && titleTextEnd != -1 && snippetTextStart != -1 && snippetTextEnd != -1) {
                        val title = bodyFragment.substring(titleTextStart + 1, titleTextEnd)
                            .replace(Regex("<[^>]*>"), "")
                            .trim()
                        val snippet = bodyFragment.substring(snippetTextStart + 1, snippetTextEnd)
                            .replace(Regex("<[^>]*>"), "")
                            .trim()

                        searchResults.add("**$title**\n$snippet")
                    }

                    if (searchResults.size >= 4) break
                    index = resultStart + 100
                }

                if (searchResults.isEmpty()) {
                    return@withContext "No search results parsed from web."
                }

                return@withContext searchResults.joinToString("\n\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryWebSearch failed", e)
            return@withContext "Error querying search results: ${e.message}"
        }
    }

    // Call custom OpenAI-compatible TTS API to generate Audio ByteArray
    suspend fun callOpenAiTts(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        voiceName: String,
        text: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        val ttsBaseUrl = baseUrl.ifBlank { "https://api.openai.com/v1/" }
        val cleanUrl = ttsBaseUrl.trim().let {
            if (it.endsWith("/")) it else "$it/"
        } + "audio/speech"

        try {
            val root = JSONObject()
            root.put("model", modelName.ifBlank { "tts-1" })
            root.put("voice", voiceName.ifBlank { "alloy" })
            root.put("input", text)

            val requestBody = root.toString().toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url(cleanUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    Log.e(TAG, "TTS call failed: $errBody")
                    return@withContext null
                }
                return@withContext response.body?.bytes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "callOpenAiTts failed", e)
            null
        }
    }

    // Call Remote MCP Server
    suspend fun callRemoteMcp(
        context: Context,
        baseUrl: String,
        apiKey: String,
        modelName: String,
        messages: List<ChatMessage>,
        systemInstruction: String?,
        attachments: List<Uri> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val trimmed = baseUrl.trim()
        val hasExplicitPath = trimmed.endsWith("/chat") || trimmed.endsWith("/completions") || trimmed.endsWith("/generate")
        val cleanUrl = if (hasExplicitPath) {
            trimmed
        } else {
            val base = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
            if (base.contains("/v1")) {
                base + "chat/completions"
            } else {
                base + "api/chat"
            }
        }

        try {
            val root = JSONObject()
            root.put("model", modelName.ifBlank { "mcp-model" })
            
            val messagesArray = JSONArray()
            if (!systemInstruction.isNullOrBlank()) {
                messagesArray.put(JSONObject().put("role", "system").put("content", systemInstruction))
            }
            messages.forEach { msg ->
                val roleName = if (msg.role == "model") "assistant" else msg.role
                messagesArray.put(JSONObject().put("role", roleName).put("content", msg.text))
            }
            root.put("messages", messagesArray)
            root.put("temperature", 0.7)
            root.put("max_tokens", 2048)

            val requestBody = root.toString().toRequestBody(JSON_MEDIA_TYPE)
            val requestBuilder = Request.Builder()
                .url(cleanUrl)
                .post(requestBody)

            if (apiKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }
            requestBuilder.addHeader("X-MCP-Client", "OmniChat-Android")

            val request = requestBuilder.build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    if (cleanUrl.endsWith("/api/chat")) {
                        val fallbackUrl = if (trimmed.endsWith("/")) trimmed + "chat/completions" else "$trimmed/chat/completions"
                        val fallbackRequest = requestBuilder.url(fallbackUrl).build()
                        client.newCall(fallbackRequest).execute().use { fbResponse ->
                            if (fbResponse.isSuccessful) {
                                val fbBody = fbResponse.body?.string() ?: ""
                                return@withContext parseOpenAiResponseDirectly(fbBody)
                            }
                        }
                    }
                    throw Exception("MCP Server error ${response.code}: $responseBody")
                }
                
                return@withContext parseMcpResponseText(responseBody)
            }
        } catch (e: Exception) {
            Log.e(TAG, "callRemoteMcp failed on URL $cleanUrl", e)
            throw e
        }
    }

    private fun parseOpenAiResponseDirectly(body: String): String {
        val jsonResponse = JSONObject(body)
        val choices = jsonResponse.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val choice = choices.getJSONObject(0)
            val message = choice.optJSONObject("message")
            if (message != null) {
                return message.optString("content", "No content")
            }
        }
        return body
    }

    private fun parseMcpResponseText(body: String): String {
        return try {
            val json = JSONObject(body)
            when {
                json.has("choices") -> parseOpenAiResponseDirectly(body)
                json.has("response") -> json.getString("response")
                json.has("text") -> json.getString("text")
                json.has("content") -> json.getString("content")
                else -> body
            }
        } catch (e: Exception) {
            body
        }
    }
}
