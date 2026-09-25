/*
 * chat_engine.c - OmniChat-P4 chat orchestration.
 *
 * IMPORTANT NOTE ON THREADING: this engine uses a FreeRTOS worker task for the
 * blocking network calls. UI (LVGL) must run on its own task and must only touch
 * LVGL objects there. We guard state with a mutex; UI reads via snapshot.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "esp_log.h"
#include "esp_system.h"
#include "esp_random.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "cJSON.h"
#include "chat_engine.h"
#include "chat_storage.h"
#include "llm_client.h"
#include "web_search.h"
#include "tts.h"
#include "app_config.h"

static const char *TAG = "chat_engine";

static app_state_t s_state;
static chat_session_t *s_sessions = NULL;
static int s_session_count = 0;
static chat_session_t s_active;
static bool s_active_loaded = false;

static chat_engine_listener_t s_listener = NULL;
static SemaphoreHandle_t s_mutex = NULL;
static volatile bool s_busy = false;

/* Generate a simple unique id from time+counter. */
static void gen_id(char *out, int sz)
{
    uint32_t t = (uint32_t)esp_log_timestamp();
    uint32_t r = esp_random();
    snprintf(out, sz, "id%.8lx%.4lx", (unsigned long)t, (unsigned long)(r & 0xffff));
}

/* Deep-copy sessions: dst gets its own heap messages + text strings. */
static void session_deep_copy(chat_session_t *dst, const chat_session_t *src)
{
    chat_session_init(dst);
    strncpy(dst->id, src->id, sizeof(dst->id) - 1);
    strncpy(dst->title, src->title, sizeof(dst->title) - 1);
    dst->created_at = src->created_at;
    strncpy(dst->system_prompt, src->system_prompt, sizeof(dst->system_prompt) - 1);
    for (int i = 0; i < src->message_count && i < APP_MAX_MESSAGES; i++) {
        dst->messages[i] = src->messages[i];
        if (src->messages[i].text) {
            dst->messages[i].text = strdup(src->messages[i].text);
        }
    }
    dst->message_count = src->message_count;
}

static void notify(void)
{
    if (s_listener) s_listener();
}

/* Keep the in-memory session list in sync with the active session, which is
 * where new/changed messages accumulate at runtime. Without this, switching
 * away and back to a session would restore a stale snapshot from s_sessions
 * and the latest messages would seem to disappear. */
static void sync_active_to_list(void)
{
    if (!s_active_loaded || s_active.id[0] == '\0') {
        return;
    }
    for (int i = 0; i < s_session_count; i++) {
        if (strcmp(s_sessions[i].id, s_active.id) == 0) {
            chat_session_free(&s_sessions[i]);
            memset(&s_sessions[i], 0, sizeof(s_sessions[i]));
            session_deep_copy(&s_sessions[i], &s_active);
            return;
        }
    }
}

const app_state_t *chat_engine_state(void) { return &s_state; }

int chat_engine_init(void)
{
    memset(&s_state, 0, sizeof(s_state));

    /* Defaults */
    strncpy(s_state.wifi_ssid, APP_WIFI_SSID, sizeof(s_state.wifi_ssid) - 1);
    strncpy(s_state.wifi_password, APP_WIFI_PASSWORD, sizeof(s_state.wifi_password) - 1);
    strncpy(s_state.system_prompt, APP_DEFAULT_SYSTEM_PROMPT, sizeof(s_state.system_prompt) - 1);
    strncpy(s_state.persona, APP_DEFAULT_PERSONA, sizeof(s_state.persona) - 1);
    strncpy(s_state.language, APP_DEFAULT_LANGUAGE, sizeof(s_state.language) - 1);
    s_state.tts_enabled = APP_TTS_ENABLED_DEFAULT;
    s_state.tts_speak_ai = APP_TTS_SPEAK_AI_DEFAULT;
    s_state.tts_volume = APP_AUDIO_VOLUME_DEFAULT;
    strncpy(s_state.tts_url, APP_TTS_URL, sizeof(s_state.tts_url) - 1);
    strncpy(s_state.tts_model, APP_TTS_MODEL, sizeof(s_state.tts_model) - 1);
    strncpy(s_state.tts_voice, APP_TTS_VOICE, sizeof(s_state.tts_voice) - 1);

    /* Seed the two hard-coded providers (opencode.ai + Ollama Cloud). */
    s_state.provider_count = 0;
    {
        ai_provider_t *p = &s_state.providers[s_state.provider_count++];
        strncpy(p->id, PROVIDER_OPENCODE_ID, sizeof(p->id) - 1);
        strncpy(p->name, PROVIDER_OPENCODE_NAME, sizeof(p->name) - 1);
        strncpy(p->base_url, PROVIDER_OPENCODE_BASE_URL, sizeof(p->base_url) - 1);
        strncpy(p->api_key, PROVIDER_OPENCODE_API_KEY, sizeof(p->api_key) - 1);
        strncpy(p->model, PROVIDER_OPENCODE_MODEL, sizeof(p->model) - 1);
        p->priority = PROVIDER_OPENCODE_PRIORITY;
        p->enabled = true;
    }
    {
        ai_provider_t *p = &s_state.providers[s_state.provider_count++];
        strncpy(p->id, PROVIDER_LM_STUDIO_ID, sizeof(p->id) - 1);
        strncpy(p->name, PROVIDER_LM_STUDIO_NAME, sizeof(p->name) - 1);
        strncpy(p->base_url, PROVIDER_LM_STUDIO_BASE_URL, sizeof(p->base_url) - 1);
        strncpy(p->api_key, PROVIDER_LM_STUDIO_API_KEY, sizeof(p->api_key) - 1);
        strncpy(p->model, PROVIDER_LM_STUDIO_MODEL, sizeof(p->model) - 1);
        p->priority = PROVIDER_LM_STUDIO_PRIORITY;
        p->enabled = true;
    }
    {
        ai_provider_t *p = &s_state.providers[s_state.provider_count++];
        strncpy(p->id, PROVIDER_LAN_ID, sizeof(p->id) - 1);
        strncpy(p->name, PROVIDER_LAN_NAME, sizeof(p->name) - 1);
        strncpy(p->base_url, PROVIDER_LAN_BASE_URL, sizeof(p->base_url) - 1);
        strncpy(p->api_key, PROVIDER_LAN_API_KEY, sizeof(p->api_key) - 1);
        strncpy(p->model, PROVIDER_LAN_MODEL, sizeof(p->model) - 1);
        p->priority = PROVIDER_LAN_PRIORITY;
        p->enabled = true;
    }
    strncpy(s_state.active_provider_id, APP_DEFAULT_PROVIDER_ID, sizeof(s_state.active_provider_id) - 1);

    /* Create mutex BEFORE any session operations that use it. */
    s_mutex = xSemaphoreCreateMutex();

    /* Load persisted state, overlaying the seeded values where present. */
    chat_storage_init();
    chat_storage_load_state(&s_state);

    /* Re-add any seeded providers that are missing from the saved state
     * (so newly added providers appear on existing devices too). */
    const char *seed_ids[] = { PROVIDER_OPENCODE_ID, PROVIDER_LM_STUDIO_ID, PROVIDER_LAN_ID };
    int seed_pri[3] = { PROVIDER_OPENCODE_PRIORITY, PROVIDER_LM_STUDIO_PRIORITY, PROVIDER_LAN_PRIORITY };
    for (int i = 0; i < 3; i++) {
        ai_provider_t *found = chat_engine_find_provider(seed_ids[i]);
        if (!found && s_state.provider_count < APP_MAX_PROVIDERS) {
            /* Rebuild from constants into a new slot. */
            ai_provider_t *slot = &s_state.providers[s_state.provider_count++];
            memset(slot, 0, sizeof(*slot));
            strncpy(slot->id, seed_ids[i], sizeof(slot->id) - 1);
            if (i == 0) {
                strncpy(slot->name, PROVIDER_OPENCODE_NAME, sizeof(slot->name) - 1);
                strncpy(slot->base_url, PROVIDER_OPENCODE_BASE_URL, sizeof(slot->base_url) - 1);
                strncpy(slot->api_key, PROVIDER_OPENCODE_API_KEY, sizeof(slot->api_key) - 1);
                strncpy(slot->model, PROVIDER_OPENCODE_MODEL, sizeof(slot->model) - 1);
            } else if (i == 1) {
                strncpy(slot->name, PROVIDER_LM_STUDIO_NAME, sizeof(slot->name) - 1);
                strncpy(slot->base_url, PROVIDER_LM_STUDIO_BASE_URL, sizeof(slot->base_url) - 1);
                strncpy(slot->api_key, PROVIDER_LM_STUDIO_API_KEY, sizeof(slot->api_key) - 1);
                strncpy(slot->model, PROVIDER_LM_STUDIO_MODEL, sizeof(slot->model) - 1);
            } else {
                strncpy(slot->name, PROVIDER_LAN_NAME, sizeof(slot->name) - 1);
                strncpy(slot->base_url, PROVIDER_LAN_BASE_URL, sizeof(slot->base_url) - 1);
                strncpy(slot->api_key, PROVIDER_LAN_API_KEY, sizeof(slot->api_key) - 1);
                strncpy(slot->model, PROVIDER_LAN_MODEL, sizeof(slot->model) - 1);
            }
            slot->priority = seed_pri[i];
            slot->enabled = true;
        }
    }

    /* Remove the deprecated Ollama Cloud provider if still present in the
     * saved state (persisted on the SD card from older firmware). */
    if (chat_engine_find_provider("ollama_cloud") != NULL) {
        chat_engine_delete_provider("ollama_cloud");
        ESP_LOGI(TAG, "removed deprecated provider 'ollama_cloud'");
    }

    /* Load sessions. */
    s_sessions = NULL;
    s_session_count = chat_storage_load_sessions(&s_sessions, APP_MAX_SESSIONS);

    /* Restore last session. */
    memset(&s_active, 0, sizeof(s_active));
    if (s_state.last_session_id[0]) {
        chat_engine_select_session(s_state.last_session_id);
    }
    if (!s_active_loaded) {
        chat_engine_new_session();
    }

    tts_init();
    tts_set_config(s_state.tts_url, s_state.tts_model, s_state.tts_voice);

    ESP_LOGI(TAG, "engine initialized: %d sessions, %d providers", s_session_count, s_state.provider_count);
    return 0;
}

void chat_engine_set_listener(chat_engine_listener_t cb) { s_listener = cb; }

const chat_session_t *chat_engine_sessions(int *count)
{
    if (count) *count = s_session_count;
    return s_sessions;
}

chat_session_t *chat_engine_active_session(void)
{
    return s_active_loaded ? &s_active : NULL;
}

ai_provider_t *chat_engine_find_provider(const char *id)
{
    for (int i = 0; i < s_state.provider_count; i++) {
        if (strcmp(s_state.providers[i].id, id) == 0) {
            return &s_state.providers[i];
        }
    }
    return NULL;
}

int chat_engine_set_active_provider(const char *id)
{
    if (!chat_engine_find_provider(id)) return -1;
    strncpy(s_state.active_provider_id, id, sizeof(s_state.active_provider_id) - 1);
    chat_engine_save_state_now();
    notify();
    return 0;
}

int chat_engine_select_session(const char *id)
{
    for (int i = 0; i < s_session_count; i++) {
        if (strcmp(s_sessions[i].id, id) == 0) {
            xSemaphoreTake(s_mutex, portMAX_DELAY);
            chat_session_free(&s_active);
            memset(&s_active, 0, sizeof(s_active));
            session_deep_copy(&s_active, &s_sessions[i]);
            s_active_loaded = true;
            snprintf(s_state.last_session_id, sizeof(s_state.last_session_id), "%s", id);
            xSemaphoreGive(s_mutex);
            chat_engine_save_state_now();
            notify();
            return 0;
        }
    }
    return -1;
}

int chat_engine_new_session(void)
{
    /* Free any previous active session's messages. */
    xSemaphoreTake(s_mutex, portMAX_DELAY);
    chat_session_free(&s_active);
    memset(&s_active, 0, sizeof(s_active));
    chat_session_init(&s_active);

    gen_id(s_active.id, sizeof(s_active.id));
    time_t now = time(NULL);
    s_active.created_at = (int64_t)now;
    const char *title = "New Chat";
    if (strcmp(s_state.language, "ru") == 0) title = "Новый диалог";
    else if (strcmp(s_state.language, "de") == 0) title = "Neuer Chat";
    snprintf(s_active.title, sizeof(s_active.title), "%s", title);
    if (s_state.system_prompt[0]) {
        snprintf(s_active.system_prompt, sizeof(s_active.system_prompt), "%s", s_state.system_prompt);
    }

    s_active_loaded = true;
    snprintf(s_state.last_session_id, sizeof(s_state.last_session_id), "%s", s_active.id);
    xSemaphoreGive(s_mutex);
    chat_engine_save_state_now();

    /* Add an independent deep copy to the in-memory session list. */
    if (s_session_count < APP_MAX_SESSIONS) {
        if (!s_sessions) {
            s_sessions = calloc(APP_MAX_SESSIONS, sizeof(chat_session_t));
        }
        if (s_sessions) {
            session_deep_copy(&s_sessions[s_session_count], &s_active);
            s_session_count++;
        }
    }
    notify();
    return 0;
}

int chat_engine_delete_session(const char *id)
{
    if (strcmp(s_active.id, id) == 0) {
        /* switching away from the deleted session */
        xSemaphoreTake(s_mutex, portMAX_DELAY);
        memset(&s_active, 0, sizeof(s_active));
        s_active_loaded = false;
        xSemaphoreGive(s_mutex);
        chat_engine_new_session();
    }
    chat_storage_delete_session(id);
    int out = -1;
    for (int i = 0; i < s_session_count; i++) {
        if (strcmp(s_sessions[i].id, id) == 0) {
            chat_session_free(&s_sessions[i]);
            out = i;
            break;
        }
    }
    if (out >= 0) {
        for (int i = out; i < s_session_count - 1; i++) {
            /* Move the struct (with its own heap messages) down; the last slot
             * becomes unused and is zeroed to avoid a dangling pointer. */
            s_sessions[i] = s_sessions[i + 1];
        }
        memset(&s_sessions[s_session_count - 1], 0, sizeof(chat_session_t));
        s_session_count--;
    }
    notify();
    return 0;
}

int chat_engine_push_message(msg_role_t role, const char *text)
{
    if (!s_active_loaded) return -1;
    if (s_active.message_count >= APP_MAX_MESSAGES) return -1;

    xSemaphoreTake(s_mutex, portMAX_DELAY);
    chat_message_t *m = &s_active.messages[s_active.message_count];
    memset(m, 0, sizeof(*m));
    gen_id(m->id, sizeof(m->id));
    m->role = role;
    m->text = strdup(text ? text : "");
    s_active.message_count++;
    xSemaphoreGive(s_mutex);

    chat_storage_save_session(&s_active);
    notify();
    sync_active_to_list();
    return 0;
}

/* Build the final system instruction: prompt + persona + memories + language. */
static void build_system_instruction(char *buf, size_t sz)
{
    int off = 0;
    off += snprintf(buf + off, sz - off, "%s", s_state.system_prompt);
    if (s_state.persona[0] && strcmp(s_state.persona, "default") != 0) {
        off += snprintf(buf + off, sz - off, "\n\nRole: %s", s_state.persona);
    }
    if (s_state.memory_count > 0) {
        off += snprintf(buf + off, sz - off, "\n\n[User Memories & Long-term Preferences]:");
        for (int i = 0; i < s_state.memory_count; i++) {
            off += snprintf(buf + off, sz - off, "\n- %s", s_state.memories[i].content);
        }
    }
    if (strcmp(s_state.language, "ru") == 0) {
        off += snprintf(buf + off, sz - off, "\n\nPlease respond in Russian.");
    } else if (strcmp(s_state.language, "de") == 0) {
        off += snprintf(buf + off, sz - off, "\n\nPlease respond in German.");
    } else {
        off += snprintf(buf + off, sz - off, "\n\nPlease respond in English.");
    }
}

static volatile int s_web_search_enabled = 0;

/* The worker task: run the full request pipeline. */
static void engine_worker(void *arg)
{
    (void)arg;
    char *user_text = NULL;

    xSemaphoreTake(s_mutex, portMAX_DELAY);
    chat_session_t *s = &s_active;
    if (s->message_count == 0 || !s_active_loaded) {
        xSemaphoreGive(s_mutex);
        s_busy = false;
        notify();
        vTaskDelete(NULL);
        return;
    }
    chat_message_t *last = &s->messages[s->message_count - 1];
    if (last->role != MSG_ROLE_USER) {
        xSemaphoreGive(s_mutex);
        s_busy = false;
        notify();
        vTaskDelete(NULL);
        return;
    }
    user_text = strdup(last->text ? last->text : "");
    xSemaphoreGive(s_mutex);
    if (!user_text) {
        s_busy = false;
        notify();
        vTaskDelete(NULL);
        return;
    }

    int web_enabled = s_web_search_enabled;
    char *enriched = malloc(8192);
    if (!enriched) {
        free(user_text);
        s_busy = false; notify(); vTaskDelete(NULL); return;
    }
    snprintf(enriched, 8192, "%s", user_text);

    if (web_enabled) {
        char *search = web_search_query(user_text);
        if (search) {
            snprintf(enriched, 8192,
                     "[Real-time Web Search Results for \"%s\"]:\n%s\n\n[User Prompt]:\n%s\n\n"
                     "Please write an advanced, thorough response using the web search results above.",
                     user_text, search, user_text);
            free(search);
        }
    }

    /* Update the last user message to enriched text. */
    free(last->text);
    last->text = strdup(enriched);

    /* Build system instruction (heap to keep the worker stack small). */
    char *sys = malloc(APP_MAX_SYSTEM_PROMPT + 1024);
    if (!sys) {
        free(enriched); free(user_text);
        s_busy = false; notify(); vTaskDelete(NULL); return;
    }
    build_system_instruction(sys, APP_MAX_SYSTEM_PROMPT + 1024);

    /* Fallback chain over enabled providers, primary first. */
    ai_provider_t *primary = chat_engine_find_provider(s_state.active_provider_id);
    if (!primary || !primary->enabled) primary = NULL;

    llm_result_t res;
    char *assistant_text = NULL;
    (void)primary;

    /* Order: primary, then all other enabled. */
    int attempts = 0;
    ai_provider_t *chain[APP_MAX_PROVIDERS];
    int chain_n = 0;
    if (primary) chain[chain_n++] = primary;
    for (int i = 0; i < s_state.provider_count; i++) {
        if (primary && &s_state.providers[i] == primary) continue;
        if (s_state.providers[i].enabled) chain[chain_n++] = &s_state.providers[i];
    }

    for (int i = 0; i < chain_n; i++) {
        attempts++;
        ESP_LOGI(TAG, "attempt %d provider %s", attempts, chain[i]->id);
        int rc = llm_openai_compatible(chain[i], s->messages, s->message_count, sys, &res);
        if (rc == 0) {
            char *parsed = llm_parse_choices(res.body);
            llm_result_free(&res);
            if (parsed && parsed[0]) {
                assistant_text = parsed;
                break;
            }
            if (parsed) free(parsed);
        } else if (res.body) {
            llm_result_free(&res);
        }
    }

    if (assistant_text) {
        xSemaphoreTake(s_mutex, portMAX_DELAY);
        chat_message_t *am = &s->messages[s->message_count];
        memset(am, 0, sizeof(*am));
        gen_id(am->id, sizeof(am->id));
        am->role = MSG_ROLE_ASSISTANT;
        am->text = assistant_text;
        s->message_count++;
        xSemaphoreGive(s_mutex);

        chat_storage_save_session(s);

        /* TTS autoplay of AI response. */
        if (s_state.tts_enabled && s_state.tts_speak_ai) {
            tts_speak(assistant_text, s_state.tts_volume);
        }
    } else {
        xSemaphoreTake(s_mutex, portMAX_DELAY);
        chat_message_t *am = &s->messages[s->message_count];
        memset(am, 0, sizeof(*am));
        gen_id(am->id, sizeof(am->id));
        am->role = MSG_ROLE_ASSISTANT;
        char err[256];
        snprintf(err, sizeof(err), "Error: all AI providers failed (%d attempts).", attempts);
        am->text = strdup(err);
        am->is_error = 1;
        s->message_count++;
        xSemaphoreGive(s_mutex);
        chat_storage_save_session(s);
    }

    free(enriched);
    free(sys);
    free(user_text);
    sync_active_to_list();
    s_busy = false;
    notify();
    vTaskDelete(NULL);
}

int chat_engine_send_user(const char *text, int web_search_enabled)
{
    if (s_busy) return -1;
    if (!s_active_loaded) return -1;
    int rc = chat_engine_push_message(MSG_ROLE_USER, text);
    if (rc != 0) return -1;

    s_busy = true;
    s_web_search_enabled = web_search_enabled;

    /* Auto-title the session from the first user message. */
    if (s_active.message_count == 1) {
        char t[128];
        snprintf(t, sizeof(t), "%.40s", text);
        snprintf(s_active.title, sizeof(s_active.title), "%s", t);
    }

    xTaskCreate(engine_worker, "engine", 20 * 1024, NULL, 5, NULL);
    notify();
    return 0;
}

bool chat_engine_is_busy(void) { return s_busy; }

int chat_engine_retry_last(void)
{
    if (s_busy) return -1;
    if (!s_active_loaded) return -1;
    if (s_active.message_count < 2) return -1;

    xSemaphoreTake(s_mutex, portMAX_DELAY);

    chat_message_t *last = &s_active.messages[s_active.message_count - 1];
    if (last->role != MSG_ROLE_ASSISTANT) { xSemaphoreGive(s_mutex); return -1; }

    chat_message_t *user_msg = &s_active.messages[s_active.message_count - 2];
    if (user_msg->role != MSG_ROLE_USER) { xSemaphoreGive(s_mutex); return -1; }

    char *text = strdup(user_msg->text);

    free(last->text);
    memset(last, 0, sizeof(*last));
    free(user_msg->text);
    memset(user_msg, 0, sizeof(*user_msg));
    s_active.message_count -= 2;

    xSemaphoreGive(s_mutex);

    chat_storage_save_session(&s_active);
    sync_active_to_list();
    notify();
    return chat_engine_send_user(text, 0);
}

int chat_engine_clear_active(void)
{
    if (s_busy) return -1;
    if (!s_active_loaded) return -1;

    xSemaphoreTake(s_mutex, portMAX_DELAY);
    for (int i = 0; i < s_active.message_count; i++) {
        free(s_active.messages[i].text);
        memset(&s_active.messages[i], 0, sizeof(s_active.messages[i]));
    }
    s_active.message_count = 0;
    xSemaphoreGive(s_mutex);

    chat_storage_save_session(&s_active);
    sync_active_to_list();
    notify();
    return 0;
}

bool chat_engine_save_state_now(void) { return chat_storage_save_state(&s_state) == 0; }

void chat_engine_update_system_prompt(const char *text)
{
    strncpy(s_state.system_prompt, text, sizeof(s_state.system_prompt) - 1);
    if (s_active_loaded) {
        strncpy(s_active.system_prompt, text, sizeof(s_active.system_prompt) - 1);
    }
    chat_engine_save_state_now();
    sync_active_to_list();
    notify();
}

void chat_engine_add_memory(const char *category, const char *content)
{
    if (s_state.memory_count >= APP_MAX_MEMORIES) return;
    memory_item_t *m = &s_state.memories[s_state.memory_count];
    memset(m, 0, sizeof(*m));
    gen_id(m->id, sizeof(m->id));
    strncpy(m->category, category ? category : "general", sizeof(m->category) - 1);
    strncpy(m->content, content ? content : "", sizeof(m->content) - 1);
    s_state.memory_count++;
    chat_engine_save_state_now();
    notify();
}

void chat_engine_remove_memory(const char *id)
{
    int out = -1;
    for (int i = 0; i < s_state.memory_count; i++) {
        if (strcmp(s_state.memories[i].id, id) == 0) { out = i; break; }
    }
    if (out >= 0) {
        for (int i = out; i < s_state.memory_count - 1; i++) {
            s_state.memories[i] = s_state.memories[i + 1];
        }
        s_state.memory_count--;
        chat_engine_save_state_now();
        notify();
    }
}

bool chat_engine_tts_enabled(void) { return s_state.tts_enabled; }
void chat_engine_set_tts_enabled(bool en) { s_state.tts_enabled = en; chat_engine_save_state_now(); notify(); }
void chat_engine_set_tts_volume(int vol) { s_state.tts_volume = vol; tts_set_volume(vol); chat_engine_save_state_now(); }

void chat_engine_set_tts_config(const char *url, const char *model, const char *voice, int speak_ai)
{
    if (url && url[0]) strncpy(s_state.tts_url, url, sizeof(s_state.tts_url) - 1);
    if (model && model[0]) strncpy(s_state.tts_model, model, sizeof(s_state.tts_model) - 1);
    if (voice && voice[0]) strncpy(s_state.tts_voice, voice, sizeof(s_state.tts_voice) - 1);
    s_state.tts_speak_ai = speak_ai;
    tts_set_config(s_state.tts_url, s_state.tts_model, s_state.tts_voice);
    chat_engine_save_state_now();
    notify();
}

int chat_engine_upsert_provider(const ai_provider_t *p)
{
    if (!p || !p->id[0]) return -1;
    /* Update existing by id, else append a new slot. */
    ai_provider_t *existing = chat_engine_find_provider(p->id);
    if (existing) {
        *existing = *p;
    } else if (s_state.provider_count < APP_MAX_PROVIDERS) {
        ai_provider_t *slot = &s_state.providers[s_state.provider_count++];
        *slot = *p;
    } else {
        return -1;
    }
    /* If the active provider was just added/renamed, keep the id valid. */
    if (s_state.active_provider_id[0] == '\0') {
        strncpy(s_state.active_provider_id, p->id, sizeof(s_state.active_provider_id) - 1);
    }
    chat_engine_save_state_now();
    notify();
    return 0;
}

int chat_engine_delete_provider(const char *id)
{
    int out = -1;
    for (int i = 0; i < s_state.provider_count; i++) {
        if (strcmp(s_state.providers[i].id, id) == 0) { out = i; break; }
    }
    if (out < 0) return -1;
    for (int i = out; i < s_state.provider_count - 1; i++) {
        s_state.providers[i] = s_state.providers[i + 1];
    }
    s_state.provider_count--;
    if (strcmp(s_state.active_provider_id, id) == 0) {
        s_state.active_provider_id[0] = '\0';
    }
    chat_engine_save_state_now();
    notify();
    return 0;
}

/* ---- Background connection tests ---- */
typedef struct {
    char id[24];
    chat_engine_test_cb_t cb;
} test_provider_arg_t;

static void test_provider_task(void *arg)
{
    test_provider_arg_t *a = (test_provider_arg_t *)arg;
    ESP_LOGI(TAG, "test_provider_task: testing '%s'", a->id);
    ai_provider_t *p = chat_engine_find_provider(a->id);
    char result[512] = "Provider not found";

    if (p) {
        chat_message_t msgs[1];
        memset(msgs, 0, sizeof(msgs));
        gen_id(msgs[0].id, sizeof(msgs[0].id));
        msgs[0].role = MSG_ROLE_USER;
        msgs[0].text = strdup("Ping");

        llm_result_t res;
        memset(&res, 0, sizeof(res));
        int rc = llm_openai_compatible(p, msgs, 1, "You are a test. Reply with a single word: OK", &res);
        if (rc == 0) {
            char *text = llm_parse_choices(res.body);
            snprintf(result, sizeof(result), "OK: %s", text ? text : "(empty)");
            if (text) free(text);
            llm_result_free(&res);
        } else {
            snprintf(result, sizeof(result), "FAIL: %s", res.error[0] ? res.error : "unknown error");
        }
        free(msgs[0].text);
    }
    ESP_LOGI(TAG, "test_provider_task result: %s", result);
    if (a->cb) a->cb(result);
    free(a);
    vTaskDelete(NULL);
}

int chat_engine_test_provider(const char *id, chat_engine_test_cb_t cb)
{
    test_provider_arg_t *a = calloc(1, sizeof(test_provider_arg_t));
    if (!a) return -1;
    strncpy(a->id, id, sizeof(a->id) - 1);
    a->cb = cb;
    xTaskCreate(test_provider_task, "test_provider", 20 * 1024, a, 5, NULL);
    return 0;
}

static void test_tts_task(void *arg)
{
    test_provider_arg_t *a = (test_provider_arg_t *)arg;
    char result[256];
    const char *long_text = "Привет! Это тест озвучки OmniChat. "
        "Данный текст специально сделан длинным, чтобы проверить "
        "корректность воспроизведения аудио через FishTTS прокси. "
        "Если вы слышите этот текст нормально, значит всё работает!";
    int rc = tts_speak(long_text, s_state.tts_volume);
    if (rc == 0) snprintf(result, sizeof(result), "OK: TTS played a test phrase");
    else snprintf(result, sizeof(result), "FAIL: TTS playback error");
    if (a->cb) a->cb(result);
    free(a);
    vTaskDelete(NULL);
}

int chat_engine_test_tts(chat_engine_test_cb_t cb)
{
    test_provider_arg_t *a = calloc(1, sizeof(test_provider_arg_t));
    if (!a) return -1;
    a->cb = cb;
    xTaskCreate(test_tts_task, "test_tts", 14 * 1024, a, 5, NULL);
    return 0;
}
