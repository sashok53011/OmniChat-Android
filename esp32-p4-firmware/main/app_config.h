/*
 * app_config.h - OmniChat-P4 firmware configuration
 *
 * Hard-coded defaults. This is a personal, single-user device, so secrets are
 * baked in as defaults. They are editable at runtime in Settings and persisted
 * to SPIFFS; the values below are only the initial seed.
 */
#ifndef APP_CONFIG_H
#define APP_CONFIG_H

/* -------- WiFi (hard-coded) -------- */
#define APP_WIFI_SSID       "Vodafone-6E42"
#define APP_WIFI_PASSWORD   "A521931p!"

/* -------- LLM providers (only opencode.ai + ollama cloud) -------- */
#define PROVIDER_OPENCODE_ID       "opencode"
#define PROVIDER_OPENCODE_NAME     "opencode.ai"
#define PROVIDER_OPENCODE_BASE_URL "https://opencode.ai/zen/go/v1/"
#define PROVIDER_OPENCODE_API_KEY  "sk-nAgoe9M2uWgJLUL217j5vZ8xUhHo1zPd3Nju9b8ff49ygXjq2gvAFdlczo50Nsle"
#define PROVIDER_OPENCODE_MODEL    "mimo-v2.5"
#define PROVIDER_OPENCODE_PRIORITY 0

#define PROVIDER_OLLAMA_ID         "ollama_cloud"
#define PROVIDER_OLLAMA_NAME       "Ollama Cloud"
#define PROVIDER_OLLAMA_BASE_URL   "https://ollama.com/v1/"
#define PROVIDER_OLLAMA_API_KEY    ""
#define PROVIDER_OLLAMA_MODEL      "llama3"
#define PROVIDER_OLLAMA_PRIORITY   1

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
#define APP_FONT_BODY      &lv_font_montserrat_16
#define APP_FONT_BODY_LG   &lv_font_montserrat_20
#define APP_FONT_TITLE     &lv_font_montserrat_24

#endif /* APP_CONFIG_H */
