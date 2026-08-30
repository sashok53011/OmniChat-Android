/*
 * Simple Syntax Highlighter for C/C++/Python/JavaScript
 * Tokenizes code and assigns colors to different elements
 */
#pragma once

#include "lvgl.h"
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Token types
typedef enum {
    TOK_PLAIN,
    TOK_KEYWORD,
    TOK_STRING,
    TOK_COMMENT,
    TOK_NUMBER,
    TOK_PREPROC,
    TOK_TYPE,
} token_type_t;

// Token
typedef struct {
    token_type_t type;
    const char *start;
    uint16_t length;
} code_token_t;

// Max tokens per line
#define MAX_TOKENS 128

// Tokenize a line of code
// Returns number of tokens
int syntax_tokenize(const char *line, uint16_t len, code_token_t *tokens);

// Get color for token type
lv_color_t syntax_get_color(token_type_t type, const lv_color_t *colors);

#ifdef __cplusplus
}
#endif
