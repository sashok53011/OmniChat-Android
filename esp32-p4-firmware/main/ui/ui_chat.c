/*
 * ui_chat.c - chat screen: scrollable message list (markdown-rendered) + input + keyboard.
 */
#include <stdlib.h>
#include <string.h>
#include "esp_log.h"
#include "lvgl.h"
#include "chat_engine.h"
#include "app_wifi.h"
#include "md_renderer.h"
#include "app_config.h"
#include "ui_chat.h"

static const char *TAG = "ui_chat";

static lv_obj_t *s_list = NULL;           /* scrollable container for messages */
static lv_obj_t *s_textarea = NULL;
static lv_obj_t *s_keyboard = NULL;
static lv_obj_t *s_send_btn = NULL;
static lv_obj_t *s_status_label = NULL;
static lv_display_t *s_disp = NULL;

static void on_send_clicked(lv_event_t *e);
static void on_kbd_toggle(lv_event_t *e);
static void on_textarea_clicked(lv_event_t *e);

/* Append a message bubble rendered via the markdown renderer. */
static void add_message_bubble(const chat_message_t *m)
{
    if (!s_list) return;

    lv_obj_t *bubble = lv_obj_create(s_list);
    lv_obj_set_width(bubble, lv_pct(92));
    lv_obj_set_height(bubble, LV_SIZE_CONTENT);
    lv_obj_set_style_border_width(bubble, 1, 0);
    lv_obj_set_style_radius(bubble, 12, 0);
    lv_obj_clear_flag(bubble, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_flex_flow(bubble, LV_FLEX_FLOW_COLUMN);

    if (m->role == MSG_ROLE_USER) {
        lv_obj_set_style_bg_color(bubble, lv_color_make(0x1F, 0x6F, 0xEB), 0);
        lv_obj_set_style_border_color(bubble, lv_color_make(0x1F, 0x6F, 0xEB), 0);
        lv_obj_align(bubble, LV_ALIGN_TOP_RIGHT, 0, 0);
        lv_obj_t *l = lv_label_create(bubble);
        lv_label_set_long_mode(l, LV_LABEL_LONG_WRAP);
        lv_label_set_text(l, m->text ? m->text : "");
        lv_obj_set_style_text_color(l, lv_color_make(0xE6, 0xED, 0xF3), 0);
        lv_obj_set_style_text_font(l, APP_FONT_BODY, 0);
    } else {
        lv_obj_set_style_bg_color(bubble, lv_color_make(0x16, 0x1B, 0x22), 0);
        lv_obj_set_style_border_color(bubble, lv_color_make(0x30, 0x36, 0x3D), 0);
        lv_obj_align(bubble, LV_ALIGN_TOP_LEFT, 0, 0);

        /* Render markdown into the bubble.
         * md_document_t is ~268 KB (blocks[256] x inline[64]); allocate on heap
         * so it never exhausts the LVGL task stack. */
        md_document_t *doc = calloc(1, sizeof(md_document_t));
        if (doc) {
            const char *md = m->text ? m->text : "";
            md_parse(md, doc);
            lv_obj_t *inner = lv_obj_create(bubble);
            lv_obj_set_size(inner, lv_pct(100), lv_pct(100));
            lv_obj_set_flex_flow(inner, LV_FLEX_FLOW_COLUMN);
            lv_obj_set_style_bg_opa(inner, LV_OPA_TRANSP, 0);
            lv_obj_set_style_border_width(inner, 0, 0);
            lv_obj_clear_flag(inner, LV_OBJ_FLAG_SCROLLABLE);

            md_render_config_t cfg = md_create_default_config();
            cfg.content_width = 650;
            md_render_document(doc, inner, &cfg);
            md_free(doc);
            free(doc);
        }
    }

    /* Spacer between bubbles. */
    lv_obj_t *sp = lv_obj_create(s_list);
    lv_obj_set_size(sp, lv_pct(100), 6);
    lv_obj_set_style_bg_opa(sp, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(sp, 0, 0);
    lv_obj_clear_flag(sp, LV_OBJ_FLAG_SCROLLABLE);
}

void ui_chat_init(lv_obj_t *parent, lv_display_t *disp)
{
    s_disp = disp;

    /* Screen height 720. Reserved heights (input on TOP):
     *   status  24  (y 0..24)
     *   input   54  (y 24..78)
     *   list    590 (y 78..668)
     *   navbar  52  (y 668..720, added by ui_main)
     */
    const lv_coord_t H = lv_display_get_horizontal_resolution(disp);
    const lv_coord_t status_h = 24;
    const lv_coord_t input_h = 54;
    const lv_coord_t nav_h = 52;
    const lv_coord_t input_y = status_h;
    const lv_coord_t list_y = status_h + input_h;
    const lv_coord_t list_h = H - list_y - nav_h;

    /* Status bar label. */
    lv_obj_t *status = lv_obj_create(parent);
    lv_obj_set_size(status, lv_pct(100), status_h);
    lv_obj_align(status, LV_ALIGN_TOP_MID, 0, 0);
    lv_obj_set_style_bg_color(status, lv_color_make(0x16, 0x1B, 0x22), 0);
    lv_obj_set_style_border_width(status, 0, 0);
    s_status_label = lv_label_create(status);
    lv_obj_set_style_text_color(s_status_label, lv_color_make(0x8B, 0x94, 0x9E), 0);
    lv_obj_set_style_text_font(s_status_label, &lv_font_montserrat_14, 0);
    lv_label_set_text(s_status_label, "");
    lv_obj_align(s_status_label, LV_ALIGN_LEFT_MID, 12, 0);

    /* Input row (TOP, right below status). */
    lv_obj_t *input_row = lv_obj_create(parent);
    lv_obj_set_size(input_row, lv_pct(100), input_h);
    lv_obj_set_pos(input_row, 0, input_y);
    lv_obj_set_style_bg_color(input_row, lv_color_make(0x16, 0x1B, 0x22), 0);
    lv_obj_set_style_border_width(input_row, 0, 0);
    lv_obj_clear_flag(input_row, LV_OBJ_FLAG_SCROLLABLE);

    s_textarea = lv_textarea_create(input_row);
    lv_obj_set_size(s_textarea, lv_pct(65), 40);
    lv_obj_align(s_textarea, LV_ALIGN_LEFT_MID, 8, 0);
    lv_obj_set_style_bg_color(s_textarea, lv_color_make(0x0D, 0x11, 0x17), 0);
    lv_obj_set_style_text_color(s_textarea, lv_color_make(0xE6, 0xED, 0xF3), 0);
    lv_obj_set_style_text_font(s_textarea, APP_FONT_BODY, 0);
    lv_obj_set_style_pad_all(s_textarea, 4, 0);
    lv_textarea_set_one_line(s_textarea, true);
    lv_textarea_set_placeholder_text(s_textarea, "Ask anything...");
    lv_obj_add_event_cb(s_textarea, on_textarea_clicked, LV_EVENT_CLICKED, NULL);

    /* Keyboard toggle button (⌨) before Send. */
    lv_obj_t *kbd_btn = lv_button_create(input_row);
    lv_obj_set_size(kbd_btn, 48, 40);
    lv_obj_align(kbd_btn, LV_ALIGN_RIGHT_MID, -80, 0);
    lv_obj_set_style_bg_color(kbd_btn, lv_color_make(0x26, 0x2C, 0x33), 0);
    lv_obj_t *kbd_lab = lv_label_create(kbd_btn);
    lv_label_set_text(kbd_lab, "Aa");
    lv_obj_center(kbd_lab);
    lv_obj_set_style_text_color(kbd_lab, lv_color_make(0xE6, 0xED, 0xF3), 0);
    lv_obj_add_event_cb(kbd_btn, on_kbd_toggle, LV_EVENT_CLICKED, NULL);

    /* Send button. */
    s_send_btn = lv_button_create(input_row);
    lv_obj_set_size(s_send_btn, 64, 40);
    lv_obj_align(s_send_btn, LV_ALIGN_RIGHT_MID, -8, 0);
    lv_obj_set_style_bg_color(s_send_btn, lv_color_make(0x58, 0xA6, 0xFF), 0);
    lv_obj_t *send_lab = lv_label_create(s_send_btn);
    lv_label_set_text(send_lab, "Send");
    lv_obj_center(send_lab);
    lv_obj_set_style_text_color(send_lab, lv_color_make(0x0D, 0x11, 0x17), 0);

    lv_obj_add_event_cb(s_send_btn, on_send_clicked, LV_EVENT_CLICKED, NULL);

    /* Message list (below input). */
    s_list = lv_obj_create(parent);
    lv_obj_set_size(s_list, lv_pct(100), list_h);
    lv_obj_set_pos(s_list, 0, list_y);
    lv_obj_set_style_bg_color(s_list, lv_color_make(0x0D, 0x11, 0x17), 0);
    lv_obj_set_style_border_width(s_list, 0, 0);
    lv_obj_set_flex_flow(s_list, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(s_list, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_END, LV_FLEX_ALIGN_END);
    lv_obj_set_scroll_dir(s_list, LV_DIR_VER);
    lv_obj_set_scrollbar_mode(s_list, LV_SCROLLBAR_MODE_AUTO);

    /* Reset existing content (the list is empty initially). */
    lv_obj_clean(s_list);

    /* Keyboard (hidden initially). */
    s_keyboard = lv_keyboard_create(parent);
    lv_keyboard_set_textarea(s_keyboard, s_textarea);
    lv_obj_add_flag(s_keyboard, LV_OBJ_FLAG_HIDDEN);
}

void on_send_clicked(lv_event_t *e)
{
    (void)e;
    const char *txt = lv_textarea_get_text(s_textarea);
    if (!txt || !txt[0]) return;
    chat_engine_send_user(txt, 0);
    lv_textarea_set_text(s_textarea, "");
    /* Hide keyboard after send. */
    if (s_keyboard) lv_obj_add_flag(s_keyboard, LV_OBJ_FLAG_HIDDEN);
}

static void on_kbd_toggle(lv_event_t *e)
{
    (void)e;
    if (!s_keyboard) return;
    if (lv_obj_has_flag(s_keyboard, LV_OBJ_FLAG_HIDDEN)) {
        lv_obj_remove_flag(s_keyboard, LV_OBJ_FLAG_HIDDEN);
    } else {
        lv_obj_add_flag(s_keyboard, LV_OBJ_FLAG_HIDDEN);
    }
}

static void on_textarea_clicked(lv_event_t *e)
{
    (void)e;
    if (!s_keyboard) return;
    lv_obj_remove_flag(s_keyboard, LV_OBJ_FLAG_HIDDEN);
}

void ui_chat_refresh(void)
{
    if (!s_list) return;
    lv_obj_clean(s_list);

    chat_session_t *sess = chat_engine_active_session();
    if (!sess) {
        lv_obj_t *l = lv_label_create(s_list);
        lv_label_set_text(l, "No chat yet");
        lv_obj_set_style_text_color(l, lv_color_make(0x8B, 0x94, 0x9E), 0);
        return;
    }

    for (int i = 0; i < sess->message_count; i++) {
        add_message_bubble(&sess->messages[i]);
    }

    lv_obj_update_layout(s_list);
}

void ui_chat_update_status(void)
{
    if (!s_status_label) return;

    bool wifi = app_wifi_is_connected();

    const app_state_t *st = chat_engine_state();
    const char *prov = st && st->active_provider_id[0] ? st->active_provider_id : "-";

    char buf[200];
    snprintf(buf, sizeof(buf), "%s %s  |  %s",
             wifi ? "\xE2\x9C\x94" : "\xE2\x9D\x8C",
             wifi ? "WiFi" : "WiFi off",
             prov);
    lv_label_set_text(s_status_label, buf);
}

void ui_chat_show_keyboard(bool show)
{
    if (!s_keyboard) return;
    if (show) {
        lv_obj_remove_flag(s_keyboard, LV_OBJ_FLAG_HIDDEN);
    } else {
        lv_obj_add_flag(s_keyboard, LV_OBJ_FLAG_HIDDEN);
    }
}

void ui_chat_set_input_text(const char *text)
{
    if (s_textarea) lv_textarea_set_text(s_textarea, text ? text : "");
}
