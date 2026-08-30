/*
 * Simple Syntax Highlighter for C/C++/Python/JavaScript
 */
#include "syntax_highlight.h"
#include <string.h>
#include <ctype.h>

// C/C++ keywords
static const char *c_keywords[] = {
    "auto", "break", "case", "char", "const", "continue", "default", "do",
    "double", "else", "enum", "extern", "float", "for", "goto", "if",
    "inline", "int", "long", "register", "restrict", "return", "short",
    "signed", "sizeof", "static", "struct", "switch", "typedef", "union",
    "unsigned", "void", "volatile", "while",
    // C++ extras
    "class", "namespace", "template", "typename", "virtual", "override",
    "nullptr", "new", "delete", "this", "public", "private", "protected",
    "try", "catch", "throw", "operator", "friend", "using", "namespace",
    "bool", "true", "false",
    NULL
};

// Python keywords
static const char *py_keywords[] = {
    "and", "as", "assert", "async", "await", "break", "class", "continue",
    "def", "del", "elif", "else", "except", "finally", "for", "from",
    "global", "if", "import", "in", "is", "lambda", "nonlocal", "not",
    "or", "pass", "raise", "return", "try", "while", "with", "yield",
    "True", "False", "None", "print", "self",
    NULL
};

// JavaScript keywords
static const char *js_keywords[] = {
    "async", "await", "break", "case", "catch", "class", "const",
    "continue", "debugger", "default", "delete", "do", "else", "export",
    "extends", "finally", "for", "from", "function", "if", "import",
    "in", "instanceof", "let", "new", "of", "return", "static", "super",
    "switch", "this", "throw", "try", "typeof", "var", "void", "while",
    "with", "yield",
    "true", "false", "null", "undefined", "NaN", "Infinity",
    NULL
};

// Check if word matches any keyword in list
static bool is_keyword(const char *word, uint16_t len, const char **keywords)
{
    for (int i = 0; keywords[i] != NULL; i++) {
        uint16_t klen = strlen(keywords[i]);
        if (len == klen && memcmp(word, keywords[i], len) == 0) {
            return true;
        }
    }
    return false;
}

// Detect language from first line of code
static enum { LANG_C, LANG_PY, LANG_JS } detect_language(const char *code, uint16_t len)
{
    // Simple heuristic: look for #include or def or function
    if (len > 0 && code[0] == '#') {
        return LANG_C;  // Preprocessor = C/C++
    }
    // Check for Python
    if (strstr(code, "def ") != NULL || strstr(code, "import ") != NULL ||
        strstr(code, "class ") != NULL) {
        // Could be either, but check for Python-specific patterns
        if (strstr(code, "def ") != NULL && strstr(code, ":") != NULL) {
            return LANG_PY;
        }
    }
    return LANG_C;  // Default to C
}

int syntax_tokenize(const char *line, uint16_t len, code_token_t *tokens)
{
    if (!line || len == 0) {
        return 0;
    }

    int count = 0;
    int i = 0;

    // Detect language from first line
    enum { LANG_C, LANG_PY, LANG_JS } lang = LANG_C;

    while (i < len && count < MAX_TOKENS) {
        char c = line[i];

        // Whitespace
        if (c == ' ' || c == '\t') {
            int start = i;
            while (i < len && (line[i] == ' ' || line[i] == '\t')) {
                i++;
            }
            tokens[count].type = TOK_PLAIN;
            tokens[count].start = line + start;
            tokens[count].length = i - start;
            count++;
            continue;
        }

        // Preprocessor directive
        if (c == '#' && i == 0) {
            tokens[count].type = TOK_PREPROC;
            tokens[count].start = line + i;
            tokens[count].length = len - i;
            count++;
            break;
        }

        // Single-line comment //
        if (c == '/' && i + 1 < len && line[i + 1] == '/') {
            tokens[count].type = TOK_COMMENT;
            tokens[count].start = line + i;
            tokens[count].length = len - i;
            count++;
            break;
        }

        // Python comment #
        if (c == '#' && i > 0) {
            tokens[count].type = TOK_COMMENT;
            tokens[count].start = line + i;
            tokens[count].length = len - i;
            count++;
            break;
        }

        // String "..." or '...'
        if (c == '"' || c == '\'') {
            char quote = c;
            int start = i;
            i++;
            while (i < len && line[i] != quote) {
                if (line[i] == '\\' && i + 1 < len) {
                    i++;  // Skip escaped char
                }
                i++;
            }
            if (i < len) {
                i++;  // Skip closing quote
            }
            tokens[count].type = TOK_STRING;
            tokens[count].start = line + start;
            tokens[count].length = i - start;
            count++;
            continue;
        }

        // Number
        if (isdigit((unsigned char)c) ||
            (c == '.' && i + 1 < len && isdigit((unsigned char)line[i + 1]))) {
            int start = i;
            while (i < len && (isdigit((unsigned char)line[i]) ||
                               line[i] == '.' || line[i] == 'x' ||
                               line[i] == 'X' || line[i] == 'b' ||
                               line[i] == 'B' ||
                               (line[i] >= 'a' && line[i] <= 'f') ||
                               (line[i] >= 'A' && line[i] <= 'F') ||
                               line[i] == 'L' || line[i] == 'U' ||
                               line[i] == 'l' || line[i] == 'u')) {
                i++;
            }
            tokens[count].type = TOK_NUMBER;
            tokens[count].start = line + start;
            tokens[count].length = i - start;
            count++;
            continue;
        }

        // Word (identifier or keyword)
        if (isalpha((unsigned char)c) || c == '_') {
            int start = i;
            while (i < len && (isalnum((unsigned char)line[i]) || line[i] == '_')) {
                i++;
            }
            uint16_t wlen = i - start;
            const char *word = line + start;

            // Check for C keywords
            if (is_keyword(word, wlen, c_keywords)) {
                tokens[count].type = TOK_KEYWORD;
            }
            // Check for Python keywords
            else if (is_keyword(word, wlen, py_keywords)) {
                tokens[count].type = TOK_KEYWORD;
            }
            // Check for JS keywords
            else if (is_keyword(word, wlen, js_keywords)) {
                tokens[count].type = TOK_KEYWORD;
            }
            // Check for common types
            else if ((wlen >= 3 && wlen <= 12) &&
                     (memcmp(word, "int", 3) == 0 ||
                      memcmp(word, "char", 4) == 0 ||
                      memcmp(word, "void", 4) == 0 ||
                      memcmp(word, "bool", 4) == 0 ||
                      memcmp(word, "float", 5) == 0 ||
                      memcmp(word, "double", 6) == 0 ||
                      memcmp(word, "uint8_t", 7) == 0 ||
                      memcmp(word, "uint16_t", 8) == 0 ||
                      memcmp(word, "uint32_t", 8) == 0 ||
                      memcmp(word, "size_t", 6) == 0 ||
                      memcmp(word, "esp_err_t", 9) == 0 ||
                      memcmp(word, "bool", 4) == 0)) {
                tokens[count].type = TOK_TYPE;
            }
            else {
                tokens[count].type = TOK_PLAIN;
            }

            tokens[count].start = word;
            tokens[count].length = wlen;
            count++;
            continue;
        }

        // Other characters (braces, operators, etc.)
        {
            int start = i;
            i++;
            tokens[count].type = TOK_PLAIN;
            tokens[count].start = line + start;
            tokens[count].length = 1;
            count++;
        }
    }

    return count;
}

lv_color_t syntax_get_color(token_type_t type, const lv_color_t *colors)
{
    // colors array: [keyword, string, comment, number, preproc, type]
    switch (type) {
    case TOK_KEYWORD: return colors[0];
    case TOK_STRING:  return colors[1];
    case TOK_COMMENT: return colors[2];
    case TOK_NUMBER:  return colors[3];
    case TOK_PREPROC: return colors[4];
    case TOK_TYPE:    return colors[5];
    default:          return colors[6];  // Plain text color
    }
}
