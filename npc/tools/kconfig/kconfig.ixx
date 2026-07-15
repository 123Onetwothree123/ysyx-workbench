// SPDX-License-Identifier: GPL-2.0
// C++23 Module Interface for Kconfig
// Ported from Linux kconfig: expr.h, lkc.h, lkc_proto.h

module;

#include <cassert>
#include <cstddef>
#include <cstdio>
#include <string>
#include <vector>
#include <list>
#include <memory>
#include <functional>

export module npc.kconfig;

// ============================================================
// Enums
// ============================================================

export enum tristate {
    no, mod, yes
};

export enum expr_type {
    E_NONE, E_OR, E_AND, E_NOT,
    E_EQUAL, E_UNEQUAL, E_LTH, E_LEQ, E_GTH, E_GEQ,
    E_LIST, E_SYMBOL, E_RANGE
};

export enum symbol_type {
    S_UNKNOWN, S_BOOLEAN, S_TRISTATE, S_INT, S_HEX, S_STRING
};

export enum prop_type {
    P_UNKNOWN,
    P_PROMPT,
    P_COMMENT,
    P_MENU,
    P_DEFAULT,
    P_CHOICE,
    P_SELECT,
    P_IMPLY,
    P_RANGE,
    P_SYMBOL,
};

export enum conf_def_mode {
    def_default,
    def_yes,
    def_mod,
    def_y2m,
    def_m2y,
    def_no,
    def_random
};

export enum variable_flavor {
    VAR_SIMPLE,
    VAR_RECURSIVE,
    VAR_APPEND,
};

export enum sym_def_type {
    S_DEF_USER,
    S_DEF_AUTO,
    S_DEF_DEF3,
    S_DEF_DEF4,
    S_DEF_COUNT
};

// ============================================================
// Symbol flags
// ============================================================

export constexpr int SYMBOL_CONST    = 0x0001;
export constexpr int SYMBOL_CHECK    = 0x0008;
export constexpr int SYMBOL_CHOICE   = 0x0010;
export constexpr int SYMBOL_CHOICEVAL= 0x0020;
export constexpr int SYMBOL_VALID    = 0x0080;
export constexpr int SYMBOL_OPTIONAL = 0x0100;
export constexpr int SYMBOL_WRITE    = 0x0200;
export constexpr int SYMBOL_CHANGED  = 0x0400;
export constexpr int SYMBOL_WRITTEN  = 0x0800;
export constexpr int SYMBOL_NO_WRITE = 0x1000;
export constexpr int SYMBOL_CHECKED  = 0x2000;
export constexpr int SYMBOL_WARNED   = 0x8000;
export constexpr int SYMBOL_DEF      = 0x10000;
export constexpr int SYMBOL_DEF_USER = 0x10000;
export constexpr int SYMBOL_DEF_AUTO = 0x20000;
export constexpr int SYMBOL_DEF3     = 0x40000;
export constexpr int SYMBOL_DEF4     = 0x80000;
export constexpr int SYMBOL_NEED_SET_CHOICE_VALUES = 0x100000;
export constexpr int SYMBOL_ALLNOCONFIG_Y = 0x200000;

// ============================================================
// Menu flags
// ============================================================
export constexpr unsigned int MENU_CHANGED = 0x0001;
export constexpr unsigned int MENU_ROOT    = 0x0002;

// ============================================================
// Constants
// ============================================================
export constexpr int SYMBOL_MAXLENGTH = 256;
export constexpr int SYMBOL_HASHSIZE  = 9973;
export constexpr int JUMP_NB = 9;

// ============================================================
// EXPR macros (as inline functions)
// ============================================================
export inline int EXPR_OR(int dep1, int dep2)  { return (dep1) > (dep2) ? (dep1) : (dep2); }
export inline int EXPR_AND(int dep1, int dep2) { return (dep1) < (dep2) ? (dep1) : (dep2); }
export inline int EXPR_NOT(int dep)            { return 2 - (dep); }

// ============================================================
// Forward declarations
// ============================================================
export struct expr;
export struct symbol;
export struct menu;
export struct property;
export struct file;
export struct gstr;
export struct list_head;
export struct jump_key;

// ============================================================
// Data structures
// ============================================================

export struct file {
    file *next;
    file *parent;
    std::string name;
    int lineno;
};

export struct symbol_value {
    void *val;
    tristate tri;
};

export struct expr_value {
    expr *expr;
    tristate tri;
};

export struct expr {
    expr_type type;
    expr *next;
    union {
        expr *e;
        symbol *sym;
    } left;
    union {
        expr *e;
        symbol *sym;
    } right;
};

export struct property {
    property *next;
    prop_type type;
    std::string text;
    expr_value visible;
    expr *expr;
    menu *menu;
    file *file;
    int lineno;
};

export struct symbol {
    symbol *next;
    std::string name;
    symbol_type type;
    symbol_value curr;
    symbol_value def[S_DEF_COUNT];
    tristate visible;
    int flags;
    property *prop;
    expr_value dir_dep;
    expr_value rev_dep;
    expr_value implied;
};

export struct menu {
    menu *next;
    menu *parent;
    menu *list;
    symbol *sym;
    property *prompt;
    expr *visibility;
    expr *dep;
    unsigned int flags;
    std::string help;
    file *file;
    int lineno;
    void *data;
};

export struct jump_key {
    std::list<jump_key>::iterator entries;
    size_t offset;
    menu *target;
    int index;
};

export struct gstr {
    size_t len;
    char *s;
    int max_width;
};

// ============================================================
// Global externs
// ============================================================

export extern symbol symbol_yes;
export extern symbol symbol_mod;
export extern symbol symbol_no;
export extern symbol *modules_sym;
export extern symbol *sym_defconfig_list;
export extern symbol *symbol_hash[SYMBOL_HASHSIZE];
export extern menu rootmenu;
export extern file *file_list;
export extern file *current_file;
export extern menu *current_entry;
export extern menu *current_menu;

// ============================================================
// Expression API
// ============================================================

export expr *expr_alloc_symbol(symbol *sym);
export expr *expr_alloc_one(expr_type type, expr *ce);
export expr *expr_alloc_two(expr_type type, expr *e1, expr *e2);
export expr *expr_alloc_comp(expr_type type, symbol *s1, symbol *s2);
export expr *expr_alloc_and(expr *e1, expr *e2);
export expr *expr_alloc_or(expr *e1, expr *e2);
export expr *expr_copy(const expr *org);
export void expr_free(expr *e);
export void expr_eliminate_eq(expr **ep1, expr **ep2);
export int expr_eq(expr *e1, expr *e2);
export tristate expr_calc_value(expr *e);
export expr *expr_trans_bool(expr *e);
export expr *expr_eliminate_dups(expr *e);
export expr *expr_transform(expr *e);
export int expr_contains_symbol(expr *dep, symbol *sym);
export bool expr_depends_symbol(expr *dep, symbol *sym);
export expr *expr_trans_compare(expr *e, expr_type type, symbol *sym);

export void expr_fprint(expr *e, FILE *out);
export void expr_gstr_print(expr *e, gstr *gs);
export void expr_gstr_print_revdep(expr *e, gstr *gs, tristate pr_type, const char *title);

export inline int expr_is_yes(expr *e) {
    return !e || (e->type == E_SYMBOL && e->left.sym == &symbol_yes);
}
export inline int expr_is_no(expr *e) {
    return e && (e->type == E_SYMBOL && e->left.sym == &symbol_no);
}

// ============================================================
// Symbol API
// ============================================================

export void sym_clear_all_valid(void);
export symbol *sym_lookup(const char *name, int flags);
export symbol *sym_find(const char *name);
export symbol *sym_choice_default(symbol *sym);
export property *sym_get_range_prop(symbol *sym);
export const char *sym_get_string_default(symbol *sym);
export symbol *sym_check_deps(symbol *sym);
export symbol *prop_get_symbol(property *prop);
export const char *sym_escape_string_value(const char *in);
export symbol **sym_re_search(const char *pattern);
export const char *sym_type_name(symbol_type type);
export void sym_calc_value(symbol *sym);
export symbol_type sym_get_type(symbol *sym);
export bool sym_tristate_within_range(symbol *sym, tristate tri);
export bool sym_set_tristate_value(symbol *sym, tristate tri);
export tristate sym_toggle_tristate_value(symbol *sym);
export bool sym_string_valid(symbol *sym, const char *newval);
export bool sym_string_within_range(symbol *sym, const char *str);
export bool sym_set_string_value(symbol *sym, const char *newval);
export bool sym_is_changeable(symbol *sym);
export property *sym_get_choice_prop(symbol *sym);
export const char *sym_get_string_value(symbol *sym);
export const char *prop_get_type_name(prop_type type);

export inline tristate sym_get_tristate_value(symbol *sym) { return sym->curr.tri; }
export inline symbol *sym_get_choice_value(symbol *sym) { return (symbol *)sym->curr.val; }
export inline bool sym_set_choice_value(symbol *ch, symbol *chval) { return sym_set_tristate_value(chval, yes); }
export inline bool sym_is_choice(symbol *sym) { return sym->flags & SYMBOL_CHOICE; }
export inline bool sym_is_choice_value(symbol *sym) { return sym->flags & SYMBOL_CHOICEVAL; }
export inline bool sym_is_optional(symbol *sym) { return sym->flags & SYMBOL_OPTIONAL; }
export inline bool sym_has_value(symbol *sym) { return sym->flags & SYMBOL_DEF_USER; }

// ============================================================
// confdata API
// ============================================================

export void conf_parse(const char *name);
export int conf_read(const char *name);
export int conf_read_simple(const char *name, int def);
export int conf_write_defconfig(const char *name);
export int conf_write(const char *name);
export int conf_write_autoconf(int overwrite);
export bool conf_get_changed(void);
export void conf_set_changed_callback(void (*fn)(void));
export void conf_set_message_callback(void (*fn)(const char *s));
export const char *conf_get_configname(void);
export void sym_set_change_count(int count);
export void sym_add_change_count(int count);
export bool conf_set_all_new_symbols(conf_def_mode mode);
export void conf_rewrite_mod_or_yes(conf_def_mode mode);
export void set_all_choice_values(symbol *csym);

// ============================================================
// Menu API
// ============================================================

export void _menu_init(void);
export void menu_add_entry(symbol *sym);
export menu *menu_add_menu(void);
export void menu_end_menu(void);
export void menu_add_dep(expr *dep);
export void menu_add_visibility(expr *dep);
export property *menu_add_prompt(prop_type type, char *prompt, expr *dep);
export void menu_add_expr(prop_type type, expr *e, expr *dep);
export void menu_add_symbol(prop_type type, symbol *sym, expr *dep);
export void menu_add_option_modules(void);
export void menu_add_option_defconfig_list(void);
export void menu_add_option_allnoconfig_y(void);
export void menu_finalize(menu *parent);
export void menu_set_type(int type);
export bool menu_is_empty(menu *menu);
export bool menu_is_visible(menu *menu);
export bool menu_has_prompt(menu *menu);
export const char *menu_get_prompt(menu *menu);
export menu *menu_get_root_menu(menu *menu);
export menu *menu_get_parent_menu(menu *menu);
export bool menu_has_help(menu *menu);
export const char *menu_get_help(menu *menu);
export void menu_get_ext_help(menu *menu, gstr *help);
export void menu_warn(menu *menu, const char *fmt, ...);

// ============================================================
// preprocess API
// ============================================================

export void env_write_dep(FILE *f, const char *auto_conf_name);
export void variable_add(const char *name, const char *value, variable_flavor flavor);
export char *variable_expand(const char *name);
export void variable_all_del(void);
export char *expand_dollar(const char **str);
export char *expand_one_token(const char **str);

// ============================================================
// util API
// ============================================================

export file *file_lookup(const char *name);
export void *xmalloc(size_t size);
export void *xcalloc(size_t nmemb, size_t size);
export void *xrealloc(void *p, size_t size);
export char *xstrdup(const char *s);
export char *xstrndup(const char *s, size_t n);
export gstr str_new(void);
export void str_free(gstr *gs);
export void str_append(gstr *gs, const char *s);
export void str_printf(gstr *gs, const char *fmt, ...);
export const char *str_get(gstr *gs);

// ============================================================
// Macros (as inline functions in C++)
// ============================================================

export inline void xfwrite(const void *str, size_t len, size_t count, FILE *out) {
    assert(len != 0);
    if (fwrite(str, len, count, out) != count)
        fprintf(stderr, "Error in writing or end of file.\n");
}
