/*
 * chat_engine.h - OmniChat-P4 chat orchestration
 *
 * Holds the active session, the providers, and runs the request lifecycle:
 *   build prompt -> (optional web search) -> provider fallback -> store response
 *   -> (optional TTS) -> notify UI.
 */
#ifndef CHAT_ENGINE_H
#define CHAT_ENGINE_H

#include <stdbool.h>
#include "chat_model.h"

/* Callback: called from the chat worker thread when the UI should refresh. */
typedef void (*chat_engine_listener_t)(void);

/* Initialize engine: load persisted state + sessions. Returns 0 on success. */
int chat_engine_init(void);

/* Register a UI refresh listener (called after state changes). */
void chat_engine_set_listener(chat_engine_listener_t cb);

/* Access to the live state (read-only from UI thread). */
const app_state_t *chat_engine_state(void);

/* Sessions list (array returned by load). */
const chat_session_t *chat_engine_sessions(int *count);

/* The currently active session. */
chat_session_t *chat_engine_active_session(void);

/* Select an existing session by id, or create a new one. */
int chat_engine_select_session(const char *id);
int chat_engine_new_session(void);

/* Delete a session. */
int chat_engine_delete_session(const char *id);

/* Send a user message: appends to session, runs the pipeline in a worker task. */
int chat_engine_send_user(const char *text, int web_search_enabled);

/* Retry the last exchange: removes the last user+assistant pair and re-sends. */
int chat_engine_retry_last(void);

/* Delete all messages in the active session (keeps the session itself). */
int chat_engine_clear_active(void);

/* Append a message directly (e.g. from HTTP /text push). */
int chat_engine_push_message(msg_role_t role, const char *text);

/* ---- Provider/config helpers (called from UI) ---- */
ai_provider_t *chat_engine_find_provider(const char *id);
int chat_engine_set_active_provider(const char *id);
bool chat_engine_save_state_now(void);
void chat_engine_update_system_prompt(const char *text);
void chat_engine_add_memory(const char *category, const char *content);
void chat_engine_remove_memory(const char *id);
bool chat_engine_tts_enabled(void);
void chat_engine_set_tts_enabled(bool en);
void chat_engine_set_tts_volume(int vol);
void chat_engine_set_tts_config(const char *url, const char *model, const char *voice, int speak_ai);
bool chat_engine_is_busy(void);

/* ---- Provider CRUD ---- */
int chat_engine_upsert_provider(const ai_provider_t *p);
int chat_engine_delete_provider(const char *id);

/* ---- Connection tests (run in worker tasks; callback with result) ---- */
typedef void (*chat_engine_test_cb_t)(const char *result);
int chat_engine_test_provider(const char *id, chat_engine_test_cb_t cb);
int chat_engine_test_tts(chat_engine_test_cb_t cb);

#endif
