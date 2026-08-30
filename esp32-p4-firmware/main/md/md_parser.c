/*
 * Markdown Parser for ESP32-P4
 * Parses markdown text into structured blocks with inline formatting
 */
#include "md_parser.h"
#include <string.h>
#include <stdlib.h>
#include <ctype.h>

// ============================================================
// Helpers
// ============================================================

static char *strdup_range(const char *start, uint16_t len)
{
    char *s = (char *)malloc(len + 1);
    if (s) {
        memcpy(s, start, len);
        s[len] = '\0';
    }
    return s;
}

static const char *skip_spaces(const char *p)
{
    while (*p == ' ' || *p == '\t') {
        p++;
    }
    return p;
}

static int count_char(const char *s, char c)
{
    int n = 0;
    while (*s) {
        if (*s == c) {
            n++;
        }
        s++;
    }
    return n;
}

// ============================================================
// Block-level parsing
// ============================================================

static bool is_header(const char *line, uint16_t len, uint8_t *level)
{
    if (len == 0 || line[0] != '#') {
        return false;
    }
    uint8_t lvl = 0;
    while (lvl < len && line[lvl] == '#') {
        lvl++;
    }
    if (lvl > 6 || lvl == 0) {
        return false;
    }
    if (lvl < len && line[lvl] == ' ') {
        *level = lvl;
        return true;
    }
    return false;
}

static bool is_code_fence(const char *line, uint16_t len)
{
    return (len >= 3 && line[0] == '`' && line[1] == '`' && line[2] == '`');
}

static bool is_hr(const char *line, uint16_t len)
{
    const char *p = skip_spaces(line);
    int dashes = 0;
    while (*p == '-') {
        dashes++;
        p++;
    }
    while (*p == ' ') {
        p++;
    }
    return (*p == '\0' || *p == '\n') && dashes >= 3;
}

static bool is_list_ul(const char *line, uint16_t len)
{
    const char *p = skip_spaces(line);
    return (*p == '-' || *p == '*') && (p[1] == ' ' || p[1] == '\t');
}

static bool is_list_ol(const char *line, uint16_t len)
{
    const char *p = skip_spaces(line);
    if (!isdigit((unsigned char)*p)) {
        return false;
    }
    while (isdigit((unsigned char)*p)) {
        p++;
    }
    return (*p == '.' || *p == ')') && (p[1] == ' ' || p[1] == '\t');
}

static bool is_blockquote(const char *line, uint16_t len)
{
    const char *p = skip_spaces(line);
    return (*p == '>' && (p[1] == ' ' || p[1] == '\0' || p[1] == '\n'));
}

// ============================================================
// Inline text parsing
// ============================================================

void md_parse_inline(md_block_t *block)
{
    if (!block || !block->text || block->length == 0) {
        block->inline_count = 0;
        return;
    }

    const char *text = block->text;
    uint16_t len = block->length;
    md_inline_t *segs = block->inline_segments;
    int seg_count = 0;

    int i = 0;
    int seg_start = 0;

    while (i < len && seg_count < MD_MAX_INLINE) {
        char c = text[i];

        // Escaped character
        if (c == '\\' && i + 1 < len) {
            // Flush plain text before escape
            if (i > seg_start) {
                segs[seg_count].type = INLINE_TEXT;
                segs[seg_count].text = (char *)(text + seg_start);
                segs[seg_count].length = i - seg_start;
                seg_count++;
            }
            // The escaped character becomes plain text
            segs[seg_count].type = INLINE_TEXT;
            segs[seg_count].text = (char *)(text + i + 1);
            segs[seg_count].length = 1;
            seg_count++;
            i += 2;
            seg_start = i;
            continue;
        }

        // Inline code `...`
        if (c == '`') {
            int end = i + 1;
            while (end < len && text[end] != '`') {
                end++;
            }
            if (end < len) {
                // Flush plain text
                if (i > seg_start) {
                    segs[seg_count].type = INLINE_TEXT;
                    segs[seg_count].text = (char *)(text + seg_start);
                    segs[seg_count].length = i - seg_start;
                    seg_count++;
                }
                // Code segment (skip backticks)
                segs[seg_count].type = INLINE_CODE;
                segs[seg_count].text = (char *)(text + i + 1);
                segs[seg_count].length = end - i - 1;
                seg_count++;
                i = end + 1;
                seg_start = i;
                continue;
            }
        }

        // Math $...$
        if (c == '$') {
            int end = i + 1;
            while (end < len && text[end] != '$') {
                end++;
            }
            if (end < len && end > i + 1) {
                // Flush plain text
                if (i > seg_start) {
                    segs[seg_count].type = INLINE_TEXT;
                    segs[seg_count].text = (char *)(text + seg_start);
                    segs[seg_count].length = i - seg_start;
                    seg_count++;
                }
                // Math segment (skip $)
                segs[seg_count].type = INLINE_MATH;
                segs[seg_count].text = (char *)(text + i + 1);
                segs[seg_count].length = end - i - 1;
                seg_count++;
                i = end + 1;
                seg_start = i;
                continue;
            }
        }

        // Link [text](url)
        if (c == '[') {
            int close_bracket = -1;
            for (int j = i + 1; j < len; j++) {
                if (text[j] == ']') {
                    close_bracket = j;
                    break;
                }
            }
            if (close_bracket > 0 && close_bracket + 1 < len &&
                text[close_bracket + 1] == '(') {
                int close_paren = -1;
                for (int j = close_bracket + 2; j < len; j++) {
                    if (text[j] == ')') {
                        close_paren = j;
                        break;
                    }
                }
                if (close_paren > 0) {
                    // Flush plain text
                    if (i > seg_start) {
                        segs[seg_count].type = INLINE_TEXT;
                        segs[seg_count].text = (char *)(text + seg_start);
                        segs[seg_count].length = i - seg_start;
                        seg_count++;
                    }
                    // Link text
                    segs[seg_count].type = INLINE_LINK;
                    segs[seg_count].text = (char *)(text + i + 1);
                    segs[seg_count].length = close_bracket - i - 1;
                    seg_count++;
                    i = close_paren + 1;
                    seg_start = i;
                    continue;
                }
            }
        }

        // Bold **...**
        if (c == '*' && i + 1 < len && text[i + 1] == '*') {
            int end = i + 2;
            while (end + 1 < len && !(text[end] == '*' && text[end + 1] == '*')) {
                end++;
            }
            if (end + 1 < len) {
                // Flush plain text
                if (i > seg_start) {
                    segs[seg_count].type = INLINE_TEXT;
                    segs[seg_count].text = (char *)(text + seg_start);
                    segs[seg_count].length = i - seg_start;
                    seg_count++;
                }
                // Bold segment
                segs[seg_count].type = INLINE_BOLD;
                segs[seg_count].text = (char *)(text + i + 2);
                segs[seg_count].length = end - i - 2;
                seg_count++;
                i = end + 2;
                seg_start = i;
                continue;
            }
        }

        // Italic *...*
        if (c == '*') {
            int end = i + 1;
            while (end < len && text[end] != '*') {
                end++;
            }
            if (end < len && end > i + 1) {
                // Flush plain text
                if (i > seg_start) {
                    segs[seg_count].type = INLINE_TEXT;
                    segs[seg_count].text = (char *)(text + seg_start);
                    segs[seg_count].length = i - seg_start;
                    seg_count++;
                }
                // Italic segment
                segs[seg_count].type = INLINE_ITALIC;
                segs[seg_count].text = (char *)(text + i + 1);
                segs[seg_count].length = end - i - 1;
                seg_count++;
                i = end + 1;
                seg_start = i;
                continue;
            }
        }

        i++;
    }

    // Flush remaining plain text
    if (i > seg_start && seg_count < MD_MAX_INLINE) {
        segs[seg_count].type = INLINE_TEXT;
        segs[seg_count].text = (char *)(text + seg_start);
        segs[seg_count].length = i - seg_start;
        seg_count++;
    }

    block->inline_count = seg_count;
}

// ============================================================
// Main parse function
// ============================================================

int md_parse(const char *markdown, md_document_t *doc)
{
    if (!markdown || !doc) {
        return 0;
    }

    memset(doc, 0, sizeof(md_document_t));
    const char *p = markdown;
    int block_count = 0;

    while (*p && block_count < MD_MAX_BLOCKS) {
        // Skip leading newlines
        while (*p == '\n') {
            p++;
        }
        if (*p == '\0') {
            break;
        }

        // Find end of current line
        const char *line_start = p;
        while (*p && *p != '\n') {
            p++;
        }
        uint16_t line_len = p - line_start;

        // Skip trailing \r
        if (line_len > 0 && line_start[line_len - 1] == '\r') {
            line_len--;
        }

        md_block_t *block = &doc->blocks[block_count];
        memset(block, 0, sizeof(md_block_t));

        // Empty line
        if (line_len == 0) {
            block->type = MD_BLOCK_EMPTY;
            block_count++;
            continue;
        }

        // Header
        uint8_t header_level;
        if (is_header(line_start, line_len, &header_level)) {
            block->type = MD_BLOCK_HEADER;
            block->header_level = header_level;
            // Skip "# "
            const char *text_start = line_start + header_level + 1;
            uint16_t text_len = line_len - header_level - 1;
            // Trim trailing spaces
            while (text_len > 0 && text_start[text_len - 1] == ' ') {
                text_len--;
            }
            block->text = strdup_range(text_start, text_len);
            block->length = text_len;
            md_parse_inline(block);
            block_count++;
            continue;
        }

        // Horizontal rule
        if (is_hr(line_start, line_len)) {
            block->type = MD_BLOCK_HR;
            block_count++;
            continue;
        }

        // Code block
        if (is_code_fence(line_start, line_len)) {
            block->type = MD_BLOCK_CODE;
            // Skip the opening ``` line
            // Find language tag if any
            const char *lang = line_start + 3;
            while (*lang == ' ') {
                lang++;
            }
            // Collect code until closing ```
            const char *code_start = p + 1;  // After \n
            const char *code_end = code_start;
            while (*code_end) {
                const char *line = code_end;
                while (*code_end && *code_end != '\n') {
                    code_end++;
                }
                uint16_t clen = code_end - line;
                if (clen >= 3 && line[0] == '`' && line[1] == '`' &&
                    line[2] == '`') {
                    break;
                }
                if (*code_end == '\n') {
                    code_end++;
                }
            }
            uint16_t code_len = code_end - code_start;
            // Trim trailing newline
            while (code_len > 0 &&
                   (code_start[code_len - 1] == '\n' ||
                    code_start[code_len - 1] == '\r')) {
                code_len--;
            }
            block->text = strdup_range(code_start, code_len);
            block->length = code_len;
            block->inline_count = 0;
            p = code_end;
            block_count++;
            continue;
        }

        // Blockquote
        if (is_blockquote(line_start, line_len)) {
            block->type = MD_BLOCK_QUOTE;
            const char *text_start = line_start + 1;
            while (*text_start == ' ') {
                text_start++;
            }
            uint16_t text_len = line_len - (text_start - line_start);
            // Trim trailing spaces
            while (text_len > 0 && text_start[text_len - 1] == ' ') {
                text_len--;
            }
            block->text = strdup_range(text_start, text_len);
            block->length = text_len;
            md_parse_inline(block);
            block_count++;
            continue;
        }

        // Unordered list
        if (is_list_ul(line_start, line_len)) {
            block->type = MD_BLOCK_LIST_UL;
            const char *text_start = line_start;
            while (*text_start == ' ' || *text_start == '\t') {
                text_start++;
            }
            text_start += 2;  // Skip "- "
            uint16_t text_len = line_len - (text_start - line_start);
            // Trim trailing spaces
            while (text_len > 0 && text_start[text_len - 1] == ' ') {
                text_len--;
            }
            block->text = strdup_range(text_start, text_len);
            block->length = text_len;
            md_parse_inline(block);
            block_count++;
            continue;
        }

        // Ordered list
        if (is_list_ol(line_start, line_len)) {
            block->type = MD_BLOCK_LIST_OL;
            const char *text_start = line_start;
            while (*text_start == ' ' || *text_start == '\t') {
                text_start++;
            }
            // Skip digits and . or )
            while (isdigit((unsigned char)*text_start)) {
                text_start++;
            }
            text_start += 2;  // Skip ". "
            uint16_t text_len = line_len - (text_start - line_start);
            // Trim trailing spaces
            while (text_len > 0 && text_start[text_len - 1] == ' ') {
                text_len--;
            }
            block->text = strdup_range(text_start, text_len);
            block->length = text_len;
            md_parse_inline(block);
            block_count++;
            continue;
        }

        // Regular paragraph
        block->type = MD_BLOCK_PARAGRAPH;
        block->text = strdup_range(line_start, line_len);
        block->length = line_len;
        md_parse_inline(block);
        block_count++;
    }

    doc->count = block_count;
    return block_count;
}

void md_free(md_document_t *doc)
{
    if (!doc) {
        return;
    }
    for (int i = 0; i < doc->count; i++) {
        if (doc->blocks[i].text) {
            free(doc->blocks[i].text);
            doc->blocks[i].text = NULL;
        }
    }
    doc->count = 0;
}
