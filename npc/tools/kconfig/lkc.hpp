/* SPDX-License-Identifier: GPL-2.0 */
/*
 * Copyright (C) 2002 Roman Zippel <zippel@linux-m68k.org>
 */

#ifndef LKC_H
#define LKC_H

#include <cassert>  /* assert */
#include <cstdio>   /* FILE */
#include <cstddef>  /* size_t */
#include <cstdlib>  /* getenv */
#include <memory>   /* std::unique_ptr */
#include <string>   /* std::string */
#include <vector>   /* std::vector */

#include "expr.hpp"

/* All kconfig sources are C++ (.cpp); use uniform C++ linkage. */
#include "lkc_proto.hpp"

namespace kconfig {

#define SRCTREE "srctree"

#ifndef PACKAGE
#define PACKAGE "linux"
#endif

/*
 * CONFIG_ macro trick: the rest of the code uses CONFIG_ as if it were a
 * plain prefix string, but it is really a function call so the prefix can be
 * overridden at runtime via the CONFIG_ environment variable.  The first
 * (temporary) definition only supplies the default value for CONFIG_prefix();
 * it is then undefined and CONFIG_ is redefined as the function call.  A
 * build-time -DCONFIG_=... still overrides the default value.
 */
#ifndef CONFIG_
#define CONFIG_ "CONFIG_"
#endif
static inline const char *CONFIG_prefix(void)
{
	const char *p = getenv("CONFIG_");

	return p ? p : CONFIG_;
}
#undef CONFIG_
#define CONFIG_ CONFIG_prefix()

enum class conf_def_mode {
	def_default,
	def_yes,
	def_mod,
	def_y2m,
	def_m2y,
	def_m2n,
	def_no,
	def_random
};

/* See tristate above: bare-enumerator compatibility aliases. */
inline constexpr conf_def_mode def_default = conf_def_mode::def_default;
inline constexpr conf_def_mode def_yes     = conf_def_mode::def_yes;
inline constexpr conf_def_mode def_mod     = conf_def_mode::def_mod;
inline constexpr conf_def_mode def_y2m     = conf_def_mode::def_y2m;
inline constexpr conf_def_mode def_m2y     = conf_def_mode::def_m2y;
inline constexpr conf_def_mode def_m2n     = conf_def_mode::def_m2n;
inline constexpr conf_def_mode def_no      = conf_def_mode::def_no;
inline constexpr conf_def_mode def_random  = conf_def_mode::def_random;

void zconfdump(FILE *out);
void zconf_starthelp(void);
FILE *zconf_fopen(const char *name);
void zconf_initscan(const char *name);
void zconf_nextfile(const char *name);
int zconf_lineno(void);
const char *zconf_curname(void);

/* confdata.c */
const char *conf_get_configname(void);
void sym_set_change_count(int count);
void sym_add_change_count(int count);
bool conf_set_all_new_symbols(enum conf_def_mode mode);
void conf_set_randconfig_seed(void);
void conf_suppress_changed_input_warning(void);
void conf_rewrite_mod_or_yes(enum conf_def_mode mode);
void set_all_choice_values(struct symbol *csym);

/* confdata.c and expr.c */
static inline void xfwrite(const void *str, size_t len, size_t count, FILE *out)
{
	assert(len != 0);

	if (fwrite(str, len, count, out) != count)
		fprintf(stderr, "Error in writing or end of file.\n");
}

/* util.c */
struct file *file_lookup(const char *name, const char *parent_name,
			 int parent_lineno);
[[nodiscard]] void *xmalloc(size_t size);
[[nodiscard]] void *xcalloc(size_t nmemb, size_t size);
[[nodiscard]] void *xrealloc(void *p, size_t size);
[[nodiscard]] char *xstrdup(const char *s);
[[nodiscard]] char *xstrndup(const char *s, size_t n);

/* lexer.l */
int yylex(void);

/*
 * 可增长字符串。现代实现：内部为 std::string；max_width 不为 0 时，
 * 打印表达式时会在超宽处折行（见 expr.cpp 的 expr_print_gstr_helper）。
 */
struct gstr {
	std::string s;
	int max_width = 0;
};
struct gstr str_new(void);
void str_free(struct gstr *gs);
void str_append(struct gstr *gs, const char *s);
void str_append(struct gstr *gs, const std::string &s);
void str_printf(struct gstr *gs, const char *fmt, ...);
const char *str_get(struct gstr *gs);

/* FILE* 的 RAII 包装：析构时自动 fclose */
struct file_closer {
	void operator()(FILE *fp) const { if (fp) fclose(fp); }
};
using FilePtr = std::unique_ptr<FILE, file_closer>;

/* menu.c */
void _menu_init(void);
void menu_warn(struct menu *menu, const char *fmt, ...);
struct menu *menu_add_menu(void);
void menu_end_menu(void);
void menu_add_entry(struct symbol *sym);
void menu_add_dep(struct expr *dep, struct expr *cond);
void menu_add_visibility(struct expr *dep);
struct property *menu_add_prompt(enum prop_type type, char *prompt, struct expr *dep);
void menu_add_expr(enum prop_type type, struct expr *expr, struct expr *dep);
void menu_add_symbol(enum prop_type type, struct symbol *sym, struct expr *dep);
void menu_add_option_modules(void);
void menu_add_option_defconfig_list(void);
void menu_add_option_allnoconfig_y(void);
void menu_finalize(struct menu *parent);
void menu_set_type(enum symbol_type type);

extern struct menu rootmenu;

bool menu_is_empty(struct menu *menu);
bool menu_is_visible(struct menu *menu);
bool menu_has_prompt(struct menu *menu);
const char *menu_get_prompt(struct menu *menu);
struct menu *menu_get_root_menu(struct menu *menu);
struct menu *menu_get_parent_menu(struct menu *menu);
bool menu_has_help(struct menu *menu);
const char *menu_get_help(struct menu *menu);
struct gstr get_relations_str(const std::vector<struct symbol *> &sym_arr,
			      struct list_head *head);
void menu_get_ext_help(struct menu *menu, struct gstr *help);

/* symbol.c */
void sym_clear_all_valid(void);
struct symbol *sym_choice_default(struct symbol *sym);
struct property *sym_get_range_prop(struct symbol *sym);
const char *sym_get_string_default(struct symbol *sym);
struct symbol *sym_check_deps(struct symbol *sym);
struct symbol *prop_get_symbol(struct property *prop);

static inline tristate sym_get_tristate_value(struct symbol *sym)
{
	return sym->curr.tri;
}


static inline struct symbol *sym_get_choice_value(struct symbol *sym)
{
	return sym->curr.val.sym;
}

static inline bool sym_set_choice_value(struct symbol *ch, struct symbol *chval)
{
	return sym_set_tristate_value(chval, yes);
}

static inline bool sym_is_choice(struct symbol *sym)
{
	return sym->flags & SYMBOL_CHOICE ? true : false;
}

static inline bool sym_is_choice_value(struct symbol *sym)
{
	return sym->flags & SYMBOL_CHOICEVAL ? true : false;
}

static inline bool sym_is_optional(struct symbol *sym)
{
	return sym->flags & SYMBOL_OPTIONAL ? true : false;
}

static inline bool sym_has_value(struct symbol *sym)
{
	return sym->flags & SYMBOL_DEF_USER ? true : false;
}

/*
 * 解析期上下文：把原先散落的解析状态全局变量收拢为一个对象
 * （current_file 由词法器维护，其余由文法动作维护）。
 */
struct ParseContext {
	struct menu *current_menu = nullptr;
	struct menu *current_entry = nullptr;
	struct menu *current_choice = nullptr;
	struct file *current_file = nullptr;
};
extern ParseContext parse_ctx;
/* 所有被解析的 Kconfig 文件，按包含顺序（最旧在前；depfile 用逆序） */
const std::vector<struct file *> &kconfig_files(void);
extern void zconf_error(const char *err, ...);

extern int yydebug;

} /* namespace kconfig */

/* flex 的 %option yylineno 在全局作用域定义 yylineno */
extern int yylineno;

#endif /* LKC_H */
