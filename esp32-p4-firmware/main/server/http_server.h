/*
 * http_server.h - exposes OmniChat-compatible endpoints so the Android app can push
 * AI responses to this device (matches the OmniChat ESP32 HTTP path).
 */
#ifndef HTTP_SERVER_H
#define HTTP_SERVER_H

#include "esp_err.h"

/* Start the HTTP server on port 80. Returns ESP_OK. */
esp_err_t http_server_start(void);

#endif
