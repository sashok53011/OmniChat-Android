package com.example.data.api

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GgufInfo(
    val isValid: Boolean,
    val version: Int = 0,
    val tensorCount: Long = 0,
    val kvCount: Long = 0,
    val error: String? = null,
    val modelArchitecture: String = "Unknown",
    val contextLength: Int = 2048,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Reads GGUF file headers including KV metadata.
 *
 * GGUF binary layout:
 *   [4B magic] [4B version] [8B tensor_count] [8B kv_count] [KV pairs...]
 *
 * Each KV pair:
 *   [4B key_len] [key_bytes] [4B value_type] [value_bytes...]
 *
 * Value types: 0=U8, 1=I8, 2=U16, 3=I16, 4=U32, 5=I32, 6=F32,
 *              7=BOOL, 8=STRING, 9=ARRAY, 10=U64, 11=I64, 12=F64
 */
object GgufReader {
    private const val TAG = "GgufReader"
    private const val HEADER_SIZE = 24 // magic(4) + version(4) + tensorCount(8) + kvCount(8)
    private const val MAX_READ_BYTES = 4 * 1024 * 1024 // 4MB cap for metadata parsing

    fun readHeaders(context: Context, uri: Uri): GgufInfo {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                readHeadersFromStream(inputStream)
            } ?: GgufInfo(isValid = false, error = "Cannot open InputStream")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading GGUF header", e)
            GgufInfo(isValid = false, error = e.localizedMessage ?: "File read error")
        }
    }

    fun readHeadersFromFile(file: java.io.File): GgufInfo {
        return try {
            file.inputStream().use { inputStream ->
                readHeadersFromStream(inputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading GGUF header from file", e)
            GgufInfo(isValid = false, error = e.localizedMessage ?: "File read error")
        }
    }

    private fun readHeadersFromStream(inputStream: InputStream): GgufInfo {
        val headerBuf = ByteArray(HEADER_SIZE)
        val bytesRead = inputStream.read(headerBuf)
        if (bytesRead < HEADER_SIZE) {
            return GgufInfo(isValid = false, error = "File too small (need $HEADER_SIZE bytes, got $bytesRead)")
        }

        val buffer = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)

        // Magic: "GGUF" (0x47 0x47 0x55 0x46)
        val magic = ByteArray(4)
        buffer.get(magic)
        if (String(magic) != "GGUF") {
            return GgufInfo(isValid = false, error = "Invalid magic bytes (not a valid GGUF file)")
        }

        val version = buffer.int
        val tensorCount = buffer.long
        val kvCount = buffer.long

        Log.d(TAG, "GGUF header: version=$version, tensors=$tensorCount, kvs=$kvCount")

        // Parse KV metadata
        val metadata = mutableMapOf<String, Any>()
        var arch = "Unknown"
        var ctxLen = 2048

        if (kvCount > 0 && kvCount < 10000) {
            try {
                // Read up to MAX_READ_BYTES for KV parsing
                val maxBytes = MAX_READ_BYTES - HEADER_SIZE
                val kvBuf = ByteArray(maxBytes)
                var totalRead = 0
                var read: Int

                while (totalRead < maxBytes) {
                    read = inputStream.read(kvBuf, totalRead, maxBytes - totalRead)
                    if (read == -1) break
                    totalRead += read
                }

                val kvBuffer = ByteBuffer.wrap(kvBuf, 0, totalRead).order(ByteOrder.LITTLE_ENDIAN)

                for (i in 0 until kvCount) {
                    if (kvBuffer.remaining() < 4) break
                    val keyLen = kvBuffer.int
                    if (keyLen <= 0 || keyLen > 1024) break
                    if (kvBuffer.remaining() < keyLen + 4) break

                    val keyBytes = ByteArray(keyLen)
                    kvBuffer.get(keyBytes)
                    val key = String(keyBytes, Charsets.UTF_8)

                    val valueType = kvBuffer.int
                    val value = readGgufValue(kvBuffer, valueType) ?: continue
                    metadata[key] = value

                    // Extract architecture
                    if (key == "general.architecture" && value is String) {
                        arch = value
                    }

                    // Extract context length: {arch}.context_length
                    if (key.endsWith(".context_length") && (value is Int || value is Long)) {
                        ctxLen = when (value) {
                            is Int -> value
                            is Long -> value.toInt()
                            else -> 2048
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Partial KV parse: ${e.message}")
            }
        }

        // Fallback: derive architecture from metadata keys if not found
        if (arch == "Unknown") {
            arch = deriveArchitecture(metadata)
        }

        Log.d(TAG, "Parsed: arch=$arch, ctx=$ctxLen, kvCount=${metadata.size}")

        return GgufInfo(
            isValid = true,
            version = version,
            tensorCount = tensorCount,
            kvCount = kvCount,
            modelArchitecture = arch,
            contextLength = ctxLen,
            metadata = metadata
        )
    }

    private fun readGgufValue(buffer: ByteBuffer, type: Int): Any? {
        return when (type) {
            0 -> buffer.get().toInt() and 0xFF            // U8
            1 -> buffer.get().toInt()                      // I8
            2 -> buffer.short.toInt() and 0xFFFF          // U16
            3 -> buffer.short.toInt()                      // I16
            4 -> buffer.int and 0xFFFFFFFF.toInt()         // U32
            5 -> buffer.int                                // I32
            6 -> buffer.float                              // F32
            7 -> buffer.get() != 0.toByte()                // BOOL
            8 -> { // STRING
                val len = buffer.int
                if (len <= 0 || len > 65536) return null
                val bytes = ByteArray(len)
                buffer.get(bytes)
                String(bytes, Charsets.UTF_8)
            }
            9 -> null // ARRAY — skip (complex, rarely needed for arch/ctx)
            10 -> buffer.long                              // U64 (read as signed Long)
            11 -> buffer.long                              // I64
            12 -> buffer.double                            // F64
            else -> null
        }
    }

    private fun deriveArchitecture(metadata: Map<String, Any>): String {
        // Check for known architecture keys in metadata
        val archKey = metadata.keys.find { it == "general.architecture" }
        if (archKey != null) {
            val archValue = metadata[archKey]
            if (archValue is String) return archValue
        }

        // Check model type keys (llama, qwen, mistral, etc.)
        val knownArchs = listOf("llama", "qwen", "mistral", "phi", "gemma", "deepseek", "command-r")
        for (arch in knownArchs) {
            if (metadata.keys.any { it.startsWith("$arch.") }) {
                return arch.replaceFirstChar { it.uppercase() }
            }
        }

        return "Unknown"
    }
}
