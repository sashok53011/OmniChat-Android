/*
 * ui_settings.h - settings screen (WiFi, provider, prompt, TTS, memory)
 */
#ifndef UI_SETTINGS_H
#define UI_SETTINGS_H

#include "lvgl.h"

void ui_settings_init(lv_obj_t *parent, lv_display_t *disp);

/* Rebuild the main settings list (after edits). */
void ui_settings_rebuild(void);

/* Apply any queued test results. Called from the LVGL loop. */
void ui_settings_pump(void);

#endif
