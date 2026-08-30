/*
 * http_server.c - OmniChat bridge endpoints.
 *
 *   GET  /status   -> { device, ip, wifi_connected, free_heap, uptime }
 *   POST /text     -> JSON { title, text } -> pushes an AI message bubble into the chat
 *   OPTIONS        -> CORS
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "esp_log.h"
#include "esp_http_server.h"
#include "esp_system.h"
#include "esp_netif.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "cJSON.h"
#include "chat_engine.h"
#include "app_wifi.h"
#include "app_config.h"

static const char *TAG = "http_server";

#define HTTP_PORT 80

static char *get_wifi_ip(void)
{
    esp_netif_ip_info_t ip;
    esp_netif_t *netif = esp_netif_get_handle_from_ifkey("WIFI_STA_DEF");
    if (!netif || esp_netif_get_ip_info(netif, &ip) != ESP_OK) {
        return strdup("0.0.0.0");
    }
    char *s = malloc(20);
    if (s) snprintf(s, 20, IPSTR, IP2STR(&ip.ip));
    return s;
}

/* GET /status */
static esp_err_t status_handler(httpd_req_t *req)
{
    char *ip = get_wifi_ip();
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "device", "OmniChat-P4");
    cJSON_AddStringToObject(root, "ip", ip ? ip : "0.0.0.0");
    cJSON_AddBoolToObject(root, "wifi_connected", app_wifi_is_connected());
    cJSON_AddNumberToObject(root, "free_heap", (int)esp_get_free_heap_size());
    cJSON_AddNumberToObject(root, "uptime", (int)(esp_timer_get_time() / 1000000));
    char *out = cJSON_Print(root);
    cJSON_Delete(root);
    free(ip);

    httpd_resp_set_type(req, "application/json");
    httpd_resp_send(req, out, HTTPD_RESP_USE_STRLEN);

    /* Refresh a fixed status placeholder later (not needed here). */
    (void)APP_HTTP_STATUS_REFRESH_MS;
    free(out);
    return ESP_OK;
}

/* Offload the (potentially heavy) push + persist to a separate task so the
 * small httpd worker stack is never exhausted by cJSON Print/Persist. */
typedef struct {
    char title[128];
    char text[4096];
} push_arg_t;

static void push_task(void *arg)
{
    push_arg_t *a = (push_arg_t *)arg;
    chat_engine_push_message(MSG_ROLE_ASSISTANT, a->text);
    if (a->title[0]) {
        chat_session_t *sess = chat_engine_active_session();
        if (sess) strncpy(sess->title, a->title, sizeof(sess->title) - 1);
    }
    chat_engine_save_state_now();
    free(a);
    vTaskDelete(NULL);
}

/* POST /text  {title, text} */
static esp_err_t text_handler(httpd_req_t *req)
{
    char buf[2048];
    const char *title = "";
    const char *text = "";

    int total = 0;
    size_t want = req->content_len;
    if (want == 0) want = 256;
    if (want > sizeof(buf) - 1) want = sizeof(buf) - 1;
    while (total < (int)want) {
        int r = httpd_req_recv(req, buf + total, want - total);
        if (r <= 0) break;
        total += r;
    }
    if (total <= 0) {
        httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "no body");
        return ESP_FAIL;
    }
    buf[total] = '\0';

    cJSON *root = cJSON_Parse(buf);
    if (root) {
        cJSON *n;
        if ((n = cJSON_GetObjectItem(root, "title")) && n->valuestring) title = n->valuestring;
        if ((n = cJSON_GetObjectItem(root, "text")) && n->valuestring) text = n->valuestring;
    }

    httpd_resp_set_type(req, "application/json");
    const char *ok = "{\"status\":\"ok\"}";
    httpd_resp_send(req, ok, HTTPD_RESP_USE_STRLEN);
    if (root) cJSON_Delete(root);

    /* Push message on a dedicated task to keep the httpd stack safe. */
    if (text[0]) {
        push_arg_t *a = calloc(1, sizeof(push_arg_t));
        if (a) {
            strncpy(a->title, title, sizeof(a->title) - 1);
            strncpy(a->text, text, sizeof(a->text) - 1);
            xTaskCreate(push_task, "push_msg", 12288, a, 5, NULL);
        }
    }
    return ESP_OK;
}

/* OPTIONS CORS for POST /text */
static esp_err_t options_handler(httpd_req_t *req)
{
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    httpd_resp_set_hdr(req, "Access-Control-Allow-Methods", "POST, OPTIONS");
    httpd_resp_set_hdr(req, "Access-Control-Allow-Headers", "Content-Type");
    httpd_resp_set_status(req, "204 No Content");
    httpd_resp_send(req, "", 0);
    return ESP_OK;
}

esp_err_t http_server_start(void)
{
    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.server_port = HTTP_PORT;
    config.max_uri_handlers = 8;
    config.lru_purge_enable = true;
    config.stack_size = 16384;   /* handler uses 2KB buffer + cJSON (recursive print); needs large stack */
    config.max_open_sockets = 6;

    httpd_handle_t server = NULL;
    if (httpd_start(&server, &config) != ESP_OK) {
        ESP_LOGE(TAG, "httpd_start failed");
        return ESP_FAIL;
    }

    httpd_uri_t uri = {0};
    uri.uri = "/status";   uri.method = HTTP_GET;  uri.handler = status_handler; uri.user_ctx = NULL;
    httpd_register_uri_handler(server, &uri);
    uri.uri = "/text";     uri.method = HTTP_POST; uri.handler = text_handler;      uri.user_ctx = NULL;
    httpd_register_uri_handler(server, &uri);
    uri.uri = "/text";     uri.method = HTTP_OPTIONS; uri.handler = options_handler; uri.user_ctx = NULL;
    httpd_register_uri_handler(server, &uri);

    ESP_LOGI(TAG, "HTTP server on port %d", HTTP_PORT);
    return ESP_OK;
}
