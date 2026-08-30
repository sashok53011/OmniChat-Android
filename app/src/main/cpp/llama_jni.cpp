#include <jni.h>
#include <string>
#include <thread>
#include <mutex>
#include <vector>
#include <atomic>
#include <unordered_map>
#include <memory>
#include <android/log.h>

#include "llama.h"

#define TAG "LocalLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

struct LlmSession {
    llama_model *model  = nullptr;
    llama_context *ctx  = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx           = 4096;
    int n_threads       = 4;
    bool ready          = false;
};

static std::mutex g_mutex;
static std::atomic<int64_t> g_next_id{1};

static std::unordered_map<int64_t, std::shared_ptr<LlmSession>> g_sessions;

static std::string jstring_to_std(JNIEnv *env, jstring js) {
    if (!js) return "";
    const char *c = env->GetStringUTFChars(js, nullptr);
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_data_api_llm_LocalLlmNative_nativeLoadModel(
        JNIEnv *env, jobject /* this */,
        jstring modelPath, jint nThreads, jint nCtx) {

    std::string path = jstring_to_std(env, modelPath);
    LOGI("Loading model: %s (threads=%d, nCtx=%d)", path.c_str(), nThreads, nCtx);

    static bool backend_inited = false;
    if (!backend_inited) {
        llama_backend_init();
        backend_inited = true;
    }

    auto session = std::make_shared<LlmSession>();
    session->n_threads = nThreads;
    session->n_ctx = nCtx;

    struct llama_model_params mparams = llama_model_default_params();
    session->model = llama_model_load_from_file(path.c_str(), mparams);
    if (!session->model) {
        LOGE("Failed to load model: %s", path.c_str());
        return -1;
    }

    session->vocab = llama_model_get_vocab(session->model);

    struct llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx   = nCtx;
    cparams.n_batch = 512;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;
    session->ctx = llama_init_from_model(session->model, cparams);
    if (!session->ctx) {
        LOGE("Failed to create context");
        llama_model_free(session->model);
        session->model = nullptr;
        return -1;
    }

    session->ready = true;

    std::lock_guard<std::mutex> lock(g_mutex);
    int64_t id = g_next_id.fetch_add(1);
    g_sessions[id] = session;
    LOGI("Model loaded, handle=%lld", id);
    return static_cast<jlong>(id);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_data_api_llm_LocalLlmNative_nativeGenerate(
        JNIEnv *env, jobject /* this */,
        jlong handle, jstring prompt,
        jint maxTokens, jfloat temp, jfloat topP,
        jstring stopToken) {

    std::string sprompt = jstring_to_std(env, prompt);
    std::string sstop   = jstring_to_std(env, stopToken);

    std::lock_guard<std::mutex> lock(g_mutex);

    auto it = g_sessions.find(handle);
    if (it == g_sessions.end() || !it->second->ready) {
        return env->NewStringUTF("[error: invalid model handle]");
    }
    auto &s = it->second;

    const int n_prompt = -llama_tokenize(
        s->vocab,
        sprompt.c_str(), sprompt.size(),
        nullptr, 0,
        true,
        true
    );

    std::vector<llama_token> tokens(n_prompt);
    int n_tokens = llama_tokenize(
        s->vocab,
        sprompt.c_str(), sprompt.size(),
        tokens.data(), tokens.size(),
        true,
        true
    );

    if (n_tokens < 0) {
        LOGE("Tokenize failed: %d", n_tokens);
        return env->NewStringUTF("[error: tokenize failed]");
    }

    LOGI("Prompt tokens: %d", n_tokens);

    if (llama_decode(s->ctx, llama_batch_get_one(tokens.data(), n_tokens))) {
        LOGE("Failed to eval prompt");
        return env->NewStringUTF("[error: eval prompt failed]");
    }

    std::string response;
    int n_generated = 0;

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(0));

    for (int i = 0; i < maxTokens; i++) {
        llama_token new_token = llama_sampler_sample(sampler, s->ctx, -1);
        llama_sampler_accept(sampler, new_token);

        char buf[256];
        int n = llama_token_to_piece(s->vocab, new_token, buf, sizeof(buf), 0, true);
        if (n <= 0) continue;
        buf[n] = '\0';

        if (new_token == llama_vocab_eos(s->vocab)) {
            LOGI("EOS reached at token %d", i);
            break;
        }

        if (!sstop.empty()) {
            response += buf;
            if (response.find(sstop) != std::string::npos) {
                auto pos = response.find(sstop);
                response = response.substr(0, pos);
                break;
            }
        } else {
            response += buf;
        }

        n_generated++;

        if (llama_decode(s->ctx, llama_batch_get_one(&new_token, 1))) {
            LOGW("decode failed at token %d", i);
            break;
        }
    }

    llama_sampler_free(sampler);

    LOGI("Generated %d tokens, response length: %zu", n_generated, response.size());
    return env->NewStringUTF(response.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_data_api_llm_LocalLlmNative_nativeGenerateStreaming(
        JNIEnv *env, jobject /* this */,
        jlong handle, jstring prompt,
        jint maxTokens, jfloat temp, jfloat topP,
        jstring stopToken, jobject callback) {

    std::string sprompt = jstring_to_std(env, prompt);
    std::string sstop   = jstring_to_std(env, stopToken);

    std::lock_guard<std::mutex> lock(g_mutex);

    auto it = g_sessions.find(handle);
    if (it == g_sessions.end() || !it->second->ready) {
        return env->NewStringUTF("[error: invalid model handle]");
    }
    auto &s = it->second;

    const int n_prompt = -llama_tokenize(
        s->vocab,
        sprompt.c_str(), sprompt.size(),
        nullptr, 0,
        true,
        true
    );

    std::vector<llama_token> tokens(n_prompt);
    int n_tokens = llama_tokenize(
        s->vocab,
        sprompt.c_str(), sprompt.size(),
        tokens.data(), tokens.size(),
        true,
        true
    );

    if (n_tokens < 0) {
        LOGE("Tokenize failed: %d", n_tokens);
        return env->NewStringUTF("[error: tokenize failed]");
    }

    LOGI("Prompt tokens: %d", n_tokens);

    if (llama_decode(s->ctx, llama_batch_get_one(tokens.data(), n_tokens))) {
        LOGE("Failed to eval prompt");
        return env->NewStringUTF("[error: eval prompt failed]");
    }

    std::string response;
    int n_generated = 0;

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(0));

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "(Ljava/lang/String;)V");

    if (!onTokenMethod || !onCompleteMethod) {
        LOGE("Failed to find callback methods");
        llama_sampler_free(sampler);
        return env->NewStringUTF("[error: invalid callback]");
    }

    for (int i = 0; i < maxTokens; i++) {
        llama_token new_token = llama_sampler_sample(sampler, s->ctx, -1);
        llama_sampler_accept(sampler, new_token);

        char buf[256];
        int n = llama_token_to_piece(s->vocab, new_token, buf, sizeof(buf), 0, true);
        if (n <= 0) continue;
        buf[n] = '\0';

        if (new_token == llama_vocab_eos(s->vocab)) {
            LOGI("EOS reached at token %d", i);
            break;
        }

        std::string tokenStr(buf);

        if (!sstop.empty()) {
            response += buf;
            if (response.find(sstop) != std::string::npos) {
                auto pos = response.find(sstop);
                response = response.substr(0, pos);
                break;
            }
        } else {
            response += buf;
        }

        jstring jToken = env->NewStringUTF(tokenStr.c_str());
        env->CallVoidMethod(callback, onTokenMethod, jToken);
        env->DeleteLocalRef(jToken);

        n_generated++;

        if (llama_decode(s->ctx, llama_batch_get_one(&new_token, 1))) {
            LOGW("decode failed at token %d", i);
            break;
        }
    }

    llama_sampler_free(sampler);

    jstring jFullResponse = env->NewStringUTF(response.c_str());
    env->CallVoidMethod(callback, onCompleteMethod, jFullResponse);
    env->DeleteLocalRef(jFullResponse);

    LOGI("Streaming generated %d tokens, response length: %zu", n_generated, response.size());
    return env->NewStringUTF(response.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_data_api_llm_LocalLlmNative_nativeFreeModel(
        JNIEnv *env, jobject /* this */, jlong handle) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end()) return;

    auto &s = it->second;
    if (s->ctx) {
        llama_free(s->ctx);
        s->ctx = nullptr;
    }
    s->vocab = nullptr;
    if (s->model) {
        llama_model_free(s->model);
        s->model = nullptr;
    }
    s->ready = false;
    g_sessions.erase(it);
    LOGI("Model freed, handle=%lld", handle);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_data_api_llm_LocalLlmNative_nativeIsModelLoaded(
        JNIEnv *env, jobject /* this */, jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    return (it != g_sessions.end() && it->second->ready) ? JNI_TRUE : JNI_FALSE;
}
