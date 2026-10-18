#ifdef __clang__
import std;
#else
#include <bits/stdc++.h>
#endif
using namespace std;

// SPDX-License-Identifier: GPL-2.0
/*
 * Copyright (C) 2002 Roman Zippel <zippel@linux-m68k.org>
 */


#include "lkc.hpp"

namespace kconfig {

static const char nohelp_text[] = "There is no help available for this option.";

struct menu rootmenu;
ParseContext parse_ctx;

/*
 * 属性对象池：属性在符号的 prop 向量中仅以指针引用，池保证地址稳定；
 * 与表达式池一致，属性不再逐个释放。
 */
static std::deque<struct property> property_pool;

__attribute__((format(printf, 2, 3)))
void menu_warn(struct menu *menu, const char *fmt, ...)
{
	va_list ap;
	va_start(ap, fmt);
	fprintf(stderr, "%s:%d:warning: ", menu->file->name, menu->lineno);
	vfprintf(stderr, fmt, ap);
	fprintf(stderr, "\n");
	va_end(ap);
}

__attribute__((format(printf, 2, 3)))
static void prop_warn(struct property *prop, const char *fmt, ...)
{
	va_list ap;
	va_start(ap, fmt);
	fprintf(stderr, "%s:%d:warning: ", prop->file->name, prop->lineno);
	vfprintf(stderr, fmt, ap);
	fprintf(stderr, "\n");
	va_end(ap);
}

void _menu_init(void)
{
	parse_ctx.current_entry = parse_ctx.current_menu = &rootmenu;
}

void menu_add_entry(struct symbol *sym)
{
	auto owned = std::make_unique<struct menu>();
	struct menu *menu = owned.get();

	menu->sym = sym;
	menu->parent = parse_ctx.current_menu;
	menu->file = parse_ctx.current_file;
	menu->lineno = zconf_lineno();

	parse_ctx.current_menu->children.push_back(std::move(owned));
	parse_ctx.current_entry = menu;
	if (sym)
		menu_add_symbol(P_SYMBOL, sym, nullptr);
}

struct menu *menu_add_menu(void)
{
	parse_ctx.current_menu = parse_ctx.current_entry;
	return parse_ctx.current_menu;
}

void menu_end_menu(void)
{
	parse_ctx.current_menu = parse_ctx.current_menu->parent;
}

/*
 * Rewrites 'm' to 'm' && MODULES, so that it evaluates to 'n' when running
 * without modules
 *
 * 无副作用实现（对齐新版 Linux）：重建而非就地修改。
 */
static struct expr *rewrite_m(struct expr *e)
{
	if (!e)
		return e;

	switch (e->type) {
	case E_NOT:
		e = expr_alloc_one(E_NOT, rewrite_m(e->left.expr));
		break;
	case E_OR:
	case E_AND:
		e = expr_alloc_two(e->type,
				   rewrite_m(e->left.expr),
				   rewrite_m(e->right.expr));
		break;
	case E_SYMBOL:
		/* change 'm' into 'm' && MODULES */
		if (e->left.sym == &symbol_mod)
			return expr_alloc_and(e, expr_alloc_symbol(modules_sym));
		break;
	default:
		break;
	}
	return e;
}

void menu_add_dep(struct expr *dep, struct expr *cond)
{
	if (cond) {
		/*
		 * "depends on X if Y" 的语义（对齐新版 Linux）：
		 *	Y != n --> X
		 *	Y == n --> y
		 * 化简为: (X || (Y == n))
		 */
		dep = expr_alloc_or(dep, expr_trans_compare(cond, E_EQUAL, &symbol_no));
	}
	parse_ctx.current_entry->dep = expr_alloc_and(parse_ctx.current_entry->dep, dep);
}

void menu_set_type(enum symbol_type type)
{
	struct symbol *sym = parse_ctx.current_entry->sym;

	if (sym->type == type)
		return;
	if (sym->type == S_UNKNOWN) {
		sym->type = type;
		return;
	}
	menu_warn(parse_ctx.current_entry,
		"ignoring type redefinition of '%s' from '%s' to '%s'",
		sym->name ? sym->name : "<choice>",
		sym_type_name(sym->type), sym_type_name(type));
}

static struct property *menu_add_prop(enum prop_type type, struct expr *expr,
				      struct expr *dep)
{
	property_pool.emplace_back();
	struct property *prop = &property_pool.back();

	prop->type = type;
	prop->file = parse_ctx.current_file;
	prop->lineno = zconf_lineno();
	prop->menu = parse_ctx.current_entry;
	prop->expr = expr;
	prop->visible.expr = dep;

	/* append property to the prop list of symbol */
	if (parse_ctx.current_entry->sym)
		parse_ctx.current_entry->sym->prop.push_back(prop);

	return prop;
}

struct property *menu_add_prompt(enum prop_type type, char *prompt,
				 struct expr *dep)
{
	struct property *prop = menu_add_prop(type, nullptr, dep);

	if (isspace(static_cast<unsigned char>(*prompt))) {
		prop_warn(prop, "leading whitespace ignored");
		while (isspace(static_cast<unsigned char>(*prompt)))
			prompt++;
	}
	if (parse_ctx.current_entry->prompt)
		prop_warn(prop, "prompt redefined");

	/* Apply all upper menus' visibilities to actual prompts. */
	if (type == P_PROMPT) {
		struct menu *menu = parse_ctx.current_entry;

		while ((menu = menu->parent) != nullptr) {
			struct expr *dup_expr;

			if (!menu->visibility)
				continue;
			/*
			 * Do not add a reference to the menu's visibility
			 * expression but use a copy of it. Otherwise the
			 * expression reduction functions will modify
			 * expressions that have multiple references which
			 * can cause unwanted side effects.
			 */
			dup_expr = expr_copy(menu->visibility);

			prop->visible.expr = expr_alloc_and(prop->visible.expr,
							    dup_expr);
		}
	}

	parse_ctx.current_entry->prompt = prop;
	prop->text = prompt;

	return prop;
}

void menu_add_visibility(struct expr *expr)
{
	parse_ctx.current_entry->visibility = expr_alloc_and(parse_ctx.current_entry->visibility,
	    expr);
}

void menu_add_expr(enum prop_type type, struct expr *expr, struct expr *dep)
{
	menu_add_prop(type, expr, dep);
}

void menu_add_symbol(enum prop_type type, struct symbol *sym, struct expr *dep)
{
	menu_add_prop(type, expr_alloc_symbol(sym), dep);
}

void menu_add_option_modules(void)
{
	if (modules_sym)
		zconf_error("symbol '%s' redefines option 'modules' already defined by symbol '%s'",
			    parse_ctx.current_entry->sym->name, modules_sym->name);
	modules_sym = parse_ctx.current_entry->sym;
}

void menu_add_option_defconfig_list(void)
{
	if (!sym_defconfig_list)
		sym_defconfig_list = parse_ctx.current_entry->sym;
	else if (sym_defconfig_list != parse_ctx.current_entry->sym)
		zconf_error("trying to redefine defconfig symbol");
	sym_defconfig_list->flags |= SYMBOL_NO_WRITE;
}

void menu_add_option_allnoconfig_y(void)
{
	parse_ctx.current_entry->sym->flags |= SYMBOL_ALLNOCONFIG_Y;
}

static int menu_validate_number(struct symbol *sym, struct symbol *sym2)
{
	return sym2->type == S_INT || sym2->type == S_HEX ||
	       (sym2->type == S_UNKNOWN && sym_string_valid(sym, sym2->name));
}

static void sym_check_prop(struct symbol *sym)
{
	struct symbol *sym2;
	const char *use;

	for (struct property *prop : sym->prop) {
		switch (prop->type) {
		case P_DEFAULT:
			if ((sym->type == S_STRING || sym->type == S_INT || sym->type == S_HEX) &&
			    prop->expr->type != E_SYMBOL)
				prop_warn(prop,
				    "default for config symbol '%s'"
				    " must be a single symbol", sym->name);
			if (prop->expr->type != E_SYMBOL)
				break;
			sym2 = prop_get_symbol(prop);
			if (sym->type == S_HEX || sym->type == S_INT) {
				if (!menu_validate_number(sym, sym2))
					prop_warn(prop,
					    "'%s': number is invalid",
					    sym->name);
			}
			if (sym_is_choice(sym)) {
				struct property *choice_prop =
					sym_get_choice_prop(sym2);

				if (!choice_prop ||
				    prop_get_symbol(choice_prop) != sym)
					prop_warn(prop,
						  "choice default symbol '%s' is not contained in the choice",
						  sym2->name);
			}
			break;
		case P_SELECT:
		case P_IMPLY:
			use = prop->type == P_SELECT ? "select" : "imply";
			sym2 = prop_get_symbol(prop);
			if (sym->type != S_BOOLEAN && sym->type != S_TRISTATE)
				prop_warn(prop,
				    "config symbol '%s' uses %s, but is "
				    "not bool or tristate", sym->name, use);
			else if (sym2->type != S_UNKNOWN &&
				 sym2->type != S_BOOLEAN &&
				 sym2->type != S_TRISTATE)
				prop_warn(prop,
				    "'%s' has wrong type. '%s' only "
				    "accept arguments of bool and "
				    "tristate type", sym2->name, use);
			break;
		case P_RANGE:
			if (sym->type != S_INT && sym->type != S_HEX)
				prop_warn(prop, "range is only allowed "
						"for int or hex symbols");
			if (!menu_validate_number(sym, prop->expr->left.sym) ||
			    !menu_validate_number(sym, prop->expr->right.sym))
				prop_warn(prop, "range is invalid");
			break;
		default:
			;
		}
	}
}

void menu_finalize(struct menu *parent)
{
	struct menu *menu, *last_menu;
	struct symbol *sym;
	struct property *prop;
	struct expr *parentdep, *basedep, *dep, *dep2, **ep;

	sym = parent->sym;
	if (!parent->children.empty()) {
		/*
		 * This menu node has children. We (recursively) process them
		 * and propagate parent dependencies before moving on.
		 */

		if (sym && sym_is_choice(sym)) {
			if (sym->type == S_UNKNOWN) {
				/* find the first choice value to find out choice type */
				parse_ctx.current_entry = parent;
				for (auto &child_owner : parent->children) {
					menu = child_owner.get();
					if (menu->sym && menu->sym->type != S_UNKNOWN) {
						menu_set_type(menu->sym->type);
						break;
					}
				}
			}
			/* set the type of the remaining choice values */
			for (auto &child_owner : parent->children) {
				menu = child_owner.get();
				parse_ctx.current_entry = menu;
				if (menu->sym && menu->sym->type == S_UNKNOWN)
					menu_set_type(sym->type);
			}

			/*
			 * Use the choice itself as the parent dependency of
			 * the contained items. This turns the mode of the
			 * choice into an upper bound on the visibility of the
			 * choice value symbols.
			 */
			parentdep = expr_alloc_symbol(sym);
		} else {
			/* Menu node for 'menu', 'if' */
			parentdep = parent->dep;
		}

		/* For each child menu node... */
		for (auto &child_owner : parent->children) {
			menu = child_owner.get();
			/*
			 * Propagate parent dependencies to the child menu
			 * node, also rewriting and simplifying expressions
			 */
			basedep = rewrite_m(menu->dep);
			basedep = expr_transform(basedep);
			basedep = expr_alloc_and(expr_copy(parentdep), basedep);
			basedep = expr_eliminate_dups(basedep);
			menu->dep = basedep;

			/*
			 * For each property: 符号节点遍历其属性表（符号的
			 * prompt 也在属性表中）；非符号节点只处理自己的
			 * prompt，用一个单元素表统一循环。
			 */
			std::vector<struct property *> prompt_only;
			if (!menu->sym && menu->prompt)
				prompt_only.push_back(menu->prompt);
			for (struct property *prop :
			     menu->sym ? menu->sym->prop : prompt_only) {
				if (prop->menu != menu)
					/*
					 * Two possibilities:
					 *
					 * 1. The property lacks dependencies
					 *    and so isn't location-specific,
					 *    e.g. an 'option'
					 *
					 * 2. The property belongs to a symbol
					 *    defined in multiple locations and
					 *    is from some other location. It
					 *    will be handled there in that
					 *    case.
					 *
					 * Skip the property.
					 */
					continue;

				/*
				 * Propagate parent dependencies to the
				 * property's condition, rewriting and
				 * simplifying expressions at the same time
				 */
				dep = rewrite_m(prop->visible.expr);
				dep = expr_transform(dep);
				dep = expr_alloc_and(expr_copy(basedep), dep);
				dep = expr_eliminate_dups(dep);
				if (menu->sym && menu->sym->type != S_TRISTATE)
					dep = expr_trans_bool(dep);
				prop->visible.expr = dep;

				/*
				 * Handle selects and implies, which modify the
				 * dependencies of the selected/implied symbol
				 */
				if (prop->type == P_SELECT) {
					struct symbol *es = prop_get_symbol(prop);
					es->rev_dep.expr = expr_alloc_or(es->rev_dep.expr,
							expr_alloc_and(expr_alloc_symbol(menu->sym), expr_copy(dep)));
				} else if (prop->type == P_IMPLY) {
					struct symbol *es = prop_get_symbol(prop);
					es->implied.expr = expr_alloc_or(es->implied.expr,
							expr_alloc_and(expr_alloc_symbol(menu->sym), expr_copy(dep)));
				}
			}
		}

		if (sym && sym_is_choice(sym))
			expr_free(parentdep);

		/*
		 * Recursively process children in the same fashion before
		 * moving on（递归调用可能把后续兄弟节点搬进子菜单，
		 * 因此用下标遍历以容忍删除）
		 */
		for (size_t ci = 0; ci < parent->children.size(); ci++)
			menu_finalize(parent->children[ci].get());
	} else if (sym) {
		/*
		 * Automatic submenu creation. If sym is a symbol and A, B, C,
		 * ... are consecutive items (symbols, menus, ifs, etc.) that
		 * all depend on sym, then the following menu structure is
		 * created:
		 *
		 *	sym
		 *	 +-A
		 *	 +-B
		 *	 +-C
		 *	 ...
		 *
		 * This also works recursively, giving the following structure
		 * if A is a symbol and B depends on A:
		 *
		 *	sym
		 *	 +-A
		 *	 | +-B
		 *	 +-C
		 *	 ...
		 */

		basedep = parent->prompt ? parent->prompt->visible.expr : nullptr;
		basedep = expr_trans_compare(basedep, E_UNEQUAL, &symbol_no);
		basedep = expr_eliminate_dups(expr_transform(basedep));

		/*
		 * Examine consecutive elements after sym（在祖父的
		 * children 中从 parent 之后开始；递归调用会搬走已处理
		 * 的节点，因此用下标遍历）
		 */
		auto &siblings = parent->parent->children;
		size_t parent_idx = 0;
		for (size_t k = 0; k < siblings.size(); k++)
			if (siblings[k].get() == parent) {
				parent_idx = k;
				break;
			}

		last_menu = nullptr;
		size_t last_idx = parent_idx;
		for (size_t k = parent_idx + 1; k < siblings.size(); k++) {
			menu = siblings[k].get();
			dep = menu->prompt ? menu->prompt->visible.expr : menu->dep;
			if (!expr_contains_symbol(dep, sym))
				/* No dependency, quit */
				break;
			if (expr_depends_symbol(dep, sym))
				/* Absolute dependency, put in submenu */
				goto next;

			/*
			 * Also consider it a dependency on sym if our
			 * dependencies contain sym and are a "superset" of
			 * sym's dependencies, e.g. '(sym || Q) && R' when sym
			 * depends on R.
			 *
			 * Note that 'R' might be from an enclosing menu or if,
			 * making this a more common case than it might seem.
			 */
			dep = expr_trans_compare(dep, E_UNEQUAL, &symbol_no);
			dep = expr_eliminate_dups(expr_transform(dep));
			dep2 = expr_copy(basedep);
			expr_eliminate_eq(&dep, &dep2);
			expr_free(dep);
			if (!expr_is_yes(dep2)) {
				/* Not superset, quit */
				expr_free(dep2);
				break;
			}
			/* Superset, put in submenu */
			expr_free(dep2);
		next:
			menu_finalize(menu);
			menu->parent = parent;
			last_menu = menu;
			last_idx = k;
		}
		expr_free(basedep);
		if (last_menu) {
			/* 把 parent 之后的连续兄弟节点搬进 parent 作为子菜单 */
			std::move(siblings.begin() + parent_idx + 1,
				  siblings.begin() + last_idx + 1,
				  std::back_inserter(parent->children));
			siblings.erase(siblings.begin() + parent_idx + 1,
				       siblings.begin() + last_idx + 1);
		}

		sym->dir_dep.expr = expr_alloc_or(sym->dir_dep.expr, parent->dep);
	}
	/* 处理过程中可能向 parent->children 插入（拆平 if 块），
	 * 因此用下标遍历以容忍插入。 */
	for (size_t ci = 0; ci < parent->children.size(); ci++) {
		menu = parent->children[ci].get();
		if (sym && sym_is_choice(sym) &&
		    menu->sym && !sym_is_choice_value(menu->sym)) {
			parse_ctx.current_entry = menu;
			menu->sym->flags |= SYMBOL_CHOICEVAL;
			if (!menu->prompt)
				menu_warn(menu, "choice value must have a prompt");
			for (struct property *prop : menu->sym->prop) {
				if (prop->type == P_DEFAULT)
					prop_warn(prop, "defaults for choice "
						  "values not supported");
				if (prop->menu == menu)
					continue;
				if (prop->type == P_PROMPT &&
				    prop->menu->parent->sym != sym)
					prop_warn(prop, "choice value used outside its choice group");
			}
			/* Non-tristate choice values of tristate choices must
			 * depend on the choice being set to Y. The choice
			 * values' dependencies were propagated to their
			 * properties above, so the change here must be re-
			 * propagated.
			 */
			if (sym->type == S_TRISTATE && menu->sym->type != S_TRISTATE) {
				basedep = expr_alloc_comp(E_EQUAL, sym, &symbol_yes);
				menu->dep = expr_alloc_and(basedep, menu->dep);
				for (struct property *prop : menu->sym->prop) {
					if (prop->menu != menu)
						continue;
					prop->visible.expr = expr_alloc_and(expr_copy(basedep),
									    prop->visible.expr);
				}
			}
			menu_add_symbol(P_CHOICE, sym, nullptr);
			prop = sym_get_choice_prop(sym);
			for (ep = &prop->expr; *ep; ep = &(*ep)->left.expr)
				;
			*ep = expr_alloc_list(nullptr, menu->sym);
		}

		/*
		 * This code serves two purposes:
		 *
		 * (1) Flattening 'if' blocks, which do not specify a submenu
		 *     and only add dependencies.
		 *
		 *     (Automatic submenu creation might still create a submenu
		 *     from an 'if' before this code runs.)
		 *
		 * (2) "Undoing" any automatic submenus created earlier below
		 *     promptless symbols.
		 *
		 * Before:
		 *
		 *	A
		 *	if ... (or promptless symbol)
		 *	 +-B
		 *	 +-C
		 *	D
		 *
		 * After:
		 *
		 *	A
		 *	if ... (or promptless symbol)
		 *	B
		 *	C
		 *	D
		 */
		if (!menu->children.empty() && (!menu->prompt || !menu->prompt->text)) {
			/* 把 menu 的子节点按原顺序插入到它后面（同一个 children 表） */
			for (auto &grandchild : menu->children)
				grandchild->parent = parent;
			auto &siblings = parent->children;
			auto pos = std::find_if(siblings.begin(), siblings.end(),
						[menu](const std::unique_ptr<struct menu> &p) {
							return p.get() == menu;
						});
			siblings.insert(pos + 1,
					std::make_move_iterator(menu->children.begin()),
					std::make_move_iterator(menu->children.end()));
			menu->children.clear();
		}
	}

	if (sym && !(sym->flags & SYMBOL_WARNED)) {
		if (sym->type == S_UNKNOWN)
			menu_warn(parent, "config symbol defined without type");

		if (sym_is_choice(sym) && !parent->prompt)
			menu_warn(parent, "choice must have a prompt");

		/* Check properties connected to this symbol */
		sym_check_prop(sym);
		sym->flags |= SYMBOL_WARNED;
	}

	/*
	 * For non-optional choices, add a reverse dependency (corresponding to
	 * a select) of '<visibility> && m'. This prevents the user from
	 * setting the choice mode to 'n' when the choice is visible.
	 *
	 * This would also work for non-choice symbols, but only non-optional
	 * choices clear SYMBOL_OPTIONAL as of writing. Choices are implemented
	 * as a type of symbol.
	 */
	if (sym && !sym_is_optional(sym) && parent->prompt) {
		sym->rev_dep.expr = expr_alloc_or(sym->rev_dep.expr,
				expr_alloc_and(parent->prompt->visible.expr,
					expr_alloc_symbol(&symbol_mod)));
	}
}

[[nodiscard]] bool menu_has_prompt(struct menu *menu)
{
	return menu->prompt != nullptr;
}

/*
 * Determine if a menu is empty.
 * A menu is considered empty if it contains no or only
 * invisible entries.
 */
[[nodiscard]] bool menu_is_empty(struct menu *menu)
{
	for (auto &child : menu->children) {
		if (menu_is_visible(child.get()))
			return false;
	}
	return true;
}

[[nodiscard]] bool menu_is_visible(struct menu *menu)
{
	struct symbol *sym;
	tristate visible;

	if (!menu->prompt)
		return false;

	if (menu->visibility) {
		if (expr_calc_value(menu->visibility) == no)
			return false;
	}

	sym = menu->sym;
	if (sym) {
		sym_calc_value(sym);
		visible = menu->prompt->visible.tri;
	} else
		visible = menu->prompt->visible.tri = expr_calc_value(menu->prompt->visible.expr);

	if (visible != no)
		return true;

	if (!sym || sym_get_tristate_value(menu->sym) == no)
		return false;

	for (auto &child : menu->children) {
		if (menu_is_visible(child.get())) {
			if (sym)
				sym->flags |= SYMBOL_DEF_USER;
			return true;
		}
	}

	return false;
}

[[nodiscard]] const char *menu_get_prompt(struct menu *menu)
{
	if (menu->prompt)
		return menu->prompt->text;
	else if (menu->sym)
		return menu->sym->name;
	return nullptr;
}

[[nodiscard]] struct menu *menu_get_root_menu([[maybe_unused]] struct menu *menu)
{
	return &rootmenu;
}

[[nodiscard]] struct menu *menu_get_parent_menu(struct menu *menu)
{
	enum prop_type type;

	for (; menu != &rootmenu; menu = menu->parent) {
		/* P_UNKNOWN doubles as the sentinel for "no prompt" here */
		type = menu->prompt ? menu->prompt->type : P_UNKNOWN;
		if (type == P_MENU)
			break;
	}
	return menu;
}

[[nodiscard]] bool menu_has_help(struct menu *menu)
{
	return menu->help != nullptr;
}

[[nodiscard]] const char *menu_get_help(struct menu *menu)
{
	if (menu->help)
		return menu->help;
	else
		return "";
}

static void get_def_str(struct gstr *r, struct menu *menu)
{
	str_printf(r, "Defined at %s:%d\n",
		   menu->file->name, menu->lineno);
}

static void get_dep_str(struct gstr *r, struct expr *expr, const char *prefix)
{
	if (!expr_is_yes(expr)) {
		str_append(r, prefix);
		expr_gstr_print(expr, r);
		str_append(r, "\n");
	}
}

static void get_prompt_str(struct gstr *r, struct property *prop,
			   struct list_head *head)
{
	int i, j;
	struct menu *submenu[8], *menu, *location = nullptr;
	struct jump_key *jump = nullptr;

	str_printf(r, "  Prompt: %s\n", prop->text);

	get_dep_str(r, prop->menu->dep, "  Depends on: ");
	/*
	 * Most prompts in Linux have visibility that exactly matches their
	 * dependencies. For these, we print only the dependencies to improve
	 * readability. However, prompts with inline "if" expressions and
	 * prompts with a parent that has a "visible if" expression have
	 * differing dependencies and visibility. In these rare cases, we
	 * print both.
	 */
	if (!expr_eq(prop->menu->dep, prop->visible.expr))
		get_dep_str(r, prop->visible.expr, "  Visible if: ");

	menu = prop->menu->parent;
	for (i = 0; menu != &rootmenu && i < 8; menu = menu->parent) {
		bool accessible = menu_is_visible(menu);

		submenu[i++] = menu;
		if (location == nullptr && accessible)
			location = menu;
	}
	if (head && location) {
		jump = new struct jump_key();

		if (menu_is_visible(prop->menu)) {
			/*
			 * There is not enough room to put the hint at the
			 * beginning of the "Prompt" line. Put the hint on the
			 * last "Location" line even when it would belong on
			 * the former.
			 */
			jump->target = prop->menu;
		} else
			jump->target = location;

		if (list_empty(head))
			jump->index = 0;
		else
			jump->index = list_entry(head->prev, struct jump_key,
						 entries)->index + 1;

		list_add_tail(&jump->entries, head);
	}

	if (i > 0) {
		str_printf(r, "  Location:\n");
		for (j = 4; --i >= 0; j += 2) {
			menu = submenu[i];
			if (jump && menu == location)
				jump->offset = r->s.size();
			str_printf(r, "%*c-> %s", j, ' ',
				   menu_get_prompt(menu));
			if (menu->sym) {
				str_printf(r, " (%s [=%s])", menu->sym->name ?
					menu->sym->name : "<choice>",
					sym_get_string_value(menu->sym));
			}
			str_append(r, "\n");
		}
	}
}

static void get_symbol_props_str(struct gstr *r, struct symbol *sym,
				 enum prop_type tok, const char *prefix)
{
	bool hit = false;

	for (struct property *prop : sym->prop) {
		if (prop->type != tok)
			continue;
		if (!hit) {
			str_append(r, prefix);
			hit = true;
		} else
			str_printf(r, " && ");
		expr_gstr_print(prop->expr, r);
	}
	if (hit)
		str_append(r, "\n");
}

/*
 * head is optional and may be nullptr
 */
static void get_symbol_str(struct gstr *r, struct symbol *sym,
		    struct list_head *head)
{
	struct property *prop;

	if (sym && sym->name) {
		str_printf(r, "Symbol: %s [=%s]\n", sym->name,
			   sym_get_string_value(sym));
		str_printf(r, "Type  : %s\n", sym_type_name(sym->type));
		if (sym->type == S_INT || sym->type == S_HEX) {
			prop = sym_get_range_prop(sym);
			if (prop) {
				str_printf(r, "Range : ");
				expr_gstr_print(prop->expr, r);
				str_append(r, "\n");
			}
		}
	}

	/* Print the definitions with prompts before the ones without */
	for (struct property *prop : sym->prop) {
		if (prop->type != P_SYMBOL)
			continue;
		if (prop->menu->prompt) {
			get_def_str(r, prop->menu);
			get_prompt_str(r, prop->menu->prompt, head);
		}
	}

	for (struct property *prop : sym->prop) {
		if (prop->type != P_SYMBOL)
			continue;
		if (!prop->menu->prompt) {
			get_def_str(r, prop->menu);
			get_dep_str(r, prop->menu->dep, "  Depends on: ");
		}
	}

	get_symbol_props_str(r, sym, P_SELECT, "Selects: ");
	if (sym->rev_dep.expr) {
		expr_gstr_print_revdep(sym->rev_dep.expr, r, yes, "Selected by [y]:\n");
		expr_gstr_print_revdep(sym->rev_dep.expr, r, mod, "Selected by [m]:\n");
		expr_gstr_print_revdep(sym->rev_dep.expr, r, no, "Selected by [n]:\n");
	}

	get_symbol_props_str(r, sym, P_IMPLY, "Implies: ");
	if (sym->implied.expr) {
		expr_gstr_print_revdep(sym->implied.expr, r, yes, "Implied by [y]:\n");
		expr_gstr_print_revdep(sym->implied.expr, r, mod, "Implied by [m]:\n");
		expr_gstr_print_revdep(sym->implied.expr, r, no, "Implied by [n]:\n");
	}

	str_append(r, "\n\n");
}

struct gstr get_relations_str(const std::vector<struct symbol *> &sym_arr,
			      struct list_head *head)
{
	struct gstr res = str_new();

	for (struct symbol *sym : sym_arr)
		get_symbol_str(&res, sym, head);
	if (sym_arr.empty())
		str_append(&res, "No matches found.\n");
	return res;
}


void menu_get_ext_help(struct menu *menu, struct gstr *help)
{
	struct symbol *sym = menu->sym;
	const char *help_text = nohelp_text;

	if (menu_has_help(menu)) {
		if (sym->name)
			str_printf(help, "%s%s:\n\n", CONFIG_, sym->name);
		help_text = menu_get_help(menu);
	}
	str_printf(help, "%s\n", help_text);
	if (sym)
		get_symbol_str(help, sym, nullptr);
}

} /* namespace kconfig */
