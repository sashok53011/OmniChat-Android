#include <jni.h>
#include <android/log.h>
#define TAG "LocalLlm"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_data_api_llm_LocalLlmNative_nativeLoadModel(
        JNIEnv *env, jobject, jstring, jint, jint) {
    LOGW("llama.cpp not built — local GGUF inference unavailable");
    return -1;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_data_api_llm_LocalLlmNative_nativeGenerate(
        JNIEnv *env, jobject, jlong, jstring, jint, jfloat, jfloat, jstring) {
    return env->NewStringUTF("[error: llama.cpp not built — see build instructions]");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_data_api_llm_LocalLlmNative_nativeGenerateStreaming(
        JNIEnv *env, jobject, jlong, jstring, jint, jfloat, jfloat, jstring, jobject) {
    return env->NewStringUTF("[error: llama.cpp not built — streaming unavailable]");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_data_api_llm_LocalLlmNative_nativeFreeModel(
        JNIEnv *, jobject, jlong) {}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_data_api_llm_LocalLlmNative_nativeIsModelLoaded(
        JNIEnv *, jobject, jlong) {
    return JNI_FALSE;
}
