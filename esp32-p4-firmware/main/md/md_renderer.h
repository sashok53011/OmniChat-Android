/*
 * Markdown Renderer for ESP32-P4 LVGL
 * Renders parsed markdown blocks as LVGL widgets
 */
#pragma once

#include "md_parser.h"
#include "lvgl.h"

#ifdef __cplusplus
extern "C" {
#endif

// Render configuration
typedef struct {
    lv_color_t bg_color;         // Screen background
    lv_color_t text_color;       // Body text color
    lv_color_t code_bg_color;    // Code block background
    lv_color_t code_text_color;  // Code text color
    lv_color_t code_border_color;// Code block border
    lv_color_t h1_color;         // H1 color
    lv_color_t h2_color;         // H2 color
    lv_color_t h3_color;         // H3+ color
    lv_color_t link_color;       // Link color
    lv_color_t quote_color;      // Blockquote text color
    lv_color_t quote_border;     // Blockquote border color
    lv_color_t inline_code_bg;   // Inline code background
    lv_color_t hr_color;         // Horizontal rule color
    lv_color_t math_color;       // Math formula color
    lv_color_t syntax_keyword;   // Syntax: keywords
    lv_color_t syntax_string;    // Syntax: strings
    lv_color_t syntax_comment;   // Syntax: comments
    lv_color_t syntax_number;    // Syntax: numbers
    lv_color_t syntax_preproc;   // Syntax: preprocessor
    lv_color_t syntax_type;      // Syntax: types
    const lv_font_t *font_body;  // Body font
    const lv_font_t *font_code;  // Code font
    const lv_font_t *font_h1;    // H1 font
    const lv_font_t *font_h2;    // H2 font
    const lv_font_t *font_h3;    // H3+ font
    uint16_t content_width;      // Content width (screen - padding)
} md_render_config_t;

// Create default render configuration for 720x720 display
md_render_config_t md_create_default_config(void);

// Render a parsed document into an LVGL container
// The container should be scrollable and have enough height
void md_render_document(const md_document_t *doc, lv_obj_t *parent,
                        const md_render_config_t *config);

// Render a single block
void md_render_block(lv_obj_t *parent, const md_block_t *block,
                     const md_render_config_t *config);

#ifdef __cplusplus
}
#endif
