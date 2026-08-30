/*
 * WiFi Station helper for ESP32-P4
 */
#pragma once

#include "esp_err.h"
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Initialize WiFi in STA mode and connect
// Blocks until connected or timeout
esp_err_t app_wifi_init(const char *ssid, const char *password);

// Check if WiFi is connected
bool app_wifi_is_connected(void);

// Check if the internet is reachable (TCP probe to a public host)
bool app_wifi_internet_ok(void);

#ifdef __cplusplus
}
#endif
