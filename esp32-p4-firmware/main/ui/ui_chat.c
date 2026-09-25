/*
 * ui_chat.c - chat screen: message list + input + keyboard + session management.
 *
 * Layout (720px tall, input at TOP):
 *   status  40  (y 0..40)   — WiFi + provider + New/Trash/List buttons
 *   input   72  (y 40..112) — textarea + Keyboard + Retry + Send
 *   list   556  (y 112..668)
 *   navbar  52  (y 668..720, added by ui_main)
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

extern const lv_font_t lv_font_md_body_20;
extern const lv_font_t lv_font_md_bold_20;

static const char *TAG = "ui_chat";

#define CLR_BG_DARK   lv_color_make(0x0D, 0x11, 0x17)
#define CLR_BG_PANEL  lv_color_make(0x16, 0x1B, 0x22)
#define CLR_BG_INPUT  lv_color_make(0x0D, 0x11, 0x17)
#define CLR_BLUE      lv_color_make(0x58, 0xA6, 0xFF)
#define CLR_GREEN     lv_color_make(0x3F, 0xB9, 0x50)
#define CLR_RED       lv_color_make(0xF8, 0x51, 0x49)
#define CLR_ORANGE    lv_color_make(0xF0, 0x88, 0x3E)
#define CLR_TEXT      lv_color_make(0xE6, 0xED, 0xF3)
#define CLR_DIM       lv_color_make(0x8B, 0x94, 0x9E)
#define CLR_USER_BG   lv_color_make(0x1F, 0x6F, 0xEB)
#define CLR_ASST_BG   lv_color_make(0x16, 0x1B, 0x22)
#define CLR_BORDER    lv_color_make(0x30, 0x36, 0x3D)

static lv_obj_t *s_list = NULL;
static lv_obj_t *s_textarea = NULL;
static lv_obj_t *s_keyboard = NULL;
static lv_obj_t *s_send_btn = NULL;
static lv_obj_t *s_status_label = NULL;
static lv_obj_t *s_session_modal = NULL;
static lv_obj_t *s_lang_btn = NULL;
static lv_display_t *s_disp = NULL;
static bool s_is_russian = false;

static void on_send_clicked(lv_event_t *e);
static void on_kbd_toggle(lv_event_t *e);
static void on_retry_clicked(lv_event_t *e);
static void on_new_chat_clicked(lv_event_t *e);
static void on_clear_chat_clicked(lv_event_t *e);
static void on_sessions_clicked(lv_event_t *e);
static void on_textarea_clicked(lv_event_t *e);
static void on_lang_toggle(lv_event_t *e);

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
        lv_obj_set_style_bg_color(bubble, CLR_USER_BG, 0);
        lv_obj_set_style_border_color(bubble, CLR_USER_BG, 0);
        lv_obj_align(bubble, LV_ALIGN_TOP_RIGHT, 0, 0);
        lv_obj_t *l = lv_label_create(bubble);
        lv_label_set_long_mode(l, LV_LABEL_LONG_WRAP);
        lv_label_set_text(l, m->text ? m->text : "");
        lv_obj_set_style_text_color(l, CLR_TEXT, 0);
        lv_obj_set_style_text_font(l, &lv_font_md_body_20, 0);
    } else {
        lv_obj_set_style_bg_color(bubble, CLR_ASST_BG, 0);
        lv_obj_set_style_border_color(bubble, CLR_BORDER, 0);
        lv_obj_align(bubble, LV_ALIGN_TOP_LEFT, 0, 0);

        md_document_t *doc = calloc(1, sizeof(md_document_t));
        if (doc) {
            const char *md = m->text ? m->text : "";
            md_parse(md, doc);
            lv_obj_t *inner = lv_obj_create(bubble);
            lv_obj_set_width(inner, lv_pct(100));
            lv_obj_set_height(inner, LV_SIZE_CONTENT);
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

    lv_obj_t *sp = lv_obj_create(s_list);
    lv_obj_set_size(sp, lv_pct(100), 8);
    lv_obj_set_style_bg_opa(sp, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(sp, 0, 0);
    lv_obj_clear_flag(sp, LV_OBJ_FLAG_SCROLLABLE);
}

void ui_chat_init(lv_obj_t *parent, lv_display_t *disp)
{
    s_disp = disp;

    const lv_coord_t H = lv_display_get_horizontal_resolution(disp);
    const lv_coord_t status_h = 40;
    const lv_coord_t input_h = 72;
    const lv_coord_t nav_h = 52;
    const lv_coord_t input_y = status_h;
    const lv_coord_t list_y = status_h + input_h;
    const lv_coord_t list_h = H - list_y - nav_h;

    /* ---- Status bar (40px): WiFi+provider left, buttons right ---- */
    lv_obj_t *status = lv_obj_create(parent);
    lv_obj_set_size(status, lv_pct(100), status_h);
    lv_obj_align(status, LV_ALIGN_TOP_MID, 0, 0);
    lv_obj_set_style_bg_color(status, CLR_BG_PANEL, 0);
    lv_obj_set_style_border_width(status, 0, 0);
    lv_obj_clear_flag(status, LV_OBJ_FLAG_SCROLLABLE);

    s_status_label = lv_label_create(status);
    lv_obj_set_style_text_color(s_status_label, CLR_DIM, 0);
    lv_obj_set_style_text_font(s_status_label, APP_FONT_BODY, 0);
    lv_label_set_text(s_status_label, "");
    lv_obj_align(s_status_label, LV_ALIGN_LEFT_MID, 8, 0);

    /* New Chat button (+) */
    lv_obj_t *new_btn = lv_button_create(status);
    lv_obj_set_size(new_btn, 56, 32);
    lv_obj_align(new_btn, LV_ALIGN_RIGHT_MID, -8, 0);
    lv_obj_set_style_bg_color(new_btn, CLR_GREEN, 0);
    lv_obj_set_style_radius(new_btn, 8, 0);
    lv_obj_t *new_lab = lv_label_create(new_btn);
    lv_label_set_text(new_lab, "New");
    lv_obj_center(new_lab);
    lv_obj_set_style_text_color(new_lab, CLR_TEXT, 0);
    lv_obj_set_style_text_font(new_lab, APP_FONT_BODY, 0);
    lv_obj_add_event_cb(new_btn, on_new_chat_clicked, LV_EVENT_CLICKED, NULL);

    /* Clear/Trash button */
    lv_obj_t *trash_btn = lv_button_create(status);
    lv_obj_set_size(trash_btn, 56, 32);
    lv_obj_align(trash_btn, LV_ALIGN_RIGHT_MID, -72, 0);
    lv_obj_set_style_bg_color(trash_btn, CLR_RED, 0);
    lv_obj_set_style_radius(trash_btn, 8, 0);
    lv_obj_t *trash_lab = lv_label_create(trash_btn);
    lv_label_set_text(trash_lab, "Clr");
    lv_obj_center(trash_lab);
    lv_obj_set_style_text_color(trash_lab, CLR_TEXT, 0);
    lv_obj_set_style_text_font(trash_lab, APP_FONT_BODY, 0);
    lv_obj_add_event_cb(trash_btn, on_clear_chat_clicked, LV_EVENT_CLICKED, NULL);

    /* Sessions list button */
    lv_obj_t *list_btn = lv_button_create(status);
    lv_obj_set_size(list_btn, 56, 32);
    lv_obj_align(list_btn, LV_ALIGN_RIGHT_MID, -136, 0);
    lv_obj_set_style_bg_color(list_btn, CLR_ORANGE, 0);
    lv_obj_set_style_radius(list_btn, 8, 0);
    lv_obj_t *list_lab = lv_label_create(list_btn);
    lv_label_set_text(list_lab, "Chat");
    lv_obj_center(list_lab);
    lv_obj_set_style_text_color(list_lab, CLR_TEXT, 0);
    lv_obj_set_style_text_font(list_lab, APP_FONT_BODY, 0);
    lv_obj_add_event_cb(list_btn, on_sessions_clicked, LV_EVENT_CLICKED, NULL);

    /* ---- Input row (72px) ---- */
    lv_obj_t *input_row = lv_obj_create(parent);
    lv_obj_set_size(input_row, lv_pct(100), input_h);
    lv_obj_set_pos(input_row, 0, input_y);
    lv_obj_set_style_bg_color(input_row, CLR_BG_PANEL, 0);
    lv_obj_set_style_border_width(input_row, 0, 0);
    lv_obj_clear_flag(input_row, LV_OBJ_FLAG_SCROLLABLE);

    /* Textarea (bigger) */
    s_textarea = lv_textarea_create(input_row);
    lv_obj_set_size(s_textarea, lv_pct(52), 52);
    lv_obj_align(s_textarea, LV_ALIGN_LEFT_MID, 8, 0);
    lv_obj_set_style_bg_color(s_textarea, CLR_BG_INPUT, 0);
    lv_obj_set_style_text_color(s_textarea, CLR_TEXT, 0);
    lv_obj_set_style_text_font(s_textarea, &lv_font_md_body_20, 0);
    lv_obj_set_style_pad_all(s_textarea, 6, 0);
    lv_textarea_set_one_line(s_textarea, true);
    lv_textarea_set_placeholder_text(s_textarea, "Ask anything...");
    lv_obj_add_event_cb(s_textarea, on_textarea_clicked, LV_EVENT_CLICKED, NULL);

    /* Keyboard toggle */
    lv_obj_t *kbd_btn = lv_button_create(input_row);
    lv_obj_set_size(kbd_btn, 52, 52);
    lv_obj_align(kbd_btn, LV_ALIGN_RIGHT_MID, -180, 0);
    lv_obj_set_style_bg_color(kbd_btn, CLR_BG_DARK, 0);
    lv_obj_set_style_radius(kbd_btn, 8, 0);
    lv_obj_t *kbd_lab = lv_label_create(kbd_btn);
    lv_label_set_text(kbd_lab, "Aa");
    lv_obj_center(kbd_lab);
    lv_obj_set_style_text_color(kbd_lab, CLR_TEXT, 0);
    lv_obj_set_style_text_font(kbd_lab, APP_FONT_BODY, 0);
    lv_obj_add_event_cb(kbd_btn, on_kbd_toggle, LV_EVENT_CLICKED, NULL);

    /* RU/EN toggle button */
    s_lang_btn = lv_button_create(input_row);
    lv_obj_set_size(s_lang_btn, 52, 52);
    lv_obj_align(s_lang_btn, LV_ALIGN_RIGHT_MID, -240, 0);
    lv_obj_set_style_bg_color(s_lang_btn, CLR_BG_DARK, 0);
    lv_obj_set_style_radius(s_lang_btn, 8, 0);
    lv_obj_t *lang_lab = lv_label_create(s_lang_btn);
    lv_label_set_text(lang_lab, "RU");
    lv_obj_center(lang_lab);
    lv_obj_set_style_text_color(lang_lab, CLR_TEXT, 0);
    lv_obj_set_style_text_font(lang_lab, APP_FONT_BODY, 0);
    lv_obj_add_event_cb(s_lang_btn, on_lang_toggle, LV_EVENT_CLICKED, NULL);

    /* Retry button */
    lv_obj_t *retry_btn = lv_button_create(input_row);
    lv_obj_set_size(retry_btn, 52, 52);
    lv_obj_align(retry_btn, LV_ALIGN_RIGHT_MID, -120, 0);
    lv_obj_set_style_bg_color(retry_btn, CLR_ORANGE, 0);
    lv_obj_set_style_radius(retry_btn, 8, 0);
    lv_obj_t *retry_lab = lv_label_create(retry_btn);
    lv_label_set_text(retry_lab, LV_SYMBOL_REFRESH);
    lv_obj_center(retry_lab);
    lv_obj_set_style_text_color(retry_lab, CLR_TEXT, 0);
    lv_obj_set_style_text_font(retry_lab, APP_FONT_BODY, 0);
    lv_obj_add_event_cb(retry_btn, on_retry_clicked, LV_EVENT_CLICKED, NULL);

    /* Send button (bigger) */
    s_send_btn = lv_button_create(input_row);
    lv_obj_set_size(s_send_btn, 72, 52);
    lv_obj_align(s_send_btn, LV_ALIGN_RIGHT_MID, -8, 0);
    lv_obj_set_style_bg_color(s_send_btn, CLR_BLUE, 0);
    lv_obj_set_style_radius(s_send_btn, 8, 0);
    lv_obj_t *send_lab = lv_label_create(s_send_btn);
    lv_label_set_text(send_lab, LV_SYMBOL_RIGHT);
    lv_obj_center(send_lab);
    lv_obj_set_style_text_color(send_lab, CLR_TEXT, 0);
    lv_obj_set_style_text_font(send_lab, APP_FONT_BODY, 0);
    lv_obj_add_event_cb(s_send_btn, on_send_clicked, LV_EVENT_CLICKED, NULL);

    /* ---- Message list ---- */
    s_list = lv_obj_create(parent);
    lv_obj_set_size(s_list, lv_pct(100), list_h);
    lv_obj_set_pos(s_list, 0, list_y);
    lv_obj_set_style_bg_color(s_list, CLR_BG_DARK, 0);
    lv_obj_set_style_border_width(s_list, 0, 0);
    lv_obj_set_flex_flow(s_list, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(s_list, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_END, LV_FLEX_ALIGN_END);
    lv_obj_set_scroll_dir(s_list, LV_DIR_VER);
    lv_obj_set_scrollbar_mode(s_list, LV_SCROLLBAR_MODE_AUTO);
    lv_obj_clean(s_list);

    /* Keyboard (hidden) — set Cyrillic-capable font */
    s_keyboard = lv_keyboard_create(parent);
    lv_keyboard_set_textarea(s_keyboard, s_textarea);
    lv_obj_set_style_text_font(s_keyboard, &lv_font_md_body_20, 0);
    lv_obj_add_flag(s_keyboard, LV_OBJ_FLAG_HIDDEN);

    /* Russian keyboard layout (JCUKEN) */
    static const char * const ru_kb_lc[] = {
        "1#", "\xD1\x86", "\xD1\x83", "\xD0\xBA", "\xD0\xB5", "\xD0\xBD", "\xD0\xB3", "\xD1\x88", "\xD1\x89", "\xD0\xB7", "\xD1\x85", "\xD1\x8A", LV_SYMBOL_BACKSPACE, "\n",
        "ABC", "\xD1\x84", "\xD1\x8B", "\xD0\xB2", "\xD0\xB0", "\xD0\xBF", "\xD1\x80", "\xD0\xBE", "\xD0\xBB", "\xD0\xB4", "\xD0\xB6", LV_SYMBOL_NEW_LINE, "\n",
        "=", "\xD1\x8F", "\xD1\x87", "\xD1\x81", "\xD0\xBC", "\xD0\xB8", "\xD1\x82", "\xD1\x8C", "\xD0\xB1", "\xD1\x8E", ".", "?", "\n",
        LV_SYMBOL_KEYBOARD, LV_SYMBOL_LEFT, " ", LV_SYMBOL_RIGHT, LV_SYMBOL_OK, ""
    };
    static const lv_buttonmatrix_ctrl_t ru_kb_ctrl_lc[] = {
        LV_KEYBOARD_CTRL_BUTTON_FLAGS | 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 7,
        LV_KEYBOARD_CTRL_BUTTON_FLAGS | 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 7,
        4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
        LV_KEYBOARD_CTRL_BUTTON_FLAGS | 2, 4, 6, 4, 2
    };
    static const char * const ru_kb_uc[] = {
        "1#", "\xD0\xA6", "\xD0\xA3", "\xD0\x9A", "\xD0\x95", "\xD0\x9D", "\xD0\x93", "\xD0\xA8", "\xD0\xA9", "\xD0\x97", "\xD0\xA5", "\xD0\xAA", LV_SYMBOL_BACKSPACE, "\n",
        "abc", "\xD0\xA4", "\xD0\xAB", "\xD0\x92", "\xD0\x90", "\xD0\x9F", "\xD0\xA0", "\xD0\x9E", "\xD0\x9B", "\xD0\x94", "\xD0\x96", LV_SYMBOL_NEW_LINE, "\n",
        "=", "\xD0\xAF", "\xD0\xA7", "\xD0\xA1", "\xD0\x9C", "\xD0\x98", "\xD0\xA2", "\xD0\xAC", "\xD0\x91", "\xD0\xAE", ".", "?", "\n",
        LV_SYMBOL_KEYBOARD, LV_SYMBOL_LEFT, " ", LV_SYMBOL_RIGHT, LV_SYMBOL_OK, ""
    };
    static const lv_buttonmatrix_ctrl_t ru_kb_ctrl_uc[] = {
        LV_KEYBOARD_CTRL_BUTTON_FLAGS | 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 7,
        LV_KEYBOARD_CTRL_BUTTON_FLAGS | 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 7,
        4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
        LV_KEYBOARD_CTRL_BUTTON_FLAGS | 2, 4, 6, 4, 2
    };
    lv_keyboard_set_map(s_keyboard, LV_KEYBOARD_MODE_USER_1, ru_kb_lc, ru_kb_ctrl_lc);
    lv_keyboard_set_map(s_keyboard, LV_KEYBOARD_MODE_USER_2, ru_kb_uc, ru_kb_ctrl_uc);
}

/* ---- Event handlers ---- */

static void on_send_clicked(lv_event_t *e)
{
    (void)e;
    const char *txt = lv_textarea_get_text(s_textarea);
    if (!txt || !txt[0]) return;
    chat_engine_send_user(txt, 0);
    lv_textarea_set_text(s_textarea, "");
    if (s_keyboard) lv_obj_add_flag(s_keyboard, LV_OBJ_FLAG_HIDDEN);
}

static void on_kbd_toggle(lv_event_t *e)
{
    (void)e;
    if (!s_keyboard) return;
    if (lv_obj_has_flag(s_keyboard, LV_OBJ_FLAG_HIDDEN))
        lv_obj_remove_flag(s_keyboard, LV_OBJ_FLAG_HIDDEN);
    else
        lv_obj_add_flag(s_keyboard, LV_OBJ_FLAG_HIDDEN);
}

static void on_textarea_clicked(lv_event_t *e)
{
    (void)e;
    if (!s_keyboard) return;
    lv_obj_remove_flag(s_keyboard, LV_OBJ_FLAG_HIDDEN);
}

static void on_lang_toggle(lv_event_t *e)
{
    (void)e;
    if (!s_keyboard) return;
    s_is_russian = !s_is_russian;
    if (s_is_russian) {
        lv_keyboard_set_mode(s_keyboard, LV_KEYBOARD_MODE_USER_1);
        lv_label_set_text(lv_obj_get_child(s_lang_btn, 0), "EN");
    } else {
        lv_keyboard_set_mode(s_keyboard, LV_KEYBOARD_MODE_TEXT_LOWER);
        lv_label_set_text(lv_obj_get_child(s_lang_btn, 0), "RU");
    }
}

static void on_retry_clicked(lv_event_t *e)
{
    (void)e;
    if (chat_engine_is_busy()) return;
    chat_engine_retry_last();
}

static void on_new_chat_clicked(lv_event_t *e)
{
    (void)e;
    if (chat_engine_is_busy()) return;
    chat_engine_new_session();
    ui_chat_refresh();
}

static void on_clear_chat_clicked(lv_event_t *e)
{
    (void)e;
    if (chat_engine_is_busy()) return;
    chat_engine_clear_active();
    ui_chat_refresh();
}

/* ---- Session list modal ---- */

static void close_session_modal(void)
{
    if (s_session_modal) {
        lv_obj_del(s_session_modal);
        s_session_modal = NULL;
    }
}

static void on_session_row_clicked(lv_event_t *e)
{
    const char *id = (const char *)lv_event_get_user_data(e);
    if (id && id[0]) {
        chat_engine_select_session(id);
        ui_chat_refresh();
    }
    close_session_modal();
}

static void on_session_delete_clicked(lv_event_t *e)
{
    const char *id = (const char *)lv_event_get_user_data(e);
    if (id && id[0]) {
        chat_engine_delete_session(id);
        /* Rebuild the list */
        close_session_modal();
        on_sessions_clicked(NULL);
    }
}

static void on_modal_close_clicked(lv_event_t *e)
{
    (void)e;
    close_session_modal();
}

static void on_sessions_clicked(lv_event_t *e)
{
    (void)e;
    if (s_session_modal) { close_session_modal(); return; }
    if (!s_disp) return;

    lv_coord_t W = lv_display_get_horizontal_resolution(s_disp);
    lv_coord_t H = lv_display_get_vertical_resolution(s_disp);

    s_session_modal = lv_obj_create(lv_layer_top());
    lv_obj_set_size(s_session_modal, W, H);
    lv_obj_set_style_bg_color(s_session_modal, CLR_BG_DARK, 0);
    lv_obj_set_style_bg_opa(s_session_modal, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(s_session_modal, 0, 0);
    lv_obj_set_flex_flow(s_session_modal, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_scroll_dir(s_session_modal, LV_DIR_VER);
    lv_obj_set_scrollbar_mode(s_session_modal, LV_SCROLLBAR_MODE_AUTO);

    /* Header row */
    lv_obj_t *hdr = lv_obj_create(s_session_modal);
    lv_obj_set_size(hdr, lv_pct(100), 48);
    lv_obj_set_style_bg_color(hdr, CLR_BG_PANEL, 0);
    lv_obj_set_style_border_width(hdr, 0, 0);
    lv_obj_clear_flag(hdr, LV_OBJ_FLAG_SCROLLABLE);

    lv_obj_t *hdr_title = lv_label_create(hdr);
    lv_label_set_text(hdr_title, LV_SYMBOL_DIRECTORY "  Chats");
    lv_obj_set_style_text_color(hdr_title, CLR_TEXT, 0);
    lv_obj_set_style_text_font(hdr_title, APP_FONT_BODY_LG, 0);
    lv_obj_align(hdr_title, LV_ALIGN_LEFT_MID, 8, 0);

    lv_obj_t *close_btn = lv_button_create(hdr);
    lv_obj_set_size(close_btn, 48, 36);
    lv_obj_align(close_btn, LV_ALIGN_RIGHT_MID, -8, 0);
    lv_obj_set_style_bg_color(close_btn, CLR_RED, 0);
    lv_obj_set_style_radius(close_btn, 8, 0);
    lv_obj_t *close_lab = lv_label_create(close_btn);
    lv_label_set_text(close_lab, LV_SYMBOL_CLOSE);
    lv_obj_center(close_lab);
    lv_obj_set_style_text_color(close_lab, CLR_TEXT, 0);
    lv_obj_add_event_cb(close_btn, on_modal_close_clicked, LV_EVENT_CLICKED, NULL);

    /* Session list */
    int count = 0;
    const chat_session_t *sessions = chat_engine_sessions(&count);
    chat_session_t *active = chat_engine_active_session();

    for (int i = count - 1; i >= 0; i--) {
        lv_obj_t *row = lv_obj_create(s_session_modal);
        lv_obj_set_size(row, lv_pct(100), 56);
        lv_obj_set_style_bg_color(row, CLR_BG_PANEL, 0);
        lv_obj_set_style_border_color(row, CLR_BORDER, 0);
        lv_obj_set_style_border_width(row, 0, 0);
        lv_obj_clear_flag(row, LV_OBJ_FLAG_SCROLLABLE);

        bool is_active = (active && strcmp(sessions[i].id, active->id) == 0);
        if (is_active) {
            lv_obj_set_style_border_width(row, 2, 0);
            lv_obj_set_style_border_color(row, CLR_BLUE, 0);
        }

        lv_obj_t *title = lv_label_create(row);
        lv_label_set_text(title, sessions[i].title[0] ? sessions[i].title : "Chat");
        lv_label_set_long_mode(title, LV_LABEL_LONG_DOT);
        lv_obj_set_width(title, lv_pct(65));
        lv_obj_set_style_text_color(title, is_active ? CLR_BLUE : CLR_TEXT, 0);
        lv_obj_set_style_text_font(title, APP_FONT_BODY, 0);
        lv_obj_align(title, LV_ALIGN_LEFT_MID, 8, 0);

        /* Delete button */
        lv_obj_t *del_btn = lv_button_create(row);
        lv_obj_set_size(del_btn, 40, 36);
        lv_obj_align(del_btn, LV_ALIGN_RIGHT_MID, -8, 0);
        lv_obj_set_style_bg_color(del_btn, CLR_RED, 0);
        lv_obj_set_style_radius(del_btn, 8, 0);
        lv_obj_t *del_lab = lv_label_create(del_btn);
        lv_label_set_text(del_lab, LV_SYMBOL_TRASH);
        lv_obj_center(del_lab);
        lv_obj_set_style_text_color(del_lab, CLR_TEXT, 0);
        lv_obj_set_style_text_font(del_lab, APP_FONT_BODY, 0);
        /* Cast away const for user_data — safe because the id string lives in the static sessions array */
        lv_obj_add_event_cb(del_btn, on_session_delete_clicked, LV_EVENT_CLICKED, (void *)sessions[i].id);

        /* Tap row to switch */
        lv_obj_add_event_cb(row, on_session_row_clicked, LV_EVENT_CLICKED, (void *)sessions[i].id);
    }
}

/* ---- Public API ---- */

void ui_chat_refresh(void)
{
    if (!s_list) return;
    lv_obj_clean(s_list);

    chat_session_t *sess = chat_engine_active_session();
    if (!sess) {
        lv_obj_t *l = lv_label_create(s_list);
        lv_label_set_text(l, "No chat yet");
        lv_obj_set_style_text_color(l, CLR_DIM, 0);
        lv_obj_set_style_text_font(l, APP_FONT_BODY, 0);
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
             wifi ? LV_SYMBOL_OK : LV_SYMBOL_CLOSE,
             wifi ? "WiFi" : "WiFi off",
             prov);
    lv_label_set_text(s_status_label, buf);
}

void ui_chat_show_keyboard(bool show)
{
    if (!s_keyboard) return;
    if (show) lv_obj_remove_flag(s_keyboard, LV_OBJ_FLAG_HIDDEN);
    else lv_obj_add_flag(s_keyboard, LV_OBJ_FLAG_HIDDEN);
}

void ui_chat_set_input_text(const char *text)
{
    if (s_textarea) lv_textarea_set_text(s_textarea, text ? text : "");
}
