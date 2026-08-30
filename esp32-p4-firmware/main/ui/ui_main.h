/*
 * ui_main.h - OmniChat-P4 LVGL UI
 */
#ifndef UI_MAIN_H
#define UI_MAIN_H

#include "lvgl.h"

/* Initialize the UI: create screens, bind to engine state. Call from lvgl task. */
void ui_init(lv_display_t *disp);

/* Switch between screens. screen: "home" | "chat" | "settings" */
void ui_go_screen(const char *screen);

/* Called from the engine listener (must be from LVGL task context, or marshal). */
void ui_refresh(void);

/* Show/hide the on-screen keyboard. */
void ui_show_keyboard(bool show);

/* Apply any marshalled engine updates. Called from the LVGL loop. */
void ui_pump(void);

#endif
