// Compatibility header: maps original mconf.c/lxdialog API calls to C++ bridge
// Include this instead of "lkc.h" when compiling against the C++ module
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <limits.h>
#include <unistd.h>
#include <sys/stat.h>

// Types matching the C++ module (opaque from C's perspective)
typedef struct symbol symbol;
typedef struct menu menu;
typedef struct expr expr;
typedef struct property property;
typedef struct file file;
typedef struct gstr gstr;

enum tristate { no = 0, mod = 1, yes = 2 };
enum symbol_type { S_UNKNOWN=0, S_BOOLEAN=1, S_TRISTATE=2, S_INT=3, S_HEX=4, S_STRING=5, S_OTHER=6 };
enum prop_type { P_UNKNOWN=0, P_PROMPT=1, P_COMMENT=2, P_MENU=3, P_DEFAULT=4, P_CHOICE=5, P_SELECT=6, P_IMPLY=7, P_RANGE=8, P_SYMBOL=9 };

#define SYMBOL_CONST      0x0001
#define SYMBOL_CHOICE     0x0002
#define SYMBOL_CHOICEVAL  0x0004
#define SYMBOL_VALID      0x0008
#define SYMBOL_OPTIONAL   0x0010
#define SYMBOL_WRITE      0x0020
#define SYMBOL_CHANGED    0x0040
#define SYMBOL_NO_WRITE   0x0080
#define SYMBOL_CHECKED    0x0100
#define SYMBOL_WARNED     0x0200
#define SYMBOL_DEF_USER   0x10000
#define SYMBOL_DEF_AUTO   0x20000
#define SYMBOL_DEF3        0x40000
#define SYMBOL_DEF4        0x80000

#define S_DEF_USER 0
#define S_DEF_AUTO 1
#define S_DEF_DEF3 2
#define S_DEF_DEF4 3
#define S_DEF_COUNT 4

#define CONFIG_ "CONFIG_"
#define PATH_MAX 4096

// List macros (C compatible)
struct list_head { struct list_head *next, *prev; };
#define LIST_HEAD_INIT(n) { &(n), &(n) }
#define LIST_HEAD(n) struct list_head n = LIST_HEAD_INIT(n)
#define INIT_LIST_HEAD(p) do { (p)->next = (p); (p)->prev = (p); } while(0)
static inline void __list_add(struct list_head *n, struct list_head *prev, struct list_head *next) { n->next = next; n->prev = prev; prev->next = n; next->prev = n; }
static inline void list_add_tail(struct list_head *n, struct list_head *h) { __list_add(n, h->prev, h); }
static inline void list_del(struct list_head *e) { e->prev->next = e->next; e->next->prev = e->prev; }
#define list_entry(ptr, type, member) ((type *)((char *)(ptr) - (size_t)(&((type *)0)->member)))
#define list_first_entry(ptr, type, member) list_entry((ptr)->next, type, member)
#define list_for_each_entry(pos, head, member) for (pos = list_entry((head)->next, typeof(*pos), member); &pos->member != (head); pos = list_entry(pos->member.next, typeof(*pos), member))
#define list_for_each_entry_safe(pos, n, head, member) for (pos = list_entry((head)->next, typeof(*pos), member), n = list_entry(pos->member.next, typeof(*pos), member); &pos->member != (head); pos = n, n = list_entry(n->member.next, typeof(*n), member))

// Variable flavor
enum variable_flavor { VAR_SIMPLE=0, VAR_RECURSIVE=1, VAR_APPEND=2 };

// Bridge functions from C++ module
extern symbol *c_sym_lookup(const char*, int);
extern symbol *c_sym_find(const char*);
extern int c_sym_get_type(symbol*);
extern tristate c_sym_get_tristate_value(symbol*);
extern const char *c_sym_get_string_value(symbol*);
extern int c_sym_set_tristate_value(symbol*, tristate);
extern int c_sym_set_string_value(symbol*, const char*);
extern int c_sym_has_value(symbol*);
extern int c_sym_is_changeable(symbol*);
extern int c_sym_is_choice(symbol*);
extern int c_sym_is_choice_value(symbol*);
extern void c_sym_calc_value(symbol*);
extern symbol *c_sym_get_choice_value(symbol*);
extern int c_sym_tristate_within_range(symbol*, tristate);
extern const char *c_sym_escape_string_value(const char*);

extern int c_menu_is_visible(menu*);
extern const char *c_menu_get_prompt(menu*);
extern menu *c_menu_get_parent_menu(menu*);
extern void c_menu_get_ext_help(menu*, gstr*);
extern int c_menu_is_empty(menu*);
extern const char *c_menu_get_help(menu*);

extern int c_conf_read(const char*);
extern int c_conf_write(const char*);
extern int c_conf_write_autoconf(int);
extern int c_conf_get_changed();
extern void c_conf_set_message_callback(void(*)(const char*));

extern gstr c_str_new();
extern void c_str_printf(gstr*, const char*, ...);
extern const char *c_str_get(gstr*);
extern void c_str_free(gstr*);

extern void c_variable_add(const char*, const char*, int);
extern char *c_variable_expand(const char*);
extern void c_variable_all_del();

extern void *c_xmalloc(size_t);
extern void *c_xcalloc(size_t, size_t);
extern void *c_xrealloc(void*, size_t);
extern char *c_xstrdup(const char*);
extern char *c_xstrndup(const char*, size_t);

extern symbol *c_symbol_yes, *c_symbol_mod, *c_symbol_no, *c_modules_sym;
extern menu *c_rootmenu, *c_current_entry, *c_current_menu;

// Convenience macros mapping to bridge
#define symbol_yes (*c_symbol_yes)
#define symbol_mod (*c_symbol_mod)
#define symbol_no (*c_symbol_no)
#define modules_sym c_modules_sym
#define rootmenu (*c_rootmenu)
#define current_entry c_current_entry
#define current_menu c_current_menu

#define sym_lookup c_sym_lookup
#define sym_find c_sym_find
#define sym_get_type c_sym_get_type
#define sym_get_tristate_value c_sym_get_tristate_value
#define sym_get_string_value c_sym_get_string_value
#define sym_set_tristate_value c_sym_set_tristate_value
#define sym_set_string_value c_sym_set_string_value
#define sym_has_value c_sym_has_value
#define sym_is_changeable c_sym_is_changeable
#define sym_is_choice c_sym_is_choice
#define sym_is_choice_value c_sym_is_choice_value
#define sym_calc_value c_sym_calc_value
#define sym_get_choice_value c_sym_get_choice_value
#define sym_tristate_within_range c_sym_tristate_within_range
#define sym_escape_string_value c_sym_escape_string_value

#define menu_is_visible c_menu_is_visible
#define menu_get_prompt c_menu_get_prompt
#define menu_get_parent_menu c_menu_get_parent_menu
#define menu_get_ext_help c_menu_get_ext_help
#define menu_is_empty c_menu_is_empty
#define menu_get_help c_menu_get_help

#define conf_read c_conf_read
#define conf_write c_conf_write
#define conf_write_autoconf c_conf_write_autoconf
#define conf_get_changed c_conf_get_changed
#define conf_set_message_callback c_conf_set_message_callback

#define str_new c_str_new
#define str_printf c_str_printf
#define str_get c_str_get
#define str_free c_str_free

#define variable_add c_variable_add
#define variable_expand c_variable_expand
#define variable_all_del c_variable_all_del

#define xmalloc c_xmalloc
#define xcalloc c_xcalloc
#define xrealloc c_xrealloc
#define xstrdup c_xstrdup
#define xstrndup c_xstrndup

#ifdef __cplusplus
}
#endif

// Parser
extern void c_conf_parse(const char *name);
#define conf_parse c_conf_parse
