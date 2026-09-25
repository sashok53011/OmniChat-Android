/*
 * ui_main.c - top-level OmniChat-P4 UI: navigation + screen switching.
 *
 * LVGL runs in the esp_lv_adapter worker task (main.c never calls
 * lv_timer_handler). The engine listener runs on a worker thread; we marshal
 * refreshes via an event group and apply them inside ui_pump(), which is driven
 * by an LVGL timer (lv_timer_create) that fires within lv_timer_handler.
 */
#include <stdlib.h>
#include <string.h>
#include "esp_log.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "lvgl.h"
#include "esp_lv_adapter.h"
#include "chat_engine.h"
#include "app_config.h"
#include "ui_main.h"
#include "ui_chat.h"
#include "ui_settings.h"

static const char *TAG = "ui_main";

#define UI_EVENT_REFRESH BIT0

static lv_obj_t *s_screen_home = NULL;
static lv_obj_t *s_screen_chat = NULL;
static lv_obj_t *s_screen_settings = NULL;
static const char *s_current = "chat";
static EventGroupHandle_t s_ui_events = NULL;
static lv_display_t *s_disp = NULL;

static void on_nav_home(lv_event_t *e) { (void)e; s_current = "home"; lv_screen_load(s_screen_home); }
static void on_nav_chat(lv_event_t *e) { (void)e; s_current = "chat"; lv_screen_load(s_screen_chat); }
static void on_nav_settings(lv_event_t *e) { (void)e; s_current = "settings"; lv_screen_load(s_screen_settings); }

void ui_go_screen(const char *screen)
{
    s_current = screen;
    if (strcmp(screen, "home") == 0 && s_screen_home) lv_screen_load(s_screen_home);
    else if (strcmp(screen, "chat") == 0 && s_screen_chat) lv_screen_load(s_screen_chat);
    else if (strcmp(screen, "settings") == 0 && s_screen_settings) lv_screen_load(s_screen_settings);
}

void ui_refresh(void)
{
    if (s_ui_events) xEventGroupSetBits(s_ui_events, UI_EVENT_REFRESH);
}

/* LVGL timer callback (created in ui_init). Called by esp_lv_adapter's worker
 * inside lv_timer_handler(), i.e. from the LVGL thread - safe to touch widgets.
 * Runs at 20 ms; ui_pump() keeps the heavier WiFi status update at 1 s. */
static void ui_pump_cb(lv_timer_t *timer)
{
    (void)timer;
    ui_pump();
}

/* Add a bottom navigation bar to a screen. */
static void add_navbar(lv_obj_t *scr)
{
    lv_obj_t *nb = lv_obj_create(scr);
    lv_obj_set_size(nb, lv_pct(100), 52);
    lv_obj_align(nb, LV_ALIGN_BOTTOM_MID, 0, 0);
    lv_obj_set_style_bg_color(nb, lv_color_make(0x16, 0x1B, 0x22), 0);
    lv_obj_set_style_border_width(nb, 0, 0);
    lv_obj_clear_flag(nb, LV_OBJ_FLAG_SCROLLABLE);

    struct { const char *label; const char *target; void (*fn)(lv_event_t *); } tabs[3] = {
        { "Home",   "home",     on_nav_home },
        { "Chat",   "chat",     on_nav_chat },
        { "Settings","settings", on_nav_settings },
    };

    for (int i = 0; i < 3; i++) {
        lv_obj_t *btn = lv_button_create(nb);
        lv_obj_set_pos(btn, 20 + i * 130, 6);
        lv_obj_set_size(btn, 110, 40);
        int is_sel = (strcmp(s_current, tabs[i].target) == 0);
        lv_obj_set_style_bg_color(btn,
            is_sel ? lv_color_make(0x58, 0xA6, 0xFF) : lv_color_make(0x26, 0x2C, 0x33), 0);
        lv_obj_add_event_cb(btn, tabs[i].fn, LV_EVENT_CLICKED, NULL);
        lv_obj_t *lab = lv_label_create(btn);
        lv_label_set_text(lab, tabs[i].label);
        lv_obj_center(lab);
        lv_obj_set_style_text_color(lab, lv_color_make(0xE6, 0xED, 0xF3), 0);
    }
}

void ui_init(lv_display_t *disp)
{
    s_disp = disp;
    s_ui_events = xEventGroupCreate();

    /* Chat screen (default). */
    ESP_LOGI(TAG, "ui_init: chat");
    s_screen_chat = lv_obj_create(NULL);
    lv_obj_set_style_bg_color(s_screen_chat, lv_color_make(0x0D, 0x11, 0x17), 0);
    lv_obj_clear_flag(s_screen_chat, LV_OBJ_FLAG_SCROLLABLE);
    ui_chat_init(s_screen_chat, disp);
    add_navbar(s_screen_chat);
    ESP_LOGI(TAG, "ui_init: chat done");

    /* Home screen. */
    ESP_LOGI(TAG, "ui_init: home");
    s_screen_home = lv_obj_create(NULL);
    lv_obj_set_style_bg_color(s_screen_home, lv_color_make(0x0D, 0x11, 0x17), 0);
    lv_obj_clear_flag(s_screen_home, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_t *title = lv_label_create(s_screen_home);
    lv_label_set_text(title, "OmniChat AI");
    lv_obj_set_style_text_color(title, lv_color_make(0x58, 0xA6, 0xFF), 0);
    lv_obj_set_style_text_font(title, &lv_font_montserrat_24, 0);
    lv_obj_align(title, LV_ALIGN_TOP_MID, 0, 40);
    lv_obj_t *sub = lv_label_create(s_screen_home);
    lv_label_set_text(sub, "AI chat on ESP32-P4");
    lv_obj_set_style_text_color(sub, lv_color_make(0x8B, 0x94, 0x9E), 0);
    lv_obj_align(sub, LV_ALIGN_TOP_MID, 0, 80);
    add_navbar(s_screen_home);
    ESP_LOGI(TAG, "ui_init: home done");

    /* Settings screen. */
    ESP_LOGI(TAG, "ui_init: settings");
    s_screen_settings = lv_obj_create(NULL);
    lv_obj_set_style_bg_color(s_screen_settings, lv_color_make(0x0D, 0x11, 0x17), 0);
    lv_obj_clear_flag(s_screen_settings, LV_OBJ_FLAG_SCROLLABLE);
    ui_settings_init(s_screen_settings, disp);
    add_navbar(s_screen_settings);
    ESP_LOGI(TAG, "ui_init: settings done");

    lv_screen_load(s_screen_chat);
    s_current = "chat";
    ESP_LOGI(TAG, "UI initialized");

    /* Periodic pump owned by the LVGL loop (runs inside lv_timer_handler of
     * the adapter worker): engine-event refresh + 1 s WiFi status. */
    lv_timer_create(ui_pump_cb, 20, NULL);

#if CONFIG_ESP_LVGL_ADAPTER_ENABLE_FPS_STATS
    esp_lv_adapter_fps_stats_enable(s_disp, true);
#endif
}

void ui_pump(void)
{
    if (s_ui_events) {
        EventBits_t bits = xEventGroupGetBits(s_ui_events);
        if (bits & UI_EVENT_REFRESH) {
            xEventGroupClearBits(s_ui_events, UI_EVENT_REFRESH);
            ui_chat_refresh();
        }
    }
    ui_settings_pump();

    /* Periodically refresh the WiFi/internet status indicator (every ~1s). */
    static uint32_t last_status = 0;
    uint32_t now = (uint32_t)xTaskGetTickCount();
    if (now - last_status >= 1000) {
        last_status = now;
        ui_chat_update_status();
    }

    /* Diagnostic dump every ~15 s: per-task CPU%, LVGL FPS, free heap.
     * Only compiled when the supporting FreeRTOS / adapter options are on. */
    static uint32_t last_diag = 0;
    if (now >= 30000 && now - last_diag >= 15000) {
        last_diag = now;
#if CONFIG_FREERTOS_USE_STATS_FORMATTING_FUNCTIONS
        char *buf = malloc(2048);
        if (buf) {
            vTaskGetRunTimeStats(buf);
            ESP_LOGW(TAG, "--- CPU stats ---\n%s\nheap free: %u",
                     buf, (unsigned)esp_get_free_heap_size());
            free(buf);
        }
#endif
#if CONFIG_ESP_LVGL_ADAPTER_ENABLE_FPS_STATS
        uint32_t fps = 0;
        if (esp_lv_adapter_get_fps(s_disp, &fps) == ESP_OK) {
            ESP_LOGW(TAG, "LVGL FPS: %u", (unsigned)fps);
        }
#endif
    }
}
