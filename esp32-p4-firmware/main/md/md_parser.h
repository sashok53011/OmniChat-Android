/*
 * Markdown Parser for ESP32-P4
 * Parses markdown text into structured blocks
 */
#pragma once

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Block types
typedef enum {
    MD_BLOCK_HEADER,      // # H1, ## H2, etc.
    MD_BLOCK_PARAGRAPH,   // Regular text
    MD_BLOCK_CODE,        // ``` code blocks
    MD_BLOCK_LIST_UL,     // - item or * item
    MD_BLOCK_LIST_OL,     // 1. item
    MD_BLOCK_QUOTE,       // > quote
    MD_BLOCK_HR,          // --- or ***
    MD_BLOCK_EMPTY,       // Empty line
} md_block_type_t;

// Inline text segment (for formatted text within a block)
typedef enum {
    INLINE_TEXT,     // Plain text
    INLINE_BOLD,     // **bold**
    INLINE_ITALIC,   // *italic*
    INLINE_CODE,     // `code`
    INLINE_LINK,     // [text](url)
    INLINE_MATH,     // $formula$
} md_inline_type_t;

// Inline text segment
typedef struct {
    md_inline_type_t type;
    char *text;       // Segment text (points into original string)
    uint16_t length;  // Length of text
} md_inline_t;

// Max inline segments per block
#define MD_MAX_INLINE 64

// Parsed block
typedef struct {
    md_block_type_t type;
    uint8_t header_level;  // 1-6 for headers
    char *text;            // Block text
    uint16_t length;       // Text length
    md_inline_t inline_segments[MD_MAX_INLINE];  // Formatted segments
    uint8_t inline_count;  // Number of inline segments
} md_block_t;

// Max blocks
#define MD_MAX_BLOCKS 256

// Parsed document
typedef struct {
    md_block_t blocks[MD_MAX_BLOCKS];
    uint16_t count;
} md_document_t;

// Parse markdown text into document structure
// Returns number of blocks parsed
int md_parse(const char *markdown, md_document_t *doc);

// Free parsed document (clears all allocated memory)
void md_free(md_document_t *doc);

// Parse inline formatting in a block
void md_parse_inline(md_block_t *block);

#ifdef __cplusplus
}
#endif
