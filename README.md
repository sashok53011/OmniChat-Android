# MyVoice — AI Companion System

Full-stack AI companion: Android Kotlin app + ESP32-P4 touchscreen firmware. Multi-provider LLM chat with voice, markdown rendering, web search, and Bluetooth companion link.

## Components

### Android App (`app/`)
- **Stack**: Kotlin, Jetpack Compose, Room DB, llama.cpp JNI
- **26 Kotlin source files** across bluetooth, data, UI, and service layers

### ESP32-P4 Firmware (`esp32-p4-firmware/`)
- **Target**: Waveshare ESP32-P4-WIFI6-Touch-LCD-4B
- **Stack**: ESP-IDF v6.0.2, LVGL, FreeRTOS
- **44 C source/header files** — custom firmware, not a template

## Features

- Multi-provider LLM (OpenAI-compatible API, Ollama, LM Studio, cloud)
- Local on-device inference via llama.cpp (GGUF models)
- FishTTS voice synthesis with configurable voice
- Markdown rendering (headings, bold, italic, code blocks, links, lists)
- Web search (DuckDuckGo) integrated into chat
- Session/memory persistence (SPIFFS on ESP32, Room on Android)
- Bluetooth companion link to ESP32 (serial protocol)
- UDP messaging for ESP32 communication
- Media observation service (auto-process new gallery images)
- Deutsche Bahn API integration
- MCP (Model Context Protocol) server/tool support
- Touchscreen UI with chat, settings, provider management
- Runtime-configurable: WiFi, LLM provider, TTS, persona, system prompt

## Architecture

```
┌─────────────────┐     WiFi/HTTP/UDP     ┌──────────────────┐
│  Android App    │◄────────────────────►│  ESP32-P4 Board  │
│  (Kotlin)       │     Bluetooth         │  (LVGL + C)      │
│                 │◄────────────────────►│                   │
│  • Local LLM    │                      │  • LLM Client     │
│  • Chat UI      │                      │  • TTS (FishTTS)  │
│  • Room DB      │                      │  • Touch UI       │
│  • Media Obs.   │                      │  • Markdown       │
│  • MCP Tools    │                      │  • Web Search     │
└─────────────────┘                      └──────────────────┘
        │                                        │
        ▼                                        ▼
   LLM Providers                          LLM Providers
   • Gemini API                           • OpenCode.ai
   • OpenAI-compatible                    • Ollama (LAN)
   • Local llama.cpp                      • LM Studio
```

## ESP32-P4 Firmware Structure

```
esp32-p4-firmware/main/
├── main.c              # Entry point, LVGL init, task lifecycle
├── app_config.h        # Build defaults, provider configs, secrets
├── chat/               # Chat engine, session storage, provider fallback
├── md/                 # Markdown parser + LVGL renderer + syntax highlight
├── net/                # WiFi, LLM client (OpenAI-compat), TTS, web search
├── server/             # HTTP server for remote control/push
├── ui/                 # LVGL screens: chat, settings, status bar
└── fonts/              # Custom Montserrat font files for markdown
```

## Quick Start

### ESP32-P4 Firmware
```bash
# Set up ESP-IDF v6.0.2
. $IDF_PATH/export.sh

# Configure secrets
cp esp32-p4-firmware/main/secrets.h.example esp32-p4-firmware/main/secrets.h
# Edit with your WiFi and API keys

# Build and flash
cd esp32-p4-firmware
idf.py build flash monitor
```

### Android App
```bash
# Open in Android Studio
# Or command line:
./gradlew assembleDebug
```

## LLM Providers (ESP32 firmware)

| Provider | Default URL | Notes |
|----------|-------------|-------|
| OpenCode.ai | opencode.ai/zen/go/v1/ | Cloud API |
| Ollama LAN | http://192.168.0.133:11434/v1/ | Local network |
| LM Studio | http://192.168.0.133:1234/v1/ | Local network |

Priority-based fallback: if the primary provider fails, the next is tried automatically. All configurable at runtime via the touch UI.

## Voice

- **FishTTS** — free TTS API (no API key required)
- Configurable URL, model, voice, volume
- Speak-AI toggle: voice output for AI responses
- Stop/playback controls

## Requirements

- ESP-IDF v6.0.2 (for firmware)
- Android SDK 36, min 24 (for app)
- llama.cpp (for Android local inference, JNI bridge)
