/*
 * ui_chat.h - chat screen (message list + input + keyboard)
 */
#ifndef UI_CHAT_H
#define UI_CHAT_H

#include "lvgl.h"

void ui_chat_init(lv_obj_t *parent, lv_display_t *disp);
void ui_chat_refresh(void);
void ui_chat_show_keyboard(bool show);
void ui_chat_set_input_text(const char *text);

/* Refresh the top status bar (WiFi / internet / active provider). Call from LVGL loop. */
void ui_chat_update_status(void);

#endif
