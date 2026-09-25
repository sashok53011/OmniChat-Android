/*
 * Syntax Highlighter for C/C++/Python/JavaScript/Go/Rust/Java/Bash/SQL
 */
#include "syntax_highlight.h"
#include <string.h>
#include <ctype.h>

static const char *c_keywords[] = {
    "auto", "break", "case", "char", "const", "continue", "default", "do",
    "double", "else", "enum", "extern", "float", "for", "goto", "if",
    "inline", "int", "long", "register", "restrict", "return", "short",
    "signed", "sizeof", "static", "struct", "switch", "typedef", "union",
    "unsigned", "void", "volatile", "while",
    "class", "namespace", "template", "typename", "virtual", "override",
    "nullptr", "new", "delete", "this", "public", "private", "protected",
    "try", "catch", "throw", "operator", "friend", "using", "namespace",
    "bool", "true", "false",
    NULL
};

static const char *py_keywords[] = {
    "and", "as", "assert", "async", "await", "break", "class", "continue",
    "def", "del", "elif", "else", "except", "finally", "for", "from",
    "global", "if", "import", "in", "is", "lambda", "nonlocal", "not",
    "or", "pass", "raise", "return", "try", "while", "with", "yield",
    "True", "False", "None", "print", "self",
    NULL
};

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

static const char *go_keywords[] = {
    "break", "case", "chan", "const", "continue", "default", "defer",
    "else", "fallthrough", "for", "func", "go", "goto", "if", "import",
    "interface", "map", "package", "range", "return", "select", "struct",
    "switch", "type", "var",
    "true", "false", "iota", "nil",
    "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16",
    "uint32", "uint64", "float32", "float64", "complex64", "complex128",
    "bool", "byte", "rune", "string", "error", "any",
    "make", "len", "cap", "append", "copy", "delete", "new", "panic",
    "recover", "print", "println",
    NULL
};

static const char *rust_keywords[] = {
    "as", "async", "await", "break", "const", "continue", "crate",
    "dyn", "else", "enum", "extern", "false", "fn", "for", "if",
    "impl", "in", "let", "loop", "match", "mod", "move", "mut",
    "pub", "ref", "return", "self", "Self", "static", "struct",
    "super", "trait", "true", "type", "unsafe", "use", "where", "while",
    "abstract", "become", "box", "do", "final", "macro", "override",
    "priv", "typeof", "unsized", "virtual", "yield",
    "i8", "i16", "i32", "i64", "i128", "isize",
    "u8", "u16", "u32", "u64", "u128", "usize",
    "f32", "f64", "bool", "char", "str", "String",
    "Option", "Result", "Some", "None", "Ok", "Err",
    "Vec", "Box", "Rc", "Arc", "HashMap", "HashSet",
    "println", "print", "format", "vec", "panic",
    NULL
};

static const char *java_keywords[] = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch",
    "char", "class", "const", "continue", "default", "do", "double",
    "else", "enum", "extends", "final", "finally", "float", "for",
    "goto", "if", "implements", "import", "instanceof", "int",
    "interface", "long", "native", "new", "package", "private",
    "protected", "public", "return", "short", "static", "strictfp",
    "super", "switch", "synchronized", "this", "throw", "throws",
    "transient", "try", "void", "volatile", "while",
    "true", "false", "null",
    "String", "System", "Integer", "Double", "Float", "Boolean",
    "Object", "List", "Map", "Set", "ArrayList", "HashMap",
    "System.out.println", "System.out.print",
    NULL
};

static const char *bash_keywords[] = {
    "if", "then", "else", "elif", "fi", "for", "while", "do", "done",
    "case", "esac", "function", "return", "exit", "local", "export",
    "source", "alias", "unalias", "echo", "printf", "read", "test",
    "set", "unset", "shift", "exec", "eval", "trap", "wait",
    "cd", "pwd", "pushd", "popd", "dirs",
    "ls", "cat", "grep", "sed", "awk", "find", "sort", "uniq", "wc",
    "head", "tail", "cut", "tr", "tee", "xargs", "chmod", "chown",
    "mkdir", "rm", "cp", "mv", "ln", "touch",
    "curl", "wget", "ssh", "scp", "rsync",
    "git", "docker", "npm", "pip",
    "true", "false", "null",
    NULL
};

static const char *sql_keywords[] = {
    "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE",
    "SET", "DELETE", "CREATE", "TABLE", "DROP", "ALTER", "INDEX",
    "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "ON",
    "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "UNION",
    "ALL", "DISTINCT", "AS", "AND", "OR", "NOT", "IN", "EXISTS",
    "BETWEEN", "LIKE", "IS", "NULL", "TRUE", "FALSE",
    "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE",
    "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT",
    "INTEGER", "TEXT", "REAL", "BLOB", "VARCHAR", "BOOLEAN",
    "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION",
    "IF", "ELSE", "CASE", "WHEN", "THEN", "END",
    "ASC", "DESC", "NULLS", "FIRST", "LAST",
    NULL
};

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

static bool is_type_word(const char *word, uint16_t len)
{
    static const char *types[] = {
        "int", "char", "void", "bool", "float", "double", "size_t",
        "uint8_t", "uint16_t", "uint32_t", "uint64_t", "int8_t", "int16_t",
        "int32_t", "int64_t", "esp_err_t", "esp_event_handler_t",
        "lv_obj_t", "lv_event_t", "lv_color_t", "lv_coord_t",
        "cJSON", "FILE",
        "string", "vector", "map", "set", "list", "array",
        "nullptr", "true", "false", "NULL",
        "None", "True", "False", "self",
        "undefined", "NaN", "Infinity",
        "iota", "nil", "any", "error", "rune", "byte",
        "isize", "usize", "f32", "f64", "i8", "i16", "i32", "i64",
        "u8", "u16", "u32", "u64",
        "Option", "Result", "Some", "None", "Ok", "Err",
        "Vec", "Box", "Rc", "Arc", "String", "str",
        "Object", "System",
        NULL
    };
    for (int i = 0; types[i]; i++) {
        uint16_t tlen = strlen(types[i]);
        if (len == tlen && memcmp(word, types[i], len) == 0) return true;
    }
    return false;
}

int syntax_tokenize(const char *line, uint16_t len, code_token_t *tokens)
{
    if (!line || len == 0) return 0;

    int count = 0;
    int i = 0;

    while (i < len && count < MAX_TOKENS) {
        char c = line[i];

        if (c == ' ' || c == '\t') {
            int start = i;
            while (i < len && (line[i] == ' ' || line[i] == '\t')) i++;
            tokens[count].type = TOK_PLAIN;
            tokens[count].start = line + start;
            tokens[count].length = i - start;
            count++;
            continue;
        }

        if (c == '#' && i == 0) {
            tokens[count].type = TOK_PREPROC;
            tokens[count].start = line + i;
            tokens[count].length = len - i;
            count++;
            break;
        }

        if (c == '/' && i + 1 < len && line[i + 1] == '/') {
            tokens[count].type = TOK_COMMENT;
            tokens[count].start = line + i;
            tokens[count].length = len - i;
            count++;
            break;
        }

        if (c == '#' && i > 0) {
            tokens[count].type = TOK_COMMENT;
            tokens[count].start = line + i;
            tokens[count].length = len - i;
            count++;
            break;
        }

        if (c == '-' && i + 1 < len && line[i + 1] == '-') {
            tokens[count].type = TOK_COMMENT;
            tokens[count].start = line + i;
            tokens[count].length = len - i;
            count++;
            break;
        }

        if (c == '"' || c == '\'') {
            char quote = c;
            int start = i;
            i++;
            while (i < len && line[i] != quote) {
                if (line[i] == '\\' && i + 1 < len) i++;
                i++;
            }
            if (i < len) i++;
            tokens[count].type = TOK_STRING;
            tokens[count].start = line + start;
            tokens[count].length = i - start;
            count++;
            continue;
        }

        if (c == '`') {
            int start = i;
            i++;
            while (i < len && line[i] != '`') i++;
            if (i < len) i++;
            tokens[count].type = TOK_STRING;
            tokens[count].start = line + start;
            tokens[count].length = i - start;
            count++;
            continue;
        }

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

        if (isalpha((unsigned char)c) || c == '_') {
            int start = i;
            while (i < len && (isalnum((unsigned char)line[i]) || line[i] == '_')) i++;
            uint16_t wlen = i - start;
            const char *word = line + start;

            if (is_keyword(word, wlen, c_keywords) ||
                is_keyword(word, wlen, py_keywords) ||
                is_keyword(word, wlen, js_keywords) ||
                is_keyword(word, wlen, go_keywords) ||
                is_keyword(word, wlen, rust_keywords) ||
                is_keyword(word, wlen, java_keywords) ||
                is_keyword(word, wlen, bash_keywords) ||
                is_keyword(word, wlen, sql_keywords)) {
                tokens[count].type = TOK_KEYWORD;
            } else if (is_type_word(word, wlen)) {
                tokens[count].type = TOK_TYPE;
            } else {
                tokens[count].type = TOK_PLAIN;
            }

            tokens[count].start = word;
            tokens[count].length = wlen;
            count++;
            continue;
        }

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
    switch (type) {
    case TOK_KEYWORD: return colors[0];
    case TOK_STRING:  return colors[1];
    case TOK_COMMENT: return colors[2];
    case TOK_NUMBER:  return colors[3];
    case TOK_PREPROC: return colors[4];
    case TOK_TYPE:    return colors[5];
    default:          return colors[6];
    }
}
