/*
 * app_config.h - OmniChat-P4 firmware configuration
 *
 * Build defaults. Secrets (WiFi credentials, API keys) come from main/secrets.h,
 * which is NOT committed — copy secrets.h.example to secrets.h and fill it in.
 * All values are editable at runtime in Settings and persisted to SPIFFS; the
 * values here are only the initial seed.
 */
#ifndef APP_CONFIG_H
#define APP_CONFIG_H

/* -------- Secrets --------
 * Real values live in main/secrets.h, which is NOT committed (see .gitignore).
 * Copy secrets.h.example to secrets.h and fill it in.
 */
#if defined(__has_include)
#  if __has_include("secrets.h")
#    include "secrets.h"
#  endif
#endif

/* -------- WiFi -------- */
#ifndef APP_WIFI_SSID
#  define APP_WIFI_SSID       "CHANGE_ME_SSID"
#endif
#ifndef APP_WIFI_PASSWORD
#  define APP_WIFI_PASSWORD   "CHANGE_ME_PASSWORD"
#endif
/* -------- LLM providers (opencode.ai, Ollama LAN, LM Studio) -------- */
#define PROVIDER_OPENCODE_ID       "opencode"
#define PROVIDER_OPENCODE_NAME     "opencode.ai"
#define PROVIDER_OPENCODE_BASE_URL "https://opencode.ai/zen/go/v1/"
#ifndef PROVIDER_OPENCODE_API_KEY
#  define PROVIDER_OPENCODE_API_KEY  "CHANGE_ME_API_KEY"
#endif
#define PROVIDER_OPENCODE_MODEL    "mimo-v2.5"
#define PROVIDER_OPENCODE_PRIORITY 0

#define PROVIDER_LM_STUDIO_ID       "lm_studio"
#define PROVIDER_LM_STUDIO_NAME     "LM Studio"
#define PROVIDER_LM_STUDIO_BASE_URL "http://192.168.0.133:1234/v1/"
#define PROVIDER_LM_STUDIO_API_KEY  ""
#define PROVIDER_LM_STUDIO_MODEL    "qwen3-vl-4b-instruct-1m"
#define PROVIDER_LM_STUDIO_PRIORITY 1

#define PROVIDER_LAN_ID           "ollama_lan"
#define PROVIDER_LAN_NAME         "Ollama LAN"
#define PROVIDER_LAN_BASE_URL     "http://192.168.0.133:11434/v1/"
#define PROVIDER_LAN_API_KEY      ""
#define PROVIDER_LAN_MODEL        "qwen3.5:4b"
#define PROVIDER_LAN_PRIORITY     2

#define APP_DEFAULT_PROVIDER_ID    PROVIDER_OPENCODE_ID

/* -------- TTS (FishTTS, no API key) -------- */
#define APP_TTS_URL    "https://fishtts.devhorizon.online/v1/audio/speech"
#define APP_TTS_MODEL  "tts-1"
#define APP_TTS_VOICE  "auto"
#define APP_TTS_ENABLED_DEFAULT 1
#define APP_TTS_SPEAK_AI_DEFAULT 1

/* -------- System prompt / persona -------- */
#define APP_DEFAULT_SYSTEM_PROMPT \
    "You are OmniChat AI, an extremely advanced AI companion. Help the user intelligently."
#define APP_DEFAULT_PERSONA ""
#define APP_DEFAULT_LANGUAGE "en"

/* -------- Server -------- */
#define APP_HTTP_STATUS_REFRESH_MS 5000

/* -------- Limits -------- */
#define APP_MAX_MESSAGES 200
#define APP_MAX_MEMORIES 64
#define APP_MAX_SESSIONS 64
#define APP_MAX_SYSTEM_PROMPT 4096
#define APP_MAX_RESPONSE 16384
#define APP_HTTP_TIMEOUT_MS 60000

/* -------- Audio -------- */
#define APP_AUDIO_SAMPLE_RATE 16000
#define APP_AUDIO_VOLUME_DEFAULT 60

/* UI font choices (Montserrat included in LVGL compile) */
#define APP_FONT_BODY      &lv_font_montserrat_20
#define APP_FONT_BODY_LG   &lv_font_montserrat_24
#define APP_FONT_TITLE     &lv_font_montserrat_24

#endif /* APP_CONFIG_H */
