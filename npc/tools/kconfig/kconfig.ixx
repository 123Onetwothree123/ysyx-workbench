/* SPDX-License-Identifier: GPL-2.0 */
/*
 * kconfig.core：Kconfig 核心模块接口（C++20 模块）。
 *
 * 本文件取代原来的 expr.hpp / lkc_proto.hpp / lkc.hpp / list.hpp：
 * 类型、函数与变量声明都在模块里；
 * - 宏（模块无法导出）见 kconfig_macros.hpp；
 * - 由 bison/flex 生成文件定义的解析/词法入口见 kconfig_parse.hpp。
 */
module;
#include <cassert> /* assert（bits/stdc++.h 不含它；宏无法从 std 模块获得） */
#include <cstdio>  /* stderr（libc++ 的 std 模块不导出 C 的 stdio 全局量） */
#ifdef __clang__
/* clang：std 走模块（在下方 import） */
#else
#include <bits/stdc++.h>
#endif
export module kconfig.core;

#ifdef __clang__
import std;
#endif
using namespace std;

export namespace kconfig {

/* ==================== 侵入式链表（原 list.hpp） ==================== */
/**
 * list_container_of - cast a member of a structure out to the containing
 * structure (template implementation behind the container_of() macro)
 * @ptr:           the pointer to the member.
 * @member_offset: offsetof() of the member within the containing struct.
 */
template <typename T, typename M>
inline T *list_container_of(M *ptr, size_t member_offset)
{
	char *p = const_cast<char *>(reinterpret_cast<const char *>(ptr));

	return reinterpret_cast<T *>(p - member_offset);
}


struct list_head {
	struct list_head *next, *prev;
};

/**
 * list_empty - tests whether a list is empty
 * @head: the list to test.
 */
inline int list_empty(const struct list_head *head)
{
	return head->next == head;
}

/*
 * Insert a new entry between two known consecutive entries.
 *
 * This is only for internal list manipulation where we know
 * the prev/next entries already!
 */
inline void __list_add(struct list_head *_new,
			      struct list_head *prev,
			      struct list_head *next)
{
	next->prev = _new;
	_new->next = next;
	_new->prev = prev;
	prev->next = _new;
}

/**
 * list_add_tail - add a new entry
 * @new: new entry to be added
 * @head: list head to add it before
 *
 * Insert a new entry before the specified head.
 * This is useful for implementing queues.
 */
inline void list_add_tail(struct list_head *_new, struct list_head *head)
{
	__list_add(_new, head->prev, head);
}

/*
 * Delete a list entry by making the prev/next entries
 * point to each other.
 *
 * This is only for internal list manipulation where we know
 * the prev/next entries already!
 */
inline void __list_del(struct list_head *prev, struct list_head *next)
{
	next->prev = prev;
	prev->next = next;
}

/* 删除后的毒指针（原 list.hpp 的 LIST_POISON1/2 宏；整数形式，constexpr） */
inline constexpr uintptr_t LIST_POISON1 = 0x00100100;
inline constexpr uintptr_t LIST_POISON2 = 0x00200200;

/**
 * list_del - deletes entry from list.
 * @entry: the element to delete from the list.
 * Note: list_empty() on entry does not return true after this, the entry is
 * in an undefined state.
 */
inline void list_del(struct list_head *entry)
{
	__list_del(entry->prev, entry->next);
	entry->next = reinterpret_cast<struct list_head *>(LIST_POISON1);
	entry->prev = reinterpret_cast<struct list_head *>(LIST_POISON2);
}


/* ==================== 类型与声明（原 expr.hpp） ==================== */

struct file {
	struct file *parent;
	const char *name;
	int lineno;
	const char *parent_name; /* 首次包含它的文件（用于重复包含报错） */
	int parent_lineno;       /* 首次包含它的行号 */
};

enum class tristate {
	no, mod, yes
};

/*
 * Unqualified-name compatibility aliases. The codebase (including the
 * generated parser/lexer) uses the bare enumerators everywhere; these keep
 * all those call sites source-compatible with the scoped enum.
 */
inline constexpr tristate no  = tristate::no;
inline constexpr tristate mod = tristate::mod;
inline constexpr tristate yes = tristate::yes;

enum class expr_type {
	E_NONE, E_OR, E_AND, E_NOT,
	E_EQUAL, E_UNEQUAL, E_LTH, E_LEQ, E_GTH, E_GEQ,
	E_LIST, E_SYMBOL, E_RANGE
};

/* See tristate above: bare-enumerator compatibility aliases. */
inline constexpr expr_type E_NONE    = expr_type::E_NONE;
inline constexpr expr_type E_OR      = expr_type::E_OR;
inline constexpr expr_type E_AND     = expr_type::E_AND;
inline constexpr expr_type E_NOT     = expr_type::E_NOT;
inline constexpr expr_type E_EQUAL   = expr_type::E_EQUAL;
inline constexpr expr_type E_UNEQUAL = expr_type::E_UNEQUAL;
inline constexpr expr_type E_LTH     = expr_type::E_LTH;
inline constexpr expr_type E_LEQ     = expr_type::E_LEQ;
inline constexpr expr_type E_GTH     = expr_type::E_GTH;
inline constexpr expr_type E_GEQ     = expr_type::E_GEQ;
inline constexpr expr_type E_LIST    = expr_type::E_LIST;
inline constexpr expr_type E_SYMBOL  = expr_type::E_SYMBOL;
inline constexpr expr_type E_RANGE   = expr_type::E_RANGE;

union expr_data {
	void *_initdata; /* 原始指针：表达式驻留（去重）用 */
	struct expr *expr;
	struct symbol *sym;
};

struct expr {
	enum expr_type type;
	union expr_data left, right;
};

inline constexpr tristate EXPR_OR(tristate dep1, tristate dep2)
{
	return dep1 > dep2 ? dep1 : dep2;
}

inline constexpr tristate EXPR_AND(tristate dep1, tristate dep2)
{
	return dep1 < dep2 ? dep1 : dep2;
}

inline constexpr tristate EXPR_NOT(tristate dep)
{
	return static_cast<tristate>(2 - static_cast<int>(dep));
}

struct expr_value {
	struct expr *expr;
	tristate tri;
};

/*
 * A symbol's value: 'str' holds string/int/hex values (and the constant
 * tristate symbols' literal names), 'sym' holds the selected choice-value
 * symbol of a choice. Which one is meaningful follows from the symbol's
 * type/flags at the access site.
 */
struct symbol_value {
	std::string str;
	struct symbol *sym = nullptr;
	tristate tri = no;
};

enum class symbol_type {
	S_UNKNOWN, S_BOOLEAN, S_TRISTATE, S_INT, S_HEX, S_STRING
};

/* See tristate above: bare-enumerator compatibility aliases. */
inline constexpr symbol_type S_UNKNOWN  = symbol_type::S_UNKNOWN;
inline constexpr symbol_type S_BOOLEAN  = symbol_type::S_BOOLEAN;
inline constexpr symbol_type S_TRISTATE = symbol_type::S_TRISTATE;
inline constexpr symbol_type S_INT      = symbol_type::S_INT;
inline constexpr symbol_type S_HEX      = symbol_type::S_HEX;
inline constexpr symbol_type S_STRING   = symbol_type::S_STRING;

/* enum values are used as index to symbol.def[] */
enum {
	S_DEF_USER,		/* main user value */
	S_DEF_AUTO,		/* values read from auto.conf */
	S_DEF_DEF3,		/* Reserved for UI usage */
	S_DEF_DEF4,		/* Reserved for UI usage */
	S_DEF_COUNT
};

/*
 * Represents a configuration symbol.
 *
 * Choices are represented as a special kind of symbol and have the
 * SYMBOL_CHOICE bit set in 'flags'.
 */
struct property; /* 属性（prop_type）——对象由 menu.cpp 的属性池拥有 */

struct symbol {
	/* The name of the symbol, e.g. "FOO" for 'config FOO' */
	const char *name;

	/* S_BOOLEAN, S_TRISTATE, ... */
	enum symbol_type type;

	/*
	 * The calculated value of the symbol. The SYMBOL_VALID bit is set in
	 * 'flags' when this is up to date. Note that this value might differ
	 * from the user value set in e.g. a .config file, due to visibility.
	 */
	struct symbol_value curr;

	/*
	 * Values for the symbol provided from outside. def[S_DEF_USER] holds
	 * the .config value.
	 */
	struct symbol_value def[S_DEF_COUNT];

	/*
	 * An upper bound on the tristate value the user can set for the symbol
	 * if it is a boolean or tristate. Calculated from prompt dependencies,
	 * which also inherit dependencies from enclosing menus, choices, and
	 * ifs. If 'n', the user value will be ignored.
	 *
	 * Symbols lacking prompts always have visibility 'n'.
	 */
	tristate visible;

	/* SYMBOL_* flags */
	int flags;

	/* List of properties. See prop_type.（元素为指向属性池的裸指针） */
	std::vector<struct property *> prop;

	/* Dependencies from enclosing menus, choices, and ifs */
	struct expr_value dir_dep;

	/* Reverse dependencies through being selected by other symbols */
	struct expr_value rev_dep;

	/*
	 * "Weak" reverse dependencies through being implied by other symbols
	 */
	struct expr_value implied;
};

/*
 * 按历史 hash 桶顺序遍历全部符号（现代容器实现，见 symbol.cpp）。
 * 遍历顺序被刻意保留，以保证 auto.conf / autoconf.h 逐字节不变。
 */
class SymbolRange {
public:
	class iterator {
	public:
		using iterator_category = std::forward_iterator_tag;
		using value_type = struct symbol *;
		using difference_type = std::ptrdiff_t;
		using pointer = struct symbol *;
		using reference = struct symbol *;

		iterator() = default;

		struct symbol *operator*() const;
		iterator &operator++();
		bool operator!=(const iterator &o) const
		{
			return table_ != o.table_ || bucket_ != o.bucket_ || pos_ != o.pos_;
		}

	private:
		friend class SymbolRange;
		iterator(const void *table, size_t bucket, size_t pos)
			: table_(table), bucket_(bucket), pos_(pos) {}

		const void *table_ = nullptr; /* 符号表内部指针（不透明） */
		size_t bucket_ = 0;
		size_t pos_ = 0; /* 桶内位置（自尾部向前，复刻历史前插顺序） */
	};

	iterator begin() const;
	iterator end() const { return {}; }
};

SymbolRange all_symbols();

inline constexpr int SYMBOL_CONST      = 0x0001;  /* symbol is const */
inline constexpr int SYMBOL_CHECK      = 0x0008;  /* used during dependency checking */
inline constexpr int SYMBOL_CHOICE     = 0x0010;  /* start of a choice block (null name) */
inline constexpr int SYMBOL_CHOICEVAL  = 0x0020;  /* used as a value in a choice block */
inline constexpr int SYMBOL_VALID      = 0x0080;  /* set when symbol.curr is calculated */
inline constexpr int SYMBOL_OPTIONAL   = 0x0100;  /* choice is optional - values can be 'n' */
inline constexpr int SYMBOL_WRITE      = 0x0200;  /* write symbol to file (KCONFIG_CONFIG) */
inline constexpr int SYMBOL_CHANGED    = 0x0400;  /* ? */
inline constexpr int SYMBOL_WRITTEN    = 0x0800;  /* track info to avoid double-write to .config */
inline constexpr int SYMBOL_NO_WRITE   = 0x1000;  /* Symbol for internal use only; it will not be written */
inline constexpr int SYMBOL_CHECKED    = 0x2000;  /* used during dependency checking */
inline constexpr int SYMBOL_WARNED     = 0x8000;  /* warning has been issued */

/* Set when symbol.def[] is used */
inline constexpr int SYMBOL_DEF        = 0x10000;  /* First bit of SYMBOL_DEF */
inline constexpr int SYMBOL_DEF_USER   = 0x10000;  /* symbol.def[S_DEF_USER] is valid */
inline constexpr int SYMBOL_DEF_AUTO   = 0x20000;  /* symbol.def[S_DEF_AUTO] is valid */
inline constexpr int SYMBOL_DEF3       = 0x40000;  /* symbol.def[S_DEF_3] is valid */
inline constexpr int SYMBOL_DEF4       = 0x80000;  /* symbol.def[S_DEF_4] is valid */

/* choice values need to be set before calculating this symbol value */
inline constexpr int SYMBOL_NEED_SET_CHOICE_VALUES = 0x100000;

/* Set symbol to y if allnoconfig; used for symbols that hide others */
inline constexpr int SYMBOL_ALLNOCONFIG_Y = 0x200000;

/*
 * Upstream assigns 0x0100 to SYMBOL_TRANS, but that bit is still used
 * by SYMBOL_OPTIONAL in this port, so the next free bit is taken here.
 */
inline constexpr int SYMBOL_TRANS      = 0x400000;  /* symbol is transitional only (not visible) */

inline constexpr int SYMBOL_MAXLENGTH = 256;
inline constexpr int SYMBOL_HASHSIZE = 9973;

/* A property represent the config options that can be associated
 * with a config "symbol".
 * Sample:
 * config FOO
 *         default y
 *         prompt "foo prompt"
 *         select BAR
 * config BAZ
 *         int "BAZ Value"
 *         range 1..255
 *
 * Please, also check parser.y:print_symbol() when modifying the
 * list of property types!
 */
enum class prop_type {
	P_UNKNOWN,
	P_PROMPT,   /* prompt "foo prompt" or "BAZ Value" */
	P_COMMENT,  /* text associated with a comment */
	P_MENU,     /* prompt associated with a menu or menuconfig symbol */
	P_DEFAULT,  /* default y */
	P_CHOICE,   /* choice value */
	P_SELECT,   /* select BAR */
	P_IMPLY,    /* imply BAR */
	P_RANGE,    /* range 7..100 (for a symbol) */
	P_SYMBOL,   /* where a symbol is defined */
};

/* See tristate above: bare-enumerator compatibility aliases. */
inline constexpr prop_type P_UNKNOWN = prop_type::P_UNKNOWN;
inline constexpr prop_type P_PROMPT  = prop_type::P_PROMPT;
inline constexpr prop_type P_COMMENT = prop_type::P_COMMENT;
inline constexpr prop_type P_MENU    = prop_type::P_MENU;
inline constexpr prop_type P_DEFAULT = prop_type::P_DEFAULT;
inline constexpr prop_type P_CHOICE  = prop_type::P_CHOICE;
inline constexpr prop_type P_SELECT  = prop_type::P_SELECT;
inline constexpr prop_type P_IMPLY   = prop_type::P_IMPLY;
inline constexpr prop_type P_RANGE   = prop_type::P_RANGE;
inline constexpr prop_type P_SYMBOL  = prop_type::P_SYMBOL;

struct property {
	struct property *next;     /* next property - null if last */
	enum prop_type type;       /* type of property */
	const char *text;          /* the prompt value - P_PROMPT, P_MENU, P_COMMENT */
	struct expr_value visible;
	struct expr *expr;         /* the optional conditional part of the property */
	struct menu *menu;         /* the menu the property are associated with
	                            * valid for: P_SELECT, P_RANGE, P_CHOICE,
	                            * P_PROMPT, P_DEFAULT, P_MENU, P_COMMENT */
	struct file *file;         /* what file was this property defined */
	int lineno;                /* what lineno was this property defined */
};


/*
 * Represents a node in the menu tree, as seen in e.g. menuconfig (though used
 * for all front ends). Each symbol, menu, etc. defined in the Kconfig files
 * gets a node. A symbol defined in multiple locations gets one node at each
 * location.
 */
struct menu {
	/* 子节点（拥有；保持插入顺序） */
	std::vector<std::unique_ptr<struct menu>> children;

	/* The parent menu node, corresponding to e.g. a menu or choice */
	struct menu *parent;

	/*
	 * The symbol associated with the menu node. Choices are implemented as
	 * a special kind of symbol. NULL for menus, comments, and ifs.
	 */
	struct symbol *sym;

	/*
	 * The prompt associated with the node. This holds the prompt for a
	 * symbol as well as the text for a menu or comment, along with the
	 * type (P_PROMPT, P_MENU, etc.)
	 */
	struct property *prompt;

	/*
	 * 'visible if' dependencies. If more than one is given, they will be
	 * ANDed together.
	 */
	struct expr *visibility;

	/*
	 * Ordinary dependencies from e.g. 'depends on' and 'if', ANDed
	 * together
	 */
	struct expr *dep;

	/* MENU_* flags */
	unsigned int flags;

	/* Any help text associated with the node */
	char *help;

	/* The location where the menu node appears in the Kconfig files */
	struct file *file;
	int lineno;

	/* For use by front ends that need to store auxiliary data */
	void *data;
};

/*
 * Set on a menu node when the corresponding symbol changes state in some way.
 * Can be checked by front ends.
 */
inline constexpr int MENU_CHANGED = 0x0001;

inline constexpr int MENU_ROOT = 0x0002;

struct jump_key {
	struct list_head entries;
	size_t offset;
	struct menu *target;
	int index;
};

inline constexpr int JUMP_NB = 9;

/* current_file 已并入 ParseContext（见 lkc.hpp） */

extern struct symbol symbol_yes, symbol_no, symbol_mod;
struct expr *expr_alloc_symbol(struct symbol *sym);
struct expr *expr_alloc_one(enum expr_type type, struct expr *ce);
struct expr *expr_alloc_two(enum expr_type type, struct expr *e1, struct expr *e2);
struct expr *expr_alloc_comp(enum expr_type type, struct symbol *s1, struct symbol *s2);
struct expr *expr_alloc_list(struct expr *next, struct symbol *sym);
struct expr *expr_alloc_and(struct expr *e1, struct expr *e2);
struct expr *expr_alloc_or(struct expr *e1, struct expr *e2);
struct expr *expr_copy(const struct expr *org);
void expr_free(struct expr *e);
void expr_eliminate_eq(struct expr **ep1, struct expr **ep2);
[[nodiscard]] bool expr_eq(const struct expr *e1, const struct expr *e2);
tristate expr_calc_value(struct expr *e);
struct expr *expr_trans_bool(struct expr *e);
struct expr *expr_eliminate_dups(struct expr *e);
struct expr *expr_transform(struct expr *e);
[[nodiscard]] bool expr_contains_symbol(const struct expr *dep, struct symbol *sym);
[[nodiscard]] bool expr_depends_symbol(const struct expr *dep, struct symbol *sym);
struct expr *expr_trans_compare(struct expr *e, enum expr_type type, struct symbol *sym);

void expr_fprint(struct expr *e, FILE *out);
struct gstr; /* forward */
void expr_gstr_print(struct expr *e, struct gstr *gs);
void expr_gstr_print_revdep(struct expr *e, struct gstr *gs,
			    tristate pr_type, const char *title);

[[nodiscard]] inline bool expr_is_yes(const struct expr *e)
{
	return !e || (e->type == E_SYMBOL && e->left.sym == &symbol_yes);
}

[[nodiscard]] inline bool expr_is_no(const struct expr *e)
{
	return e && (e->type == E_SYMBOL && e->left.sym == &symbol_no);
}


/* ==================== 核心 API 声明（原 lkc_proto.hpp） ==================== */

/* confdata.c */
int conf_read(const char *name);
int conf_read_simple(const char *name, int);
int conf_write_defconfig(const char *name);
int conf_write(const char *name);
int conf_write_autoconf(int overwrite);
[[nodiscard]] bool conf_get_changed(void);
void conf_set_changed_callback(void (*fn)(void));
void conf_set_message_callback(void (*fn)(const char *s));
bool conf_errors(void);

/* symbol.c */

struct symbol * sym_lookup(const char *name, int flags);
struct symbol * sym_find(const char *name);
std::string sym_escape_string_value(const char *in);
std::vector<struct symbol *> sym_re_search(const char *pattern);
const char * sym_type_name(enum symbol_type type);
bool sym_dep_errors(void);
void sym_calc_value(struct symbol *sym);
enum symbol_type sym_get_type(struct symbol *sym);
bool sym_tristate_within_range(struct symbol *sym,tristate tri);
bool sym_set_tristate_value(struct symbol *sym,tristate tri);
tristate sym_toggle_tristate_value(struct symbol *sym);
bool sym_string_valid(struct symbol *sym, const char *newval);
bool sym_string_within_range(struct symbol *sym, const char *str);
bool sym_set_string_value(struct symbol *sym, const char *newval);
bool sym_is_changeable(struct symbol *sym);
struct property * sym_get_choice_prop(struct symbol *sym);
const char * sym_get_string_value(struct symbol *sym);

const char * prop_get_type_name(enum prop_type type);

/* preprocess.c */
enum class variable_flavor {
	VAR_SIMPLE,
	VAR_RECURSIVE,
	VAR_APPEND,
};

/* See tristate above: bare-enumerator compatibility aliases. */
inline constexpr variable_flavor VAR_SIMPLE    = variable_flavor::VAR_SIMPLE;
inline constexpr variable_flavor VAR_RECURSIVE = variable_flavor::VAR_RECURSIVE;
inline constexpr variable_flavor VAR_APPEND    = variable_flavor::VAR_APPEND;
void env_write_dep(FILE *f, const char *auto_conf_name);
void variable_add(const char *name, const char *value,
		  enum variable_flavor flavor);
void variable_all_del(void);
char *expand_dollar(const char **str);
char *expand_one_token(const char **str);

/* expr.c */
void expr_print(struct expr *e, void (*fn)(void *, struct symbol *, const char *), void *data, expr_type prevtoken);


/* ==================== 其余声明与 Kconfig 对象（原 lkc.hpp） ==================== */

/*
 * CONFIG_ 宏 trick 的模块侧：CONFIG_prefix() 返回运行时前缀。
 * 宏本身（CONFIG_ 展开为 CONFIG_prefix()）在 kconfig_macros.hpp 中定义。
 */
#ifndef CONFIG_
#define CONFIG_ "CONFIG_"
#endif
inline const char *CONFIG_prefix(void)
{
	const char *p = getenv("CONFIG_");

	return p ? p : CONFIG_;
}
#undef CONFIG_

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
inline void xfwrite(const void *str, size_t len, size_t count, FILE *out)
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

inline tristate sym_get_tristate_value(struct symbol *sym)
{
	return sym->curr.tri;
}


inline struct symbol *sym_get_choice_value(struct symbol *sym)
{
	return sym->curr.sym;
}

inline bool sym_set_choice_value(struct symbol *ch, struct symbol *chval)
{
	return sym_set_tristate_value(chval, yes);
}

inline bool sym_is_choice(struct symbol *sym)
{
	return sym->flags & SYMBOL_CHOICE ? true : false;
}

inline bool sym_is_choice_value(struct symbol *sym)
{
	return sym->flags & SYMBOL_CHOICEVAL ? true : false;
}

inline bool sym_is_optional(struct symbol *sym)
{
	return sym->flags & SYMBOL_OPTIONAL ? true : false;
}

inline bool sym_has_value(struct symbol *sym)
{
	return sym->flags & SYMBOL_DEF_USER ? true : false;
}

/*
 * 致命错误：由深层代码抛出，main() 作为唯一的异常边界统一捕获。
 * 调用点负责先打印错误信息；抛出异常而不是直接 exit()，是为了
 * 让栈展开执行 RAII 清理（临时文件、FILE* 等）。
 */
struct FatalError : std::runtime_error {
	FatalError() : std::runtime_error("kconfig: fatal error") {}
};

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

/*
 * Kconfig：一次配置处理的全部状态，取代原先散落在各 .cpp 里的
 * 文件级静态单例。
 *
 * - 对象池拥有全部 symbol/expr/property/file，其余代码只持有裸指针，
 *   池内地址稳定；
 * - ctx 通过 %parse-param 注入给 yy::parser（词法器受 flex C 模式
 *   限制，直接访问同一对象，见 lexer.l 顶部说明）；
 * - 模块私有的查找索引（哈希表/桶）仍留在各自 .cpp 内，属于模块
 *   内部实现而非 Kconfig 状态。
 */
struct Kconfig {
	/* 解析期上下文 */
	ParseContext ctx;

	/* 菜单树根节点 */
	struct menu root;

	/* 对象池 */
	std::vector<std::unique_ptr<struct symbol>> symbols;
	std::deque<struct expr> exprs;
	std::deque<struct property> props;
	std::vector<std::unique_ptr<struct file>> files;

	/* 已解析的 Kconfig 文件，按包含顺序（最旧在前；depfile 用逆序） */
	std::vector<struct file *> file_order;
	std::unordered_map<std::string, struct file *> file_index;

	/* 特殊符号 */
	struct symbol *modules_sym = nullptr;
	struct symbol *sym_defconfig_list = nullptr;
	tristate modules_val = no;

	/* 配置读写状态 */
	const char *conf_filename = nullptr;
	int conf_lineno = 0;
	int conf_warnings = 0;
	bool changed_input_warning_suppressed = false;
	int sym_change_count = 0;
	void (*changed_callback)(void) = nullptr;

	/* conf_write_dep 的路径前缀 */
	std::string depfile_path;
	size_t depfile_prefix_len = 0;

	/* 高层入口（把自由函数收敛到对象接口上；实现见 kconfig.cpp） */
	void parse(const char *kconfig_file);
	int read(const char *name);
	int write(const char *name);
};

extern Kconfig kconf;

/* 所有被解析的 Kconfig 文件，按包含顺序（最旧在前；depfile 用逆序） */
const std::vector<struct file *> &kconfig_files(void);

extern int yydebug;


} /* export namespace kconfig */
