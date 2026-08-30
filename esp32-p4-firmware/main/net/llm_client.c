/*
 * llm_client.c - OpenAI-compatible HTTPS client for OmniChat-P4
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "esp_log.h"
#include "esp_http_client.h"
#include "esp_crt_bundle.h"
#include "esp_tls.h"
#include "cJSON.h"
#include "llm_client.h"
#include "app_config.h"
#include "lwip/sockets.h"
#include "lwip/netdb.h"

static const char *TAG = "llm_client";

/* Parse "scheme://host:port/" -> host string. Returns 0 on success. */
static int parse_host_port(const char *url, char *host, int hlen, int *port)
{
    const char *p = strstr(url, "://");
    p = p ? p + 3 : url;
    const char *colon = strchr(p, ':');
    const char *slash = strchr(p, '/');
    const char *end = colon ? colon : (slash ? slash : p + strlen(p));
    int hl = (int)(end - p);
    if (hl > hlen - 1) hl = hlen - 1;
    memcpy(host, p, hl); host[hl] = '\0';
    if (colon) {
        *port = atoi(colon + 1);
    } else {
        *port = (strstr(url, "https://") == url) ? 443 : 80;
    }
    return 0;
}

/* Diagnostic: raw TCP connect to isolate DNS/routing/port failures. */
static void probe_connect(const char *url)
{
    char host[128]; int port = 0;
    parse_host_port(url, host, sizeof(host), &port);
    struct sockaddr_in sa; memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_port = htons(port);
    /* Try numeric IPv4 first, else resolve. */
    sa.sin_addr.s_addr = inet_addr(host);
    if (sa.sin_addr.s_addr == INADDR_NONE) {
        struct hostent *he = gethostbyname(host);
        if (!he) {
            ESP_LOGE(TAG, "probe: DNS failed for '%s'", host);
            return;
        }
        memcpy(&sa.sin_addr, he->h_addr, 4);
    }
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) { ESP_LOGE(TAG, "probe: socket() failed errno=%d", errno); return; }
    int rc = connect(fd, (struct sockaddr *)&sa, sizeof(sa));
    ESP_LOGI(TAG, "probe: connect %s:%d -> %s (errno=%d)", host, port,
             rc == 0 ? "OK" : "FAIL", errno);
    close(fd);
}

/*
 * Raw HTTP/1.1 POST via lwIP sockets. Avoids esp_http_client which hangs on
 * chunked responses over the ESP-Hosted (C6) stack. Reads the full response
 * into out_buf (heap, caller frees) and returns HTTP status. Only for plain http://.
 */
static int http_post_raw(const char *url, const char *body, const char *auth,
                         char **out_buf, int *out_len)
{
    char host[128]; int port = 0;
    parse_host_port(url, host, sizeof(host), &port);

    struct sockaddr_in sa; memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_port = htons(port);
    sa.sin_addr.s_addr = inet_addr(host);
    if (sa.sin_addr.s_addr == INADDR_NONE) {
        struct hostent *he = gethostbyname(host);
        if (!he) { ESP_LOGE(TAG, "http_raw: DNS failed for %s", host); return -1; }
        memcpy(&sa.sin_addr, he->h_addr, 4);
    }

    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) { ESP_LOGE(TAG, "http_raw: socket failed"); return -1; }
    if (connect(fd, (struct sockaddr *)&sa, sizeof(sa)) != 0) {
        ESP_LOGE(TAG, "http_raw: connect %s:%d failed errno=%d", host, port, errno);
        close(fd);
        return -1;
    }

    /* Build request: extract path from URL (scheme://host:port/PATH). */
    const char *s = strstr(url, "://");
    s = s ? s + 3 : url;
    const char *path = strchr(s, '/');
    const char *pathEnd = path ? (path + strlen(path)) : "";
    /* Build full request (header + body) into one buffer and send once. */
    char req[1600];
    int body_len = (int)strlen(body);
    int hlen = snprintf(req, sizeof(req),
        "POST %s HTTP/1.1\r\nHost: %s:%d\r\nContent-Type: application/json\r\n"
        "Accept: application/json\r\nConnection: close\r\nContent-Length: %d\r\n",
        path ? path : "/", host, port, body_len);
    if (auth && auth[0]) {
        hlen += snprintf(req + hlen, sizeof(req) - hlen, "Authorization: %s", auth);
    }
    hlen += snprintf(req + hlen, sizeof(req) - hlen, "\r\n");
    (void)pathEnd;
    if (hlen <= 0 || hlen >= (int)sizeof(req) - body_len - 1) { close(fd); return -1; }
    memcpy(req + hlen, body, body_len + 1);
    int total_req = hlen + body_len;

    if (send(fd, req, total_req, 0) < 0) { ESP_LOGE(TAG, "http_raw: send failed"); close(fd); return -1; }
    ESP_LOGI(TAG, "http_raw: sent %d bytes to %s:%d", total_req, host, port);

    /* Read until close (with a bounded timeout so we never block forever). */
    int cap = APP_MAX_RESPONSE;
    char *buf = malloc(cap + 1);
    if (!buf) { close(fd); return -1; }
    int total = 0;
    struct timeval tv = { .tv_sec = 15, .tv_usec = 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    while (total < cap) {
        int r = recv(fd, buf + total, cap - total, 0);
        if (r < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                ESP_LOGI(TAG, "http_raw: read timeout, got %d bytes", total);
                break; /* read timeout */
            }
            ESP_LOGE(TAG, "http_raw: recv errno=%d", errno);
            break;
        }
        if (r == 0) { ESP_LOGI(TAG, "http_raw: connection closed, got %d bytes", total); break; }
        total += r;
    }
    close(fd);
    buf[total] = '\0';
    *out_buf = buf;
    *out_len = total;

    /* Parse status line. */
    int status = 0;
    if (sscanf(buf, "HTTP/%*s %d", &status) != 1) status = 0;
    return status;
}

/*
 * Raw HTTPS POST via esp_tls. We force IPv4 resolution (addr_family = AF_INET)
 * to avoid the unstable AAAA/getaddrinfo path over ESP-Hosted, and attach the
 * system CA bundle for server verification. Reads the full response into out_buf.
 */
static int https_post_raw(const char *url, const char *body, const char *auth,
                          char **out_buf, int *out_len)
{
    char host[128]; int port = 0;
    parse_host_port(url, host, sizeof(host), &port);

    esp_tls_cfg_t cfg = {0};
    cfg.crt_bundle_attach = esp_crt_bundle_attach;
    cfg.timeout_ms = APP_HTTP_TIMEOUT_MS;
    cfg.addr_family = ESP_TLS_AF_INET;   /* force IPv4 */

    esp_tls_t *tls = esp_tls_init();
    if (!tls) { ESP_LOGE(TAG, "https_raw: tls init failed"); return -1; }

    if (esp_tls_conn_new_sync(host, strlen(host), port, &cfg, tls) != 1) {
        ESP_LOGE(TAG, "https_raw: tls connect %s:%d failed", host, port);
        esp_tls_conn_destroy(tls);
        return -1;
    }

    /* Build request (path from URL). */
    const char *s = strstr(url, "://"); s = s ? s + 3 : url;
    const char *path = strchr(s, '/');
    if (!path) path = "/";
    char req[1600];
    int body_len = (int)strlen(body);
    int n = snprintf(req, sizeof(req),
        "POST %s HTTP/1.1\r\nHost: %s:%d\r\nContent-Type: application/json\r\n"
        "Accept: application/json\r\nConnection: close\r\nContent-Length: %d\r\n",
        path, host, port, body_len);
    if (auth && auth[0]) n += snprintf(req + n, sizeof(req) - n, "Authorization: %s", auth);
    n += snprintf(req + n, sizeof(req) - n, "\r\n");
    if (n <= 0 || n >= (int)sizeof(req) - body_len - 1) { esp_tls_conn_destroy(tls); return -1; }
    memcpy(req + n, body, body_len + 1);
    int total_req = n + body_len;

    int written = esp_tls_conn_write(tls, req, total_req);
    if (written < 0) { ESP_LOGE(TAG, "https_raw: write failed"); esp_tls_conn_destroy(tls); return -1; }

    int cap = APP_MAX_RESPONSE;
    char *buf = malloc(cap + 1);
    if (!buf) { esp_tls_conn_destroy(tls); return -1; }
    int total = 0;
    while (total < cap) {
        int r = esp_tls_conn_read(tls, buf + total, cap - total);
        if (r < 0) { ESP_LOGE(TAG, "https_raw: read errno"); break; }
        if (r == 0) break;
        total += r;
    }
    esp_tls_conn_destroy(tls);
    buf[total] = '\0';
    *out_buf = buf;
    *out_len = total;
    int status = 0;
    if (sscanf(buf, "HTTP/%*s %d", &status) != 1) status = 0;
    return status;
}

/* Build the chat/completions request body (malloc'd). */
static void build_body(const char *model, const chat_message_t *messages,
                       int message_count, const char *system_prompt,
                       char **out, int *out_len)
{
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "model", model);
    cJSON *arr = cJSON_CreateArray();

    if (system_prompt && system_prompt[0]) {
        cJSON *m = cJSON_CreateObject();
        cJSON_AddStringToObject(m, "role", "system");
        cJSON_AddStringToObject(m, "content", system_prompt);
        cJSON_AddItemToArray(arr, m);
    }

    for (int i = 0; i < message_count; i++) {
        if (messages[i].role == MSG_ROLE_SYSTEM) continue;
        const char *role = (messages[i].role == MSG_ROLE_USER) ? "user" : "assistant";
        cJSON *m = cJSON_CreateObject();
        cJSON_AddStringToObject(m, "role", role);
        cJSON_AddStringToObject(m, "content", messages[i].text ? messages[i].text : "");
        cJSON_AddItemToArray(arr, m);
    }

    cJSON_AddItemToObject(root, "messages", arr);
    cJSON_AddNumberToObject(root, "temperature", 0.7);

    char *body = cJSON_Print(root);
    cJSON_Delete(root);
    *out = body;
    *out_len = body ? (int)strlen(body) : 0;
}

int llm_openai_compatible(const ai_provider_t *provider,
                          const chat_message_t *messages, int message_count,
                          const char *system_prompt, llm_result_t *out)
{
    memset(out, 0, sizeof(*out));

    char url[256];
    /* Normalize: if base_url ends with '/' append "chat/completions". */
    const char *base = provider->base_url[0] ? provider->base_url : "";
    if (strstr(base, "chat/completions")) {
        snprintf(url, sizeof(url), "%s", base);
    } else {
        snprintf(url, sizeof(url), "%schat/completions", base);
    }

    char *body = NULL;
    int body_len = 0;
    build_body(provider->model[0] ? provider->model : "gpt-4o-mini",
               messages, message_count, system_prompt, &body, &body_len);
    if (!body) {
        snprintf(out->error, sizeof(out->error), "out of memory");
        return -1;
    }

    char auth[220];
    if (provider->api_key[0]) {
        snprintf(auth, sizeof(auth), "Bearer %s", provider->api_key);
    } else {
        auth[0] = '\0';
    }
    ESP_LOGI(TAG, "calling provider '%s': %s (model=%s)", provider->id, url, provider->model);

    /* Plain HTTP (LAN Ollama) -> raw socket (avoids esp_http_client chunked hang).
     * HTTPS -> raw TLS. */
    int is_http = (strncmp(url, "http://", 7) == 0);
    if (is_http) {
        char *raw = NULL; int raw_len = 0;
        int status = http_post_raw(url, body, auth[0] ? auth : NULL, &raw, &raw_len);
        if (raw) {
            if (status != 200) {
                snprintf(out->error, sizeof(out->error), "HTTP %d", status);
                free(raw); free(body);
                return -1;
            }
            /* Skip HTTP headers: body starts after "\r\n\r\n". */
            char *hdr_end = strstr(raw, "\r\n\r\n");
            char *body_start = hdr_end ? hdr_end + 4 : raw;
            int body_len2 = raw_len - (int)(body_start - raw);
            if (body_len2 < 0) body_len2 = 0;
            char *json = (char *)malloc(body_len2 + 1);
            if (!json) { free(raw); free(body); return -1; }
            memcpy(json, body_start, body_len2);
            json[body_len2] = '\0';

            /* De-chunk if Transfer-Encoding: chunked (bounds-checked, safe). */
            if (strstr(raw, "chunked")) {
                char *out = (char *)malloc(body_len2 + 1);
                if (out) {
                    char *inp = json;
                    int opos = 0;
                    while (*inp && opos < body_len2) {
                        char *crlf = strstr(inp, "\r\n");
                        if (!crlf) break;
                        size_t chunk = (size_t)strtoul(inp, NULL, 16);
                        if (chunk == 0) break;
                        char *data = crlf + 2;
                        if (opos + (int)chunk > body_len2) chunk = body_len2 - opos;
                        memcpy(out + opos, data, chunk);
                        opos += (int)chunk;
                        inp = data + chunk + 2;
                    }
                    out[opos] = '\0';
                    free(json);
                    json = out;
                }
            }

            free(raw);
            free(body);
           
            out->body = json;     /* caller owns and frees via llm_result_free */
            out->status = status;
            return 0;
        }
        ESP_LOGE(TAG, "http_post_raw failed for %s", url);
        free(body);
        snprintf(out->error, sizeof(out->error), "network: raw http failed");
        return -1;
    }

    /* HTTPS -> raw TLS via esp_tls (IPv4 forced, avoids unstable getaddrinfo). */
    char *raw = NULL; int raw_len = 0;
    int status = https_post_raw(url, body, auth[0] ? auth : NULL, &raw, &raw_len);
    if (raw) {
        if (status != 200) {
            snprintf(out->error, sizeof(out->error), "HTTP %d", status);
            free(raw); free(body);
            return -1;
        }
        /* Skip headers. */
        char *hdr_end = strstr(raw, "\r\n\r\n");
        char *body_start = hdr_end ? hdr_end + 4 : raw;
        int body_len2 = raw_len - (int)(body_start - raw);
        if (body_len2 < 0) body_len2 = 0;
        char *json = (char *)malloc(body_len2 + 1);
        if (!json) { free(raw); free(body); return -1; }
        memcpy(json, body_start, body_len2);
        json[body_len2] = '\0';

        /* De-chunk if Transfer-Encoding: chunked (bounds-checked). */
        if (strstr(raw, "chunked")) {
            char *out = (char *)malloc(body_len2 + 1);
            if (out) {
                char *inp = json; int opos = 0;
                while (*inp && opos < body_len2) {
                    char *crlf = strstr(inp, "\r\n"); if (!crlf) break;
                    size_t chunk = (size_t)strtoul(inp, NULL, 16);
                    if (chunk == 0) break;
                    char *data = crlf + 2;
                    if (opos + (int)chunk > body_len2) chunk = body_len2 - opos;
                    memcpy(out + opos, data, chunk);
                    opos += (int)chunk;
                    inp = data + chunk + 2;
                }
                out[opos] = '\0';
                free(json); json = out;
            }
        }

        free(raw);
        free(body);
       
        out->body = json;
        out->status = status;
        return 0;
    }
    ESP_LOGE(TAG, "https_post_raw failed for %s", url);
    free(body);
    snprintf(out->error, sizeof(out->error), "network: raw https failed");
    return -1;
}

char *llm_parse_choices(const char *json_body)
{
    cJSON *root = cJSON_Parse(json_body);
    if (!root) return NULL;
    cJSON *choices = cJSON_GetObjectItem(root, "choices");
    const char *text = NULL;
    if (cJSON_IsArray(choices) && cJSON_GetArraySize(choices) > 0) {
        cJSON *first = cJSON_GetArrayItem(choices, 0);
        cJSON *msg = cJSON_GetObjectItem(first, "message");
        cJSON *content = cJSON_GetObjectItem(msg, "content");
        if (content && content->valuestring) {
            text = content->valuestring;
        }
    }
    char *out = text ? strdup(text) : NULL;
    cJSON_Delete(root);
    return out;
}

void llm_result_free(llm_result_t *r)
{
    if (r->body) { free(r->body); r->body = NULL; }
}
