package com.example.data.api.llm

/**
 * Native llama.cpp JNI bridge.
 *
 * The native LLM (llama.cpp) has been stripped from the build. This object is kept
 * so that all callers compile unchanged. Every native call reports "not available",
 * so the Local GGUF provider gracefully shows an error instead of crashing.
 */
object LocalLlmNative {
    init { /* Native library intentionally not loaded (llama.cpp was removed from build). */ }

    interface TokenCallback {
        fun onToken(token: String)
        fun onComplete(fullResponse: String)
    }

    fun nativeLoadModel(modelPath: String, nThreads: Int, nCtx: Int): Long = -1L

    fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stopToken: String?
    ): String = "[error: on-device LLM is not built in this version]"

    fun nativeGenerateStreaming(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stopToken: String?,
        callback: TokenCallback
    ): String {
        callback.onComplete("[error: on-device LLM is not built in this version]")
        return "[error: on-device LLM is not built in this version]"
    }

    fun nativeFreeModel(handle: Long) { /* no-op */ }

    fun nativeIsModelLoaded(handle: Long): Boolean = false
}
