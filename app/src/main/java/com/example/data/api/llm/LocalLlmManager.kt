package com.example.data.api.llm

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object LocalLlmManager {
    private const val TAG = "LocalLlmManager"
    private const val PREFS_NAME = "gguf_model_prefs"
    private const val KEY_SAVED_URI = "saved_model_uri"
    private const val KEY_SAVED_CHECKSUM = "saved_model_checksum"
    private const val KEY_SAVED_FILENAME = "saved_model_filename"
    private const val KEY_PROMPT_FORMAT = "prompt_format"
    private const val MIN_STORAGE_MB = 500
    private const val GGUF_MAGIC = 0x46554747

    enum class PromptFormat(
        val systemPrefix: String,
        val systemSuffix: String,
        val userPrefix: String,
        val userSuffix: String,
        val assistantPrefix: String,
        val assistantSuffix: String,
        val displayName: String
    ) {
        CHATML(
            systemPrefix = "<|system|>\n",
            systemSuffix = "\n</s>\n",
            userPrefix = "<|user|>\n",
            userSuffix = "\n</s>\n",
            assistantPrefix = "<|assistant|>\n",
            assistantSuffix = "\n</s>\n",
            displayName = "ChatML (Qwen, most modern)"
        ),
        LLAMA2(
            systemPrefix = "<<SYS>>\n",
            systemSuffix = "\n<</SYS>>\n\n[INST] ",
            userPrefix = "",
            userSuffix = " [/INST] ",
            assistantPrefix = "",
            assistantSuffix = " </s><s>[INST] ",
            displayName = "LLaMA-2 / LLaMA-3"
        ),
        ALPACA(
            systemPrefix = "### System:\n",
            systemSuffix = "\n\n",
            userPrefix = "### Human:\n",
            userSuffix = "\n\n",
            assistantPrefix = "### Assistant:\n",
            assistantSuffix = "\n\n",
            displayName = "Alpaca / Vicuna"
        ),
        ZEPHYR(
            systemPrefix = "<|system|>\n",
            systemSuffix = "\n",
            userPrefix = "<|user|>\n",
            userSuffix = "\n",
            assistantPrefix = "<|assistant|>\n",
            assistantSuffix = "\n",
            displayName = "Zephyr / Mistral"
        );
    }

    enum class ModelState { UNLOADED, LOADING, LOADED, ERROR }

    private val _state = MutableStateFlow(ModelState.UNLOADED)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private val _modelName = MutableStateFlow("")
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _progressMessage = MutableStateFlow("")
    val progressMessage: StateFlow<String> = _progressMessage.asStateFlow()

    private val _promptFormat = MutableStateFlow(PromptFormat.CHATML)
    val promptFormat: StateFlow<PromptFormat> = _promptFormat.asStateFlow()

    private val mutex = Mutex()
    private var handle: Long = -1L
    private var loadedPath: String = ""
    private var nThreads: Int = 4
    private var nCtx: Int = 4096
    private var maxTokens: Int = 1024
    private var temperature: Float = 0.7f
    private var topP: Float = 0.9f

    val isLoaded: Boolean get() = handle > 0 && _state.value == ModelState.LOADED
    val isAvailable: Boolean get() = try { LocalLlmNative; true } catch (_: Throwable) { false }

    fun setPromptFormat(format: PromptFormat) {
        _promptFormat.value = format
    }

    fun buildPrompt(systemPrompt: String, messages: List<Pair<String, String>>, format: PromptFormat): String {
        val sb = StringBuilder()
        if (systemPrompt.isNotBlank()) {
            sb.append(format.systemPrefix)
            sb.append(systemPrompt)
            sb.append(format.systemSuffix)
        }
        for ((role, content) in messages) {
            when (role) {
                "user" -> {
                    sb.append(format.userPrefix)
                    sb.append(content)
                    sb.append(format.userSuffix)
                }
                "assistant" -> {
                    sb.append(format.assistantPrefix)
                    sb.append(content)
                    sb.append(format.assistantSuffix)
                }
            }
        }
        // Always end with assistant prefix so the model knows to generate
        if (!sb.toString().endsWith(format.assistantPrefix)) {
            sb.append(format.assistantPrefix)
        }
        return sb.toString()
    }

    fun loadPromptFormat(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_PROMPT_FORMAT, null) ?: return
        val format = try {
            PromptFormat.valueOf(name)
        } catch (_: IllegalArgumentException) {
            return
        }
        _promptFormat.value = format
    }

    fun savePromptFormat(context: Context, format: PromptFormat) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROMPT_FORMAT, format.name)
            .apply()
    }

    suspend fun loadModel(
        context: Context,
        uri: Uri,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
        contextSize: Int = 4096
    ) = mutex.withLock {
        if (_state.value == ModelState.LOADING) {
            Log.w(TAG, "Model already loading")
            return@withLock
        }

        _state.value = ModelState.LOADING
        _statusMessage.value = "Preparing model..."

        try {
            if (handle > 0) {
                withContext(Dispatchers.IO) {
                    LocalLlmNative.nativeFreeModel(handle)
                }
                handle = -1
            }

            val fileName = getFileName(context, uri) ?: "model.gguf"
            val internalDir = File(context.filesDir, "models")
            internalDir.mkdirs()
            val destFile = File(internalDir, fileName)

            _statusMessage.value = "Validating GGUF file..."
            val sourceSize = getFileSize(context, uri)
            if (sourceSize <= 0) {
                _state.value = ModelState.ERROR
                _statusMessage.value = "Error: source file is empty or unreadable"
                return@withLock
            }

            val availableBytes = getAvailableStorage(internalDir)
            if (availableBytes < sourceSize + MIN_STORAGE_MB * 1024L * 1024L) {
                val neededMB = (sourceSize / (1024L * 1024L)) + MIN_STORAGE_MB
                val availMB = availableBytes / (1024L * 1024L)
                _state.value = ModelState.ERROR
                _statusMessage.value = "Error: not enough storage. Need ~${neededMB}MB, have ${availMB}MB"
                Log.e(TAG, "Insufficient storage: need $neededMB MB, available $availMB MB")
                return@withLock
            }

            if (!validateGgufMagic(context, uri)) {
                _state.value = ModelState.ERROR
                _statusMessage.value = "Error: not a valid GGUF file (bad magic bytes)"
                return@withLock
            }

            val sourceChecksum = computeChecksum(context, uri)
            val savedChecksum = getSavedChecksum(context)

            val needsCopy = !destFile.exists() ||
                    destFile.length() == 0L ||
                    destFile.length() != sourceSize ||
                    sourceChecksum != savedChecksum

            if (needsCopy) {
                _statusMessage.value = "Copying model to internal storage..."
                _progress.value = 0f

                if (destFile.exists()) {
                    destFile.delete()
                }

                try {
                    copyWithProgress(context, uri, destFile, sourceSize)
                } catch (e: Exception) {
                    if (destFile.exists()) destFile.delete()
                    throw Exception("Failed to copy model: ${e.message}", e)
                }

                val destChecksum = computeChecksum(destFile)
                if (destChecksum != sourceChecksum) {
                    destFile.delete()
                    _state.value = ModelState.ERROR
                    _statusMessage.value = "Error: file integrity check failed after copy"
                    Log.e(TAG, "Checksum mismatch: source=$sourceChecksum, dest=$destChecksum")
                    return@withLock
                }

                saveModelState(context, uri.toString(), sourceChecksum, fileName)
                Log.i(TAG, "Model copied and verified: $fileName (${sourceSize / (1024*1024)}MB)")
            } else {
                Log.i(TAG, "Using cached model: $fileName (checksum match)")
            }

            _progress.value = 0.8f
            _progressMessage.value = "Loading into memory..."
            _statusMessage.value = "Loading into memory (this may take a moment)..."
            nThreads = threads
            nCtx = contextSize
            loadedPath = destFile.absolutePath

            handle = withContext(Dispatchers.IO) {
                LocalLlmNative.nativeLoadModel(loadedPath, nThreads, nCtx)
            }

            if (handle > 0) {
                _progress.value = 1.0f
                _state.value = ModelState.LOADED
                _modelName.value = fileName.removeSuffix(".gguf")
                _statusMessage.value = "Ready ($fileName, ${nThreads} threads, ctx=$nCtx)"
                Log.i(TAG, "Model loaded: $fileName, handle=$handle")
            } else {
                _state.value = ModelState.ERROR
                _statusMessage.value = "Failed to load model (native error — is llama.cpp built?)"
                Log.e(TAG, "nativeLoadModel returned -1 for $loadedPath")
            }
        } catch (e: Exception) {
            _state.value = ModelState.ERROR
            _statusMessage.value = "Error: ${e.message}"
            Log.e(TAG, "loadModel failed", e)
        } finally {
            _progress.value = 0f
            _progressMessage.value = ""
        }
    }

    suspend fun loadModelFromPath(
        path: String,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
        contextSize: Int = 4096
    ) = mutex.withLock {
        if (_state.value == ModelState.LOADING) return@withLock

        _state.value = ModelState.LOADING
        _statusMessage.value = "Loading model..."

        try {
            if (handle > 0) {
                withContext(Dispatchers.IO) {
                    LocalLlmNative.nativeFreeModel(handle)
                }
                handle = -1
            }

            val file = File(path)
            if (!file.exists()) {
                _state.value = ModelState.ERROR
                _statusMessage.value = "File not found: $path"
                return@withLock
            }

            nThreads = threads
            nCtx = contextSize
            loadedPath = path
            handle = withContext(Dispatchers.IO) {
                LocalLlmNative.nativeLoadModel(path, nThreads, nCtx)
            }

            if (handle > 0) {
                _state.value = ModelState.LOADED
                _modelName.value = file.name.removeSuffix(".gguf")
                _statusMessage.value = "Ready (${file.name}, ${nThreads} threads)"
            } else {
                _state.value = ModelState.ERROR
                _statusMessage.value = "Failed to load model"
            }
        } catch (e: Exception) {
            _state.value = ModelState.ERROR
            _statusMessage.value = "Error: ${e.message}"
        }
    }

    suspend fun tryRestoreModel(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUri = prefs.getString(KEY_SAVED_URI, null) ?: return false
        val savedChecksum = prefs.getString(KEY_SAVED_CHECKSUM, null) ?: return false
        val savedFileName = prefs.getString(KEY_SAVED_FILENAME, null) ?: return false

        val destFile = File(File(context.filesDir, "models"), savedFileName)
        if (!destFile.exists() || destFile.length() == 0L) {
            clearSavedState(context)
            return false
        }

        val currentChecksum = computeChecksum(destFile)
        if (currentChecksum != savedChecksum) {
            Log.w(TAG, "Cached model checksum mismatch, clearing cache")
            destFile.delete()
            clearSavedState(context)
            return false
        }

        Log.i(TAG, "Restoring model from cache: $savedFileName")
        loadModelFromPath(destFile.absolutePath)

        loadPromptFormat(context)

        return isLoaded
    }

    suspend fun generate(prompt: String): String {
        if (handle <= 0 || _state.value != ModelState.LOADED) {
            return "[error: no model loaded]"
        }
        return try {
            withTimeout(300_000L) {
                val latch = CountDownLatch(1)
                var result = ""
                var error: Exception? = null

                val thread = Thread {
                    try {
                        result = LocalLlmNative.nativeGenerate(
                            handle = handle,
                            prompt = prompt,
                            maxTokens = maxTokens,
                            temperature = temperature,
                            topP = topP,
                            stopToken = "</s>"
                        )
                    } catch (e: Exception) {
                        error = e
                    } finally {
                        latch.countDown()
                    }
                }
                thread.name = "llama-generate"
                thread.priority = Thread.MIN_PRIORITY
                thread.start()

                withContext(Dispatchers.IO) { latch.await(5, TimeUnit.MINUTES) }

                if (error != null) "[error: ${error!!.message}]" else result
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e(TAG, "generate timed out after 5 minutes")
            "[error: generation timed out]"
        }
    }

    fun generateStreaming(prompt: String): Flow<String> = callbackFlow {
        if (handle <= 0 || _state.value != ModelState.LOADED) {
            trySend("[error: no model loaded]")
            close()
            return@callbackFlow
        }

        val callback = object : LocalLlmNative.TokenCallback {
            override fun onToken(token: String) {
                trySend(token)
            }

            override fun onComplete(fullResponse: String) {
                close()
            }
        }

        val thread = Thread {
            try {
                LocalLlmNative.nativeGenerateStreaming(
                    handle = handle,
                    prompt = prompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    topP = topP,
                    stopToken = "</s>",
                    callback = callback
                )
            } catch (e: Exception) {
                Log.e(TAG, "generateStreaming failed", e)
                trySend("[error: ${e.message}]")
                close()
            }
        }
        thread.name = "llama-streaming"
        thread.priority = Thread.MIN_PRIORITY
        thread.start()

        awaitClose { thread.interrupt() }
    }

    fun unloadModel() {
        if (handle > 0) {
            LocalLlmNative.nativeFreeModel(handle)
            handle = -1
        }
        loadedPath = ""
        _state.value = ModelState.UNLOADED
        _modelName.value = ""
        _statusMessage.value = ""
        Log.i(TAG, "Model unloaded")
    }

    fun setParameters(
        maxTokens: Int = this.maxTokens,
        temperature: Float = this.temperature,
        topP: Float = this.topP
    ) {
        this.maxTokens = maxTokens.coerceIn(1, 8192)
        this.temperature = temperature.coerceIn(0f, 2f)
        this.topP = topP.coerceIn(0f, 1f)
    }

    fun getLoadedModelPath(): String? = if (isLoaded) loadedPath else null

    fun getLoadedModelSize(): Long {
        if (!isLoaded) return -1
        val file = File(loadedPath)
        return if (file.exists()) file.length() else -1
    }

    fun isGgufFile(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 &&
                magic[0] == 'G'.code.toByte() &&
                magic[1] == 'G'.code.toByte() &&
                magic[2] == 'U'.code.toByte() &&
                magic[3] == 'F'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun clearCache(context: Context) {
        val modelsDir = File(context.filesDir, "models")
        if (modelsDir.exists()) {
            modelsDir.listFiles()?.forEach { it.delete() }
        }
        clearSavedState(context)
        Log.i(TAG, "Model cache cleared")
    }

    private fun copyWithProgress(context: Context, uri: Uri, destFile: File, totalSize: Long) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output ->
                val buffer = ByteArray(1024 * 1024)
                var totalRead = 0L
                var read: Int

                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    totalRead += read
                    if (totalSize > 0) {
                        _progress.value = (totalRead.toFloat() / totalSize).coerceIn(0f, 0.7f)
                        val percent = (totalRead * 100 / totalSize).toInt()
                        _progressMessage.value = "Copying... $percent% (${totalRead / (1024*1024)}/${totalSize / (1024*1024)} MB)"
                    }
                }
                output.flush()
            }
        } ?: throw Exception("Cannot open model URI for reading")
    }

    private fun validateGgufMagic(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                read == 4 &&
                header[0] == 0x47.toByte() &&
                header[1] == 0x47.toByte() &&
                header[2] == 0x55.toByte() &&
                header[3] == 0x46.toByte()
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "GGUF validation failed", e)
            false
        }
    }

    private fun getFileSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getLong(sizeIndex)
                } else -1
            } ?: -1
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get file size", e)
            -1
        }
    }

    private fun getAvailableStorage(dir: File): Long {
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check storage", e)
            Long.MAX_VALUE
        }
    }

    private fun computeChecksum(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun computeChecksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(idx)
                }
            }
        }
        if (name == null) {
            name = uri.lastPathSegment
        }
        return name
    }

    private fun saveModelState(context: Context, uri: String, checksum: String, fileName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SAVED_URI, uri)
            .putString(KEY_SAVED_CHECKSUM, checksum)
            .putString(KEY_SAVED_FILENAME, fileName)
            .apply()
    }

    private fun getSavedChecksum(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SAVED_CHECKSUM, null)
    }

    private fun clearSavedState(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
