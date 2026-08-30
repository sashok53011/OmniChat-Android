#ifndef CONFIG_H
#define CONFIG_H

// WiFi Settings
#define WIFI_SSID "YOUR_WIFI_SSID"
#define WIFI_PASSWORD "YOUR_WIFI_PASSWORD"
#define WIFI_TIMEOUT_MS 10000

// Device Settings
#define DEVICE_NAME "OmniChat-CYD"
#define HTTP_PORT 80

// Display Settings
#define SCREEN_WIDTH 320
#define SCREEN_HEIGHT 240
#define JPEG_BUF_SIZE (SCREEN_WIDTH * SCREEN_HEIGHT * 2)

// Status display
#define STATUS_Y 0
#define STATUS_HEIGHT 20

#endif
