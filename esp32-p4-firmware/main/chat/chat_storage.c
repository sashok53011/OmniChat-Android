/*
 * chat_storage.c - uSD card persistence of OmniChat-P4 state using cJSON.
 *
 * Layout (FAT on the uSD card, mounted at /sdcard):
 *   /sdcard/omnichat/state.json   -> providers, config, memory
 *   /sdcard/omnichat/sessions/<id>.json -> one file per chat session
 *
 * The card is formatted (FAT) automatically if it cannot be mounted, so a
 * brand-new or corrupted card is made usable on the first boot.
 *
 * NOTE: the P4 has a single SDMMC controller, and the WiFi module (ESP32-C6
 * via ESP-Hosted) claims it during startup. The microSD card is therefore
 * driven over SPI on its own pins (CLK=43, CMD=44, D0=39; DAT3 works as CS).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/unistd.h>
#include <sys/fcntl.h>
#include <dirent.h>
#include "esp_log.h"
#include "esp_vfs_fat.h"
#include "driver/spi_common.h"
#include "driver/sdspi_host.h"
#include "driver/sdmmc_types.h"
#include "cJSON.h"
#include "chat_storage.h"
#include "app_config.h"
#include "bsp/esp32_p4_wifi6_touch_lcd_4b.h"

static const char *TAG = "chat_storage";

#define STORAGE_PATH   "/sdcard/omnichat"
#define STATE_FILE     STORAGE_PATH "/state.json"
#define SESSIONS_DIR   STORAGE_PATH "/sessions"

static bool s_mounted = false;
static sdmmc_card_t *s_card = NULL;

/* Mount the uSD card over SPI (FAT, format if mount fails).
 *
 * The ESP32-C6 WiFi module (ESP-Hosted) grabs the P4's only SDMMC controller
 * during startup, so the microSD card cannot use SDMMC slot 0 while WiFi is
 * active. Instead we talk to the card in SPI mode on its own pins:
 *   BSP_SD_CLK=43 -> SCLK,  BSP_SD_CMD=44 -> MOSI,
 *   BSP_SD_D0=39  -> MISO,  BSP_SD_D3=42  -> CS.
 *
 * We deliberately do NOT call bsp_sdcard_mount(): besides the SDMMC conflict,
 * it would re-acquire the VO4 LDO channel that bsp_display_start() already
 * holds. The 3.3 V rail is powered from the display init. */
static int mount_sd(void)
{
    if (s_mounted) {
        return 0;
    }

    const spi_host_device_t spi_host = SPI3_HOST;

    spi_bus_config_t bus_cfg = {
        .sclk_io_num = BSP_SD_CLK,
        .mosi_io_num = BSP_SD_CMD,
        .miso_io_num = BSP_SD_D0,
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = 4096,
    };
    esp_err_t err = spi_bus_initialize(spi_host, &bus_cfg, SPI_DMA_CH_AUTO);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "SD SPI bus init failed: %s", esp_err_to_name(err));
        return -1;
    }

    sdmmc_host_t host = SDSPI_HOST_DEFAULT();
    host.slot = spi_host;
    host.max_freq_khz = 5000;

    sdspi_device_config_t slot_cfg = {
        .host_id = spi_host,
        .gpio_cs = BSP_SD_D3,
        .gpio_cd = SDSPI_SLOT_NO_CD,
        .gpio_wp = SDSPI_SLOT_NO_WP,
        .gpio_int = SDSPI_SLOT_NO_INT,
    };

    esp_vfs_fat_mount_config_t mount_config = {
        .format_if_mount_failed = true,
        .max_files = 10,
        .allocation_unit_size = 64 * 1024,
    };

    err = esp_vfs_fat_sdspi_mount("/sdcard", &host, &slot_cfg,
                                  &mount_config, &s_card);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "SD card mount failed: %s", esp_err_to_name(err));
        return -1;
    }

    ESP_LOGI(TAG, "uSD mounted (SPI): %s", s_card ? s_card->cid.name : "?");
    return 0;
}

void chat_session_init(chat_session_t *s)
{
    memset(s, 0, sizeof(*s));
    s->messages = calloc(APP_MAX_MESSAGES, sizeof(chat_message_t));
}

void chat_session_free(chat_session_t *s)
{
    if (s->messages) {
        for (int i = 0; i < s->message_count; i++) {
            if (s->messages[i].text) free(s->messages[i].text);
        }
        free(s->messages);
        s->messages = NULL;
    }
    s->message_count = 0;
}

static void ensure_dir(const char *path)
{
    struct stat st = {0};
    if (stat(path, &st) == -1) {
        mkdir(path, 0755);
    }
}

static int write_file(const char *path, const char *data)
{
    if (!s_mounted) {
        return -1;
    }
    /* Best-effort atomic write: write to a temp file, then move it over the
     * target. FATFS cannot rename over an existing target (f_rename -> FR_EXIST),
     * so the destination is unlinked first. A power cut in between leaves no
     * file at all, which is safer than a half-written one. */
    char tmp[192];
    snprintf(tmp, sizeof(tmp), "%s.tmp", path);
    FILE *f = fopen(tmp, "w");
    if (!f) {
        ESP_LOGE(TAG, "cannot open %s", tmp);
        return -1;
    }
    fputs(data, f);
    fclose(f);
    unlink(path);
    if (rename(tmp, path) != 0) {
        ESP_LOGE(TAG, "rename %s -> %s failed", tmp, path);
        unlink(tmp);
        return -1;
    }
    return 0;
}

static char *read_file(const char *path)
{
    struct stat st;
    if (stat(path, &st) != 0) {
        return NULL;
    }
    FILE *f = fopen(path, "r");
    if (!f) {
        return NULL;
    }
    char *buf = malloc(st.st_size + 1);
    if (!buf) {
        fclose(f);
        return NULL;
    }
    size_t n = fread(buf, 1, st.st_size, f);
    buf[n] = '\0';
    fclose(f);
    return buf;
}

int chat_storage_init(void)
{
    if (s_mounted) {
        return 0;
    }
    if (mount_sd() != 0) {
        /* No card (or format failed). Keep going without persistence. */
        s_mounted = false;
        return -1;
    }
    ensure_dir(STORAGE_PATH);
    ensure_dir(SESSIONS_DIR);
    s_mounted = true;
    ESP_LOGI(TAG, "storage ready at %s", STORAGE_PATH);
    return 0;
}

/* --------------- JSON helpers --------------- */

static void add_provider(cJSON *arr, const ai_provider_t *p)
{
    cJSON *o = cJSON_CreateObject();
    cJSON_AddStringToObject(o, "id", p->id);
    cJSON_AddStringToObject(o, "name", p->name);
    cJSON_AddStringToObject(o, "base_url", p->base_url);
    cJSON_AddStringToObject(o, "api_key", p->api_key);
    cJSON_AddStringToObject(o, "model", p->model);
    cJSON_AddNumberToObject(o, "priority", p->priority);
    cJSON_AddBoolToObject(o, "enabled", p->enabled);
    cJSON_AddItemToArray(arr, o);
}

static void read_provider(const cJSON *o, ai_provider_t *p)
{
    memset(p, 0, sizeof(*p));
    const cJSON *n;
    if ((n = cJSON_GetObjectItem(o, "id")) && n->valuestring)
        strncpy(p->id, n->valuestring, sizeof(p->id) - 1);
    if ((n = cJSON_GetObjectItem(o, "name")) && n->valuestring)
        strncpy(p->name, n->valuestring, sizeof(p->name) - 1);
    if ((n = cJSON_GetObjectItem(o, "base_url")) && n->valuestring)
        strncpy(p->base_url, n->valuestring, sizeof(p->base_url) - 1);
    if ((n = cJSON_GetObjectItem(o, "api_key")) && n->valuestring)
        strncpy(p->api_key, n->valuestring, sizeof(p->api_key) - 1);
    if ((n = cJSON_GetObjectItem(o, "model")) && n->valuestring)
        strncpy(p->model, n->valuestring, sizeof(p->model) - 1);
    cJSON *prio = cJSON_GetObjectItem(o, "priority");
    if (prio) p->priority = prio->valueint;
    cJSON *b = cJSON_GetObjectItem(o, "enabled");
    if (b) p->enabled = cJSON_IsTrue(b);
}

static void add_message(cJSON *arr, const chat_message_t *m)
{
    cJSON *o = cJSON_CreateObject();
    cJSON_AddStringToObject(o, "id", m->id);
    cJSON_AddNumberToObject(o, "role", (int)m->role);
    cJSON_AddStringToObject(o, "text", m->text ? m->text : "");
    cJSON_AddNumberToObject(o, "web", m->is_web_result);
    cJSON_AddNumberToObject(o, "error", m->is_error);
    cJSON_AddItemToArray(arr, o);
}

static void read_message(const cJSON *o, chat_message_t *m)
{
    memset(m, 0, sizeof(*m));
    cJSON *n;
    if ((n = cJSON_GetObjectItem(o, "id")) && n->valuestring)
        strncpy(m->id, n->valuestring, sizeof(m->id) - 1);
    if ((n = cJSON_GetObjectItem(o, "role"))) m->role = (msg_role_t)n->valueint;
    if ((n = cJSON_GetObjectItem(o, "text")) && n->valuestring)
        m->text = strdup(n->valuestring);
    if ((n = cJSON_GetObjectItem(o, "web"))) m->is_web_result = n->valueint;
    if ((n = cJSON_GetObjectItem(o, "error"))) m->is_error = n->valueint;
}

int chat_storage_load_state(app_state_t *state)
{
    char *buf = read_file(STATE_FILE);
    if (!buf) {
        return -1;   /* no state yet */
    }
    cJSON *root = cJSON_Parse(buf);
    free(buf);
    if (!root) {
        return -1;
    }

    const cJSON *j, *n;

    if ((n = cJSON_GetObjectItem(root, "wifi_ssid")) && n->valuestring)
        strncpy(state->wifi_ssid, n->valuestring, sizeof(state->wifi_ssid) - 1);
    if ((n = cJSON_GetObjectItem(root, "wifi_password")) && n->valuestring)
        strncpy(state->wifi_password, n->valuestring, sizeof(state->wifi_password) - 1);
    if ((n = cJSON_GetObjectItem(root, "system_prompt")) && n->valuestring)
        strncpy(state->system_prompt, n->valuestring, sizeof(state->system_prompt) - 1);
    if ((n = cJSON_GetObjectItem(root, "persona")) && n->valuestring)
        strncpy(state->persona, n->valuestring, sizeof(state->persona) - 1);
    if ((n = cJSON_GetObjectItem(root, "language")) && n->valuestring)
        strncpy(state->language, n->valuestring, sizeof(state->language) - 1);
    if ((n = cJSON_GetObjectItem(root, "active_provider")) && n->valuestring)
        strncpy(state->active_provider_id, n->valuestring, sizeof(state->active_provider_id) - 1);
    if ((n = cJSON_GetObjectItem(root, "last_session")) && n->valuestring)
        strncpy(state->last_session_id, n->valuestring, sizeof(state->last_session_id) - 1);

    if ((n = cJSON_GetObjectItem(root, "tts_enabled"))) state->tts_enabled = cJSON_IsTrue(n);
    if ((n = cJSON_GetObjectItem(root, "tts_speak_ai"))) state->tts_speak_ai = cJSON_IsTrue(n);
    if ((n = cJSON_GetObjectItem(root, "tts_volume"))) state->tts_volume = n->valueint;
    if ((n = cJSON_GetObjectItem(root, "tts_url")) && n->valuestring)
        strncpy(state->tts_url, n->valuestring, sizeof(state->tts_url) - 1);
    if ((n = cJSON_GetObjectItem(root, "tts_model")) && n->valuestring)
        strncpy(state->tts_model, n->valuestring, sizeof(state->tts_model) - 1);
    if ((n = cJSON_GetObjectItem(root, "tts_voice")) && n->valuestring)
        strncpy(state->tts_voice, n->valuestring, sizeof(state->tts_voice) - 1);

    j = cJSON_GetObjectItem(root, "providers");
    if (cJSON_IsArray(j)) {
        int count = 0;
        cJSON *it;
        cJSON_ArrayForEach(it, j) {
            if (count >= APP_MAX_PROVIDERS) break;
            read_provider(it, &state->providers[count]);
            count++;
        }
        state->provider_count = count;
    }

    j = cJSON_GetObjectItem(root, "memories");
    if (cJSON_IsArray(j)) {
        int count = 0;
        cJSON *it;
        cJSON_ArrayForEach(it, j) {
            if (count >= APP_MAX_MEMORIES) break;
            const cJSON *o, *v;
            memory_item_t *mem = &state->memories[count];
            memset(mem, 0, sizeof(*mem));
            if ((o = cJSON_GetObjectItem(it, "id")) && o->valuestring)
                strncpy(mem->id, o->valuestring, sizeof(mem->id) - 1);
            if ((v = cJSON_GetObjectItem(it, "category")) && v->valuestring)
                strncpy(mem->category, v->valuestring, sizeof(mem->category) - 1);
            if ((v = cJSON_GetObjectItem(it, "content")) && v->valuestring)
                strncpy(mem->content, v->valuestring, sizeof(mem->content) - 1);
            count++;
        }
        state->memory_count = count;
    }

    cJSON_Delete(root);
    return 0;
}

int chat_storage_save_state(const app_state_t *state)
{
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "wifi_ssid", state->wifi_ssid);
    cJSON_AddStringToObject(root, "wifi_password", state->wifi_password);
    cJSON_AddStringToObject(root, "system_prompt", state->system_prompt);
    cJSON_AddStringToObject(root, "persona", state->persona);
    cJSON_AddStringToObject(root, "language", state->language);
    cJSON_AddStringToObject(root, "active_provider", state->active_provider_id);
    cJSON_AddStringToObject(root, "last_session", state->last_session_id);
    cJSON_AddBoolToObject(root, "tts_enabled", state->tts_enabled);
    cJSON_AddBoolToObject(root, "tts_speak_ai", state->tts_speak_ai);
    cJSON_AddNumberToObject(root, "tts_volume", state->tts_volume);
    cJSON_AddStringToObject(root, "tts_url", state->tts_url);
    cJSON_AddStringToObject(root, "tts_model", state->tts_model);
    cJSON_AddStringToObject(root, "tts_voice", state->tts_voice);

    cJSON *providers = cJSON_AddArrayToObject(root, "providers");
    for (int i = 0; i < state->provider_count && i < APP_MAX_PROVIDERS; i++) {
        add_provider(providers, &state->providers[i]);
    }

    cJSON *mems = cJSON_AddArrayToObject(root, "memories");
    for (int i = 0; i < state->memory_count && i < APP_MAX_MEMORIES; i++) {
        const memory_item_t *m = &state->memories[i];
        cJSON *o = cJSON_CreateObject();
        cJSON_AddStringToObject(o, "id", m->id);
        cJSON_AddStringToObject(o, "category", m->category);
        cJSON_AddStringToObject(o, "content", m->content);
        cJSON_AddItemToArray(mems, o);
    }

    char *out = cJSON_Print(root);
    cJSON_Delete(root);
    if (!out) return -1;
    int ret = write_file(STATE_FILE, out);
    free(out);
    return ret;
}

int chat_storage_load_sessions(chat_session_t **out_sessions, int max)
{
    (void)max;
    /* Load all *.json files in the sessions dir. */
    /* Use a simple glob with DIR; we limit to APP_MAX_SESSIONS. */
    *out_sessions = NULL;
    if (!s_mounted) return 0;

    /* Build a list via opendir */
    DIR *d = NULL;
    struct dirent *e;
    int count = 0;
    chat_session_t *arr = calloc(APP_MAX_SESSIONS, sizeof(chat_session_t));
    if (!arr) return 0;

    d = opendir(SESSIONS_DIR);
    if (!d) { free(arr); return 0; }

    while ((e = readdir(d)) != NULL && count < APP_MAX_SESSIONS) {
        if (e->d_name[0] == '.') continue;
        size_t len = strlen(e->d_name);
        if (len < 6 || strcmp(e->d_name + len - 5, ".json") != 0) continue;

        char path[128];
        snprintf(path, sizeof(path), "%s/%s", SESSIONS_DIR, e->d_name);
        char *buf = read_file(path);
        if (!buf) continue;
        cJSON *root = cJSON_Parse(buf);
        free(buf);
        if (!root) continue;

        chat_session_t *s = &arr[count];
        chat_session_init(s);
        cJSON *n;
        if ((n = cJSON_GetObjectItem(root, "id")) && n->valuestring)
            strncpy(s->id, n->valuestring, sizeof(s->id) - 1);
        if ((n = cJSON_GetObjectItem(root, "title")) && n->valuestring)
            strncpy(s->title, n->valuestring, sizeof(s->title) - 1);
        if ((n = cJSON_GetObjectItem(root, "created_at"))) s->created_at = (int64_t)n->valuedouble;
        if ((n = cJSON_GetObjectItem(root, "system_prompt")) && n->valuestring)
            strncpy(s->system_prompt, n->valuestring, sizeof(s->system_prompt) - 1);

        cJSON *msgs = cJSON_GetObjectItem(root, "messages");
        if (cJSON_IsArray(msgs)) {
            int mc = 0;
            cJSON *it;
            cJSON_ArrayForEach(it, msgs) {
                if (mc >= APP_MAX_MESSAGES) break;
                read_message(it, &s->messages[mc]);
                mc++;
            }
            s->message_count = mc;
        }
        cJSON_Delete(root);
        count++;
    }
    closedir(d);

    *out_sessions = arr;
    return count;
}

int chat_storage_save_session(const chat_session_t *session)
{
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "id", session->id);
    cJSON_AddStringToObject(root, "title", session->title);
    cJSON_AddNumberToObject(root, "created_at", (double)session->created_at);
    cJSON_AddStringToObject(root, "system_prompt", session->system_prompt);

    cJSON *msgs = cJSON_AddArrayToObject(root, "messages");
    for (int i = 0; i < session->message_count; i++) {
        add_message(msgs, &session->messages[i]);
    }

    char *out = cJSON_Print(root);
    cJSON_Delete(root);
    if (!out) return -1;

    char path[128];
    snprintf(path, sizeof(path), "%s/%s.json", SESSIONS_DIR, session->id);
    int ret = write_file(path, out);
    free(out);
    return ret;
}

int chat_storage_delete_session(const char *id)
{
    char path[128];
    snprintf(path, sizeof(path), "%s/%s.json", SESSIONS_DIR, id);
    unlink(path);
    return 0;
}
