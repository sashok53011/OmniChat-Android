/*
 * web_search.c - DuckDuckGo HTML web search (no API key)
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "esp_log.h"
#include "esp_http_client.h"
#include "web_search.h"
#include "app_config.h"

static const char *TAG = "web_search";

/* URL-encode a query string into a malloc'd buffer. */
static char *url_encode(const char *s)
{
    size_t len = strlen(s);
    char *out = malloc(len * 3 + 1);
    if (!out) return NULL;
    int o = 0;
    for (size_t i = 0; i < len; i++) {
        unsigned char c = (unsigned char)s[i];
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
            (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
            out[o++] = (char)c;
        } else {
            snprintf(out + o, 4, "%%%02X", c);
            o += 3;
        }
    }
    out[o] = '\0';
    return out;
}

/* Simple tag-stripper that copies while skipping <...> */
static void strip_tags(const char *in, char *out, int max_out)
{
    int j = 0;
    int in_tag = 0;
    for (int i = 0; in[i] && j < max_out - 1; i++) {
        if (in[i] == '<') { in_tag = 1; continue; }
        if (in[i] == '>') { in_tag = 0; continue; }
        if (!in_tag) out[j++] = in[i];
    }
    out[j] = '\0';
}

/* Collapse multiple whitespace/newlines into single spaces. */
static void collapse_ws(char *s)
{
    int j = 0;
    int last_space = 0;
    for (int i = 0; s[i]; i++) {
        if (s[i] == '\n' || s[i] == '\r' || s[i] == '\t' || s[i] == ' ') {
            if (!last_space) { s[j++] = ' '; last_space = 1; }
        } else {
            s[j++] = s[i];
            last_space = 0;
        }
    }
    s[j] = '\0';
}

char *web_search_query(const char *query)
{
    char *encoded = url_encode(query);
    if (!encoded) return NULL;

    char url[320];
    snprintf(url, sizeof(url),
             "https://html.duckduckgo.com/html/?q=%s", encoded);
    free(encoded);

    esp_http_client_config_t cfg = {
        .url = url,
        .timeout_ms = 15000,
        .user_agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    };
    esp_http_client_handle_t client = esp_http_client_init(&cfg);
    if (!client) return NULL;

    esp_err_t err = esp_http_client_perform(client);
    int status = esp_http_client_get_status_code(client);
    if (err != ESP_OK || status != 200) {
        ESP_LOGE(TAG, "search HTTP %d (%s)", status, esp_err_to_name(err));
        esp_http_client_cleanup(client);
        return NULL;
    }

    char *html = malloc(8192 + 1);
    if (!html) { esp_http_client_cleanup(client); return NULL; }
    int got = (int)esp_http_client_read_response(client, html, 8192);
    if (got <= 0) got = 0;
    html[got] = '\0';
    esp_http_client_cleanup(client);

    /* Results container: 4 items max */
    char *result = malloc(4096 + 1);
    if (!result) { free(html); return NULL; }
    int r = 0;
    result[0] = '\0';

    const char *p = html;
    int found = 0;
    int collected = 0;

    while ((p = strstr(p, "<div class=\"result__body\">")) != NULL && collected < 4) {
        p += strlen("<div class=\"result__body\">");
        const char *end = strstr(p, "<div class=\"result__body\">");
        const char *section_end = end ? end : p + 1200;
        int section_len = (int)(section_end - p);
        if (section_len > 1200) section_len = 1200;

        char section[1201];
        memcpy(section, p, section_len);
        section[section_len] = '\0';

        char title[512] = "";
        char snippet[512] = "";

        const char *tl = strstr(section, "<a class=\"result__url\"");
        if (tl) {
            const char *gt = strchr(tl, '>');
            const char *lt = strstr(gt ? gt + 1 : section, "</a>");
            if (gt && lt) {
                strip_tags(gt + 1, title, sizeof(title));
            }
        }
        const char *sl = strstr(section, "<a class=\"result__snippet\"");
        if (sl) {
            const char *gt = strchr(sl, '>');
            const char *lt = strstr(gt ? gt + 1 : section, "</a>");
            if (gt && lt) {
                strip_tags(gt + 1, snippet, sizeof(snippet));
            }
        }
        collapse_ws(title);
        collapse_ws(snippet);

        if (title[0]) {
            int n = snprintf(result + r, 4096 - r,
                             "**%s**\n%s\n\n", title, snippet);
            if (n > 0) r += n;
            collected++;
        }
        found = 1;
        p += sizeof("<div class=\"result__body\">") - 1;
    }

    free(html);
    if (!found || r == 0) {
        free(result);
        return NULL;
    }
    return result;
}
