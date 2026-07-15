// C linkage bridge — wraps module C++ functions with C-linkage symbols via __asm__ helpers.

#include <cstdlib>
#include <cstring>
#include <cstdarg>
#include <cstdio>

struct symbol; struct menu; struct expr; struct property;
struct file; struct gstr; struct list_head; struct jump_key;

enum tristate { no, mod, yes };
enum symbol_type { S_UNKNOWN, S_BOOLEAN, S_TRISTATE, S_INT, S_HEX, S_STRING };
enum prop_type { P_UNKNOWN, P_PROMPT, P_COMMENT, P_MENU, P_DEFAULT,
                 P_CHOICE, P_SELECT, P_IMPLY, P_RANGE, P_SYMBOL };
enum expr_type { E_NONE, E_OR, E_AND, E_NOT, E_EQUAL, E_UNEQUAL,
                 E_LTH, E_LEQ, E_GTH, E_GEQ, E_LIST, E_SYMBOL, E_RANGE };
enum conf_def_mode { def_default, def_yes, def_mod, def_y2m, def_m2y, def_no, def_random };
enum variable_flavor { VAR_SIMPLE, VAR_RECURSIVE, VAR_APPEND };
struct gstr { size_t len; char *s; int max_width; };

// ---- Module function declarations (C++ linkage → via __asm__ c-linkage helpers) ----

#define M(ret, name, mangled, ...) \
    extern "C" ret _mod_##name(__VA_ARGS__) __asm__(mangled)

M(void *,xmalloc,     "_ZW3npcW7kconfig7xmallocm", size_t s);
M(void *,xcalloc,     "_ZW3npcW7kconfig7xcallocmm", size_t n, size_t sz);
M(void *,xrealloc,    "_ZW3npcW7kconfig8xreallocPvm", void *p, size_t s);
M(char *,xstrdup,     "_ZW3npcW7kconfig7xstrdupPKc", const char *s);
M(char *,xstrndup,    "_ZW3npcW7kconfig8xstrndupPKcm", const char *s, size_t n);
M(struct file *,file_lookup, "_ZW3npcW7kconfig11file_lookupPKc", const char *n);

M(struct symbol *,sym_lookup,    "_ZW3npcW7kconfig10sym_lookupPKci", const char *n, int f);
M(struct symbol *,sym_find,      "_ZW3npcW7kconfig8sym_findPKc", const char *n);
M(const char *,sym_get_sv,       "_ZW3npcW7kconfig20sym_get_string_valuePS0_6symbol", struct symbol *s);
M(const char *,sym_get_sdef,     "_ZW3npcW7kconfig22sym_get_string_defaultPS0_6symbol", struct symbol *s);
M(bool,sym_is_chg,               "_ZW3npcW7kconfig17sym_is_changeablePS0_6symbol", struct symbol *s);
M(bool,sym_set_tri,              "_ZW3npcW7kconfig22sym_set_tristate_valuePS0_6symbolS0_8tristate", struct symbol *s, tristate v);
M(tristate,sym_toggle,           "_ZW3npcW7kconfig25sym_toggle_tristate_valuePS0_6symbol", struct symbol *s);
M(void,sym_calc,                 "_ZW3npcW7kconfig14sym_calc_valuePS0_6symbol", struct symbol *s);
M(bool,sym_tri_range,            "_ZW3npcW7kconfig25sym_tristate_within_rangePS0_6symbolS0_8tristate", struct symbol *s, tristate v);
M(const char *,sym_escape,       "_ZW3npcW7kconfig23sym_escape_string_valuePKc", const char *s);
M(void,sym_add_chg,              "_ZW3npcW7kconfig20sym_add_change_counti", int c);
M(void,sym_set_chgc,             "_ZW3npcW7kconfig20sym_set_change_counti", int c);
M(void,sym_clear_v,              "_ZW3npcW7kconfig19sym_clear_all_validv", void);
M(bool,sym_str_range,            "_ZW3npcW7kconfig23sym_string_within_rangePS0_6symbolPKc", struct symbol *s, const char *v);
M(struct property *,sym_ch_prop, "_ZW3npcW7kconfig19sym_get_choice_propPS0_6symbol", struct symbol *s);
M(struct symbol *,prop_get_sym,  "_ZW3npcW7kconfig15prop_get_symbolPS0_8property", struct property *p);
M(symbol_type,sym_type,          "_ZW3npcW7kconfig12sym_get_typePS0_6symbol", struct symbol *s);
M(struct symbol *,sym_ch_def,    "_ZW3npcW7kconfig18sym_choice_defaultPS0_6symbol", struct symbol *s);
M(struct symbol *,sym_chk_deps,  "_ZW3npcW7kconfig14sym_check_depsPS0_6symbol", struct symbol *s);
M(void,sym_set_ch,               "_ZW3npcW7kconfig15sym_set_changedPS0_6symbol", struct symbol *s);
M(void,sym_all_ch,               "_ZW3npcW7kconfig19sym_set_all_changedv", void);
M(bool,sym_set_str,              "_ZW3npcW7kconfig20sym_set_string_valuePS0_6symbolPKc", struct symbol *s, const char *v);

M(bool,menu_vis,       "_ZW3npcW7kconfig15menu_is_visiblePS0_4menu", struct menu *m);
M(const char *,menu_prompt, "_ZW3npcW7kconfig15menu_get_promptPS0_4menu", struct menu *m);

M(struct expr *,expr_alloc_sym,  "_ZW3npcW7kconfig17expr_alloc_symbolPS0_6symbol", struct symbol *s);
M(struct expr *,expr_alloc_one,  "_ZW3npcW7kconfig14expr_alloc_oneS0_9expr_typePS0_4expr", expr_type t, struct expr *e);
M(struct expr *,expr_alloc_two,  "_ZW3npcW7kconfig14expr_alloc_twoS0_9expr_typePS0_4exprS3_", expr_type t, struct expr *e1, struct expr *e2);
M(struct expr *,expr_alloc_comp, "_ZW3npcW7kconfig15expr_alloc_compS0_9expr_typePS0_6symbolS3_", expr_type t, struct symbol *s1, struct symbol *s2);
M(struct expr *,expr_alloc_and,  "_ZW3npcW7kconfig14expr_alloc_andPS0_4exprS2_", struct expr *e1, struct expr *e2);
M(struct expr *,expr_alloc_or,   "_ZW3npcW7kconfig13expr_alloc_orPS0_4exprS2_", struct expr *e1, struct expr *e2);
M(struct expr *,expr_copy,       "_ZW3npcW7kconfig9expr_copyPKS0_4expr", const struct expr *o);
M(void,expr_free_f,              "_ZW3npcW7kconfig9expr_freePS0_4expr", struct expr *e);
M(tristate,expr_calc,            "_ZW3npcW7kconfig15expr_calc_valuePS0_4expr", struct expr *e);
M(struct expr *,expr_elim_yn,    "_ZW3npcW7kconfig17expr_eliminate_ynPS0_4expr", struct expr *e);
M(struct expr *,expr_elim_dup,   "_ZW3npcW7kconfig19expr_eliminate_dupsPS0_4expr", struct expr *e);
M(struct expr *,expr_trans_bool, "_ZW3npcW7kconfig15expr_trans_boolPS0_4expr", struct expr *e);
M(struct expr *,expr_trans_cmp,  "_ZW3npcW7kconfig18expr_trans_comparePS0_4exprS0_9expr_typePS0_6symbol", struct expr *e, expr_type t, struct symbol *s);
M(struct expr *,expr_transformf, "_ZW3npcW7kconfig14expr_transformPS0_4expr", struct expr *e);
M(int,expr_contains,             "_ZW3npcW7kconfig20expr_contains_symbolPS0_4exprPS0_6symbol", struct expr *e, struct symbol *s);
M(bool,expr_depends,             "_ZW3npcW7kconfig19expr_depends_symbolPS0_4exprPS0_6symbol", struct expr *e, struct symbol *s);
M(void,expr_fprintf,             "_ZW3npcW7kconfig11expr_fprintPS0_4exprP8_IO_FILE", struct expr *e, FILE *f);
M(void,expr_printf,              "_ZW3npcW7kconfig10expr_printPS0_4exprPFvPvPS0_6symbolPKcES3_i", struct expr *e, void (*fn)(void *, struct symbol*, const char*), void *d, int pt);

M(int,conf_readf,            "_ZW3npcW7kconfig9conf_readPKc", const char *n);
M(int,conf_read_simplef,     "_ZW3npcW7kconfig16conf_read_simplePKci", const char *n, int f);
M(int,conf_writef,           "_ZW3npcW7kconfig10conf_writePKc", const char *n);
M(int,conf_write_auto,       "_ZW3npcW7kconfig19conf_write_autoconfi", int o);
M(int,conf_write_def,        "_ZW3npcW7kconfig20conf_write_defconfigPKc", const char *n);
M(bool,conf_changed,         "_ZW3npcW7kconfig16conf_get_changedv", void);
M(void,conf_set_msg,         "_ZW3npcW7kconfig25conf_set_message_callbackPFvPKcE", void (*fn)(const char*));
M(bool,conf_all_new,         "_ZW3npcW7kconfig24conf_set_all_new_symbolsS0_13conf_def_mode", conf_def_mode m);
M(void,conf_rewrite,         "_ZW3npcW7kconfig23conf_rewrite_mod_or_yesS0_13conf_def_mode", conf_def_mode m);
M(const char *,conf_cfgname, "_ZW3npcW7kconfig19conf_get_confignamev", void);
M(const char *,conf_autoname,"_ZW3npcW7kconfig24conf_get_autoconfig_namev", void);
M(void,conf_touch,           "_ZW3npcW7kconfig14conf_touch_depPKc", const char *n);

M(void,env_write,           "_ZW3npcW7kconfig13env_write_depP8_IO_FILEPKc", FILE *f, const char *n);

M(gstr,str_newf,            "_ZW3npcW7kconfig7str_newv", void);
M(void,str_appendf,         "_ZW3npcW7kconfig10str_appendPS0_4gstrPKc", struct gstr *gs, const char *s);
M(const char *,str_getf,    "_ZW3npcW7kconfig7str_getPS0_4gstr", struct gstr *gs);
M(void,str_freef,           "_ZW3npcW7kconfig8str_freePS0_4gstr", struct gstr *gs);

extern "C" void _mod_str_printf(struct gstr *gs, const char *f, va_list ap) __asm__("_ZW3npcW7kconfig10str_printfPS0_4gstrPKcz");

#undef M

// Globals
extern "C" {
extern struct menu rootmenu    __asm__("_ZW3npcW7kconfig8rootmenu");
extern struct symbol symbol_yes __asm__("_ZW3npcW7kconfig10symbol_yes");
extern struct symbol symbol_mod __asm__("_ZW3npcW7kconfig10symbol_mod");
extern struct symbol symbol_no  __asm__("_ZW3npcW7kconfig9symbol_no");
extern struct symbol *modules_sym __asm__("_ZW3npcW7kconfig11modules_sym");
extern struct symbol *sym_defconfig_list __asm__("_ZW3npcW7kconfig18sym_defconfig_list");
extern struct file *current_file __asm__("_ZW3npcW7kconfig12current_file");
extern struct menu *current_entry __asm__("_ZW3npcW7kconfig13current_entry");
extern struct menu *current_menu __asm__("_ZW3npcW7kconfig12current_menu");
}

// ========================================================================
// C-linkage wrappers — call the _mod_* helpers
// ========================================================================
extern "C" {

void *xmalloc(size_t s)        { return _mod_xmalloc(s); }
void *xcalloc(size_t n, size_t sz) { void *p = _mod_xmalloc(n * sz); memset(p, 0, n * sz); return p; }
void *xrealloc(void *p, size_t s) { return _mod_xrealloc(p, s); }
char *xstrdup(const char *s)   { return _mod_xstrdup(s); }
char *xstrndup(const char *s, size_t n) { return _mod_xstrndup(s, n); }
void  xfree(void *)            {}
struct file *file_lookup(const char *n) { return _mod_file_lookup(n); }

struct symbol *sym_lookup(const char *n, int f)       { return _mod_sym_lookup(n, f); }
struct symbol *sym_find(const char *n)                 { return _mod_sym_find(n); }
struct symbol *sym_lookup_all(const char *n, int f)    { return _mod_sym_lookup(n, f); }
const char *sym_get_string_value(struct symbol *s)     { return _mod_sym_get_sv(s); }
const char *sym_get_string_default(struct symbol *s)   { return _mod_sym_get_sdef(s); }
bool sym_is_changeable(struct symbol *s)               { return _mod_sym_is_chg(s); }
bool sym_set_tristate_value(struct symbol *s, tristate v) { return _mod_sym_set_tri(s, v); }
tristate sym_toggle_tristate_value(struct symbol *s)         { return _mod_sym_toggle(s); }
void sym_calc_value(struct symbol *s)                  { _mod_sym_calc(s); }
bool sym_tristate_within_range(struct symbol *s, tristate v) { return _mod_sym_tri_range(s, v); }
const char *sym_escape_string_value(const char *s)     { return _mod_sym_escape(s); }
void sym_add_change_count(int c)                       { _mod_sym_add_chg(c); }
void sym_set_change_count(int c)                       { _mod_sym_set_chgc(c); }
void sym_clear_all_valid(void)                         { _mod_sym_clear_v(); }
bool sym_string_within_range(struct symbol *s, const char *v) { return _mod_sym_str_range(s, v); }
struct property *sym_get_choice_prop(struct symbol *s) { return _mod_sym_ch_prop(s); }
struct symbol *prop_get_symbol(struct property *p)     { return _mod_prop_get_sym(p); }
symbol_type sym_get_type(struct symbol *s)             { return _mod_sym_type(s); }
bool sym_set_string_value(struct symbol *s, const char *v) { return _mod_sym_set_str(s, v); }
struct symbol *sym_choice_default(struct symbol *s)    { return _mod_sym_ch_def(s); }
struct symbol *sym_check_deps(struct symbol *s)        { return _mod_sym_chk_deps(s); }
void sym_set_changed(struct symbol *s)                 { _mod_sym_set_ch(s); }
void sym_set_all_changed(void)                         { _mod_sym_all_ch(); }
int sym_get_range(struct symbol *)                     { return 0; }

bool menu_is_visible(struct menu *m)       { return _mod_menu_vis(m); }
const char *menu_get_prompt(struct menu *m) { return _mod_menu_prompt(m); }
struct menu *menu_get_parent_menu(struct menu *) { return nullptr; }
bool menu_is_empty(struct menu *m)         { return _mod_menu_vis(m); /* stub */ }
const char *menu_get_help(struct menu *)   { return ""; }
void menu_get_ext_help(struct menu *, struct gstr *h) { h->s = nullptr; h->len = 0; }
bool menu_has_prompt(struct menu *m)       { return _mod_menu_vis(m); /* stub */ }

struct expr *expr_alloc_symbol(struct symbol *s)  { return _mod_expr_alloc_sym(s); }
struct expr *expr_alloc_one(expr_type t, struct expr *e) { return _mod_expr_alloc_one(t, e); }
struct expr *expr_alloc_two(expr_type t, struct expr *e1, struct expr *e2) { return _mod_expr_alloc_two(t, e1, e2); }
struct expr *expr_alloc_comp(expr_type t, struct symbol *s1, struct symbol *s2) { return _mod_expr_alloc_comp(t, s1, s2); }
struct expr *expr_alloc_and(struct expr *e1, struct expr *e2) { return _mod_expr_alloc_and(e1, e2); }
struct expr *expr_alloc_or(struct expr *e1, struct expr *e2)  { return _mod_expr_alloc_or(e1, e2); }
struct expr *expr_copy(const struct expr *o)                   { return _mod_expr_copy(o); }
void expr_free(struct expr *e)                                 { _mod_expr_free_f(e); }
tristate expr_calc_value(struct expr *e)                       { return _mod_expr_calc(e); }
struct expr *expr_eliminate_yn(struct expr *e)                 { return _mod_expr_elim_yn(e); }
struct expr *expr_eliminate_dups(struct expr *e)               { return _mod_expr_elim_dup(e); }
struct expr *expr_trans_bool(struct expr *e)                   { return _mod_expr_trans_bool(e); }
struct expr *expr_trans_compare(struct expr *e, expr_type t, struct symbol *s) { return _mod_expr_trans_cmp(e, t, s); }
struct expr *expr_transform(struct expr *e)                    { return _mod_expr_transformf(e); }
int expr_contains_symbol(struct expr *d, struct symbol *s)     { return _mod_expr_contains(d, s); }
bool expr_depends_symbol(struct expr *d, struct symbol *s)     { return _mod_expr_depends(d, s); }
void expr_fprint(struct expr *e, FILE *f)                      { _mod_expr_fprintf(e, f); }
void expr_print(struct expr *e, void (*fn)(void *, struct symbol *, const char *), void *d, int pt) { _mod_expr_printf(e, fn, d, pt); }

int  conf_read(const char *n) { return _mod_conf_readf(n); }
int  conf_read_simple(const char *n, int f) { return _mod_conf_read_simplef(n, f); }
int  conf_write(const char *n) { return _mod_conf_writef(n); }
int  conf_write_autoconf(int o) { return _mod_conf_write_auto(o); }
int  conf_write_defconfig(const char *n) { return _mod_conf_write_def(n); }
bool conf_get_changed(void) { return _mod_conf_changed(); }
void conf_set_message_callback(void (*fn)(const char *)) { _mod_conf_set_msg(fn); }
bool conf_set_all_new_symbols(conf_def_mode m) { return _mod_conf_all_new(m); }
void conf_rewrite_mod_or_yes(conf_def_mode m) { _mod_conf_rewrite(m); }
const char *conf_get_configname(void) { return _mod_conf_cfgname(); }
const char *conf_get_autoconfig_name(void) { return _mod_conf_autoname(); }
void conf_touch_dep(const char *n) { _mod_conf_touch(n); }

void env_write_dep(FILE *f, const char *n) { _mod_env_write(f, n); }

gstr str_new(void) { return _mod_str_newf(); }
void str_append(struct gstr *gs, const char *s) { _mod_str_appendf(gs, s); }
const char *str_get(struct gstr *gs) { return _mod_str_getf(gs); }
void str_free(struct gstr *gs) { _mod_str_freef(gs); }
void str_printf(struct gstr *gs, const char *f, ...) {
    va_list ap; va_start(ap, f); _mod_str_printf(gs, f, ap); va_end(ap);
}

// Stubs for unimplemented parser functions
void _menu_init(void) {}
void menu_add_entry(struct symbol *) {}
struct menu *menu_add_menu(void) { return nullptr; }
void menu_end_menu(void) {}
void menu_add_dep(struct expr *) {}
void menu_add_visibility(struct expr *) {}
struct property *menu_add_prompt(prop_type, char *, struct expr *) { return nullptr; }
void menu_add_expr(prop_type, struct expr *, struct expr *) {}
void menu_add_symbol(prop_type, struct symbol *, struct expr *) {}
void menu_add_option_modules(void) {}
void menu_add_option_defconfig_list(void) {}
void menu_add_option_allnoconfig_y(void) {}
void menu_finalize(struct menu *) {}
void menu_end_entry(void) {}
void menu_set_type(int) {}
struct menu *menu_get_root_menu(struct menu *m) { return m; }
void menu_warn(struct menu *, const char *, ...) {}
struct gstr get_relations_str(struct symbol **, struct list_head *) { gstr g = {0, nullptr, 0}; return g; }
bool menu_has_help(struct menu *) { return false; }
void set_all_choice_values(struct symbol *) {}
void variable_add(const char *, const char *, variable_flavor) {}
void variable_all_del(void) {}
char *expand_dollar(const char **) { return nullptr; }
char *expand_one_token(const char **) { return nullptr; }

// Parser callbacks
extern int yyparse(void);
extern int zconf_lineno(void);
extern const char *zconf_curname(void);
extern FILE *zconf_fopen(const char *);
extern void zconf_initscan(const char *);
extern void zconf_nextfile(const char *);
extern void zconf_starthelp(void);
extern void zconf_error(const char *fmt, ...);

void zconf_errors(const char *s) { zconf_error("%s", s); }
int  c_yyparse(void) { return yyparse(); }
int  c_zconf_lineno(void) { return zconf_lineno(); }
const char *c_zconf_curname(void) { return zconf_curname(); }
FILE *c_zconf_fopen(const char *n) { return zconf_fopen(n); }
void c_zconf_initscan(const char *n) { zconf_initscan(n); }
void c_zconf_nextfile(const char *n) { zconf_nextfile(n); }
void c_zconf_starthelp(void) { zconf_starthelp(); }

} // extern "C"
