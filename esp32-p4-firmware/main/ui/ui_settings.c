/*
 * ui_settings.c - settings screen for OmniChat-P4.
 *
 * Sections: System Prompt, AI Providers (add/edit/delete/test), TTS config,
 * Memory. Editing uses a full-screen detail panel and a shared LVGL keyboard.
 */
#include <stdlib.h>
#include <string.h>
#include "lvgl.h"
#include "esp_random.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "chat_engine.h"
#include "app_config.h"
#include "ui_settings.h"

static const char *TAG = "ui_settings";

/* Thread-safety: LVGL must only be touched from the LVGL task. Test result
 * callbacks arrive from worker tasks, so we stash the text here and apply it
 * on the LVGL loop via ui_settings_pump(). */
static char s_result_buf[512];
static volatile int s_result_pending = 0;
static SemaphoreHandle_t s_result_mutex = NULL;

static lv_obj_t *s_list = NULL;          /* main scroll content */
static lv_obj_t *s_detail = NULL;        /* editor overlay (hidden by default) */
static lv_obj_t *s_kbd = NULL;           /* shared on-screen keyboard */
static lv_obj_t *s_focus_ta = NULL;      /* textarea the keyboard targets */
static lv_obj_t *s_status_label = NULL;  /* result of Test/connection (persistent) */
static int s_editing_provider = -1;      /* index being edited, -1 = add */

/* Forward decls */
static void on_kbd_toggle(lv_event_t *e);
static void on_detail_back(lv_event_t *e);
static void on_field_clicked(lv_event_t *e);
static void kbd_attach(lv_obj_t *ta);
static void on_test_result(const char *result);
static void on_provider_row_edit(lv_event_t *e);
static void on_provider_row_test(lv_event_t *e);
static void on_provider_row_active(lv_event_t *e);
static void on_provider_open_add(lv_event_t *e);
static void on_provider_save(lv_event_t *e);
static void on_provider_delete(lv_event_t *e);
static void on_provider_test(lv_event_t *e);
static void open_provider_editor(int idx);
static void on_prompt_open(lv_event_t *e);
static void on_prompt_save(lv_event_t *e);
static void on_tts_open(lv_event_t *e);
static void on_tts_save(lv_event_t *e);
static void on_tts_test(lv_event_t *e);


/* ---------- generic helpers ---------- */

static void make_section_title(lv_obj_t *parent, const char *text)
{
    lv_obj_t *sect = lv_obj_create(parent);
    lv_obj_set_size(sect, lv_pct(100), 36);
    lv_obj_set_style_bg_opa(sect, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(sect, 0, 0);
    lv_obj_clear_flag(sect, LV_OBJ_FLAG_SCROLLABLE);

    lv_obj_t *l = lv_label_create(sect);
    lv_label_set_text(l, text);
    lv_obj_set_style_text_color(l, lv_color_make(0x58, 0xA6, 0xFF), 0);
    lv_obj_set_style_text_font(l, APP_FONT_BODY_LG, 0);
}

static void make_info_row(lv_obj_t *parent, const char *key, const char *value)
{
    lv_obj_t *row = lv_obj_create(parent);
    lv_obj_set_size(row, lv_pct(100), 44);
    lv_obj_set_style_bg_color(row, lv_color_make(0x16, 0x1B, 0x22), 0);
    lv_obj_set_style_border_width(row, 0, 0);
    lv_obj_set_style_radius(row, 8, 0);
    lv_obj_clear_flag(row, LV_OBJ_FLAG_SCROLLABLE);

    lv_obj_t *k = lv_label_create(row);
    lv_label_set_text(k, key);
    lv_obj_set_style_text_color(k, lv_color_make(0x8B, 0x94, 0x9E), 0);
    lv_obj_align(k, LV_ALIGN_LEFT_MID, 12, 0);

    lv_obj_t *v = lv_label_create(row);
    lv_label_set_text(v, value);
    lv_obj_set_style_text_color(v, lv_color_make(0xE6, 0xED, 0xF3), 0);
    lv_obj_align(v, LV_ALIGN_RIGHT_MID, -12, 0);
}

/* Row with two buttons on the right. */
static lv_obj_t *make_action_row(lv_obj_t *parent, const char *label,
                                 lv_event_cb_t a_cb, const char *a_text,
                                 lv_event_cb_t b_cb, const char *b_text,
                                 void *user_data)
{
    lv_obj_t *row = lv_obj_create(parent);
    lv_obj_set_size(row, lv_pct(100), 64);
    lv_obj_set_style_bg_color(row, lv_color_make(0x16, 0x1B, 0x22), 0);
    lv_obj_set_style_border_width(row, 0, 0);
    lv_obj_set_style_radius(row, 8, 0);
    lv_obj_clear_flag(row, LV_OBJ_FLAG_SCROLLABLE);

    lv_obj_t *l = lv_label_create(row);
    lv_label_set_text(l, label);
    lv_obj_set_style_text_color(l, lv_color_make(0xE6, 0xED, 0xF3), 0);
    lv_obj_set_style_text_font(l, APP_FONT_BODY, 0);
    lv_obj_align(l, LV_ALIGN_LEFT_MID, 12, 0);

    if (b_cb) {
        lv_obj_t *b = lv_button_create(row);
        lv_obj_set_size(b, 96, 52);
        lv_obj_align(b, LV_ALIGN_RIGHT_MID, -12, 0);
        lv_obj_set_style_bg_color(b, lv_color_make(0x26, 0x2C, 0x33), 0);
        lv_obj_t *bl = lv_label_create(b);
        lv_label_set_text(bl, b_text);
        lv_obj_center(bl);
        lv_obj_set_style_text_font(bl, APP_FONT_BODY, 0);
        lv_obj_set_style_text_color(bl, lv_color_make(0xE6, 0xED, 0xF3), 0);
        lv_obj_add_event_cb(b, b_cb, LV_EVENT_CLICKED, user_data);
    }
    if (a_cb) {
        lv_obj_t *a = lv_button_create(row);
        lv_obj_set_size(a, 96, 52);
        lv_obj_align(a, LV_ALIGN_RIGHT_MID, -118, 0);
        lv_obj_set_style_bg_color(a, lv_color_make(0x1F, 0x6F, 0xEB), 0);
        lv_obj_t *al = lv_label_create(a);
        lv_label_set_text(al, a_text);
        lv_obj_center(al);
        lv_obj_set_style_text_font(al, APP_FONT_BODY, 0);
        lv_obj_set_style_text_color(al, lv_color_make(0xE6, 0xED, 0xF3), 0);
        lv_obj_add_event_cb(a, a_cb, LV_EVENT_CLICKED, user_data);
    }
    return row;
}

/* ---------- status line for test results ---------- */

static void set_status(const char *text)
{
    if (s_status_label) lv_label_set_text(s_status_label, text ? text : "");
    else ESP_LOGI(TAG, "status: %s", text ? text : "");
}

/* ---------- keyboard ---------- */

static void kbd_attach(lv_obj_t *ta)
{
    s_focus_ta = ta;
    if (s_kbd) lv_keyboard_set_textarea(s_kbd, ta);
}

static void kbd_show(void)
{
    if (!s_kbd) return;
    lv_obj_remove_flag(s_kbd, LV_OBJ_FLAG_HIDDEN);
    lv_obj_move_to_index(s_kbd, lv_obj_get_child_count(lv_obj_get_parent(s_kbd)) - 1);
}

static void kbd_hide(void)
{
    if (s_kbd) lv_obj_add_flag(s_kbd, LV_OBJ_FLAG_HIDDEN);
}

static void on_kbd_toggle(lv_event_t *e)
{
    (void)e;
    if (s_kbd && lv_obj_has_flag(s_kbd, LV_OBJ_FLAG_HIDDEN)) kbd_show();
    else kbd_hide();
}

/* Labeled textarea row; clicking it attaches + shows the keyboard. */
static lv_obj_t *make_field(lv_obj_t *parent, const char *label, const char *value,
                            const char *placeholder, int secret)
{
    lv_obj_t *ta = lv_textarea_create(parent);
    lv_obj_set_size(ta, lv_pct(100), 52);
    lv_obj_set_style_bg_color(ta, lv_color_make(0x0D, 0x11, 0x17), 0);
    lv_obj_set_style_text_color(ta, lv_color_make(0xE6, 0xED, 0xF3), 0);
    lv_obj_set_style_text_font(ta, APP_FONT_BODY, 0);
    lv_obj_set_style_pad_all(ta, 8, 0);
    if (secret) lv_textarea_set_password_mode(ta, true);
    if (placeholder) lv_textarea_set_placeholder_text(ta, placeholder);
    if (value) lv_textarea_set_text(ta, value);
    lv_obj_add_event_cb(ta, (lv_event_cb_t)on_field_clicked, LV_EVENT_CLICKED, ta);
    return ta;
}

static void on_field_clicked(lv_event_t *e)
{
    lv_obj_t *ta = (lv_obj_t *)lv_event_get_user_data(e);
    if (ta) { kbd_attach(ta); kbd_show(); }
}

/* ---------- simple inline editor (system prompt / tts / provider) ---------- */

static void show_detail(const char *title)
{
    if (!s_detail) return;
    ESP_LOGI(TAG, "show_detail: %s", title);
    lv_obj_clean(s_detail);
    lv_obj_remove_flag(s_detail, LV_OBJ_FLAG_HIDDEN);
    /* Bring the editor overlay to the foreground so it covers the list. */
    lv_obj_move_to_index(s_detail, lv_obj_get_child_count(lv_obj_get_parent(s_detail)) - 1);

    lv_obj_t *head = lv_obj_create(s_detail);
    lv_obj_set_size(head, lv_pct(100), 40);
    lv_obj_set_style_bg_color(head, lv_color_make(0x16, 0x1B, 0x22), 0);
    lv_obj_clear_flag(head, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_t *ht = lv_label_create(head);
    lv_label_set_text(ht, title);
    lv_obj_center(ht);
    lv_obj_set_style_text_color(ht, lv_color_make(0xE6, 0xED, 0xF3), 0);

    /* Back button */
    lv_obj_t *back = lv_button_create(head);
    lv_obj_set_size(back, 80, 40);
    lv_obj_align(back, LV_ALIGN_LEFT_MID, 8, 0);
    lv_obj_set_style_bg_color(back, lv_color_make(0x26, 0x2C, 0x33), 0);
    lv_obj_t *bl = lv_label_create(back);
    lv_label_set_text(bl, "< Back");
    lv_obj_center(bl);
    lv_obj_set_style_text_font(bl, APP_FONT_BODY, 0);
    lv_obj_set_style_text_color(bl, lv_color_make(0xE6, 0xED, 0xF3), 0);
    lv_obj_add_event_cb(back, on_detail_back, LV_EVENT_CLICKED, NULL);
}

static void on_detail_back(lv_event_t *e)
{
    (void)e;
    if (s_detail) lv_obj_add_flag(s_detail, LV_OBJ_FLAG_HIDDEN);
    kbd_hide();
    /* Rebuild list to reflect changes. */
    ui_settings_rebuild();
}

/* ---------- system prompt editor ---------- */

static lv_obj_t *s_prompt_ta = NULL;

static void on_prompt_save(lv_event_t *e)
{
    (void)e;
    const char *t = lv_textarea_get_text(s_prompt_ta);
    chat_engine_update_system_prompt(t ? t : "");
    set_status("Saved");
    kbd_hide();
}

static void open_prompt_editor(void)
{
    show_detail("System Prompt");
    const app_state_t *st = chat_engine_state();

    s_prompt_ta = make_field(s_detail, "", st->system_prompt, "System instructions", 0);
    lv_obj_set_size(s_prompt_ta, lv_pct(96), 110);
    lv_obj_set_pos(s_prompt_ta, 14, 50);

    lv_obj_t *kbtn = lv_button_create(s_detail);
    lv_obj_set_size(kbtn, 70, 48);
    lv_obj_set_pos(kbtn, 14, 172);
    lv_obj_set_style_bg_color(kbtn, lv_color_make(0x26, 0x2C, 0x33), 0);
    lv_obj_t *kl = lv_label_create(kbtn);
    lv_label_set_text(kl, "Aa");
    lv_obj_center(kl);
    lv_obj_set_style_text_font(kl, APP_FONT_BODY, 0);
    lv_obj_add_event_cb(kbtn, on_kbd_toggle, LV_EVENT_CLICKED, NULL);

    lv_obj_t *save = lv_button_create(s_detail);
    lv_obj_set_size(save, 160, 48);
    lv_obj_set_pos(save, 94, 172);
    lv_obj_set_style_bg_color(save, lv_color_make(0x1F, 0x6F, 0xEB), 0);
    lv_obj_t *sl = lv_label_create(save);
    lv_label_set_text(sl, "Save Prompt");
    lv_obj_center(sl);
    lv_obj_set_style_text_font(sl, APP_FONT_BODY, 0);
    lv_obj_add_event_cb(save, on_prompt_save, LV_EVENT_CLICKED, NULL);
}

/* ---------- provider editor ---------- */

static lv_obj_t *s_pf_name, *s_pf_url, *s_pf_model, *s_pf_key;

static void on_provider_save(lv_event_t *e)
{
    (void)e;
    ai_provider_t p;
    memset(&p, 0, sizeof(p));

    const char *name = lv_textarea_get_text(s_pf_name);
    const char *url = lv_textarea_get_text(s_pf_url);
    const char *model = lv_textarea_get_text(s_pf_model);
    const char *key = lv_textarea_get_text(s_pf_key);

    if (s_editing_provider >= 0 && s_editing_provider < chat_engine_state()->provider_count) {
        p = chat_engine_state()->providers[s_editing_provider];
    } else {
        char id[24];
        snprintf(id, sizeof(id), "prov%06lu", (unsigned long)(esp_random() & 0xFFFFF));
        snprintf(p.id, sizeof(p.id), "%s", id);
        p.priority = chat_engine_state()->provider_count;
        p.enabled = true;
    }
    if (name && name[0]) strncpy(p.name, name, sizeof(p.name) - 1);
    if (url && url[0]) strncpy(p.base_url, url, sizeof(p.base_url) - 1);
    if (model && model[0]) strncpy(p.model, model, sizeof(p.model) - 1);
    if (key && key[0]) strncpy(p.api_key, key, sizeof(p.api_key) - 1);

    chat_engine_upsert_provider(&p);
    set_status("Saved");
    kbd_hide();
    on_detail_back(NULL);
}

static void delete_provider_by_index(int idx)
{
    const app_state_t *st = chat_engine_state();
    if (idx >= 0 && idx < st->provider_count) {
        chat_engine_delete_provider(st->providers[idx].id);
        set_status("Deleted");
    }
}

static void on_provider_delete(lv_event_t *e)
{
    (void)e;
    delete_provider_by_index(s_editing_provider);
    on_detail_back(NULL);
}

static void on_provider_test(lv_event_t *e)
{
    (void)e;
    const app_state_t *st = chat_engine_state();
    if (s_editing_provider >= 0 && s_editing_provider < st->provider_count) {
        /* Use the currently typed values by first saving them. */
        ai_provider_t p = st->providers[s_editing_provider];
        const char *url = lv_textarea_get_text(s_pf_url);
        const char *model = lv_textarea_get_text(s_pf_model);
        const char *key = lv_textarea_get_text(s_pf_key);
        if (url && url[0]) strncpy(p.base_url, url, sizeof(p.base_url) - 1);
        if (model && model[0]) strncpy(p.model, model, sizeof(p.model) - 1);
        if (key && key[0]) strncpy(p.api_key, key, sizeof(p.api_key) - 1);
        chat_engine_upsert_provider(&p);
        set_status("Testing...");
        chat_engine_test_provider(p.id, (chat_engine_test_cb_t)on_test_result);
    } else {
        set_status("Save the provider first, then test.");
    }
}

static void on_test_result(const char *result)
{
    ESP_LOGI(TAG, "on_test_result: %s", result ? result : "(null)");
    if (!s_result_mutex) return;
    if (xSemaphoreTake(s_result_mutex, portMAX_DELAY) == pdTRUE) {
        strncpy(s_result_buf, result ? result : "", sizeof(s_result_buf) - 1);
        s_result_buf[sizeof(s_result_buf) - 1] = '\0';
        s_result_pending = 1;
        xSemaphoreGive(s_result_mutex);
    }
}

/* Called from the LVGL loop; applies any pending test result. */
void ui_settings_pump(void)
{
    if (s_result_pending) {
        if (xSemaphoreTake(s_result_mutex, portMAX_DELAY) == pdTRUE) {
            s_result_pending = 0;
            const char *t = s_result_buf;
            xSemaphoreGive(s_result_mutex);
            ESP_LOGI(TAG, "ui_settings_pump applying: %s", t);
            set_status(t);
        }
    }
}

static void on_kbd_toggle_field(lv_event_t *e)
{
    (void)e;
    if (s_kbd && lv_obj_has_flag(s_kbd, LV_OBJ_FLAG_HIDDEN)) kbd_show();
    else kbd_hide();
}

static void open_provider_editor(int idx)
{
    s_editing_provider = idx;
    const app_state_t *st = chat_engine_state();
    ai_provider_t p;
    memset(&p, 0, sizeof(p));
    if (idx >= 0 && idx < st->provider_count) p = st->providers[idx];

    show_detail(idx >= 0 ? "Edit Provider" : "Add Provider");

    s_pf_name = make_field(s_detail, "", p.name, "Provider name", 0);
    lv_obj_set_pos(s_pf_name, 14, 50);
    s_pf_url = make_field(s_detail, "", p.base_url, "https://api.../v1/", 0);
    lv_obj_set_pos(s_pf_url, 14, 96);
    s_pf_model = make_field(s_detail, "", p.model, "model-name", 0);
    lv_obj_set_pos(s_pf_model, 14, 142);
    s_pf_key = make_field(s_detail, "", p.api_key, "API key", 1);
    lv_obj_set_pos(s_pf_key, 14, 188);

    /* Buttons row */
    lv_obj_t *kb = lv_button_create(s_detail);
    lv_obj_set_size(kb, 70, 48);
    lv_obj_set_pos(kb, 14, 238);
    lv_obj_set_style_bg_color(kb, lv_color_make(0x26, 0x2C, 0x33), 0);
    lv_obj_t *kl = lv_label_create(kb);
    lv_label_set_text(kl, "Aa");
    lv_obj_center(kl);
    lv_obj_set_style_text_font(kl, APP_FONT_BODY, 0);
    lv_obj_add_event_cb(kb, on_kbd_toggle_field, LV_EVENT_CLICKED, NULL);

    lv_obj_t *save = lv_button_create(s_detail);
    lv_obj_set_size(save, 140, 48);
    lv_obj_set_pos(save, 94, 238);
    lv_obj_set_style_bg_color(save, lv_color_make(0x1F, 0x6F, 0xEB), 0);
    lv_obj_t *sl = lv_label_create(save);
    lv_label_set_text(sl, "Save");
    lv_obj_center(sl);
    lv_obj_set_style_text_font(sl, APP_FONT_BODY, 0);
    lv_obj_add_event_cb(save, on_provider_save, LV_EVENT_CLICKED, NULL);

    lv_obj_t *test = lv_button_create(s_detail);
    lv_obj_set_size(test, 140, 48);
    lv_obj_set_pos(test, 246, 238);
    lv_obj_set_style_bg_color(test, lv_color_make(0x26, 0x2C, 0x33), 0);
    lv_obj_t *tl = lv_label_create(test);
    lv_label_set_text(tl, "Test");
    lv_obj_center(tl);
    lv_obj_set_style_text_font(tl, APP_FONT_BODY, 0);
    lv_obj_add_event_cb(test, on_provider_test, LV_EVENT_CLICKED, NULL);

    if (idx >= 0) {
        lv_obj_t *del = lv_button_create(s_detail);
        lv_obj_set_size(del, 140, 48);
        lv_obj_set_pos(del, 14, 298);
        lv_obj_set_style_bg_color(del, lv_color_make(0x8B, 0x3A, 0x3A), 0);
        lv_obj_t *dl = lv_label_create(del);
        lv_label_set_text(dl, "Delete Provider");
        lv_obj_center(dl);
        lv_obj_set_style_text_font(dl, APP_FONT_BODY, 0);
        lv_obj_add_event_cb(del, on_provider_delete, LV_EVENT_CLICKED, NULL);
    }
}

/* ---------- provider list action handlers ---------- */

static void on_provider_open_add(lv_event_t *e)
{
    ESP_LOGI(TAG, "on_provider_open_add clicked");
    (void)e;
    open_provider_editor(-1);
}

static void on_provider_row_edit(lv_event_t *e)
{
    ESP_LOGI(TAG, "on_provider_row_edit clicked");
    void *ud = lv_event_get_user_data(e);
    /* Index stored as (idx+1); NULL/0 means unset. */
    int idx = (int)(intptr_t)ud - 1;
    if (idx >= 0) open_provider_editor(idx);
}

static void on_provider_row_test(lv_event_t *e)
{
    ESP_LOGI(TAG, "on_provider_row_test clicked");
    char *id = (char *)lv_event_get_user_data(e);
    if (!id) return;
    set_status("Testing...");
    chat_engine_test_provider(id, (chat_engine_test_cb_t)on_test_result);
    /* NOTE: 'id' is intentionally not freed here; LVGL may deliver CLICKED more
     * than once, which would double-free. It's a tiny leak per tap. */
}

static void on_provider_row_active(lv_event_t *e)
{
    ESP_LOGI(TAG, "on_provider_row_active clicked");
    char *id = (char *)lv_event_get_user_data(e);
    if (id) chat_engine_set_active_provider(id);
}

/* ---------- TTS editor ---------- */

static lv_obj_t *s_ttf_url, *s_ttf_model, *s_ttf_voice;

static void on_tts_save(lv_event_t *e)
{
    (void)e;
    const app_state_t *st = chat_engine_state();
    const char *url = lv_textarea_get_text(s_ttf_url);
    const char *model = lv_textarea_get_text(s_ttf_model);
    const char *voice = lv_textarea_get_text(s_ttf_voice);
    chat_engine_set_tts_config(url, model, voice, st->tts_speak_ai);
    set_status("Saved");
    kbd_hide();
    on_detail_back(NULL);
}

static void on_tts_test(lv_event_t *e)
{
    (void)e;
    set_status("Testing TTS...");
    chat_engine_test_tts((chat_engine_test_cb_t)on_test_result);
}

static void on_tts_speak_ai(lv_event_t *e)
{
    const app_state_t *st = chat_engine_state();
    /* Toggle speak_ai via a small switch-like button; simple toggle here. */
    chat_engine_set_tts_config(st->tts_url, st->tts_model, st->tts_voice, !st->tts_speak_ai);
    set_status(st->tts_speak_ai ? "Speak AI: On" : "Speak AI: Off");
    on_detail_back(NULL);
}

static void open_tts_editor(void)
{
    show_detail("TTS");
    const app_state_t *st = chat_engine_state();

    s_ttf_url = make_field(s_detail, "", st->tts_url, "https://.../audio/speech", 0);
    lv_obj_set_pos(s_ttf_url, 14, 50);
    s_ttf_model = make_field(s_detail, "", st->tts_model, "tts-1", 0);
    lv_obj_set_pos(s_ttf_model, 14, 96);
    s_ttf_voice = make_field(s_detail, "", st->tts_voice, "voice", 0);
    lv_obj_set_pos(s_ttf_voice, 14, 142);

    lv_obj_t *save = lv_button_create(s_detail);
    lv_obj_set_size(save, 140, 48);
    lv_obj_set_pos(save, 94, 194);
    lv_obj_set_style_bg_color(save, lv_color_make(0x1F, 0x6F, 0xEB), 0);
    lv_obj_t *sl = lv_label_create(save);
    lv_label_set_text(sl, "Save");
    lv_obj_center(sl);
    lv_obj_set_style_text_font(sl, APP_FONT_BODY, 0);
    lv_obj_add_event_cb(save, on_tts_save, LV_EVENT_CLICKED, NULL);

    lv_obj_t *test = lv_button_create(s_detail);
    lv_obj_set_size(test, 140, 48);
    lv_obj_set_pos(test, 246, 194);
    lv_obj_set_style_bg_color(test, lv_color_make(0x26, 0x2C, 0x33), 0);
    lv_obj_t *tl = lv_label_create(test);
    lv_label_set_text(tl, "Test");
    lv_obj_center(tl);
    lv_obj_set_style_text_font(tl, APP_FONT_BODY, 0);
    lv_obj_add_event_cb(test, on_tts_test, LV_EVENT_CLICKED, NULL);

    lv_obj_t *lbl = lv_label_create(s_detail);
    lv_label_set_text(lbl, st->tts_speak_ai ? "Speak AI: ON" : "Speak AI: OFF");
    lv_obj_set_pos(lbl, 14, 250);
    lv_obj_set_style_text_color(lbl, lv_color_make(0xE6, 0xED, 0xF3), 0);

    lv_obj_t *tgl = lv_button_create(s_detail);
    lv_obj_set_size(tgl, 180, 48);
    lv_obj_set_pos(tgl, 200, 244);
    lv_obj_set_style_bg_color(tgl, lv_color_make(0x26, 0x2C, 0x33), 0);
    lv_obj_t *tl2 = lv_label_create(tgl);
    lv_label_set_text(tl2, st->tts_speak_ai ? "Speak AI: ON" : "Speak AI: OFF");
    lv_obj_center(tl2);
    lv_obj_set_style_text_font(tl2, APP_FONT_BODY, 0);
    lv_obj_add_event_cb(tgl, on_tts_speak_ai, LV_EVENT_CLICKED, NULL);

}

static void on_tts_open(lv_event_t *e) { (void)e; open_tts_editor(); }
static void on_prompt_open(lv_event_t *e) { (void)e; open_prompt_editor(); }

/* ---------- rebuild main list ---------- */

void ui_settings_rebuild(void)
{
    if (!s_list) return;
    lv_obj_clean(s_list);

    const app_state_t *st = chat_engine_state();

    make_section_title(s_list, "Settings");

    /* System Prompt */
    make_section_title(s_list, "System Prompt");
    char prompt[160];
    snprintf(prompt, sizeof(prompt), "%.120s...", st->system_prompt[0] ? st->system_prompt : "(none)");
    make_action_row(s_list, prompt, on_prompt_open, "Edit", NULL, NULL, NULL);

    /* AI Providers */
    make_section_title(s_list, "AI Provider");
    for (int i = 0; i < st->provider_count; i++) {
        const ai_provider_t *p = &st->providers[i];
        char label[200];
        int is_active = (strcmp(st->active_provider_id, p->id) == 0);
        snprintf(label, sizeof(label), "%s  (%s)%s", p->name, p->model, is_active ? "  <active>" : "");

        char *edit_data = (char *)(intptr_t)(i + 1);
        char *test_id = strdup(p->id);
        char *act_id = strdup(p->id);
        lv_obj_t *row = lv_obj_create(s_list);
        lv_obj_set_size(row, lv_pct(100), 60);
        lv_obj_set_style_bg_color(row, lv_color_make(0x16, 0x1B, 0x22), 0);
        lv_obj_set_style_border_width(row, 0, 0);
        lv_obj_set_style_radius(row, 8, 0);
        lv_obj_clear_flag(row, LV_OBJ_FLAG_SCROLLABLE);

        lv_obj_t *nm = lv_label_create(row);
        lv_label_set_text(nm, label);
        lv_obj_set_style_text_color(nm, lv_color_make(0xE6, 0xED, 0xF3), 0);
        lv_obj_set_style_text_font(nm, APP_FONT_BODY, 0);
        lv_obj_align(nm, LV_ALIGN_LEFT_MID, 12, 0);

        /* Edit, Test, Active buttons */
        lv_obj_t *b_active = lv_button_create(row);
        lv_obj_set_size(b_active, 76, 48);
        lv_obj_align(b_active, LV_ALIGN_RIGHT_MID, -12, 0);
        lv_obj_set_style_bg_color(b_active, lv_color_make(0x1F, 0x6F, 0xEB), 0);
        lv_obj_t *al = lv_label_create(b_active);
        lv_label_set_text(al, "Use");
        lv_obj_center(al);
        lv_obj_set_style_text_font(al, APP_FONT_BODY, 0);
        lv_obj_add_event_cb(b_active, on_provider_row_active, LV_EVENT_CLICKED, act_id);

        lv_obj_t *b_test = lv_button_create(row);
        lv_obj_set_size(b_test, 76, 48);
        lv_obj_align(b_test, LV_ALIGN_RIGHT_MID, -96, 0);
        lv_obj_set_style_bg_color(b_test, lv_color_make(0x26, 0x2C, 0x33), 0);
        lv_obj_t *tl = lv_label_create(b_test);
        lv_label_set_text(tl, "Test");
        lv_obj_center(tl);
        lv_obj_set_style_text_font(tl, APP_FONT_BODY, 0);
        lv_obj_add_event_cb(b_test, on_provider_row_test, LV_EVENT_CLICKED, test_id);

        lv_obj_t *b_edit = lv_button_create(row);
        lv_obj_set_size(b_edit, 76, 48);
        lv_obj_align(b_edit, LV_ALIGN_RIGHT_MID, -180, 0);
        lv_obj_set_style_bg_color(b_edit, lv_color_make(0x26, 0x2C, 0x33), 0);
        lv_obj_t *el = lv_label_create(b_edit);
        lv_label_set_text(el, "Edit");
        lv_obj_center(el);
        lv_obj_set_style_text_font(el, APP_FONT_BODY, 0);
        lv_obj_add_event_cb(b_edit, on_provider_row_edit, LV_EVENT_CLICKED, edit_data);
    }

    lv_obj_t *add = lv_button_create(s_list);
    lv_obj_set_size(add, lv_pct(100), 52);
    lv_obj_set_style_bg_color(add, lv_color_make(0x1F, 0x6F, 0xEB), 0);
    lv_obj_t *addl = lv_label_create(add);
    lv_label_set_text(addl, "+ Add Provider");
    lv_obj_center(addl);
    lv_obj_set_style_text_font(addl, APP_FONT_BODY, 0);
    lv_obj_add_event_cb(add, on_provider_open_add, LV_EVENT_CLICKED, NULL);

    /* TTS */
    make_section_title(s_list, "TTS");
    char tts[160];
    snprintf(tts, sizeof(tts), "%s (vol %d%%, %s)", st->tts_model[0] ? st->tts_model : "-",
             st->tts_volume, st->tts_speak_ai ? "speak-ai on" : "speak-ai off");
    make_action_row(s_list, tts, on_tts_open, "Edit", NULL, NULL, NULL);

    /* Memory */
    make_section_title(s_list, "Memory");
    char mem[64];
    snprintf(mem, sizeof(mem), "%d item(s)", st->memory_count);
    make_info_row(s_list, "Saved", mem);
}

void ui_settings_init(lv_obj_t *parent, lv_display_t *disp)
{
    (void)disp;
    s_result_mutex = xSemaphoreCreateMutex();
    s_list = lv_obj_create(parent);
    lv_obj_set_size(s_list, lv_pct(100), lv_pct(100));
    lv_obj_set_pos(s_list, 0, 0);
    lv_obj_set_style_bg_color(s_list, lv_color_make(0x0D, 0x11, 0x17), 0);
    lv_obj_set_style_border_width(s_list, 0, 0);
    lv_obj_set_flex_flow(s_list, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(s_list, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_START);
    lv_obj_set_style_pad_row(s_list, 8, 0);
    lv_obj_set_scroll_dir(s_list, LV_DIR_VER);
    lv_obj_set_scrollbar_mode(s_list, LV_SCROLLBAR_MODE_AUTO);

    /* Detail overlay (hidden). */
    s_detail = lv_obj_create(parent);
    lv_obj_set_size(s_detail, lv_pct(100), lv_pct(100));
    lv_obj_set_style_bg_color(s_detail, lv_color_make(0x0D, 0x11, 0x17), 0);
    lv_obj_set_style_border_width(s_detail, 0, 0);
    lv_obj_clear_flag(s_detail, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_add_flag(s_detail, LV_OBJ_FLAG_HIDDEN);

    /* Shared keyboard (hidden). */
    s_kbd = lv_keyboard_create(parent);
    lv_obj_add_flag(s_kbd, LV_OBJ_FLAG_HIDDEN);

    /* Persistent status line (always visible, above the navbar). */
    lv_obj_t *status_wrap = lv_obj_create(parent);
    lv_obj_set_size(status_wrap, lv_pct(100), 24);
    lv_obj_set_pos(status_wrap, 0, 720 - 52 - 24);
    lv_obj_set_style_bg_color(status_wrap, lv_color_make(0x16, 0x1B, 0x22), 0);
    lv_obj_set_style_border_width(status_wrap, 0, 0);
    lv_obj_clear_flag(status_wrap, LV_OBJ_FLAG_SCROLLABLE);
    s_status_label = lv_label_create(status_wrap);
    lv_label_set_text(s_status_label, "");
    lv_obj_set_style_text_color(s_status_label, lv_color_make(0x7E, 0xE0, 0x8A), 0);
    lv_obj_set_style_text_font(s_status_label, APP_FONT_BODY, 0);
    lv_obj_align(s_status_label, LV_ALIGN_LEFT_MID, 12, 0);

    ui_settings_rebuild();
}
