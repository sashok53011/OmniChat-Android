/*
 * WiFi Station helper for ESP32-P4
 */
#include "app_wifi.h"
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "esp_wifi.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "lwip/err.h"
#include "lwip/sys.h"
#include "lwip/sockets.h"
#include "lwip/inet.h"
#include "esp_sntp.h"

static const char *TAG = "app_wifi";
static EventGroupHandle_t s_wifi_event_group;
static bool s_connected = false;
static int s_retry_count = 0;

#define WIFI_CONNECTED_BIT BIT0
#define WIFI_FAIL_BIT      BIT1
#define MAX_RETRY          10

static void event_handler(void *arg, esp_event_base_t event_base,
                          int32_t event_id, void *event_data)
{
    if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_START) {
        s_retry_count = 0;
        esp_wifi_connect();
    } else if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_DISCONNECTED) {
        s_connected = false;
        if (s_retry_count < MAX_RETRY) {
            s_retry_count++;
            /* Exponential backoff: 1s, 2s, 4s, 8s, ... capped at 30s */
            int delay_ms = (1 << (s_retry_count - 1)) * 1000;
            if (delay_ms > 30000) delay_ms = 30000;
            ESP_LOGW(TAG, "WiFi disconnected, retry %d/%d in %dms...", s_retry_count, MAX_RETRY, delay_ms);
            vTaskDelay(pdMS_TO_TICKS(delay_ms));
            esp_wifi_connect();
        } else {
            ESP_LOGE(TAG, "WiFi: max retries (%d) reached", MAX_RETRY);
            xEventGroupSetBits(s_wifi_event_group, WIFI_FAIL_BIT);
        }
    } else if (event_base == IP_EVENT && event_id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *event = (ip_event_got_ip_t *)event_data;
        ESP_LOGI(TAG, "Connected! IP: " IPSTR, IP2STR(&event->ip_info.ip));
        s_connected = true;
        s_retry_count = 0;
        xEventGroupSetBits(s_wifi_event_group, WIFI_CONNECTED_BIT);

        /* Start SNTP so mbedtls can validate TLS certificates (time must not
         * be 1970 or cert notBefore/notAfter checks will fail with -0x3000). */
        if (esp_sntp_enabled() != true) {
            sntp_setoperatingmode(SNTP_OPMODE_POLL);
            sntp_setservername(0, "pool.ntp.org");
            sntp_setservername(1, "time.nist.gov");
            sntp_init();
            ESP_LOGI(TAG, "SNTP started (polling pool.ntp.org)");
        }
    }
}

esp_err_t app_wifi_init(const char *ssid, const char *password)
{
    // Initialize NVS
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES ||
        ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    s_wifi_event_group = xEventGroupCreate();

    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_create_default_wifi_sta();

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));

    ESP_ERROR_CHECK(esp_event_handler_instance_register(
        WIFI_EVENT, ESP_EVENT_ANY_ID, &event_handler, NULL, NULL));
    ESP_ERROR_CHECK(esp_event_handler_instance_register(
        IP_EVENT, IP_EVENT_STA_GOT_IP, &event_handler, NULL, NULL));

    wifi_config_t wifi_config = {
        .sta = {
            .ssid = "",
            .password = "",
            .threshold.authmode = WIFI_AUTH_WPA2_PSK,
        },
    };
    strncpy((char *)wifi_config.sta.ssid, ssid, sizeof(wifi_config.sta.ssid) - 1);
    strncpy((char *)wifi_config.sta.password, password, sizeof(wifi_config.sta.password) - 1);

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &wifi_config));
    ESP_ERROR_CHECK(esp_wifi_start());

    ESP_LOGI(TAG, "Connecting to WiFi: %s ... (background)", ssid);

    /* Non-blocking: return immediately so the task watchdog is not triggered.
     * Connection completes asynchronously via s_connected / event handler. */
    return ESP_OK;
}

bool app_wifi_is_connected(void)
{
    return s_connected;
}

/* Lightweight internet check: TCP connect to a well-known public IP:port.
 * Returns true if reachable within the connect timeout. */
bool app_wifi_internet_ok(void)
{
    if (!s_connected) return false;
    /* Use lwIP socket to probe a stable public endpoint (Cloudflare DNS). */
    struct sockaddr_in sa;
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_port = htons(80);
    if (inet_pton(AF_INET, "1.1.1.1", &sa.sin_addr) != 1) return false;

    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return false;
    struct timeval tv = { .tv_sec = 3, .tv_usec = 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    int rc = connect(fd, (struct sockaddr *)&sa, sizeof(sa));
    close(fd);
    return rc == 0;
}
