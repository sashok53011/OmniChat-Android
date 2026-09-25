/* tts.c - FishTTS playback through the board's ES8311 codec.
 *
 * FIX: reinitialise the I2S clock to match the WAV sample rate.
 * FIX: decode HTTP chunked Transfer-Encoding before WAV parsing.
 * FIX: TLS timeout raised from 10s -> 60s.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "esp_log.h"
#include "esp_crt_bundle.h"
#include "esp_tls.h"
#include "esp_system.h"
#include "cJSON.h"
#include "bsp/esp32_p4_wifi6_touch_lcd_4b.h"
#include "tts.h"
#include "app_config.h"
#include "lwip/sockets.h"
#include "lwip/netdb.h"
#include "lwip/err.h"
#include "lwip/sys.h"

static const char *TAG = "tts";

static esp_codec_dev_handle_t s_codec = NULL;
static int s_volume = APP_AUDIO_VOLUME_DEFAULT;
static volatile bool s_stop = false;
static char s_url[160] = APP_TTS_URL;
static char s_model[64] = APP_TTS_MODEL;
static char s_voice[32] = APP_TTS_VOICE;
static int s_i2s_rate = 0;

#define MAX_AUDIO_BUF_SIZE (2 * 1024 * 1024)

void tts_set_config(const char *url, const char *model, const char *voice)
{
    if (url && url[0]) strncpy(s_url, url, sizeof(s_url) - 1);
    if (model && model[0]) strncpy(s_model, model, sizeof(s_model) - 1);
    if (voice && voice[0]) strncpy(s_voice, voice, sizeof(s_voice) - 1);
    ESP_LOGI(TAG, "TTS config: %s model=%s voice=%s", s_url, s_model, s_voice);
}

static esp_err_t audio_reinit(int sample_rate)
{
    if (!s_codec) {
        esp_err_t err = bsp_audio_init(NULL);
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
    }

    esp_err_t err = bsp_audio_reconfigure_clock((uint32_t)sample_rate);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "clock reconfigure to %d Hz failed", sample_rate);
        return err;
    }

    s_i2s_rate = sample_rate;
    ESP_LOGI(TAG, "audio_reinit OK rate=%d Hz", sample_rate);
    return ESP_OK;
}

esp_err_t tts_init(void)
{
    if (s_codec) return ESP_OK;
    return audio_reinit(24000);
}

void tts_set_volume(int volume)
{
    s_volume = volume;
    if (s_codec) esp_codec_dev_set_out_vol(s_codec, volume);
}

void tts_stop(void) { s_stop = true; }

static int wav_parse(const unsigned char *data, int len,
                     int *sample_rate, int *channels, int *bits)
{
    if (len < 44) return -1;
    if (data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F') return -1;
    if (data[8] != 'W' || data[9] != 'A' || data[10] != 'V' || data[11] != 'E') return -1;
    if (channels) *channels = (int)data[22] | ((int)data[23] << 8);
    if (sample_rate) *sample_rate = (int)data[24] | ((int)data[25] << 8) |
                                    ((int)data[26] << 16) | ((int)data[27] << 24);
    if (bits) *bits = (int)data[34] | ((int)data[35] << 8);
    int off = 12;
    while (off + 8 <= len) {
        uint32_t sz = (uint32_t)data[off+4] | ((uint32_t)data[off+5]<<8) |
                      ((uint32_t)data[off+6]<<16) | ((uint32_t)data[off+7]<<24);
        if (data[off]=='d' && data[off+1]=='a' && data[off+2]=='t' && data[off+3]=='a')
            return off + 8;
        off += 8 + sz;
    }
    return -1;
}

static void parse_tts_url(const char *url, char *host, int hlen, int *port, const char **path)
{
    const char *p = strstr(url, "://"); p = p ? p + 3 : url;
    const char *colon = strchr(p, ':');
    const char *slash = strchr(p, '/');
    const char *end = colon ? colon : (slash ? slash : p + strlen(p));
    int hl = (int)(end - p); if (hl > hlen - 1) hl = hlen - 1;
    memcpy(host, p, hl); host[hl] = '\0';
    if (colon) *port = atoi(colon + 1);
    else *port = (strstr(url, "https://") == url) ? 443 : 80;
    *path = slash ? slash : "/";
}

/* Decode chunked transfer encoding in-place.
 * Returns pointer to decoded data (caller must free raw buffer separately).
 * out_len is set to the decoded length. */
static char *decode_chunked(char *body_start, int body_len, int *out_len)
{
    char *out = malloc(body_len + 1);
    if (!out) return NULL;

    char *inp = body_start;
    char *endp = body_start + body_len;
    int opos = 0;

    while (inp < endp && *inp) {
        /* Parse hex chunk size */
        char *crlf = NULL;
        /* Find \r\n after chunk size */
        for (char *scan = inp; scan < endp - 1; scan++) {
            if (scan[0] == '\r' && scan[1] == '\n') { crlf = scan; break; }
        }
        if (!crlf) break;

        int chunk = (int)strtoul(inp, NULL, 16);
        ESP_LOGD(TAG, "chunk size=%d at offset=%d", chunk, (int)(inp - body_start));
        if (chunk == 0) break;  /* final chunk */

        char *data = crlf + 2;
        if (data + chunk > endp) {
            ESP_LOGW(TAG, "chunk %d exceeds body, clamping", chunk);
            chunk = endp - data;
        }
        if (opos + chunk > body_len) {
            ESP_LOGW(TAG, "output overflow at %d, truncating", opos);
            chunk = body_len - opos;
        }
        memcpy(out + opos, data, chunk);
        opos += chunk;

        /* Skip chunk data + trailing \r\n */
        inp = data + chunk;
        if (inp + 1 < endp && inp[0] == '\r' && inp[1] == '\n') {
            inp += 2;
        } else {
            inp = endp;  /* no trailing CRLF, we're done */
        }
    }
    out[opos] = '\0';
    *out_len = opos;
    ESP_LOGI(TAG, "chunked decode: %d bytes body -> %d bytes output", body_len, opos);
    return out;
}

static char *download(const char *url, const char *body, int body_len, int *size)
{
    char host[128]; int port = 0; const char *path = "/";
    parse_tts_url(url, host, sizeof(host), &port, &path);
    bool is_https = (strstr(url, "https://") == url);
    ESP_LOGI(TAG, "TTS download via %s: %s:%d%s", is_https ? "HTTPS" : "HTTP", host, port, path);

    char *raw = NULL;
    int raw_len = 0;

    if (is_https) {
        esp_tls_cfg_t cfg = {
            .crt_bundle_attach = esp_crt_bundle_attach,
            .timeout_ms = 60000,
            .addr_family = ESP_TLS_AF_INET,
        };
        esp_tls_t *tls = esp_tls_init();
        if (!tls) return NULL;
        if (esp_tls_conn_new_sync(host, strlen(host), port, &cfg, tls) != 1) {
            ESP_LOGE(TAG, "TLS connection failed");
            esp_tls_conn_destroy(tls);
            return NULL;
        }
        char hdr[512];
        int hlen = snprintf(hdr, sizeof(hdr),
            "POST %s HTTP/1.1\r\nHost: %s\r\nContent-Type: application/json\r\n"
            "Accept: */*\r\nUser-Agent: ESP32P4/1.0\r\nConnection: close\r\n"
            "Content-Length: %d\r\n\r\n", path, host, body_len);

        if (esp_tls_conn_write(tls, hdr, hlen) < 0 || esp_tls_conn_write(tls, body, body_len) < 0) {
            ESP_LOGE(TAG, "TLS write error");
            esp_tls_conn_destroy(tls);
            return NULL;
        }
        int cap = MAX_AUDIO_BUF_SIZE;
        raw = malloc(cap);
        if (!raw) { esp_tls_conn_destroy(tls); return NULL; }
        while (raw_len < cap - 1) {
            int r = esp_tls_conn_read(tls, raw + raw_len, cap - 1 - raw_len);
            if (r == ESP_TLS_ERR_SSL_WANT_READ || r == ESP_TLS_ERR_SSL_WANT_WRITE) {
                vTaskDelay(pdMS_TO_TICKS(5)); continue;
            }
            if (r <= 0) { ESP_LOGI(TAG, "TLS read done: r=%d total=%d", r, raw_len); break; }
            raw_len += r;
        }
        esp_tls_conn_destroy(tls);
        raw[raw_len] = '\0';
    } else {
        struct sockaddr_in sa;
        memset(&sa, 0, sizeof(sa));
        sa.sin_family = AF_INET;
        sa.sin_port = htons(port);
        sa.sin_addr.s_addr = inet_addr(host);
        if (sa.sin_addr.s_addr == INADDR_NONE) {
            struct hostent *he = gethostbyname(host);
            if (!he) { ESP_LOGE(TAG, "DNS failed"); return NULL; }
            memcpy(&sa.sin_addr, he->h_addr, 4);
        }
        int fd = socket(AF_INET, SOCK_STREAM, 0);
        if (fd < 0) return NULL;
        struct timeval tv = { .tv_sec = 10, .tv_usec = 0 };
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
        if (connect(fd, (struct sockaddr *)&sa, sizeof(sa)) != 0) {
            ESP_LOGE(TAG, "Socket connect failed"); close(fd); return NULL;
        }
        char hdr[512];
        int hlen = snprintf(hdr, sizeof(hdr),
            "POST %s HTTP/1.1\r\nHost: %s\r\nContent-Type: application/json\r\n"
            "Accept: */*\r\nUser-Agent: ESP32P4/1.0\r\nConnection: close\r\n"
            "Content-Length: %d\r\n\r\n", path, host, body_len);
        send(fd, hdr, hlen, 0);
        send(fd, body, body_len, 0);
        int cap = MAX_AUDIO_BUF_SIZE;
        raw = malloc(cap);
        if (!raw) { close(fd); return NULL; }
        while (raw_len < cap - 1) {
            int r = recv(fd, raw + raw_len, cap - 1 - raw_len, 0);
            if (r < 0) { if (errno == EAGAIN || errno == EWOULDBLOCK) break; break; }
            if (r == 0) break;
            raw_len += r;
        }
        close(fd);
        raw[raw_len] = '\0';
    }

    ESP_LOGI(TAG, "raw HTTP response: %d bytes", raw_len);
    if (!raw || raw_len == 0) { if (raw) free(raw); return NULL; }

    int status = 0;
    if (sscanf(raw, "HTTP/%*s %d", &status) != 1 || status != 200) {
        ESP_LOGE(TAG, "HTTP status %d", status);
        free(raw); return NULL;
    }

    char *hdr_end = strstr(raw, "\r\n\r\n");
    if (!hdr_end) { free(raw); return NULL; }

    char *body_start = hdr_end + 4;
    int body_left = raw_len - (int)(body_start - raw);
    if (body_left < 0) body_left = 0;

    /* Check for chunked encoding and decode it */
    int is_chunked = (strstr(raw, "Transfer-Encoding: chunked") != NULL) ||
                     (strstr(raw, "transfer-encoding: chunked") != NULL);
    ESP_LOGI(TAG, "HTTP body: %d bytes, chunked=%d", body_left, is_chunked);

    char *audio = NULL;
    int audio_len = 0;

    if (is_chunked) {
        audio = decode_chunked(body_start, body_left, &audio_len);
        free(raw);
    } else {
        audio = malloc(body_left + 1);
        if (!audio) { free(raw); return NULL; }
        memcpy(audio, body_start, body_left);
        audio[body_left] = '\0';
        audio_len = body_left;
        free(raw);
    }

    if (!audio) return NULL;

    /* Log first bytes for diagnosis */
    if (audio_len > 0) {
        ESP_LOGI(TAG, "audio[0..7]: %02x %02x %02x %02x %02x %02x %02x %02x",
                 (unsigned char)audio[0], (unsigned char)audio[1],
                 (unsigned char)audio[2], (unsigned char)audio[3],
                 (unsigned char)audio[4], (unsigned char)audio[5],
                 (unsigned char)audio[6], (unsigned char)audio[7]);
    }

    *size = audio_len;
    return audio;
}

int tts_speak(const char *text, int volume)
{
    s_stop = false;
    if (volume >= 0) s_volume = volume;

    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "model", s_model);
    cJSON_AddStringToObject(root, "voice", s_voice);
    cJSON_AddStringToObject(root, "input", text);
    cJSON_AddStringToObject(root, "response_format", "wav");
    char *body = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    if (!body) return -1;

    int size = 0;
    char *audio = download(s_url, body, strlen(body), &size);
    free(body);

    if (!audio || size <= 0) {
        ESP_LOGE(TAG, "TTS download empty");
        if (audio) free(audio);
        return -1;
    }

    int wav_rate = 0, wav_ch = 0, wav_bits = 0;
    int off = wav_parse((unsigned char *)audio, size, &wav_rate, &wav_ch, &wav_bits);
    if (off < 0) {
        ESP_LOGW(TAG, "WAV parse FAILED for %d bytes", size);
        off = 0;
    }

    int rate = (wav_rate > 0) ? wav_rate : APP_AUDIO_SAMPLE_RATE;
    int ch = (wav_ch > 0) ? wav_ch : 1;
    int bits = (wav_bits > 0) ? wav_bits : 16;

    ESP_LOGI(TAG, "Playing: %d bytes rate=%d Hz bits=%d ch=%d off=%d", size, rate, bits, ch, off);

    if (rate != s_i2s_rate) {
        ESP_LOGI(TAG, "Rate mismatch (I2S=%d WAV=%d) -- reinit", s_i2s_rate, rate);
        if (audio_reinit(rate) != ESP_OK) { free(audio); return -1; }
    } else if (!s_codec) {
        if (audio_reinit(rate) != ESP_OK) { free(audio); return -1; }
    }

    esp_codec_dev_sample_info_t info = {
        .sample_rate = rate, .channel = ch,
        .bits_per_sample = bits, .channel_mask = 0,
    };
    if (esp_codec_dev_open(s_codec, &info) != ESP_OK) {
        ESP_LOGE(TAG, "codec open failed at %d Hz", rate);
        free(audio); return -1;
    }

    int written = 0;
    int step = 4096;
    for (int i = off; i < size; i += step) {
        if (s_stop) { ESP_LOGW(TAG, "stopped"); break; }
        int chunk = (i + step <= size) ? step : (size - i);
        esp_codec_dev_write(s_codec, (void *)(audio + i), chunk);
        written += chunk;
        vTaskDelay(pdMS_TO_TICKS(1));
    }

    esp_codec_dev_close(s_codec);
    free(audio);
    ESP_LOGI(TAG, "Playback done (%d bytes)", written);
    return 0;
}
