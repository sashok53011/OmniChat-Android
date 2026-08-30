package com.example.data.api.llm

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Lightweight HTTP server exposing an OpenAI-compatible /v1/chat/completions
 * endpoint backed by the on-device GGUF model via LocalLlmManager.
 *
 * Uses raw java.net.ServerSocket — works on Android without extra deps.
 */
object LocalLlmServer {
    private const val TAG = "LocalLlmServer"
    private const val DEFAULT_PORT = 8090

    private var serverSocket: ServerSocket? = null
    private var port = DEFAULT_PORT
    private val executor = Executors.newFixedThreadPool(4)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false

    val isRunning: Boolean get() = running
    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun start(port: Int = DEFAULT_PORT) {
        if (running) {
            Log.w(TAG, "Server already running on port $port")
            return
        }
        this.port = port
        try {
            serverSocket = ServerSocket(port)
            running = true
            Log.i(TAG, "Server started on port $port")

            Thread({
                while (running) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        executor.submit { handleClient(client) }
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "Accept error", e)
                    }
                }
            }, "LocalLlmServer-Accept").start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server: ${e.message}")
            running = false
            serverSocket = null
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        Log.i(TAG, "Server stopped")
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 300_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val output = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val path = parts[1]

            // Read headers
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }

            // Read body if present
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0 && method == "POST") {
                val chars = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(chars, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(chars, 0, read)
            } else ""

            // Route
            val responseBody = when {
                path == "/health" && method == "GET" -> handleHealth()
                path == "/v1/chat/completions" && method == "POST" -> handleChat(body)
                path == "/v1/models" && method == "GET" -> handleModels()
                else -> null
            }

            if (responseBody != null) {
                sendResponse(output, 200, responseBody)
            } else {
                sendResponse(output, 404, """{"error":"Not found"}""")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client handler error", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleHealth(): String {
        return """{"status":"ok","model":"${LocalLlmManager.modelName.value}"}"""
    }

    private fun handleModels(): String {
        val modelName = LocalLlmManager.modelName.value.ifEmpty { "gguf-local" }
        return JSONObject().apply {
            put("object", "list")
            put("data", JSONArray().put(JSONObject().apply {
                put("id", modelName)
                put("object", "model")
                put("owned_by", "local")
            }))
        }.toString()
    }

    private fun handleChat(body: String): String {
        val request = JSONObject(body)
        val messagesArray = request.optJSONArray("messages") ?: JSONArray()
        val model = request.optString("model", "gguf-local")
        val maxTokens = request.optInt("max_tokens", 1024)
        val temperature = request.optDouble("temperature", 0.7).toFloat()
        val topP = request.optDouble("top_p", 0.9).toFloat()

        val prompt = buildPrompt(messagesArray)
        LocalLlmManager.setParameters(maxTokens = maxTokens, temperature = temperature, topP = topP)

        if (!LocalLlmManager.isLoaded) {
            return JSONObject().apply {
                put("error", JSONObject().apply {
                    put("message", "No GGUF model loaded")
                    put("type", "invalid_request_error")
                })
            }.toString()
        }

        val latch = java.util.concurrent.CountDownLatch(1)
        var responseText = ""
        var genError: Exception? = null

        scope.launch {
            try {
                responseText = LocalLlmManager.generate(prompt)
            } catch (e: Exception) {
                genError = e
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(5, TimeUnit.MINUTES)) {
            return JSONObject().apply {
                put("error", JSONObject().apply {
                    put("message", "Generation timed out")
                    put("type", "timeout_error")
                })
            }.toString()
        }

        if (genError != null || responseText.startsWith("[error:")) {
            return JSONObject().apply {
                put("error", JSONObject().apply {
                    put("message", genError?.message ?: responseText)
                    put("type", "server_error")
                })
            }.toString()
        }

        return JSONObject().apply {
            put("id", "chatcmpl-local-${System.currentTimeMillis()}")
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", JSONObject().apply {
                    put("role", "assistant")
                    put("content", responseText)
                })
                put("finish_reason", "stop")
            }))
        }.toString()
    }

    private fun buildPrompt(messages: JSONArray): String {
        val format = LocalLlmManager.promptFormat.value
        val systemParts = mutableListOf<String>()
        val chatParts = mutableListOf<Pair<String, String>>()

        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val role = msg.optString("role", "user")
            val content = msg.optString("content", "")
            when (role) {
                "system" -> systemParts.add(content)
                "user" -> chatParts.add("user" to content)
                "assistant" -> chatParts.add("assistant" to content)
            }
        }

        return LocalLlmManager.buildPrompt(
            systemPrompt = systemParts.joinToString("\n"),
            messages = chatParts,
            format = format
        )
    }

    private fun sendResponse(output: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "HTTP/1.1 200 OK" else "HTTP/1.1 $code Error"
        val response = "$status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(response.toByteArray())
        output.write(bytes)
        output.flush()
    }
}
