/*
 * tts.c - FishTTS playback through the board's ES8311 codec.
 *
 * Simplification: OmniChat's FishTTS endpoint returns audio. We buffer the
 * response and, if it is a WAV file, strip the header and stream the raw PCM
 * to the codec device. Non-WAV payloads are written as-is (best effort).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "esp_log.h"
#include "esp_http_client.h"
#include "esp_system.h"
#include "cJSON.h"
#include "bsp/esp32_p4_wifi6_touch_lcd_4b.h"
#include "tts.h"
#include "app_config.h"

static const char *TAG = "tts";

static esp_codec_dev_handle_t s_codec = NULL;
static int s_volume = APP_AUDIO_VOLUME_DEFAULT;
static volatile bool s_stop = false;
static char s_url[160] = APP_TTS_URL;
static char s_model[64] = APP_TTS_MODEL;
static char s_voice[32] = APP_TTS_VOICE;

void tts_set_config(const char *url, const char *model, const char *voice)
{
    if (url && url[0]) strncpy(s_url, url, sizeof(s_url) - 1);
    if (model && model[0]) strncpy(s_model, model, sizeof(s_model) - 1);
    if (voice && voice[0]) strncpy(s_voice, voice, sizeof(s_voice) - 1);
    ESP_LOGI(TAG, "TTS config: %s model=%s voice=%s", s_url, s_model, s_voice);
}

esp_err_t tts_init(void)
{
    if (s_codec) {
        return ESP_OK;
    }
    /* Initialize the BSP codec for playback. */
    esp_err_t err = bsp_audio_init(NULL);   /* default mono 22050 Hz duplex */
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "bsp_audio_init failed: %s", esp_err_to_name(err));
        return err;
    }
    s_codec = bsp_audio_codec_speaker_init();
    if (!s_codec) {
        ESP_LOGE(TAG, "speaker codec init failed");
        return ESP_FAIL;
    }
    esp_codec_dev_set_out_vol(s_codec, s_volume);
    ESP_LOGI(TAG, "TTS codec ready (volume=%d)", s_volume);
    return ESP_OK;
}

void tts_set_volume(int volume)
{
    s_volume = volume;
    if (s_codec) {
        esp_codec_dev_set_out_vol(s_codec, volume);
    }
}

void tts_stop(void)
{
    s_stop = true;
}

/* Find the start of PCM data in a WAV blob (skip RIFF header). */
static int wav_data_offset(const unsigned char *data, int len)
{
    if (len < 12) return 0;
    if (data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F') {
        return -1;   /* not WAV */
    }
    int off = 12;
    while (off + 8 <= len) {
        /* chunk: id(4) size(4) */
        uint32_t size = (uint32_t)data[off + 4] |
                        ((uint32_t)data[off + 5] << 8) |
                        ((uint32_t)data[off + 6] << 16) |
                        ((uint32_t)data[off + 7] << 24);
        if (data[off] == 'd' && data[off + 1] == 'a' && data[off + 2] == 't' && data[off + 3] == 'a') {
            return off + 8;
        }
        off += 8 + size;
    }
    return -1;
}

/* Simple HTTP download of synth audio. Returns malloc'd bytes. */
static char *download(const char *url, const char *body, int body_len,
                      const char *auth, int *size)
{
    esp_http_client_config_t cfg = {
        .url = url,
        .method = HTTP_METHOD_POST,
        .timeout_ms = APP_HTTP_TIMEOUT_MS,
    };
    esp_http_client_handle_t client = esp_http_client_init(&cfg);
    if (!client) return NULL;

    esp_http_client_set_header(client, "Content-Type", "application/json");
    if (auth && auth[0]) {
        esp_http_client_set_header(client, "Authorization", auth);
    }
    esp_http_client_set_post_field(client, body, body_len);

    esp_err_t err = esp_http_client_perform(client);
    int status = esp_http_client_get_status_code(client);
    if (err != ESP_OK || status != 200) {
        ESP_LOGE(TAG, "TTS HTTP %d (%s)", status, esp_err_to_name(err));
        esp_http_client_cleanup(client);
        return NULL;
    }

    int64_t cl = esp_http_client_get_content_length(client);
    if (cl <= 0 || cl > 8 * 1024 * 1024) {
        cl = 8 * 1024 * 1024;
    }
    char *buf = malloc(cl + 1);
    if (!buf) { esp_http_client_cleanup(client); return NULL; }
    int got = (int)esp_http_client_read_response(client, buf, cl);
    if (got <= 0) got = 0;
    buf[got] = '\0';
    *size = got;
    esp_http_client_cleanup(client);
    return buf;
}

int tts_speak(const char *text, int volume)
{
    if (!s_codec) {
        ESP_LOGE(TAG, "TTS not initialized");
        return -1;
    }
    s_stop = false;
    if (volume >= 0) {
        esp_codec_dev_set_out_vol(s_codec, volume);
    }

    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "model", s_model);
    cJSON_AddStringToObject(root, "voice", s_voice);
    cJSON_AddStringToObject(root, "input", text);
    char *body = cJSON_Print(root);
    cJSON_Delete(root);
    if (!body) return -1;

    int size = 0;
    char *audio = download(s_url, body, strlen(body), NULL, &size);
    free(body);
    if (!audio) {
        ESP_LOGE(TAG, "TTS download failed");
        return -1;
    }

    /* Determine PCM offset. */
    int off = wav_data_offset((unsigned char *)audio, size);
    if (off < 0) {
        /* Not WAV; play raw bytes best-effort. */
        off = 0;
    }
    ESP_LOGI(TAG, "TTS audio %d bytes (data offset %d)", size, off);

    esp_codec_dev_sample_info_t info = {
        .sample_rate = APP_AUDIO_SAMPLE_RATE,
        .channel = 1,
        .bits_per_sample = 16,
        .channel_mask = 0,
    };
    esp_codec_dev_open(s_codec, &info);

    int written = 0;
    int step = 4096;
    for (int i = off; i + step <= size; i += step) {
        if (s_stop) break;
        esp_codec_dev_write(s_codec, audio + i, step);
        written += step;
    }
    if (written < size - off && !s_stop) {
        esp_codec_dev_write(s_codec, audio + written, size - off - written);
    }

    esp_codec_dev_close(s_codec);
    free(audio);
    ESP_LOGI(TAG, "TTS played %d bytes", size - off);
    return 0;
}
