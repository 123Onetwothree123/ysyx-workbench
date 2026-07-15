// C++ bridge: provides C interface to npc.kconfig module for the original mconf.c
#include <cstddef>
#include <cstdarg>
import npc.kconfig;

extern "C" {
    // Config
    int c_conf_read(const char *name) { return conf_read(name); }
    int c_conf_write(const char *name) { return conf_write(name); }
    int c_conf_write_autoconf(int ov) { return conf_write_autoconf(ov); }
    bool c_conf_get_changed() { return conf_get_changed(); }
    void c_conf_set_message_callback(void (*fn)(const char *)) { conf_set_message_callback(fn); }

    // Symbol
    symbol *c_sym_lookup(const char *n, int f) { return sym_lookup(n, f); }
    symbol *c_sym_find(const char *n) { return sym_find(n); }
    tristate c_sym_get_tristate_value(symbol *s) { return sym_get_tristate_value(s); }
    const char *c_sym_get_string_value(symbol *s) { return sym_get_string_value(s); }
    bool c_sym_set_tristate_value(symbol *s, tristate v) { return sym_set_tristate_value(s, v); }
    bool c_sym_set_string_value(symbol *s, const char *v) { return sym_set_string_value(s, v); }
    bool c_sym_has_value(symbol *s) { return sym_has_value(s); }
    bool c_sym_is_changeable(symbol *s) { return sym_is_changeable(s); }
    bool c_sym_is_choice(symbol *s) { return sym_is_choice(s); }
    bool c_sym_is_choice_value(symbol *s) { return sym_is_choice_value(s); }
    void c_sym_calc_value(symbol *s) { sym_calc_value(s); }
    symbol_type c_sym_get_type(symbol *s) { return sym_get_type(s); }
    symbol *c_sym_get_choice_value(symbol *s) { return sym_get_choice_value(s); }
    bool c_sym_tristate_within_range(symbol *s, tristate v) { return sym_tristate_within_range(s, v); }
    const char *c_sym_escape_string_value(const char *s) { return sym_escape_string_value(s); }

    // Menu
    bool c_menu_is_visible(menu *m) { return menu_is_visible(m); }
    const char *c_menu_get_prompt(menu *m) { return menu_get_prompt(m); }
    menu *c_menu_get_parent_menu(menu *m) { return menu_get_parent_menu(m); }
    void c_menu_get_ext_help(menu *m, gstr *h) { menu_get_ext_help(m, h); }
    bool c_menu_is_empty(menu *m) { return menu_is_empty(m); }
    const char *c_menu_get_help(menu *m) { return menu_get_help(m); }

    // Globals
    symbol *c_symbol_yes = &symbol_yes;
    symbol *c_symbol_mod = &symbol_mod;
    symbol *c_symbol_no = &symbol_no;
    symbol *c_modules_sym = modules_sym;
    menu *c_rootmenu = &rootmenu;
    menu *c_current_entry = current_entry;
    menu *c_current_menu = current_menu;

    // str utils
    gstr c_str_new() { return str_new(); }
    void c_str_printf(gstr *gs, const char *fmt, ...) {
        va_list ap; va_start(ap, fmt);
        str_printf(gs, fmt, ap); va_end(ap);
    }
    const char *c_str_get(gstr *gs) { return str_get(gs); }
    void c_str_free(gstr *gs) { str_free(gs); }

    // vars
    void c_variable_add(const char *n, const char *v, variable_flavor fl) { variable_add(n, v, fl); }
    char *c_variable_expand(const char *n) { return variable_expand(n); }
    void c_variable_all_del() { variable_all_del(); }

    // Memory
    void *c_xmalloc(size_t s) { return xmalloc(s); }
    void *c_xcalloc(size_t n, size_t s) { return xcalloc(n, s); }
    void *c_xrealloc(void *p, size_t s) { return xrealloc(p, s); }
    char *c_xstrdup(const char *s) { return xstrdup(s); }
    char *c_xstrndup(const char *s, size_t n) { return xstrndup(s, n); }

    // Parser
    void c_conf_parse(const char *name) { conf_parse(name); }
}
