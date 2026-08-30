/*
 * chat_model.h - core data structures shared across OmniChat-P4 modules
 */
#ifndef CHAT_MODEL_H
#define CHAT_MODEL_H

#include <stdbool.h>
#include <stdint.h>
#include "app_config.h"

/* ---------------- Provider ---------------- */
typedef struct {
    char id[24];
    char name[40];
    char base_url[160];
    char api_key[160];
    char model[64];
    int priority;
    bool enabled;
} ai_provider_t;

#define APP_MAX_PROVIDERS 8

/* ---------------- Messages ---------------- */
typedef enum {
    MSG_ROLE_USER = 0,
    MSG_ROLE_ASSISTANT = 1,
    MSG_ROLE_SYSTEM = 2
} msg_role_t;

typedef struct {
    char id[24];            /* stable id */
    msg_role_t role;
    char *text;             /* heap allocated */
    int is_web_result;      /* 1 if this assistant message used web search */
    int is_error;           /* 1 if the text is an error message */
} chat_message_t;

/* ---------------- Session ---------------- */
typedef struct {
    char id[24];
    char title[128];
    int64_t created_at;
    char system_prompt[APP_MAX_SYSTEM_PROMPT];
    chat_message_t *messages;   /* heap-allocated array of APP_MAX_MESSAGES */
    int message_count;
} chat_session_t;

/* Allocate and initialise a session with a heap message array. Caller frees. */
void chat_session_init(chat_session_t *s);
/* Free the heap message array (but not the session struct itself). */
void chat_session_free(chat_session_t *s);

/* ---------------- Memory ---------------- */
typedef struct {
    char id[24];
    char category[32];
    char content[512];
} memory_item_t;

/* ---------------- Device state ---------------- */
typedef struct {
    /* WiFi */
    char wifi_ssid[64];
    char wifi_password[64];
    /* Providers */
    ai_provider_t providers[APP_MAX_PROVIDERS];
    int provider_count;
    char active_provider_id[24];
    /* Chat */
    char system_prompt[APP_MAX_SYSTEM_PROMPT];
    char persona[512];
    char language[8];
    /* Memory */
    memory_item_t memories[APP_MAX_MEMORIES];
    int memory_count;
    /* TTS */
    int tts_enabled;
    int tts_speak_ai;
    int tts_volume;
    char tts_url[160];
    char tts_model[64];
    char tts_voice[32];
    /* misc */
    char last_session_id[24];
} app_state_t;

#endif /* CHAT_MODEL_H */
