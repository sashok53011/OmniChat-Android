/*
 * main.c - OmniChat-P4 firmware entry point.
 *
 * LVGL loop ownership: esp_lv_adapter (started inside bsp_display_start_with_config)
 * owns lv_timer_handler() in its dedicated worker task. Nothing else may ever
 * call lv_timer_handler() (double loops race the LVGL state machine, peg a core
 * at 100% and wreck input/scroll handling). LVGL objects are touched only:
 *   - inside the adapter worker (ui_pump_cb lv_timer), or
 *   - under bsp_display_lock()/bsp_display_unlock() during initialization.
 *
 * Task lifecycle: one-shot init task does UI + WiFi + HTTP and deletes itself;
 * app_main deletes itself too - no busy-idle loops.
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

/* One-time init task: build the UI under the LVGL lock (so all lv_* calls run
 * while the adapter worker is blocked on the same lock), then start WiFi + HTTP
 * and leave. It must never call lv_timer_handler(). */
static void init_task(void *arg)
{
    (void)arg;

    ESP_LOGI(TAG, "ui_init (under LVGL lock)");
    bsp_display_lock(-1);
    ui_init(s_disp);
    bsp_display_unlock();
    ESP_LOGI(TAG, "ui_init done");

    /* WiFi (non-blocking) + HTTP server. */
    const app_state_t *st = chat_engine_state();
    esp_err_t wifi_ret = app_wifi_init(st->wifi_ssid, st->wifi_password);
    ESP_LOGI(TAG, "wifi_init returned: %d", (int)wifi_ret);
    http_server_start();
    ESP_LOGI(TAG, "startup complete. Active provider: %s",
             st->active_provider_id ? st->active_provider_id : "-");

    vTaskDelete(NULL);
}

void app_main(void)
{
    ESP_LOGI(TAG, "OmniChat-P4 starting");

    /* 1. BSP display. The esp_lv_adapter worker task starts here and owns
     * lv_timer_handler() from this point on. Give it a roomier stack in PSRAM:
     * our UI work (bubble/panel rebuilds) runs inside lv_timer_handler. */
    bsp_display_cfg_t disp_cfg = {
        .lv_adapter_cfg = ESP_LV_ADAPTER_DEFAULT_CONFIG(),
        .rotation = ESP_LV_ADAPTER_ROTATE_0,
        .tear_avoid_mode = ESP_LV_ADAPTER_TEAR_AVOID_MODE_TRIPLE_PARTIAL,
        .touch_flags = {
            .swap_xy = 0,
            .mirror_x = 0,
            .mirror_y = 0,
        },
    };
    disp_cfg.lv_adapter_cfg.task_stack_size = 32 * 1024;
    disp_cfg.lv_adapter_cfg.stack_in_psram = true;

    s_disp = bsp_display_start_with_config(&disp_cfg);
    if (!s_disp) {
        ESP_LOGE(TAG, "display init failed");
        return;
    }
    bsp_display_backlight_on();

    /* 2. Engine (loads state + audio codec). */
    chat_engine_init();
    chat_engine_set_listener(on_engine_change);

    /* 3. One-shot init task (UI + WiFi + HTTP). */
    xTaskCreate(init_task, "init", 32 * 1024, NULL, 5, NULL);

    /* The main task has nothing left to do - free it instead of spinning. */
    vTaskDelete(NULL);
}