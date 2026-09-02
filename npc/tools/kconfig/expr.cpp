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

constexpr bool DEBUG_EXPR = false;

static struct expr *expr_eliminate_yn(struct expr *e);

struct expr *expr_alloc_symbol(struct symbol *sym)
{
	struct expr *e = static_cast<struct expr*>(xcalloc(1, sizeof(*e)));
	e->type = E_SYMBOL;
	e->left.sym = sym;
	return e;
}

struct expr *expr_alloc_one(enum expr_type type, struct expr *ce)
{
	struct expr *e = static_cast<struct expr*>(xcalloc(1, sizeof(*e)));
	e->type = type;
	e->left.expr = ce;
	return e;
}

struct expr *expr_alloc_two(enum expr_type type, struct expr *e1, struct expr *e2)
{
	struct expr *e = static_cast<struct expr*>(xcalloc(1, sizeof(*e)));
	e->type = type;
	e->left.expr = e1;
	e->right.expr = e2;
	return e;
}

struct expr *expr_alloc_comp(enum expr_type type, struct symbol *s1, struct symbol *s2)
{
	struct expr *e = static_cast<struct expr*>(xcalloc(1, sizeof(*e)));
	e->type = type;
	e->left.sym = s1;
	e->right.sym = s2;
	return e;
}

struct expr *expr_alloc_and(struct expr *e1, struct expr *e2)
{
	if (!e1)
		return e2;
	return e2 ? expr_alloc_two(E_AND, e1, e2) : e1;
}

struct expr *expr_alloc_or(struct expr *e1, struct expr *e2)
{
	if (!e1)
		return e2;
	return e2 ? expr_alloc_two(E_OR, e1, e2) : e1;
}

struct expr *expr_copy(const struct expr *org)
{
	struct expr *e;

	if (!org)
		return nullptr;

	e = static_cast<struct expr*>(xmalloc(sizeof(*org)));
	memcpy(e, org, sizeof(*org));
	switch (org->type) {
	case E_SYMBOL:
		e->left = org->left;
		break;
	case E_NOT:
		e->left.expr = expr_copy(org->left.expr);
		break;
	case E_EQUAL:
	case E_GEQ:
	case E_GTH:
	case E_LEQ:
	case E_LTH:
	case E_UNEQUAL:
		e->left.sym = org->left.sym;
		e->right.sym = org->right.sym;
		break;
	case E_AND:
	case E_OR:
	case E_LIST:
		e->left.expr = expr_copy(org->left.expr);
		e->right.expr = expr_copy(org->right.expr);
		break;
	default:
		fprintf(stderr, "can't copy type %d\n", e->type);
		free(e);
		e = nullptr;
		break;
	}

	return e;
}

void expr_free(struct expr *e)
{
	if (!e)
		return;

	switch (e->type) {
	case E_SYMBOL:
		break;
	case E_NOT:
		expr_free(e->left.expr);
		break;
	case E_EQUAL:
	case E_GEQ:
	case E_GTH:
	case E_LEQ:
	case E_LTH:
	case E_UNEQUAL:
		break;
	case E_OR:
	case E_AND:
		expr_free(e->left.expr);
		expr_free(e->right.expr);
		break;
	default:
		fprintf(stderr, "how to free type %d?\n", e->type);
		break;
	}
	free(e);
}

/*
 * Simplification counter. Each public entry point (expr_eq(),
 * expr_eliminate_eq(), expr_eliminate_dups()) keeps its own counter in a
 * local variable and threads it through the internal helpers as the 'count'
 * parameter. This replaces the former file-scope 'trans_count' variable with
 * its manual save/restore protocol, removing the reentrancy hazard: a nested
 * call (e.g. expr_eq() from within an expr_eliminate_dups() pass) can no
 * longer clobber the outer pass's counter.
 */

/*
 * expr_eliminate_eq() helper.
 *
 * Walks the two expression trees given in 'ep1' and 'ep2'. Any node that does
 * not have type 'type' (E_OR/E_AND) is considered a leaf, and is compared
 * against all other leaves. Two equal leaves are both replaced with either 'y'
 * or 'n' as appropriate for 'type', to be eliminated later. 'count' is bumped
 * for each such simplification.
 */
static void __expr_eliminate_eq(enum expr_type type, struct expr **ep1, struct expr **ep2,
				int &count)
{
	auto &e1 = *ep1;
	auto &e2 = *ep2;
	/* Recurse down to leaves */

	if (e1->type == type) {
		__expr_eliminate_eq(type, &e1->left.expr, &e2, count);
		__expr_eliminate_eq(type, &e1->right.expr, &e2, count);
		return;
	}
	if (e2->type == type) {
		__expr_eliminate_eq(type, &e1, &e2->left.expr, count);
		__expr_eliminate_eq(type, &e1, &e2->right.expr, count);
		return;
	}

	/* e1 and e2 are leaves. Compare them. */

	if (e1->type == E_SYMBOL && e2->type == E_SYMBOL &&
	    e1->left.sym == e2->left.sym &&
	    (e1->left.sym == &symbol_yes || e1->left.sym == &symbol_no))
		return;
	if (!expr_eq(e1, e2))
		return;

	/* e1 and e2 are equal leaves. Prepare them for elimination. */

	count++;
	expr_free(e1); expr_free(e2);
	switch (type) {
	case E_OR:
		e1 = expr_alloc_symbol(&symbol_no);
		e2 = expr_alloc_symbol(&symbol_no);
		break;
	case E_AND:
		e1 = expr_alloc_symbol(&symbol_yes);
		e2 = expr_alloc_symbol(&symbol_yes);
		break;
	default:
		;
	}
}

/*
 * Rewrites the expressions 'ep1' and 'ep2' to remove operands common to both.
 * Example reductions:
 *
 *	ep1: A && B           ->  ep1: y
 *	ep2: A && B && C      ->  ep2: C
 *
 *	ep1: A || B           ->  ep1: n
 *	ep2: A || B || C      ->  ep2: C
 *
 *	ep1: A && (B && FOO)  ->  ep1: FOO
 *	ep2: (BAR && B) && A  ->  ep2: BAR
 *
 *	ep1: A && (B || C)    ->  ep1: y
 *	ep2: (C || B) && A    ->  ep2: y
 *
 * Comparisons are done between all operands at the same "level" of && or ||.
 * For example, in the expression 'e1 && (e2 || e3) && (e4 || e5)', the
 * following operands will be compared:
 *
 *	- 'e1', 'e2 || e3', and 'e4 || e5', against each other
 *	- e2 against e3
 *	- e4 against e5
 *
 * Parentheses are irrelevant within a single level. 'e1 && (e2 && e3)' and
 * '(e1 && e2) && e3' are both a single level.
 *
 * See __expr_eliminate_eq() as well.
 */
void expr_eliminate_eq(struct expr **ep1, struct expr **ep2)
{
	auto &e1 = *ep1;
	auto &e2 = *ep2;
	/* Own counter; discarded (callers cannot observe simplifications). */
	int count = 0;

	if (!e1 || !e2)
		return;
	switch (e1->type) {
	case E_OR:
	case E_AND:
		__expr_eliminate_eq(e1->type, ep1, ep2, count);
	default:
		;
	}
	if (e1->type != e2->type) switch (e2->type) {
	case E_OR:
	case E_AND:
		__expr_eliminate_eq(e2->type, ep1, ep2, count);
	default:
		;
	}
	e1 = expr_eliminate_yn(e1);
	e2 = expr_eliminate_yn(e2);
}

/*
 * Returns true if 'e1' and 'e2' are equal, after minor simplification. Two
 * &&/|| expressions are considered equal if every operand in one expression
 * equals some operand in the other (operands do not need to appear in the same
 * order), recursively.
 */
bool expr_eq(const struct expr *e1, const struct expr *e2)
{
	bool res;

	/*
	 * A nullptr expr is taken to be yes, but there's also a different way to
	 * represent yes. expr_is_yes() checks for either representation.
	 */
	if (!e1 || !e2)
		return expr_is_yes(e1) && expr_is_yes(e2);

	if (e1->type != e2->type)
		return false;
	switch (e1->type) {
	case E_EQUAL:
	case E_GEQ:
	case E_GTH:
	case E_LEQ:
	case E_LTH:
	case E_UNEQUAL:
		return e1->left.sym == e2->left.sym && e1->right.sym == e2->right.sym;
	case E_SYMBOL:
		return e1->left.sym == e2->left.sym;
	case E_NOT:
		return expr_eq(e1->left.expr, e2->left.expr);
	case E_AND:
	case E_OR: {
		struct expr *c1 = expr_copy(e1);
		struct expr *c2 = expr_copy(e2);

		if (!c1 || !c2) {
			/* expr_copy() failed on an unknown node type */
			expr_free(c1);
			expr_free(c2);
			return false;
		}
		/*
		 * expr_eliminate_eq() works on the copies and keeps its own
		 * simplification counter, so no save/restore dance is needed
		 * here anymore.
		 */
		expr_eliminate_eq(&c1, &c2);
		res = (c1->type == E_SYMBOL && c2->type == E_SYMBOL &&
		       c1->left.sym == c2->left.sym);
		expr_free(c1);
		expr_free(c2);
		return res;
	}
	case E_LIST:
	case E_RANGE:
	case E_NONE:
		/* panic */;
	}

	if constexpr (DEBUG_EXPR) {
		expr_fprint(const_cast<struct expr *>(e1), stdout);
		printf(" = ");
		expr_fprint(const_cast<struct expr *>(e2), stdout);
		printf(" ?\n");
	}

	return false;
}

/*
 * Recursively performs the following simplifications in-place (as well as the
 * corresponding simplifications with swapped operands):
 *
 *	expr && n  ->  n
 *	expr && y  ->  expr
 *	expr || n  ->  expr
 *	expr || y  ->  y
 *
 * Returns the optimized expression.
 */
static struct expr *expr_eliminate_yn(struct expr *e)
{
	struct expr *tmp;

	if (e) switch (e->type) {
	case E_AND:
		e->left.expr = expr_eliminate_yn(e->left.expr);
		e->right.expr = expr_eliminate_yn(e->right.expr);
		if (e->left.expr->type == E_SYMBOL) {
			if (e->left.expr->left.sym == &symbol_no) {
				expr_free(e->left.expr);
				expr_free(e->right.expr);
				e->type = E_SYMBOL;
				e->left.sym = &symbol_no;
				e->right.expr = nullptr;
				return e;
			} else if (e->left.expr->left.sym == &symbol_yes) {
				free(e->left.expr);
				tmp = e->right.expr;
				*e = *(e->right.expr);
				free(tmp);
				return e;
			}
		}
		if (e->right.expr->type == E_SYMBOL) {
			if (e->right.expr->left.sym == &symbol_no) {
				expr_free(e->left.expr);
				expr_free(e->right.expr);
				e->type = E_SYMBOL;
				e->left.sym = &symbol_no;
				e->right.expr = nullptr;
				return e;
			} else if (e->right.expr->left.sym == &symbol_yes) {
				free(e->right.expr);
				tmp = e->left.expr;
				*e = *(e->left.expr);
				free(tmp);
				return e;
			}
		}
		break;
	case E_OR:
		e->left.expr = expr_eliminate_yn(e->left.expr);
		e->right.expr = expr_eliminate_yn(e->right.expr);
		if (e->left.expr->type == E_SYMBOL) {
			if (e->left.expr->left.sym == &symbol_no) {
				free(e->left.expr);
				tmp = e->right.expr;
				*e = *(e->right.expr);
				free(tmp);
				return e;
			} else if (e->left.expr->left.sym == &symbol_yes) {
				expr_free(e->left.expr);
				expr_free(e->right.expr);
				e->type = E_SYMBOL;
				e->left.sym = &symbol_yes;
				e->right.expr = nullptr;
				return e;
			}
		}
		if (e->right.expr->type == E_SYMBOL) {
			if (e->right.expr->left.sym == &symbol_no) {
				free(e->right.expr);
				tmp = e->left.expr;
				*e = *(e->left.expr);
				free(tmp);
				return e;
			} else if (e->right.expr->left.sym == &symbol_yes) {
				expr_free(e->left.expr);
				expr_free(e->right.expr);
				e->type = E_SYMBOL;
				e->left.sym = &symbol_yes;
				e->right.expr = nullptr;
				return e;
			}
		}
		break;
	default:
		;
	}
	return e;
}

/*
 * bool FOO!=n => FOO
 */
struct expr *expr_trans_bool(struct expr *e)
{
	if (!e)
		return nullptr;
	switch (e->type) {
	case E_AND:
	case E_OR:
	case E_NOT:
		e->left.expr = expr_trans_bool(e->left.expr);
		e->right.expr = expr_trans_bool(e->right.expr);
		break;
	case E_UNEQUAL:
		// FOO!=n -> FOO
		if (e->left.sym->type == S_TRISTATE) {
			if (e->right.sym == &symbol_no) {
				e->type = E_SYMBOL;
				e->right.sym = nullptr;
			}
		}
		break;
	default:
		;
	}
	return e;
}

/*
 * Shared prologue of expr_join_or()/expr_join_and(): checks that 'e1' and
 * 'e2' are simple operands (symbol, comparison, or negation thereof) of one
 * and the same boolean/tristate symbol.
 *
 * Returns the common symbol when the operands are join candidates, nullptr
 * otherwise. If the operands are equal, '*eq' is set to a copy of e1 (which
 * is then the join result) and nullptr is returned.
 */
static struct symbol *expr_join_sym(struct expr *e1, struct expr *e2, struct expr **eq)
{
	struct expr *tmp;
	struct symbol *sym1, *sym2;

	*eq = nullptr;
	if (expr_eq(e1, e2)) {
		*eq = expr_copy(e1);
		return nullptr;
	}
	if (e1->type != E_EQUAL && e1->type != E_UNEQUAL && e1->type != E_SYMBOL && e1->type != E_NOT)
		return nullptr;
	if (e2->type != E_EQUAL && e2->type != E_UNEQUAL && e2->type != E_SYMBOL && e2->type != E_NOT)
		return nullptr;
	if (e1->type == E_NOT) {
		tmp = e1->left.expr;
		if (tmp->type != E_EQUAL && tmp->type != E_UNEQUAL && tmp->type != E_SYMBOL)
			return nullptr;
		sym1 = tmp->left.sym;
	} else
		sym1 = e1->left.sym;
	if (e2->type == E_NOT) {
		if (e2->left.expr->type != E_SYMBOL)
			return nullptr;
		sym2 = e2->left.expr->left.sym;
	} else
		sym2 = e2->left.sym;
	if (sym1 != sym2)
		return nullptr;
	if (sym1->type != S_BOOLEAN && sym1->type != S_TRISTATE)
		return nullptr;
	return sym1;
}

/*
 * e1 || e2 -> ?
 */
static struct expr *expr_join_or(struct expr *e1, struct expr *e2)
{
	struct expr *eq;
	struct symbol *sym1;

	sym1 = expr_join_sym(e1, e2, &eq);
	if (eq)
		return eq;
	if (!sym1)
		return nullptr;
	if (sym1->type == S_TRISTATE) {
		if (e1->type == E_EQUAL && e2->type == E_EQUAL &&
		    ((e1->right.sym == &symbol_yes && e2->right.sym == &symbol_mod) ||
		     (e1->right.sym == &symbol_mod && e2->right.sym == &symbol_yes))) {
			// (a='y') || (a='m') -> (a!='n')
			return expr_alloc_comp(E_UNEQUAL, sym1, &symbol_no);
		}
		if (e1->type == E_EQUAL && e2->type == E_EQUAL &&
		    ((e1->right.sym == &symbol_yes && e2->right.sym == &symbol_no) ||
		     (e1->right.sym == &symbol_no && e2->right.sym == &symbol_yes))) {
			// (a='y') || (a='n') -> (a!='m')
			return expr_alloc_comp(E_UNEQUAL, sym1, &symbol_mod);
		}
		if (e1->type == E_EQUAL && e2->type == E_EQUAL &&
		    ((e1->right.sym == &symbol_mod && e2->right.sym == &symbol_no) ||
		     (e1->right.sym == &symbol_no && e2->right.sym == &symbol_mod))) {
			// (a='m') || (a='n') -> (a!='y')
			return expr_alloc_comp(E_UNEQUAL, sym1, &symbol_yes);
		}
	}
	if (sym1->type == S_BOOLEAN) {
		if ((e1->type == E_NOT && e1->left.expr->type == E_SYMBOL && e2->type == E_SYMBOL) ||
		    (e2->type == E_NOT && e2->left.expr->type == E_SYMBOL && e1->type == E_SYMBOL))
			return expr_alloc_symbol(&symbol_yes);
	}

	if constexpr (DEBUG_EXPR) {
		printf("optimize (");
		expr_fprint(e1, stdout);
		printf(") || (");
		expr_fprint(e2, stdout);
		printf(")?\n");
	}
	return nullptr;
}

static struct expr *expr_join_and(struct expr *e1, struct expr *e2)
{
	struct expr *eq;
	struct symbol *sym1, *sym2;

	sym1 = expr_join_sym(e1, e2, &eq);
	if (eq)
		return eq;
	if (!sym1)
		return nullptr;

	if ((e1->type == E_SYMBOL && e2->type == E_EQUAL && e2->right.sym == &symbol_yes) ||
	    (e2->type == E_SYMBOL && e1->type == E_EQUAL && e1->right.sym == &symbol_yes))
		// (a) && (a='y') -> (a='y')
		return expr_alloc_comp(E_EQUAL, sym1, &symbol_yes);

	if ((e1->type == E_SYMBOL && e2->type == E_UNEQUAL && e2->right.sym == &symbol_no) ||
	    (e2->type == E_SYMBOL && e1->type == E_UNEQUAL && e1->right.sym == &symbol_no))
		// (a) && (a!='n') -> (a)
		return expr_alloc_symbol(sym1);

	if ((e1->type == E_SYMBOL && e2->type == E_UNEQUAL && e2->right.sym == &symbol_mod) ||
	    (e2->type == E_SYMBOL && e1->type == E_UNEQUAL && e1->right.sym == &symbol_mod))
		// (a) && (a!='m') -> (a='y')
		return expr_alloc_comp(E_EQUAL, sym1, &symbol_yes);

	if (sym1->type == S_TRISTATE) {
		if (e1->type == E_EQUAL && e2->type == E_UNEQUAL) {
			// (a='b') && (a!='c') -> 'b'='c' ? 'n' : a='b'
			sym2 = e1->right.sym;
			if ((e2->right.sym->flags & SYMBOL_CONST) && (sym2->flags & SYMBOL_CONST))
				return sym2 != e2->right.sym ? expr_alloc_comp(E_EQUAL, sym1, sym2)
							     : expr_alloc_symbol(&symbol_no);
		}
		if (e1->type == E_UNEQUAL && e2->type == E_EQUAL) {
			// (a='b') && (a!='c') -> 'b'='c' ? 'n' : a='b'
			sym2 = e2->right.sym;
			if ((e1->right.sym->flags & SYMBOL_CONST) && (sym2->flags & SYMBOL_CONST))
				return sym2 != e1->right.sym ? expr_alloc_comp(E_EQUAL, sym1, sym2)
							     : expr_alloc_symbol(&symbol_no);
		}
		if (e1->type == E_UNEQUAL && e2->type == E_UNEQUAL &&
			   ((e1->right.sym == &symbol_yes && e2->right.sym == &symbol_no) ||
			    (e1->right.sym == &symbol_no && e2->right.sym == &symbol_yes)))
			// (a!='y') && (a!='n') -> (a='m')
			return expr_alloc_comp(E_EQUAL, sym1, &symbol_mod);

		if (e1->type == E_UNEQUAL && e2->type == E_UNEQUAL &&
			   ((e1->right.sym == &symbol_yes && e2->right.sym == &symbol_mod) ||
			    (e1->right.sym == &symbol_mod && e2->right.sym == &symbol_yes)))
			// (a!='y') && (a!='m') -> (a='n')
			return expr_alloc_comp(E_EQUAL, sym1, &symbol_no);

		if (e1->type == E_UNEQUAL && e2->type == E_UNEQUAL &&
			   ((e1->right.sym == &symbol_mod && e2->right.sym == &symbol_no) ||
			    (e1->right.sym == &symbol_no && e2->right.sym == &symbol_mod)))
			// (a!='m') && (a!='n') -> (a='m')
			return expr_alloc_comp(E_EQUAL, sym1, &symbol_yes);

		if ((e1->type == E_SYMBOL && e2->type == E_EQUAL && e2->right.sym == &symbol_mod) ||
		    (e2->type == E_SYMBOL && e1->type == E_EQUAL && e1->right.sym == &symbol_mod) ||
		    (e1->type == E_SYMBOL && e2->type == E_UNEQUAL && e2->right.sym == &symbol_yes) ||
		    (e2->type == E_SYMBOL && e1->type == E_UNEQUAL && e1->right.sym == &symbol_yes))
			return nullptr;
	}

	if constexpr (DEBUG_EXPR) {
		printf("optimize (");
		expr_fprint(e1, stdout);
		printf(") && (");
		expr_fprint(e2, stdout);
		printf(")?\n");
	}
	return nullptr;
}

/*
 * expr_eliminate_dups() helper.
 *
 * Walks the two expression trees given in 'ep1' and 'ep2'. Any node that does
 * not have type 'type' (E_OR/E_AND) is considered a leaf, and is compared
 * against all other leaves to look for simplifications. 'count' is bumped for
 * each simplification performed.
 */
static void expr_eliminate_dups1(enum expr_type type, struct expr **ep1, struct expr **ep2,
				 int &count)
{
	auto &e1 = *ep1;
	auto &e2 = *ep2;
	struct expr *tmp;

	/* Recurse down to leaves */

	if (e1->type == type) {
		expr_eliminate_dups1(type, &e1->left.expr, &e2, count);
		expr_eliminate_dups1(type, &e1->right.expr, &e2, count);
		return;
	}
	if (e2->type == type) {
		expr_eliminate_dups1(type, &e1, &e2->left.expr, count);
		expr_eliminate_dups1(type, &e1, &e2->right.expr, count);
		return;
	}

	/* e1 and e2 are leaves. Compare and process them. */

	if (e1 == e2)
		return;

	switch (e1->type) {
	case E_OR: case E_AND:
		expr_eliminate_dups1(e1->type, &e1, &e1, count);
	default:
		;
	}

	switch (type) {
	case E_OR:
		tmp = expr_join_or(e1, e2);
		if (tmp) {
			expr_free(e1); expr_free(e2);
			e1 = expr_alloc_symbol(&symbol_no);
			e2 = tmp;
			count++;
		}
		break;
	case E_AND:
		tmp = expr_join_and(e1, e2);
		if (tmp) {
			expr_free(e1); expr_free(e2);
			e1 = expr_alloc_symbol(&symbol_yes);
			e2 = tmp;
			count++;
		}
		break;
	default:
		;
	}
}

/*
 * Rewrites 'e' in-place to remove ("join") duplicate and other redundant
 * operands.
 *
 * Example simplifications:
 *
 *	A || B || A    ->  A || B
 *	A && B && A=y  ->  A=y && B
 *
 * Returns the deduplicated expression.
 */
struct expr *expr_eliminate_dups(struct expr *e)
{
	/* Simplifications performed in the current pass (local: no save/restore). */
	int count;
	if (!e)
		return e;

	while (1) {
		count = 0;
		switch (e->type) {
		case E_OR: case E_AND:
			expr_eliminate_dups1(e->type, &e, &e, count);
		default:
			;
		}
		if (!count)
			/* No simplifications done in this pass. We're done */
			break;
		e = expr_eliminate_yn(e);
	}
	return e;
}

/*
 * Performs various simplifications involving logical operators and
 * comparisons.
 *
 * Allocates and returns a new expression.
 */
struct expr *expr_transform(struct expr *e)
{
	struct expr *tmp;

	if (!e)
		return nullptr;
	switch (e->type) {
	case E_EQUAL:
	case E_GEQ:
	case E_GTH:
	case E_LEQ:
	case E_LTH:
	case E_UNEQUAL:
	case E_SYMBOL:
	case E_LIST:
		break;
	default:
		e->left.expr = expr_transform(e->left.expr);
		e->right.expr = expr_transform(e->right.expr);
	}

	switch (e->type) {
	case E_EQUAL:
		if (e->left.sym->type != S_BOOLEAN)
			break;
		if (e->right.sym == &symbol_no) {
			e->type = E_NOT;
			e->left.expr = expr_alloc_symbol(e->left.sym);
			e->right.sym = nullptr;
			break;
		}
		if (e->right.sym == &symbol_mod) {
			fprintf(stderr, "boolean symbol %s tested for 'm'? test forced to 'n'\n", e->left.sym->name);
			e->type = E_SYMBOL;
			e->left.sym = &symbol_no;
			e->right.sym = nullptr;
			break;
		}
		if (e->right.sym == &symbol_yes) {
			e->type = E_SYMBOL;
			e->right.sym = nullptr;
			break;
		}
		break;
	case E_UNEQUAL:
		if (e->left.sym->type != S_BOOLEAN)
			break;
		if (e->right.sym == &symbol_no) {
			e->type = E_SYMBOL;
			e->right.sym = nullptr;
			break;
		}
		if (e->right.sym == &symbol_mod) {
			fprintf(stderr, "boolean symbol %s tested for 'm'? test forced to 'y'\n", e->left.sym->name);
			e->type = E_SYMBOL;
			e->left.sym = &symbol_yes;
			e->right.sym = nullptr;
			break;
		}
		if (e->right.sym == &symbol_yes) {
			e->type = E_NOT;
			e->left.expr = expr_alloc_symbol(e->left.sym);
			e->right.sym = nullptr;
			break;
		}
		break;
	case E_NOT:
		switch (e->left.expr->type) {
		case E_NOT:
			// !!a -> a
			tmp = e->left.expr->left.expr;
			free(e->left.expr);
			free(e);
			e = tmp;
			e = expr_transform(e);
			break;
		case E_EQUAL:
		case E_UNEQUAL:
			// !a='x' -> a!='x'
			tmp = e->left.expr;
			free(e);
			e = tmp;
			e->type = e->type == E_EQUAL ? E_UNEQUAL : E_EQUAL;
			break;
		case E_LEQ:
		case E_GEQ:
			// !a<='x' -> a>'x'
			tmp = e->left.expr;
			free(e);
			e = tmp;
			e->type = e->type == E_LEQ ? E_GTH : E_LTH;
			break;
		case E_LTH:
		case E_GTH:
			// !a<'x' -> a>='x'
			tmp = e->left.expr;
			free(e);
			e = tmp;
			e->type = e->type == E_LTH ? E_GEQ : E_LEQ;
			break;
		case E_OR:
			// !(a || b) -> !a && !b
			tmp = e->left.expr;
			e->type = E_AND;
			e->right.expr = expr_alloc_one(E_NOT, tmp->right.expr);
			tmp->type = E_NOT;
			tmp->right.expr = nullptr;
			e = expr_transform(e);
			break;
		case E_AND:
			// !(a && b) -> !a || !b
			tmp = e->left.expr;
			e->type = E_OR;
			e->right.expr = expr_alloc_one(E_NOT, tmp->right.expr);
			tmp->type = E_NOT;
			tmp->right.expr = nullptr;
			e = expr_transform(e);
			break;
		case E_SYMBOL:
			if (e->left.expr->left.sym == &symbol_yes) {
				// !'y' -> 'n'
				tmp = e->left.expr;
				free(e);
				e = tmp;
				e->type = E_SYMBOL;
				e->left.sym = &symbol_no;
				break;
			}
			if (e->left.expr->left.sym == &symbol_mod) {
				// !'m' -> 'm'
				tmp = e->left.expr;
				free(e);
				e = tmp;
				e->type = E_SYMBOL;
				e->left.sym = &symbol_mod;
				break;
			}
			if (e->left.expr->left.sym == &symbol_no) {
				// !'n' -> 'y'
				tmp = e->left.expr;
				free(e);
				e = tmp;
				e->type = E_SYMBOL;
				e->left.sym = &symbol_yes;
				break;
			}
			break;
		default:
			;
		}
		break;
	default:
		;
	}
	return e;
}

bool expr_contains_symbol(const struct expr *dep, struct symbol *sym)
{
	if (!dep)
		return false;

	switch (dep->type) {
	case E_AND:
	case E_OR:
		return expr_contains_symbol(dep->left.expr, sym) ||
		       expr_contains_symbol(dep->right.expr, sym);
	case E_SYMBOL:
		return dep->left.sym == sym;
	case E_EQUAL:
	case E_GEQ:
	case E_GTH:
	case E_LEQ:
	case E_LTH:
	case E_UNEQUAL:
		return dep->left.sym == sym ||
		       dep->right.sym == sym;
	case E_NOT:
		return expr_contains_symbol(dep->left.expr, sym);
	default:
		;
	}
	return false;
}

bool expr_depends_symbol(const struct expr *dep, struct symbol *sym)
{
	if (!dep)
		return false;

	switch (dep->type) {
	case E_AND:
		return expr_depends_symbol(dep->left.expr, sym) ||
		       expr_depends_symbol(dep->right.expr, sym);
	case E_SYMBOL:
		return dep->left.sym == sym;
	case E_EQUAL:
		if (dep->left.sym == sym) {
			if (dep->right.sym == &symbol_yes || dep->right.sym == &symbol_mod)
				return true;
		}
		break;
	case E_UNEQUAL:
		if (dep->left.sym == sym) {
			if (dep->right.sym == &symbol_no)
				return true;
		}
		break;
	default:
		;
	}
 	return false;
}

/*
 * Inserts explicit comparisons of type 'type' to symbol 'sym' into the
 * expression 'e'.
 *
 * Examples transformations for type == E_UNEQUAL, sym == &symbol_no:
 *
 *	A              ->  A!=n
 *	!A             ->  A=n
 *	A && B         ->  !(A=n || B=n)
 *	A || B         ->  !(A=n && B=n)
 *	A && (B || C)  ->  !(A=n || (B=n && C=n))
 *
 * Allocates and returns a new expression.
 */
struct expr *expr_trans_compare(struct expr *e, enum expr_type type, struct symbol *sym)
{
	struct expr *e1, *e2;

	if (!e) {
		e = expr_alloc_symbol(sym);
		if (type == E_UNEQUAL)
			e = expr_alloc_one(E_NOT, e);
		return e;
	}
	switch (e->type) {
	case E_AND:
	case E_OR: {
		struct expr *tmp;

		/*
		 * 'e' itself is borrowed (callers pass expressions they still
		 * own, e.g. prop->visible.expr), so it must not be freed here;
		 * only the freshly allocated sub-expressions are ours.
		 */
		e1 = expr_trans_compare(e->left.expr, E_EQUAL, sym);
		e2 = expr_trans_compare(e->right.expr, E_EQUAL, sym);
		if (e->type == E_AND) {
			if (sym == &symbol_yes)
				tmp = expr_alloc_two(E_AND, e1, e2);
			else if (sym == &symbol_no)
				tmp = expr_alloc_two(E_OR, e1, e2);
			else
				goto unexpected_sym;
		} else {
			if (sym == &symbol_yes)
				tmp = expr_alloc_two(E_OR, e1, e2);
			else if (sym == &symbol_no)
				tmp = expr_alloc_two(E_AND, e1, e2);
			else
				goto unexpected_sym;
		}
		if (type == E_UNEQUAL)
			tmp = expr_alloc_one(E_NOT, tmp);
		return tmp;

unexpected_sym:
		/* 'sym' is not a tristate constant: drop the unused copies */
		expr_free(e1);
		expr_free(e2);
		tmp = expr_copy(e);
		if (type == E_UNEQUAL)
			tmp = expr_alloc_one(E_NOT, tmp);
		return tmp;
	}
	case E_NOT:
		return expr_trans_compare(e->left.expr, type == E_EQUAL ? E_UNEQUAL : E_EQUAL, sym);
	case E_UNEQUAL:
	case E_LTH:
	case E_LEQ:
	case E_GTH:
	case E_GEQ:
	case E_EQUAL:
		if (type == E_EQUAL) {
			if (sym == &symbol_yes)
				return expr_copy(e);
			if (sym == &symbol_mod)
				return expr_alloc_symbol(&symbol_no);
			if (sym == &symbol_no)
				return expr_alloc_one(E_NOT, expr_copy(e));
		} else {
			if (sym == &symbol_yes)
				return expr_alloc_one(E_NOT, expr_copy(e));
			if (sym == &symbol_mod)
				return expr_alloc_symbol(&symbol_yes);
			if (sym == &symbol_no)
				return expr_copy(e);
		}
		break;
	case E_SYMBOL:
		return expr_alloc_comp(type, e->left.sym, sym);
	case E_LIST:
	case E_RANGE:
	case E_NONE:
		/* panic */;
	}
	return nullptr;
}

enum string_value_kind {
	k_string,
	k_signed,
	k_unsigned,
};

union string_value {
	unsigned long long u;
	signed long long s;
};

static enum string_value_kind expr_parse_string(const char *str,
						enum symbol_type type,
						union string_value *val)
{
	char *tail;
	enum string_value_kind kind;

	errno = 0;
	switch (type) {
	case S_BOOLEAN:
	case S_TRISTATE:
		val->s = !strcmp(str, "n") ? 0 :
			 !strcmp(str, "m") ? 1 :
			 !strcmp(str, "y") ? 2 : -1;
		return k_signed;
	case S_INT:
		val->s = strtoll(str, &tail, 10);
		kind = k_signed;
		break;
	case S_HEX:
		val->u = strtoull(str, &tail, 16);
		kind = k_unsigned;
		break;
	default:
		val->s = strtoll(str, &tail, 0);
		kind = k_signed;
		break;
	}
	/*
	 * Accept the numeric value only if the whole string was consumed and
	 * ends in a digit. isxdigit() is used for all symbol types on purpose:
	 * decimal digits (S_INT, base 10) are a subset of hex digits (S_HEX,
	 * base 16), so a single check covers every parsed base.
	 */
	return !errno && !*tail && tail > str && isxdigit(tail[-1])
	       ? kind : k_string;
}

tristate expr_calc_value(struct expr *e)
{
	tristate val1, val2;
	const char *str1, *str2;
	enum string_value_kind k1 = k_string, k2 = k_string;
	union string_value lval = {}, rval = {};
	int res;

	if (!e)
		return yes;

	switch (e->type) {
	case E_SYMBOL:
		sym_calc_value(e->left.sym);
		return e->left.sym->curr.tri;
	case E_AND:
		val1 = expr_calc_value(e->left.expr);
		val2 = expr_calc_value(e->right.expr);
		return EXPR_AND(val1, val2);
	case E_OR:
		val1 = expr_calc_value(e->left.expr);
		val2 = expr_calc_value(e->right.expr);
		return EXPR_OR(val1, val2);
	case E_NOT:
		val1 = expr_calc_value(e->left.expr);
		return EXPR_NOT(val1);
	case E_EQUAL:
	case E_GEQ:
	case E_GTH:
	case E_LEQ:
	case E_LTH:
	case E_UNEQUAL:
		break;
	default:
		fprintf(stderr, "expr_calc_value: %d?\n", e->type);
		return no;
	}

	sym_calc_value(e->left.sym);
	sym_calc_value(e->right.sym);
	str1 = sym_get_string_value(e->left.sym);
	str2 = sym_get_string_value(e->right.sym);

	if (e->left.sym->type != S_STRING || e->right.sym->type != S_STRING) {
		k1 = expr_parse_string(str1, e->left.sym->type, &lval);
		k2 = expr_parse_string(str2, e->right.sym->type, &rval);
	}

	if (k1 == k_string || k2 == k_string)
		res = strcmp(str1, str2);
	else if (k1 == k_unsigned || k2 == k_unsigned)
		res = (lval.u > rval.u) - (lval.u < rval.u);
	else /* if (k1 == k_signed && k2 == k_signed) */
		res = (lval.s > rval.s) - (lval.s < rval.s);

	switch(e->type) {
	case E_EQUAL:
		return res ? no : yes;
	case E_GEQ:
		return res >= 0 ? yes : no;
	case E_GTH:
		return res > 0 ? yes : no;
	case E_LEQ:
		return res <= 0 ? yes : no;
	case E_LTH:
		return res < 0 ? yes : no;
	case E_UNEQUAL:
		return res ? yes : no;
	default:
		fprintf(stderr, "expr_calc_value: relation %d?\n", e->type);
		return no;
	}
}

static int expr_compare_type(enum expr_type t1, enum expr_type t2)
{
	if (t1 == t2)
		return 0;
	switch (t1) {
	case E_LEQ:
	case E_LTH:
	case E_GEQ:
	case E_GTH:
		if (t2 == E_EQUAL || t2 == E_UNEQUAL)
			return 1;
	case E_EQUAL:
	case E_UNEQUAL:
		if (t2 == E_NOT)
			return 1;
	case E_NOT:
		if (t2 == E_AND)
			return 1;
	case E_AND:
		if (t2 == E_OR)
			return 1;
	case E_OR:
		if (t2 == E_LIST)
			return 1;
	case E_LIST:
		if (t2 == E_NONE)
			return 1;
	default:
		return -1;
	}
	fprintf(stderr, "[%dgt%d?]", t1, t2);
	return 0;
}

void expr_print(struct expr *e,
		void (*fn)(void *, struct symbol *, const char *),
		void *data, int prevtoken)
{
	if (!e) {
		fn(data, nullptr, "y");
		return;
	}

	if (expr_compare_type(static_cast<expr_type>(prevtoken), e->type) > 0)
		fn(data, nullptr, "(");
	switch (e->type) {
	case E_SYMBOL:
		if (e->left.sym->name)
			fn(data, e->left.sym, e->left.sym->name);
		else
			fn(data, nullptr, "<choice>");
		break;
	case E_NOT:
		fn(data, nullptr, "!");
		expr_print(e->left.expr, fn, data, E_NOT);
		break;
	case E_EQUAL:
	case E_UNEQUAL:
	case E_LEQ:
	case E_LTH:
	case E_GEQ:
	case E_GTH: {
		const char *op;

		switch (e->type) {
		case E_EQUAL:   op = "=";  break;
		case E_UNEQUAL: op = "!="; break;
		case E_LEQ:     op = "<="; break;
		case E_LTH:     op = "<";  break;
		case E_GEQ:     op = ">="; break;
		default:        op = ">";  break;
		}
		if (e->left.sym->name)
			fn(data, e->left.sym, e->left.sym->name);
		else
			fn(data, nullptr, "<choice>");
		fn(data, nullptr, op);
		fn(data, e->right.sym, e->right.sym->name);
		break;
	}
	case E_OR:
		expr_print(e->left.expr, fn, data, E_OR);
		fn(data, nullptr, " || ");
		expr_print(e->right.expr, fn, data, E_OR);
		break;
	case E_AND:
		expr_print(e->left.expr, fn, data, E_AND);
		fn(data, nullptr, " && ");
		expr_print(e->right.expr, fn, data, E_AND);
		break;
	case E_LIST:
		fn(data, e->right.sym, e->right.sym->name);
		if (e->left.expr) {
			fn(data, nullptr, " ^ ");
			expr_print(e->left.expr, fn, data, E_LIST);
		}
		break;
	case E_RANGE:
		fn(data, nullptr, "[");
		fn(data, e->left.sym, e->left.sym->name);
		fn(data, nullptr, " ");
		fn(data, e->right.sym, e->right.sym->name);
		fn(data, nullptr, "]");
		break;
	default:
	  {
		char buf[32];
		snprintf(buf, sizeof(buf), "<unknown type %d>", e->type);
		fn(data, nullptr, buf);
		break;
	  }
	}
	if (expr_compare_type(static_cast<expr_type>(prevtoken), e->type) > 0)
		fn(data, nullptr, ")");
}

static void expr_print_file_helper(void *data, struct symbol *sym, const char *str)
{
	xfwrite(str, strlen(str), 1, static_cast<FILE*>(data));
}

void expr_fprint(struct expr *e, FILE *out)
{
	expr_print(e, expr_print_file_helper, out, E_NONE);
}

static void expr_print_gstr_helper(void *data, struct symbol *sym, const char *str)
{
	struct gstr *gs = (struct gstr*)data;
	const char *sym_str = nullptr;

	if (sym)
		sym_str = sym_get_string_value(sym);

	if (gs->max_width) {
		unsigned extra_length = strlen(str);
		const char *last_cr = strrchr(gs->s, '\n');
		unsigned last_line_length;

		if (sym_str)
			extra_length += 4 + strlen(sym_str);

		if (!last_cr)
			last_cr = gs->s;

		last_line_length = strlen(gs->s) - (last_cr - gs->s);

		if ((last_line_length + extra_length) > gs->max_width)
			str_append(gs, "\\\n");
	}

	str_append(gs, str);
	if (sym && sym->type != S_UNKNOWN)
		str_printf(gs, " [=%s]", sym_str);
}

void expr_gstr_print(struct expr *e, struct gstr *gs)
{
	expr_print(e, expr_print_gstr_helper, gs, E_NONE);
}

/*
 * Transform the top level "||" tokens into newlines and prepend each
 * line with a minus. This makes expressions much easier to read.
 * Suitable for reverse dependency expressions.
 */
static void expr_print_revdep(struct expr *e,
			      void (*fn)(void *, struct symbol *, const char *),
			      void *data, tristate pr_type, const char **title)
{
	if (!e)
		return;

	if (e->type == E_OR) {
		expr_print_revdep(e->left.expr, fn, data, pr_type, title);
		expr_print_revdep(e->right.expr, fn, data, pr_type, title);
	} else if (e->type == E_SYMBOL &&
		   (e->left.sym == &symbol_yes || e->left.sym == &symbol_mod ||
		    e->left.sym == &symbol_no)) {
		/*
		 * Skip constant-symbol operands: menu_finalize() ORs a bare
		 * 'm' into the rev_dep of non-optional choices, which would
		 * otherwise show up as a stray "- m" line in the help text.
		 */
	} else if (expr_calc_value(e) == pr_type) {
		if (*title) {
			fn(data, nullptr, *title);
			*title = nullptr;
		}

		fn(data, nullptr, "  - ");
		expr_print(e, fn, data, E_NONE);
		fn(data, nullptr, "\n");
	}
}

void expr_gstr_print_revdep(struct expr *e, struct gstr *gs,
			    tristate pr_type, const char *title)
{
	if (!e)
		return;

	expr_print_revdep(e, expr_print_gstr_helper, gs, pr_type, &title);
}
