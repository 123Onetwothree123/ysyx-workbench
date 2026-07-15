// SPDX-License-Identifier: GPL-2.0
// C++23 Recursive Descent Parser for Linux Kconfig
// Pure getc-based scanner, no fixed-size buffers

module;

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cctype>
#include <cstdarg>
#include <climits>
#include <string>
#include <vector>
#include <string_view>
#include <sys/stat.h>

module npc.kconfig;

// ============================================================
// C-linkage compat
// ============================================================
int yynerrs = 0;

file *current_pos_file = nullptr;
int current_pos_lineno = 0;

extern "C" {
int zconf_lineno() { return current_pos_lineno; }
const char *zconf_curname() {
    return current_pos_file ? current_pos_file->name.c_str() : "<none>";
}
void zconf_error(const char *err, ...) {
    va_list ap;
    yynerrs++;
    fprintf(stderr, "%s:%d: ", zconf_curname(), zconf_lineno());
    va_start(ap, err);
    vfprintf(stderr, err, ap);
    va_end(ap);
    fprintf(stderr, "\n");
    if (strstr(err, "unknown option") || strstr(err, "invalid statement")
        || strstr(err, "recursive dependency"))
        yynerrs--;
}
FILE *zconf_fopen(const char *name) {
    if (!name) return nullptr;
    FILE *f = fopen(name, "r");
    if (!f && name[0] != '/') {
        char *env = getenv("srctree");
        if (env) {
            char fullname[PATH_MAX + 1];
            snprintf(fullname, sizeof(fullname), "%s/%s", env, name);
            f = fopen(fullname, "r");
        }
    }
    return f;
}
int yyparse() { return 0; }
} // extern "C"

void zconf_warn(const char *err, ...) {
    va_list ap;
    fprintf(stderr, "%s:%d:warning: ", zconf_curname(), zconf_lineno());
    va_start(ap, err);
    vfprintf(stderr, err, ap);
    va_end(ap);
    fprintf(stderr, "\n");
}

// ============================================================
// Scanner state (byte-level)
// ============================================================
struct SourceFrame {
    FILE *fp;
    std::string filename;
    int lineno;
    SourceFrame *parent;
};
static SourceFrame *sf = nullptr;

static std::string pushback;

static int read_char() {
    while (true) {
        if (!pushback.empty()) {
            int c = (unsigned char)pushback.back();
            pushback.pop_back();
            return c;
        }
        if (!sf || !sf->fp) return EOF;
        int c = fgetc(sf->fp);
        if (c == '\\') {
            int n = fgetc(sf->fp);
            if (n == '\n') { sf->lineno++; continue; }
            if (n != EOF) { pushback += (char)n; }
            return '\\';
        }
        if (c == '\n') sf->lineno++;
        return c;
    }
}

static void push_char(int c) { if (c != EOF) pushback += (char)c; }

static void push_line(const std::string &line) {
    for (int i = (int)line.size() - 1; i >= 0; i--)
        pushback += line[i];
}

// ============================================================
// Token kinds
// ============================================================
enum Tok : int {
    T_EOF, T_EOL,
    T_WORD, T_WORD_QUOTE,
    T_CONFIG, T_MENUCONFIG, T_MENU, T_ENDMENU,
    T_CHOICE, T_ENDCHOICE, T_COMMENT, T_SOURCE,
    T_IF, T_ENDIF,
    T_BOOL, T_TRISTATE, T_INT, T_HEX_TYPE, T_STRING_TYPE,
    T_DEF_BOOL, T_DEF_TRISTATE,
    T_DEFAULT, T_SELECT, T_IMPLY, T_RANGE,
    T_DEPENDS, T_ON, T_PROMPT, T_OPTION, T_OPTIONAL,
    T_HELP_KW, T_MAINMENU, T_MODULES, T_DEFCONFIG_LIST, T_ALLNOCONFIG_Y,
    T_VISIBLE,
    T_NOT, T_AND, T_OR,
    T_EQUAL, T_UNEQUAL, T_LESS, T_LESS_EQ, T_GREATER, T_GREATER_EQ,
    T_OPEN_PAREN, T_CLOSE_PAREN,
    T_COLON_EQ, T_PLUS_EQ
};

static Tok tok = T_EOL;
static Tok prev_tok = T_EOL;
static Tok prev_prev_tok = T_EOL;
static std::string tok_text;
static bool assign_val_mode = false;

// ============================================================
// Help text state
// ============================================================
static bool in_help = false;
static std::string help_text;
static int help_indent = 0;
static bool help_indent_set = false;

// ============================================================
// Raw tokenizer
// ============================================================

static std::string read_quoted_string(int quote) {
    std::string raw;
    while (true) {
        int c = read_char();
        if (c == EOF) break;
        if (c == quote) break;
        if (c == '\\') {
            int n = read_char();
            if (n != EOF) raw += (char)n;
        } else {
            raw += (char)c;
        }
    }
    std::string result;
    const char *p = raw.c_str();
    while (*p) {
        if (*p == '$' && (p[1] == '(' || p[1] == '{'
                          || isalnum((unsigned char)p[1]) || p[1] == '_')) {
            p++;
            char *exp = expand_dollar(&p);
            if (exp) { result += exp; free(exp); }
        } else {
            result += *p++;
        }
    }
    return result;
}

static std::string read_word() {
    std::string s;
    while (true) {
        int c = read_char();
        if (c == EOF) break;
        if (isalnum(c) || c == '_' || c == '-' || c == '$') {
            s += (char)c;
        } else {
            push_char(c);
            break;
        }
    }
    if (s.find('$') != std::string::npos) {
        const char *sp = s.c_str();
        char *exp = expand_one_token(&sp);
        std::string r = exp ? exp : "";
        free(exp);
        return r;
    }
    return s;
}

static std::string read_assign_val() {
    std::string s;
    while (true) {
        int c = read_char();
        if (c == EOF) break;
        if (c == '\n') break;
        s += (char)c;
    }
    while (!s.empty() && isspace((unsigned char)s.back()))
        s.pop_back();
    return s;
}

static Tok match_kw(const std::string &s) {
    if (s == "config")           return T_CONFIG;
    if (s == "menuconfig")       return T_MENUCONFIG;
    if (s == "menu")             return T_MENU;
    if (s == "endmenu")          return T_ENDMENU;
    if (s == "choice")           return T_CHOICE;
    if (s == "endchoice")        return T_ENDCHOICE;
    if (s == "comment")          return T_COMMENT;
    if (s == "source")           return T_SOURCE;
    if (s == "if")               return T_IF;
    if (s == "endif")            return T_ENDIF;
    if (s == "bool")             return T_BOOL;
    if (s == "tristate")         return T_TRISTATE;
    if (s == "int")              return T_INT;
    if (s == "hex")              return T_HEX_TYPE;
    if (s == "string")           return T_STRING_TYPE;
    if (s == "def_bool")         return T_DEF_BOOL;
    if (s == "def_tristate")     return T_DEF_TRISTATE;
    if (s == "default")          return T_DEFAULT;
    if (s == "select")           return T_SELECT;
    if (s == "imply")            return T_IMPLY;
    if (s == "range")            return T_RANGE;
    if (s == "depends")          return T_DEPENDS;
    if (s == "on")               return T_ON;
    if (s == "prompt")           return T_PROMPT;
    if (s == "option")           return T_OPTION;
    if (s == "optional")         return T_OPTIONAL;
    if (s == "help")             return T_HELP_KW;
    if (s == "mainmenu")         return T_MAINMENU;
    if (s == "modules")          return T_MODULES;
    if (s == "defconfig_list")   return T_DEFCONFIG_LIST;
    if (s == "allnoconfig_y")    return T_ALLNOCONFIG_Y;
    if (s == "visible")          return T_VISIBLE;
    return T_WORD;
}

static Tok scan_token() {
    if (assign_val_mode) {
        assign_val_mode = false;
        tok_text = read_assign_val();
        return T_WORD_QUOTE; // signal: value is in tok_text
    }

    while (true) {
        int c = read_char();
        if (c == EOF) return T_EOF;

        if (c == ' ' || c == '\t' || c == '\r') continue;

        if (c == '\n') return T_EOL;

        if (c == '#') {
            while (true) {
                c = read_char();
                if (c == EOF || c == '\n') break;
            }
            if (c == EOF) return T_EOF;
            return T_EOL;
        }

        if (c == '"' || c == '\'') {
            tok_text = read_quoted_string(c);
            return T_WORD_QUOTE;
        }

        if (c == '&') {
            int n = read_char();
            if (n == '&') return T_AND;
            push_char(n);
            goto unknown;
        }
        if (c == '|') {
            int n = read_char();
            if (n == '|') return T_OR;
            push_char(n);
            goto unknown;
        }
        if (c == '!') {
            int n = read_char();
            if (n == '=') return T_UNEQUAL;
            push_char(n);
            return T_NOT;
        }
        if (c == '<') {
            int n = read_char();
            if (n == '=') return T_LESS_EQ;
            push_char(n);
            return T_LESS;
        }
        if (c == '>') {
            int n = read_char();
            if (n == '=') return T_GREATER_EQ;
            push_char(n);
            return T_GREATER;
        }
        if (c == ':') {
            int n = read_char();
            if (n == '=') return T_COLON_EQ;
            push_char(n);
            goto unknown;
        }
        if (c == '+') {
            int n = read_char();
            if (n == '=') return T_PLUS_EQ;
            push_char(n);
            goto unknown;
        }
        if (c == '=') return T_EQUAL;
        if (c == '(') return T_OPEN_PAREN;
        if (c == ')') return T_CLOSE_PAREN;

        if (isalnum(c) || c == '_' || c == '-' || c == '$') {
            push_char(c);
            tok_text = read_word();
            return match_kw(tok_text);
        }

        unknown:
        fprintf(stderr, "%s:%d:warning: ignoring unsupported character '%c'\n",
                zconf_curname(), sf ? sf->lineno : 0, c);
    }
}

// ============================================================
// Help text consumer / advance (mutually recursive forward decl)
// ============================================================
static Tok advance();
extern "C" void zconf_starthelp();
extern "C" void zconf_nextfile(const char *name);

void consume_help() {
    in_help = true;
    help_text.clear();
    help_indent = 0;
    help_indent_set = false;

    while (true) {
        std::string line;
        while (true) {
            int c = read_char();
            if (c == EOF) break;
            if (c == '\n') { line += (char)c; break; }
            line += (char)c;
        }
        if (line.empty()) break;

        int indent = 0;
        const char *cp = line.c_str();
        while (*cp == ' ' || *cp == '\t') {
            indent += (*cp == '\t') ? (8 - (indent & 7)) : 1;
            cp++;
        }
        bool is_blank = (*cp == '\n' || *cp == '\0');

        if (!help_indent_set) {
            if (is_blank) continue;
            help_indent_set = true;
            help_indent = indent;
            std::string content = cp;
            if (!content.empty() && content.back() == '\n')
                content.pop_back();
            help_text = content;
            continue;
        }

        if (is_blank) {
            int next = read_char();
            if (next == EOF) {
                help_text += '\n';
                break;
            }
            if (next == ' ' || next == '\t' || next == '\n') {
                help_text += '\n';
                push_char(next);
                continue;
            }
            push_char(next);
            break;
        }

        if (indent < help_indent) {
            push_line(line);
            break;
        }

        std::string content = cp;
        if (!content.empty() && content.back() == '\n')
            content.pop_back();
        help_text += '\n';
        int extra = indent - help_indent;
        if (extra > 0) help_text += std::string(extra, ' ');
        help_text += content;
    }

    in_help = false;

    if (!current_entry->help.empty()) {
        current_entry->help.clear();
        zconf_warn("'%s' defined with more than one help text",
                   current_entry->sym ? current_entry->sym->name.c_str() : "<choice>");
    }
    while (!help_text.empty() && help_text.back() == '\n')
        help_text.pop_back();
    if (!help_text.empty() && help_text[0] == '\n')
        help_text = help_text.substr(1);
    current_entry->help = help_text;

    advance();
}

// ============================================================
// Token advance
// ============================================================
Tok advance() {
    while (true) {
        tok = scan_token();

        bool prev_is_term = (prev_tok == T_EOL);
        if (prev_is_term && tok == T_EOL) continue;

        if (prev_is_term && tok != T_EOF) {
            current_pos_file = current_file;
            current_pos_lineno = sf ? sf->lineno : 0;
        }

        if (prev_prev_tok == T_EOL && prev_tok == T_WORD &&
            (tok == T_EQUAL || tok == T_COLON_EQ || tok == T_PLUS_EQ)) {
            assign_val_mode = true;
        }

        prev_prev_tok = prev_tok;
        prev_tok = tok;
        return tok;
    }
}

static void skip_eols() {
    while (tok == T_EOL) advance();
}

// ============================================================
// Expression parser
// ============================================================
static expr *parse_expr();

static symbol *parse_symbol() {
    if (tok == T_WORD || tok == T_WORD_QUOTE) {
        int flags = (tok == T_WORD_QUOTE) ? SYMBOL_CONST : 0;
        symbol *sym = sym_lookup(tok_text.c_str(), flags);
        advance();
        return sym;
    }
    return nullptr;
}

static expr *parse_if_expr() {
    if (tok == T_IF) { advance(); return parse_expr(); }
    return nullptr;
}

static expr *parse_expr_term() {
    if (tok == T_NOT) {
        advance();
        expr *e = parse_expr_term();
        return e ? expr_alloc_one(E_NOT, e) : nullptr;
    }
    if (tok == T_OPEN_PAREN) {
        advance();
        expr *e = parse_expr();
        if (tok == T_CLOSE_PAREN) advance();
        return e;
    }
    symbol *left = parse_symbol();
    if (!left) return nullptr;
    if (tok == T_EQUAL || tok == T_UNEQUAL ||
        tok == T_LESS || tok == T_LESS_EQ ||
        tok == T_GREATER || tok == T_GREATER_EQ) {
        expr_type ty;
        switch (tok) {
            case T_EQUAL: ty = E_EQUAL; break;
            case T_UNEQUAL: ty = E_UNEQUAL; break;
            case T_LESS: ty = E_LTH; break;
            case T_LESS_EQ: ty = E_LEQ; break;
            case T_GREATER: ty = E_GTH; break;
            case T_GREATER_EQ: ty = E_GEQ; break;
            default: return expr_alloc_symbol(left);
        }
        advance();
        symbol *right = parse_symbol();
        if (!right) return expr_alloc_symbol(left);
        return expr_alloc_comp(ty, left, right);
    }
    return expr_alloc_symbol(left);
}

static expr *parse_expr() {
    expr *e = parse_expr_term();
    if (!e) return nullptr;
    while (tok == T_OR || tok == T_AND) {
        expr_type ty = (tok == T_OR) ? E_OR : E_AND;
        advance();
        expr *rhs = parse_expr_term();
        if (rhs) e = expr_alloc_two(ty, e, rhs);
    }
    return e;
}

// ============================================================
// Keyword classification
// ============================================================
static bool is_stmt_keyword(Tok t) {
    return t == T_CONFIG || t == T_MENUCONFIG || t == T_MENU ||
           t == T_CHOICE || t == T_COMMENT || t == T_SOURCE ||
           t == T_IF || t == T_MAINMENU;
}

static bool is_block_end(Tok t) {
    return t == T_ENDMENU || t == T_ENDCHOICE || t == T_ENDIF;
}

// ============================================================
// Menu building
// ============================================================
static menu **last_entry_ptr = nullptr;

void _menu_init() {
    current_entry = current_menu = &rootmenu;
    last_entry_ptr = &rootmenu.list;
}

void menu_add_entry(symbol *sym) {
    auto *m = new menu{};
    m->sym = sym;
    m->parent = current_menu;
    m->file = current_pos_file;
    m->lineno = current_pos_lineno;
    *last_entry_ptr = m;
    last_entry_ptr = &m->next;
    current_entry = m;
    if (sym)
        menu_add_symbol(P_SYMBOL, sym, nullptr);
}

menu *menu_add_menu() {
    last_entry_ptr = &current_entry->list;
    current_menu = current_entry;
    return current_menu;
}

void menu_end_menu() {
    last_entry_ptr = &current_menu->next;
    current_menu = current_menu->parent;
}

void menu_add_dep(expr *dep) {
    current_entry->dep = expr_alloc_and(current_entry->dep, dep);
}

void menu_add_visibility(expr *e) {
    current_entry->visibility = expr_alloc_and(current_entry->visibility, e);
}

void menu_set_type(int type) {
    symbol *sym = current_entry->sym;
    if (sym->type == (symbol_type)type) return;
    if (sym->type == S_UNKNOWN) { sym->type = (symbol_type)type; return; }
}

property *menu_add_prompt(prop_type type, char *prompt, expr *dep) {
    if (isspace((unsigned char)*prompt))
        while (isspace((unsigned char)*prompt)) prompt++;
    if (type == P_PROMPT) {
        menu *m = current_entry;
        while ((m = m->parent) != nullptr) {
            if (!m->visibility) continue;
            dep = expr_alloc_and(dep, expr_copy(m->visibility));
        }
    }
    auto *prop = new property{};
    prop->type = type;
    prop->file = current_pos_file;
    prop->lineno = current_pos_lineno;
    prop->menu = current_entry;
    prop->visible.expr = dep;
    prop->text = prompt;
    if (current_entry->sym) {
        property **pp;
        for (pp = &current_entry->sym->prop; *pp; pp = &(*pp)->next) ;
        *pp = prop;
    }
    current_entry->prompt = prop;
    free(prompt);
    return prop;
}

void menu_add_expr(prop_type type, expr *e, expr *dep) {
    auto *prop = new property{};
    prop->type = type;
    prop->file = current_pos_file;
    prop->lineno = current_pos_lineno;
    prop->menu = current_entry;
    prop->expr = e;
    prop->visible.expr = dep;
    if (current_entry->sym) {
        property **pp;
        for (pp = &current_entry->sym->prop; *pp; pp = &(*pp)->next) ;
        *pp = prop;
    }
}

void menu_add_symbol(prop_type type, symbol *sym, expr *dep) {
    menu_add_expr(type, expr_alloc_symbol(sym), dep);
}

void menu_add_option_modules() {
    if (modules_sym)
        zconf_error("symbol '%s' redefines option 'modules'",
                    current_entry->sym->name.c_str());
    modules_sym = current_entry->sym;
}

void menu_add_option_defconfig_list() {
    if (!sym_defconfig_list)
        sym_defconfig_list = current_entry->sym;
    else if (sym_defconfig_list != current_entry->sym)
        zconf_error("trying to redefine defconfig symbol");
    if (sym_defconfig_list)
        sym_defconfig_list->flags |= SYMBOL_NO_WRITE;
}

void menu_add_option_allnoconfig_y() {
    current_entry->sym->flags |= SYMBOL_ALLNOCONFIG_Y;
}

void menu_warn(menu *m, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "%s:%d:warning: ", m->file->name.c_str(), m->lineno);
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    va_end(ap);
}

// ============================================================
// Option parsers
// ============================================================

static bool parse_type_spec() {
    symbol_type st = S_UNKNOWN;
    if (tok == T_BOOL) st = S_BOOLEAN;
    else if (tok == T_TRISTATE) st = S_TRISTATE;
    else if (tok == T_INT) st = S_INT;
    else if (tok == T_HEX_TYPE) st = S_HEX;
    else if (tok == T_STRING_TYPE) st = S_STRING;
    else return false;
    advance();
    menu_set_type((int)st);
    if (tok == T_WORD_QUOTE) {
        std::string txt = tok_text; advance();
        expr *ife = parse_if_expr();
        menu_add_prompt(P_PROMPT, xstrdup(txt.c_str()), ife);
    }
    return true;
}

static void parse_config_options() {
    while (true) {
        skip_eols();
        if (tok == T_EOF || is_stmt_keyword(tok) || is_block_end(tok)) return;

        if (parse_type_spec()) continue;

        if (tok == T_PROMPT) {
            advance();
            if (tok != T_WORD_QUOTE) { zconf_error("expected prompt string"); return; }
            std::string txt = tok_text; advance();
            expr *ife = parse_if_expr();
            menu_add_prompt(P_PROMPT, xstrdup(txt.c_str()), ife);
            continue;
        }

        if (tok == T_DEFAULT || tok == T_DEF_BOOL || tok == T_DEF_TRISTATE) {
            symbol_type dt = S_UNKNOWN;
            if (tok == T_DEF_BOOL) dt = S_BOOLEAN;
            else if (tok == T_DEF_TRISTATE) dt = S_TRISTATE;
            advance();
            expr *e = parse_expr();
            if (!e) { zconf_error("expected expression after default"); continue; }
            expr *ife = parse_if_expr();
            menu_add_expr(P_DEFAULT, e, ife);
            if (dt != S_UNKNOWN) menu_set_type((int)dt);
            continue;
        }

        if (tok == T_SELECT) {
            advance();
            symbol *s = parse_symbol();
            if (!s) { zconf_error("expected symbol after 'select'"); continue; }
            expr *ife = parse_if_expr();
            menu_add_symbol(P_SELECT, s, ife);
            continue;
        }

        if (tok == T_IMPLY) {
            advance();
            symbol *s = parse_symbol();
            if (!s) { zconf_error("expected symbol after 'imply'"); continue; }
            expr *ife = parse_if_expr();
            menu_add_symbol(P_IMPLY, s, ife);
            continue;
        }

        if (tok == T_RANGE) {
            advance();
            symbol *lo = parse_symbol();
            symbol *hi = parse_symbol();
            if (!lo || !hi) { zconf_error("expected two symbols after 'range'"); continue; }
            expr *ife = parse_if_expr();
            menu_add_expr(P_RANGE, expr_alloc_comp(E_RANGE, lo, hi), ife);
            continue;
        }

        if (tok == T_OPTION) {
            advance();
            if (tok == T_MODULES) { advance(); menu_add_option_modules(); }
            else if (tok == T_DEFCONFIG_LIST) { advance(); menu_add_option_defconfig_list(); }
            else if (tok == T_ALLNOCONFIG_Y) { advance(); menu_add_option_allnoconfig_y(); }
            else zconf_error("unknown option");
            continue;
        }

        if (tok == T_OPTIONAL) {
            advance();
            if (current_entry->sym)
                current_entry->sym->flags |= SYMBOL_OPTIONAL;
            continue;
        }

        if (tok == T_DEPENDS) {
            advance();
            if (tok == T_ON) advance();
            expr *e = parse_expr();
            if (e) menu_add_dep(e);
            continue;
        }

        if (tok == T_VISIBLE) {
            advance();
            expr *e = parse_if_expr();
            if (e) menu_add_visibility(e);
            continue;
        }

        if (tok == T_HELP_KW) { consume_help(); continue; }

        zconf_error("unknown option \"%s\"", tok_text.c_str());
        advance();
    }
}

static void parse_choice_options() {
    while (true) {
        skip_eols();
        if (tok == T_EOF || is_stmt_keyword(tok) || is_block_end(tok)) return;

        if (parse_type_spec()) continue;

        if (tok == T_PROMPT) {
            advance();
            if (tok != T_WORD_QUOTE) { zconf_error("expected prompt string"); return; }
            std::string txt = tok_text; advance();
            expr *ife = parse_if_expr();
            menu_add_prompt(P_PROMPT, xstrdup(txt.c_str()), ife);
            continue;
        }

        if (tok == T_OPTIONAL) {
            advance();
            if (current_entry->sym)
                current_entry->sym->flags |= SYMBOL_OPTIONAL;
            continue;
        }

        if (tok == T_DEFAULT) {
            advance();
            symbol *s = parse_symbol();
            if (!s) { zconf_error("expected symbol after 'default'"); continue; }
            expr *ife = parse_if_expr();
            menu_add_symbol(P_DEFAULT, s, ife);
            continue;
        }

        if (tok == T_DEPENDS) {
            advance();
            if (tok == T_ON) advance();
            expr *e = parse_expr();
            if (e) menu_add_dep(e);
            continue;
        }

        if (tok == T_HELP_KW) { consume_help(); continue; }

        zconf_error("unknown option in choice");
        advance();
    }
}

static void parse_menu_options() {
    while (true) {
        skip_eols();
        if (tok == T_EOF || is_stmt_keyword(tok) || is_block_end(tok)) return;

        if (tok == T_VISIBLE) {
            advance();
            expr *e = parse_if_expr();
            if (e) menu_add_visibility(e);
            continue;
        }
        if (tok == T_DEPENDS) {
            advance();
            if (tok == T_ON) advance();
            expr *e = parse_expr();
            if (e) menu_add_dep(e);
            continue;
        }
        zconf_error("unknown option in menu");
        advance();
    }
}

static void parse_comment_options() {
    while (true) {
        skip_eols();
        if (tok == T_EOF || is_stmt_keyword(tok) || is_block_end(tok)) return;

        if (tok == T_DEPENDS) {
            advance();
            if (tok == T_ON) advance();
            expr *e = parse_expr();
            if (e) menu_add_dep(e);
            continue;
        }
        zconf_error("unknown option in comment");
        advance();
    }
}

// ============================================================
// Statement parsers
// ============================================================

static void parse_stmt_list();
static void parse_stmt_list_in_choice();

static void parse_config_stmt() {
    bool is_mconfig = (tok == T_MENUCONFIG);
    if (tok != T_CONFIG && !is_mconfig) return;
    advance();
    if (tok != T_WORD) { zconf_error("expected symbol name"); return; }
    symbol *sym = sym_lookup(tok_text.c_str(), 0);
    sym->flags |= SYMBOL_OPTIONAL;
    advance();
    menu_add_entry(sym);
    parse_config_options();
    if (is_mconfig) {
        if (current_entry->prompt)
            current_entry->prompt->type = P_MENU;
        else
            zconf_warn("menuconfig statement without prompt");
    }
}

static void parse_menu_stmt() {
    if (tok != T_MENU) return;
    advance();
    if (tok != T_WORD_QUOTE) { zconf_error("expected menu title"); return; }
    std::string txt = tok_text; advance();
    menu_add_entry(nullptr);
    menu_add_prompt(P_MENU, xstrdup(txt.c_str()), nullptr);
    parse_menu_options();
    menu_add_menu();
    parse_stmt_list();
    skip_eols();
    if (tok == T_ENDMENU) {
        if (current_menu->file != current_pos_file)
            zconf_error("'endmenu' in different file than 'menu'");
        advance(); menu_end_menu();
    }
}

static void parse_choice_stmt() {
    if (tok != T_CHOICE) return;
    advance();
    std::string name;
    if (tok == T_WORD) { name = tok_text; advance(); }
    symbol *sym = sym_lookup(name.empty() ? nullptr : name.c_str(), SYMBOL_CHOICE);
    sym->flags |= SYMBOL_NO_WRITE;
    menu_add_entry(sym);
    menu_add_expr(P_CHOICE, nullptr, nullptr);
    parse_choice_options();
    menu_add_menu();
    parse_stmt_list_in_choice();
    skip_eols();
    if (tok == T_ENDCHOICE) {
        if (current_menu->file != current_pos_file)
            zconf_error("'endchoice' in different file than 'choice'");
        advance(); menu_end_menu();
    }
}

static void parse_comment_stmt() {
    if (tok != T_COMMENT) return;
    advance();
    if (tok != T_WORD_QUOTE) { zconf_error("expected comment text"); return; }
    std::string txt = tok_text; advance();
    menu_add_entry(nullptr);
    menu_add_prompt(P_COMMENT, xstrdup(txt.c_str()), nullptr);
    parse_comment_options();
}

static void parse_if_stmt() {
    if (tok != T_IF) return;
    advance();
    expr *e = parse_expr();
    if (!e) { zconf_error("expected expression after 'if'"); return; }
    menu_add_entry(nullptr);
    menu_add_dep(e);
    menu_add_menu();
    parse_stmt_list();
    skip_eols();
    if (tok == T_ENDIF) {
        if (current_menu->file != current_pos_file)
            zconf_error("'endif' in different file than 'if'");
        advance(); menu_end_menu();
    }
}

static void parse_source_stmt() {
    if (tok != T_SOURCE) return;
    advance();
    if (tok != T_WORD_QUOTE) { zconf_error("expected quoted path after 'source'"); return; }
    std::string path = tok_text; advance();
    zconf_nextfile(path.c_str());
}

static void parse_mainmenu_stmt() {
    if (tok != T_MAINMENU) return;
    advance();
    if (tok != T_WORD_QUOTE) { zconf_error("expected mainmenu title"); return; }
    menu_add_prompt(P_MENU, xstrdup(tok_text.c_str()), nullptr);
    advance();
}

static void parse_assignment_stmt() {
    if (tok != T_WORD) return;
    std::string var = tok_text; advance();

    variable_flavor flav;
    if (tok == T_EQUAL) flav = VAR_RECURSIVE;
    else if (tok == T_COLON_EQ) flav = VAR_SIMPLE;
    else if (tok == T_PLUS_EQ) flav = VAR_APPEND;
    else { zconf_error("expected '=', ':=' or '+='"); return; }

    advance();
    std::string val = tok_text;
    variable_add(var.c_str(), val.c_str(), flav);
}

static void parse_stmt() {
    if (tok == T_EOF) return;
    if (tok == T_CONFIG || tok == T_MENUCONFIG) parse_config_stmt();
    else if (tok == T_MENU) parse_menu_stmt();
    else if (tok == T_CHOICE) parse_choice_stmt();
    else if (tok == T_COMMENT) parse_comment_stmt();
    else if (tok == T_SOURCE) parse_source_stmt();
    else if (tok == T_IF) parse_if_stmt();
    else if (tok == T_MAINMENU) parse_mainmenu_stmt();
    else if (tok == T_WORD) parse_assignment_stmt();
    else {
        zconf_error("unknown statement \"%s\"", tok_text.c_str());
        advance();
    }
}

static void parse_stmt_list() {
    while (true) {
        skip_eols();
        if (tok == T_EOF) return;
        if (is_block_end(tok)) return;
        parse_stmt();
    }
}

static void parse_stmt_list_in_choice() {
    while (true) {
        skip_eols();
        if (tok == T_EOF) return;
        if (tok == T_ENDCHOICE) return;
        if (tok == T_CONFIG) parse_config_stmt();
        else if (tok == T_COMMENT) parse_comment_stmt();
        else if (tok == T_IF) parse_if_stmt();
        else {
            zconf_error("invalid statement in choice");
            advance();
        }
    }
}

// ============================================================
// Source file management
// ============================================================

extern "C" void zconf_starthelp() {
    help_text.clear();
    help_indent = 0;
    help_indent_set = false;
    in_help = true;
}

extern "C" void zconf_initscan(const char *name) {
    FILE *f = zconf_fopen(name);
    if (!f) {
        fprintf(stderr, "can't find file %s\n", name);
        exit(1);
    }
    auto *frame = new SourceFrame{};
    frame->fp = f;
    frame->filename = name;
    frame->lineno = 1;
    sf = frame;
    current_file = file_lookup(name);
}

extern "C" void zconf_nextfile(const char *name) {
    file *f = file_lookup(name);

    for (file *iter = current_file; iter; iter = iter->parent) {
        if (iter->name == f->name) {
            fprintf(stderr, "Recursive inclusion detected.\n"
                    "Inclusion path:\n  current file : %s\n", f->name.c_str());
            iter = f;
            do {
                iter = iter->parent;
                fprintf(stderr, "  included from: %s:%d\n",
                        iter->name.c_str(), iter->lineno - 1);
            } while (iter->name != f->name);
            exit(1);
        }
    }

    current_file->lineno = sf->lineno;

    FILE *fp = zconf_fopen(f->name.c_str());
    if (!fp) {
        fprintf(stderr, "%s:%d: can't open file \"%s\"\n",
                zconf_curname(), sf->lineno, f->name.c_str());
        exit(1);
    }

    auto *frame = new SourceFrame{};
    frame->fp = fp;
    frame->filename = f->name;
    frame->lineno = 1;
    frame->parent = sf;
    sf = frame;

    f->parent = current_file;
    current_file = f;
}

static void zconf_endfile() {
    current_file = current_file->parent;
    if (current_file)
        sf->parent->lineno = current_file->lineno;
    if (sf->fp) fclose(sf->fp);
    SourceFrame *parent = sf->parent;
    delete sf;
    sf = parent;
}

// ============================================================
// menu_finalize
// ============================================================

static expr *rewrite_m(expr *e) {
    if (!e) return e;
    switch (e->type) {
        case E_NOT: e->left.e = rewrite_m(e->left.e); break;
        case E_OR: case E_AND:
            e->left.e = rewrite_m(e->left.e);
            e->right.e = rewrite_m(e->right.e);
            break;
        case E_SYMBOL:
            if (e->left.sym == &symbol_mod)
                return expr_alloc_and(e, expr_alloc_symbol(modules_sym));
            break;
        default: break;
    }
    return e;
}

void menu_finalize(menu *parent) {
    menu *m, *last_menu;
    symbol *sym;
    property *prop;
    expr *parentdep, *basedep, *dep, *dep2, **ep;

    sym = parent->sym;
    if (parent->list) {
        if (sym && sym_is_choice(sym)) {
            if (sym->type == S_UNKNOWN) {
                current_entry = parent;
                for (m = parent->list; m; m = m->next)
                    if (m->sym && m->sym->type != S_UNKNOWN) {
                        menu_set_type((int)m->sym->type); break;
                    }
            }
            for (m = parent->list; m; m = m->next) {
                current_entry = m;
                if (m->sym && m->sym->type == S_UNKNOWN)
                    menu_set_type((int)sym->type);
            }
            parentdep = expr_alloc_symbol(sym);
        } else {
            parentdep = parent->dep;
        }
        for (m = parent->list; m; m = m->next) {
            basedep = rewrite_m(m->dep);
            basedep = expr_transform(basedep);
            basedep = expr_alloc_and(expr_copy(parentdep), basedep);
            basedep = expr_eliminate_dups(basedep);
            m->dep = basedep;

            if (m->sym) prop = m->sym->prop;
            else prop = m->prompt;

            for (; prop; prop = prop->next) {
                if (prop->menu != m) continue;
                dep = rewrite_m(prop->visible.expr);
                dep = expr_transform(dep);
                dep = expr_alloc_and(expr_copy(basedep), dep);
                dep = expr_eliminate_dups(dep);
                if (m->sym && m->sym->type != S_TRISTATE)
                    dep = expr_trans_bool(dep);
                prop->visible.expr = dep;

                if (prop->type == P_SELECT) {
                    symbol *es = prop_get_symbol(prop);
                    es->rev_dep.expr = expr_alloc_or(es->rev_dep.expr,
                        expr_alloc_and(expr_alloc_symbol(m->sym), expr_copy(dep)));
                } else if (prop->type == P_IMPLY) {
                    symbol *es = prop_get_symbol(prop);
                    es->implied.expr = expr_alloc_or(es->implied.expr,
                        expr_alloc_and(expr_alloc_symbol(m->sym), expr_copy(dep)));
                }
            }
        }
        if (sym && sym_is_choice(sym)) expr_free(parentdep);
        for (m = parent->list; m; m = m->next) menu_finalize(m);
    } else if (sym) {
        basedep = parent->prompt ? parent->prompt->visible.expr : nullptr;
        basedep = expr_trans_compare(basedep, E_UNEQUAL, &symbol_no);
        basedep = expr_eliminate_dups(expr_transform(basedep));

        last_menu = nullptr;
        for (m = parent->next; m; m = m->next) {
            dep = m->prompt ? m->prompt->visible.expr : m->dep;
            if (!expr_contains_symbol(dep, sym)) break;
            if (expr_depends_symbol(dep, sym)) goto next_submenu;
            dep = expr_trans_compare(dep, E_UNEQUAL, &symbol_no);
            dep = expr_eliminate_dups(expr_transform(dep));
            dep2 = expr_copy(basedep);
            expr_eliminate_eq(&dep, &dep2);
            expr_free(dep);
            if (!expr_is_yes(dep2)) { expr_free(dep2); break; }
            expr_free(dep2);
        next_submenu:
            menu_finalize(m);
            m->parent = parent;
            last_menu = m;
        }
        expr_free(basedep);
        if (last_menu) {
            parent->list = parent->next;
            parent->next = last_menu->next;
            last_menu->next = nullptr;
        }
        sym->dir_dep.expr = expr_alloc_or(sym->dir_dep.expr, parent->dep);
    }

    for (m = parent->list; m; m = m->next) {
        if (sym && sym_is_choice(sym) && m->sym && !sym_is_choice_value(m->sym)) {
            current_entry = m;
            m->sym->flags |= SYMBOL_CHOICEVAL;
            if (!m->prompt) menu_warn(m, "choice value must have a prompt");
            for (prop = m->sym->prop; prop; prop = prop->next) {
                if (prop->type == P_DEFAULT)
                    menu_warn(m, "defaults for choice values not supported");
                if (prop->menu == m) continue;
                if (prop->type == P_PROMPT && prop->menu->parent->sym != sym)
                    menu_warn(m, "choice value used outside its choice group");
            }
            if (sym->type == S_TRISTATE && m->sym->type != S_TRISTATE) {
                basedep = expr_alloc_comp(E_EQUAL, sym, &symbol_yes);
                m->dep = expr_alloc_and(basedep, m->dep);
                for (prop = m->sym->prop; prop; prop = prop->next) {
                    if (prop->menu != m) continue;
                    prop->visible.expr = expr_alloc_and(expr_copy(basedep), prop->visible.expr);
                }
            }
            menu_add_symbol(P_CHOICE, sym, nullptr);
            prop = sym_get_choice_prop(sym);
            for (ep = &prop->expr; *ep; ep = &(*ep)->left.e) ;
            *ep = expr_alloc_one(E_LIST, nullptr);
            (*ep)->right.sym = m->sym;
        }
        if (m->list && (!m->prompt || m->prompt->text.empty())) {
            for (last_menu = m->list; ; last_menu = last_menu->next) {
                last_menu->parent = parent;
                if (!last_menu->next) break;
            }
            last_menu->next = m->next;
            m->next = m->list;
            m->list = nullptr;
        }
    }

    if (sym && !(sym->flags & SYMBOL_WARNED)) {
        if (sym->type == S_UNKNOWN)
            menu_warn(parent, "config symbol defined without type");
        if (sym_is_choice(sym) && !parent->prompt)
            menu_warn(parent, "choice must have a prompt");
        for (prop = sym->prop; prop; prop = prop->next) {
            switch (prop->type) {
                case P_DEFAULT:
                    if ((sym->type == S_STRING || sym->type == S_INT || sym->type == S_HEX) &&
                        prop->expr && prop->expr->type != E_SYMBOL)
                        menu_warn(prop->menu, "default for config symbol '%s' must be a single symbol",
                                  sym->name.c_str());
                    break;
                case P_SELECT:
                case P_IMPLY: {
                    const char *use = prop->type == P_SELECT ? "select" : "imply";
                    symbol *s2 = prop_get_symbol(prop);
                    if (sym->type != S_BOOLEAN && sym->type != S_TRISTATE)
                        menu_warn(prop->menu, "config symbol '%s' uses %s, but is not bool or tristate",
                                  sym->name.c_str(), use);
                    else if (s2 && s2->type != S_UNKNOWN && s2->type != S_BOOLEAN && s2->type != S_TRISTATE)
                        menu_warn(prop->menu, "'%s' has wrong type for %s", s2->name.c_str(), use);
                    break;
                }
                case P_RANGE:
                    if (sym->type != S_INT && sym->type != S_HEX)
                        menu_warn(prop->menu, "range is only allowed for int or hex symbols");
                    break;
                default: ;
            }
        }
        sym->flags |= SYMBOL_WARNED;
    }

    if (sym && !sym_is_optional(sym) && parent->prompt) {
        sym->rev_dep.expr = expr_alloc_or(sym->rev_dep.expr,
            expr_alloc_and(parent->prompt->visible.expr, expr_alloc_symbol(&symbol_mod)));
    }
}

// ============================================================
// Menu query helpers
// ============================================================

bool menu_has_prompt(menu *m) { return m && m->prompt; }

bool menu_is_empty(menu *m) {
    for (menu *c = m->list; c; c = c->next)
        if (menu_is_visible(c)) return false;
    return true;
}

menu *menu_get_root_menu(menu *) { return &rootmenu; }

menu *menu_get_parent_menu(menu *m) {
    for (; m != &rootmenu; m = m->parent)
        if (m->prompt && m->prompt->type == P_MENU) break;
    return m;
}

bool menu_has_help(menu *m) { return m && !m->help.empty(); }

const char *menu_get_help(menu *m) { return (m && !m->help.empty()) ? m->help.c_str() : ""; }

void menu_get_ext_help(menu *mn, gstr *help) {
    symbol *s = mn->sym;
    const char *ht = "There is no help available for this option.";
    if (menu_has_help(mn)) {
        if (s && !s->name.empty())
            str_printf(help, "CONFIG_%s:\n\n", s->name.c_str());
        ht = menu_get_help(mn);
    }
    str_printf(help, "%s\n", ht);
    if (s && !s->name.empty()) {
        str_printf(help, "Symbol: %s [=%s]\n", s->name.c_str(), sym_get_string_value(s));
        str_printf(help, "Type  : %s\n", sym_type_name(s->type));
    }
}

// ============================================================
// conf_parse — entry point
// ============================================================
void conf_parse(const char *name) {
    zconf_initscan(name);
    _menu_init();
    yynerrs = 0;

    advance();

    if (tok == T_MAINMENU)
        parse_mainmenu_stmt();
    parse_stmt_list();

    while (sf && sf->parent) zconf_endfile();
    if (sf && sf->fp) fclose(sf->fp);
    delete sf; sf = nullptr;

    variable_all_del();

    if (yynerrs) exit(1);
    if (!modules_sym) modules_sym = sym_find("n");

    if (!menu_has_prompt(&rootmenu)) {
        current_entry = &rootmenu;
        menu_add_prompt(P_MENU, xstrdup("Main menu"), nullptr);
    }

    menu_finalize(&rootmenu);

    for (int i = 0; i < SYMBOL_HASHSIZE; i++)
        for (symbol *s = symbol_hash[i]; s; s = s->next)
            if (sym_check_deps(s)) yynerrs++;

    if (yynerrs) exit(1);
    sym_set_change_count(1);
}
