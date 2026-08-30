/*
 * main.c - OmniChat-P4 firmware entry point.
 *
 * Boot order:
 *   BSP display -> engine -> start LVGL task -> (in LVGL task) UI + WiFi + HTTP.
 * UI is created inside the LVGL task so lv_timer_handler is never blocked by
 * cross-thread LVGL calls.
 */
#include <stdio.h>
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "lvgl.h"
#include "bsp/esp32_p4_wifi6_touch_lcd_4b.h"
#include "chat_engine.h"
#include "app_wifi.h"
#include "http_server.h"
#include "app_config.h"
#include "ui_main.h"

static const char *TAG = "omni";

static lv_display_t *s_disp = NULL;

/* Called from the engine worker thread -> marshal to LVGL loop. */
static void on_engine_change(void)
{
    ui_refresh();
}

/* LVGL task: init UI (on this thread), then tick + timer handler. */
static void lvgl_task(void *arg)
{
    (void)arg;

    /* Build the UI on the same thread that owns lv_timer_handler. */
    ESP_LOGI(TAG, "ui_init in lvgl task");
    ui_init(s_disp);
    ESP_LOGI(TAG, "ui_init done");

    /* WiFi (non-blocking) + HTTP server. */
    const app_state_t *st = chat_engine_state();
    esp_err_t wifi_ret = app_wifi_init(st->wifi_ssid, st->wifi_password);
    ESP_LOGI(TAG, "wifi_init returned: %d", (int)wifi_ret);
    http_server_start();
    ESP_LOGI(TAG, "startup complete. Active provider: %s",
             st->active_provider_id ? st->active_provider_id : "-");

    while (1) {
        ui_pump();
        uint32_t time_till_next = lv_timer_handler();
        /* Yield to the scheduler: LVGL tells us when the next refresh is due.
         * Clamp to a minimum so we never busy-spin and peg the CPU. */
        if (time_till_next < 5 || time_till_next >= 0xFFFFFFFFu) time_till_next = 5;
        vTaskDelay(pdMS_TO_TICKS(time_till_next));
    }
}

void app_main(void)
{
    ESP_LOGI(TAG, "OmniChat-P4 starting");

    /* 1. BSP display. */
    s_disp = bsp_display_start();
    if (!s_disp) {
        ESP_LOGE(TAG, "display init failed");
        return;
    }
    bsp_display_backlight_on();

    /* 2. Engine (loads state + audio codec). */
    chat_engine_init();
    chat_engine_set_listener(on_engine_change);

    /* 3. Start the LVGL task (it carries out UI + WiFi + HTTP). */
    xTaskCreate(lvgl_task, "lvgl", 40 * 1024, NULL, 5, NULL);

    /* main task just sleeps. */
    while (1) {
        vTaskDelay(pdMS_TO_TICKS(10000));
    }
}
