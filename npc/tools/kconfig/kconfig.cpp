// SPDX-License-Identifier: GPL-2.0
// C++23 Module Implementation for Kconfig (Part 1: util, expr, symbol)

module;

#include <cctype>
#include <cerrno>
#include <cstdarg>
#include <cstdlib>
#include <cstring>
#include <climits>
#include <ctime>
#include <string>
#include <vector>
#include <list>
#include <sstream>
#include <sys/stat.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstdio>
#include <regex.h>

extern "C" {
    extern int yylineno;
    void zconf_starthelp(void);
    FILE *zconf_fopen(const char *name);
    void zconf_initscan(const char *name);
    void zconf_nextfile(const char *name);
    int zconf_lineno(void);
    void zconf_errors(const char *s);
    void zconf_initscan(const char *name);
    int yyparse(void);
}


// Workaround for clang module initializer
namespace { struct __kconfig_init { __kconfig_init() {} } __kconfig_init_instance; }

module npc.kconfig;

#define for_all_symbols(i, sym) for (int i = 0; i < SYMBOL_HASHSIZE; i++) for (symbol *sym = symbol_hash[i]; sym; sym = sym->next)
#define for_all_properties(sym, st, tok) for (st = sym->prop; st; st = st->next) if (st->type == (tok))
#define for_all_defaults(sym, st) for_all_properties(sym, st, P_DEFAULT)
#define for_all_prompts(sym, st) for_all_properties(sym, st, P_PROMPT)
#define CONFIG_ "CONFIG_"
;

// ============================================================
// Global definitions
// ============================================================

symbol symbol_yes = {
    nullptr, "y", S_UNKNOWN, {(void*)"y", yes}, {}, no,
    SYMBOL_CONST | SYMBOL_VALID, nullptr, {}, {}, {}
};
symbol symbol_mod = {
    nullptr, "m", S_UNKNOWN, {(void*)"m", mod}, {}, no,
    SYMBOL_CONST | SYMBOL_VALID, nullptr, {}, {}, {}
};
symbol symbol_no = {
    nullptr, "n", S_UNKNOWN, {(void*)"n", no}, {}, no,
    SYMBOL_CONST | SYMBOL_VALID, nullptr, {}, {}, {}
};
symbol symbol_empty = {
    nullptr, "", S_UNKNOWN, {(void*)"", no}, {}, no,
    SYMBOL_VALID, nullptr, {}, {}, {}
};

symbol *modules_sym = nullptr;
symbol *sym_defconfig_list = nullptr;
tristate modules_val = no;
symbol *symbol_hash[SYMBOL_HASHSIZE] = {};
menu rootmenu;
file *file_list = nullptr;
file *current_file = nullptr;
menu *current_entry = nullptr;
menu *current_menu = nullptr;

// ============================================================
// util.c
// ============================================================

file *file_lookup(const char *name) {
    for (file *f = file_list; f; f = f->next)
        if (f->name == name) return f;
    auto *f = new file{};
    f->name = name;
    f->next = file_list;
    file_list = f;
    return f;
}

gstr str_new() {
    gstr gs;
    gs.s = new char[64]();
    gs.len = 64; gs.max_width = 0;
    gs.s[0] = '\0';
    return gs;
}

void str_free(gstr *gs) { if (gs->s) { delete[] gs->s; gs->s = nullptr; } gs->len = 0; }

void str_append(gstr *gs, const char *s) {
    if (s) {
        size_t l = strlen(gs->s) + strlen(s) + 1;
        if (l > gs->len) {
            auto *ns = new char[l];
            strcpy(ns, gs->s); delete[] gs->s;
            gs->s = ns; gs->len = l;
        }
        strcat(gs->s, s);
    }
}

void str_printf(gstr *gs, const char *fmt, ...) {
    va_list ap;
    char buf[10000];
    va_start(ap, fmt); vsnprintf(buf, sizeof(buf), fmt, ap); va_end(ap);
    str_append(gs, buf);
}

const char *str_get(gstr *gs) { return gs->s; }

void *xmalloc(size_t size) {
    void *p = malloc(size); if (p) return p;
    fprintf(stderr, "Out of memory.\n"); exit(1);
}
void *xcalloc(size_t n, size_t s) {
    void *p = calloc(n, s); if (p) return p;
    fprintf(stderr, "Out of memory.\n"); exit(1);
}
void *xrealloc(void *p, size_t s) {
    p = realloc(p, s); if (p) return p;
    fprintf(stderr, "Out of memory.\n"); exit(1);
}
char *xstrdup(const char *s) {
    char *p = strdup(s); if (p) return p;
    fprintf(stderr, "Out of memory.\n"); exit(1);
}
char *xstrndup(const char *s, size_t n) {
    char *p = strndup(s, n); if (p) return p;
    fprintf(stderr, "Out of memory.\n"); exit(1);
}

// ============================================================
// expr.c
// ============================================================

int trans_count;
expr *expr_eliminate_yn(expr *e);
int expr_eq(expr *e1, expr *e2);

expr *expr_alloc_symbol(symbol *sym) {
    auto *e = new expr{}; e->type = E_SYMBOL; e->left.sym = sym; return e;
}
expr *expr_alloc_one(expr_type type, expr *ce) {
    auto *e = new expr{}; e->type = type; e->left.e = ce; return e;
}
expr *expr_alloc_two(expr_type type, expr *e1, expr *e2) {
    auto *e = new expr{}; e->type = type; e->left.e = e1; e->right.e = e2; return e;
}
expr *expr_alloc_comp(expr_type type, symbol *s1, symbol *s2) {
    auto *e = new expr{}; e->type = type; e->left.sym = s1; e->right.sym = s2; return e;
}
expr *expr_alloc_and(expr *e1, expr *e2) {
    if (!e1) return e2; return e2 ? expr_alloc_two(E_AND, e1, e2) : e1;
}
expr *expr_alloc_or(expr *e1, expr *e2) {
    if (!e1) return e2; return e2 ? expr_alloc_two(E_OR, e1, e2) : e1;
}

expr *expr_copy(const expr *org) {
    if (!org) return nullptr;
    auto *e = new expr; memcpy(e, org, sizeof(*org));
    switch (org->type) {
        case E_SYMBOL: break;
        case E_NOT: e->left.e = expr_copy(org->left.e); break;
        case E_EQUAL: case E_GEQ: case E_GTH: case E_LEQ: case E_LTH: case E_UNEQUAL: break;
        case E_AND: case E_OR: case E_LIST:
            e->left.e = expr_copy(org->left.e);
            e->right.e = expr_copy(org->right.e); break;
        default: fprintf(stderr, "can't copy type %d\n", (int)e->type); delete e; return nullptr;
    }
    return e;
}

void expr_free(expr *e) {
    if (!e) return;
    switch (e->type) {
        case E_SYMBOL: break;
        case E_NOT: expr_free(e->left.e); break;
        case E_EQUAL: case E_GEQ: case E_GTH: case E_LEQ: case E_LTH: case E_UNEQUAL: break;
        case E_OR: case E_AND: expr_free(e->left.e); expr_free(e->right.e); break;
        default: fprintf(stderr, "how to free type %d?\n", (int)e->type); break;
    }
    delete e;
}

void __expr_eliminate_eq(expr_type type, expr **ep1, expr **ep2) {
#define e1 (*ep1)
#define e2 (*ep2)
    if (e1->type == type) {
        __expr_eliminate_eq(type, &e1->left.e, &e2);
        __expr_eliminate_eq(type, &e1->right.e, &e2);
        return;
    }
    if (e2->type == type) {
        __expr_eliminate_eq(type, &e1, &e2->left.e);
        __expr_eliminate_eq(type, &e1, &e2->right.e);
        return;
    }
    if (e1->type == E_SYMBOL && e2->type == E_SYMBOL &&
        e1->left.sym == e2->left.sym &&
        (e1->left.sym == &symbol_yes || e1->left.sym == &symbol_no))
        return;
    if (!expr_eq(e1, e2)) return;
    trans_count++;
    expr_free(e1); expr_free(e2);
    switch (type) {
        case E_OR: e1 = expr_alloc_symbol(&symbol_no); e2 = expr_alloc_symbol(&symbol_no); break;
        case E_AND: e1 = expr_alloc_symbol(&symbol_yes); e2 = expr_alloc_symbol(&symbol_yes); break;
        default: ;
    }
#undef e1
#undef e2
}

void expr_eliminate_eq(expr **ep1, expr **ep2) {
#define e1 (*ep1)
#define e2 (*ep2)
    if (!e1 || !e2) return;
    switch (e1->type) { case E_OR: case E_AND: __expr_eliminate_eq(e1->type, ep1, ep2); default: ; }
    if (e1->type != e2->type) switch (e2->type) { case E_OR: case E_AND: __expr_eliminate_eq(e2->type, ep1, ep2); default: ; }
    e1 = expr_eliminate_yn(e1);
    e2 = expr_eliminate_yn(e2);
#undef e1
#undef e2
}

int expr_eq(expr *e1, expr *e2) {
    if (!e1 || !e2) return expr_is_yes(e1) && expr_is_yes(e2);
    if (e1->type != e2->type) return 0;
    int res, old_count;
    switch (e1->type) {
        case E_EQUAL: case E_GEQ: case E_GTH: case E_LEQ: case E_LTH: case E_UNEQUAL:
            return e1->left.sym == e2->left.sym && e1->right.sym == e2->right.sym;
        case E_SYMBOL: return e1->left.sym == e2->left.sym;
        case E_NOT: return expr_eq(e1->left.e, e2->left.e);
        case E_AND: case E_OR: {
            e1 = expr_copy(e1); e2 = expr_copy(e2);
            old_count = trans_count;
            expr_eliminate_eq(&e1, &e2);
            res = (e1->type == E_SYMBOL && e2->type == E_SYMBOL && e1->left.sym == e2->left.sym);
            expr_free(e1); expr_free(e2);
            trans_count = old_count;
            return res;
        }
        case E_LIST: case E_RANGE: case E_NONE: ;
    }
    return 0;
}

expr *expr_eliminate_yn(expr *e) {
    if (!e) return nullptr;
    expr *tmp;
    switch (e->type) {
        case E_AND:
            e->left.e = expr_eliminate_yn(e->left.e);
            e->right.e = expr_eliminate_yn(e->right.e);
            if (e->left.e->type == E_SYMBOL) {
                if (e->left.e->left.sym == &symbol_no) {
                    expr_free(e->left.e); expr_free(e->right.e);
                    e->type = E_SYMBOL; e->left.sym = &symbol_no; e->right.e = nullptr; return e;
                } else if (e->left.e->left.sym == &symbol_yes) {
                    delete e->left.e; tmp = e->right.e;
                    *e = *(e->right.e); delete tmp; return e;
                }
            }
            if (e->right.e->type == E_SYMBOL) {
                if (e->right.e->left.sym == &symbol_no) {
                    expr_free(e->left.e); expr_free(e->right.e);
                    e->type = E_SYMBOL; e->left.sym = &symbol_no; e->right.e = nullptr; return e;
                } else if (e->right.e->left.sym == &symbol_yes) {
                    delete e->right.e; tmp = e->left.e;
                    *e = *(e->left.e); delete tmp; return e;
                }
            }
            break;
        case E_OR:
            e->left.e = expr_eliminate_yn(e->left.e);
            e->right.e = expr_eliminate_yn(e->right.e);
            if (e->left.e->type == E_SYMBOL) {
                if (e->left.e->left.sym == &symbol_no) {
                    delete e->left.e; tmp = e->right.e;
                    *e = *(e->right.e); delete tmp; return e;
                } else if (e->left.e->left.sym == &symbol_yes) {
                    expr_free(e->left.e); expr_free(e->right.e);
                    e->type = E_SYMBOL; e->left.sym = &symbol_yes; e->right.e = nullptr; return e;
                }
            }
            if (e->right.e->type == E_SYMBOL) {
                if (e->right.e->left.sym == &symbol_no) {
                    delete e->right.e; tmp = e->left.e;
                    *e = *(e->left.e); delete tmp; return e;
                } else if (e->right.e->left.sym == &symbol_yes) {
                    expr_free(e->left.e); expr_free(e->right.e);
                    e->type = E_SYMBOL; e->left.sym = &symbol_yes; e->right.e = nullptr; return e;
                }
            }
            break;
        default: ;
    }
    return e;
}

expr *expr_trans_bool(expr *e) {
    if (!e) return nullptr;
    switch (e->type) {
        case E_AND: case E_OR: case E_NOT:
            e->left.e = expr_trans_bool(e->left.e);
            e->right.e = expr_trans_bool(e->right.e); break;
        case E_UNEQUAL:
            if (e->left.sym->type == S_TRISTATE && e->right.sym == &symbol_no) {
                e->type = E_SYMBOL; e->right.sym = nullptr;
            } break;
        default: ;
    }
    return e;
}

expr *expr_join_or(expr *e1, expr *e2) {
    if (expr_eq(e1, e2)) return expr_copy(e1);
    if (e1->type != E_EQUAL && e1->type != E_UNEQUAL && e1->type != E_SYMBOL && e1->type != E_NOT) return nullptr;
    if (e2->type != E_EQUAL && e2->type != E_UNEQUAL && e2->type != E_SYMBOL && e2->type != E_NOT) return nullptr;
    symbol *sym1, *sym2;
    if (e1->type == E_NOT) {
        if (e1->left.e->type != E_EQUAL && e1->left.e->type != E_UNEQUAL && e1->left.e->type != E_SYMBOL) return nullptr;
        sym1 = e1->left.e->left.sym;
    } else sym1 = e1->left.sym;
    if (e2->type == E_NOT) {
        if (e2->left.e->type != E_SYMBOL) return nullptr;
        sym2 = e2->left.e->left.sym;
    } else sym2 = e2->left.sym;
    if (sym1 != sym2) return nullptr;
    if (sym1->type != S_BOOLEAN && sym1->type != S_TRISTATE) return nullptr;
    if (sym1->type == S_TRISTATE) {
        if (e1->type == E_EQUAL && e2->type == E_EQUAL &&
            ((e1->right.sym == &symbol_yes && e2->right.sym == &symbol_mod) ||
             (e1->right.sym == &symbol_mod && e2->right.sym == &symbol_yes)))
            return expr_alloc_comp(E_UNEQUAL, sym1, &symbol_no);
        if (e1->type == E_EQUAL && e2->type == E_EQUAL &&
            ((e1->right.sym == &symbol_yes && e2->right.sym == &symbol_no) ||
             (e1->right.sym == &symbol_no && e2->right.sym == &symbol_yes)))
            return expr_alloc_comp(E_UNEQUAL, sym1, &symbol_mod);
        if (e1->type == E_EQUAL && e2->type == E_EQUAL &&
            ((e1->right.sym == &symbol_mod && e2->right.sym == &symbol_no) ||
             (e1->right.sym == &symbol_no && e2->right.sym == &symbol_mod)))
            return expr_alloc_comp(E_UNEQUAL, sym1, &symbol_yes);
    }
    if (sym1->type == S_BOOLEAN && sym1 == sym2) {
        if ((e1->type == E_NOT && e1->left.e->type == E_SYMBOL && e2->type == E_SYMBOL) ||
            (e2->type == E_NOT && e2->left.e->type == E_SYMBOL && e1->type == E_SYMBOL))
            return expr_alloc_symbol(&symbol_yes);
    }
    return nullptr;
}

expr *expr_join_and(expr *e1, expr *e2) {
    if (expr_eq(e1, e2)) return expr_copy(e1);
    if (e1->type != E_EQUAL && e1->type != E_UNEQUAL && e1->type != E_SYMBOL && e1->type != E_NOT) return nullptr;
    if (e2->type != E_EQUAL && e2->type != E_UNEQUAL && e2->type != E_SYMBOL && e2->type != E_NOT) return nullptr;
    symbol *sym1, *sym2;
    if (e1->type == E_NOT) {
        if (e1->left.e->type != E_EQUAL && e1->left.e->type != E_UNEQUAL && e1->left.e->type != E_SYMBOL) return nullptr;
        sym1 = e1->left.e->left.sym;
    } else sym1 = e1->left.sym;
    if (e2->type == E_NOT) {
        if (e2->left.e->type != E_SYMBOL) return nullptr;
        sym2 = e2->left.e->left.sym;
    } else sym2 = e2->left.sym;
    if (sym1 != sym2) return nullptr;
    if (sym1->type != S_BOOLEAN && sym1->type != S_TRISTATE) return nullptr;
    if ((e1->type == E_SYMBOL && e2->type == E_EQUAL && e2->right.sym == &symbol_yes) ||
        (e2->type == E_SYMBOL && e1->type == E_EQUAL && e1->right.sym == &symbol_yes))
        return expr_alloc_comp(E_EQUAL, sym1, &symbol_yes);
    if ((e1->type == E_SYMBOL && e2->type == E_UNEQUAL && e2->right.sym == &symbol_no) ||
        (e2->type == E_SYMBOL && e1->type == E_UNEQUAL && e1->right.sym == &symbol_no))
        return expr_alloc_symbol(sym1);
    if ((e1->type == E_SYMBOL && e2->type == E_UNEQUAL && e2->right.sym == &symbol_mod) ||
        (e2->type == E_SYMBOL && e1->type == E_UNEQUAL && e1->right.sym == &symbol_mod))
        return expr_alloc_comp(E_EQUAL, sym1, &symbol_yes);
    if (sym1->type == S_TRISTATE) {
        if (e1->type == E_EQUAL && e2->type == E_UNEQUAL) {
            sym2 = e1->right.sym;
            if ((e2->right.sym->flags & SYMBOL_CONST) && (sym2->flags & SYMBOL_CONST))
                return sym2 != e2->right.sym ? expr_alloc_comp(E_EQUAL, sym1, sym2) : expr_alloc_symbol(&symbol_no);
        }
        if (e1->type == E_UNEQUAL && e2->type == E_EQUAL) {
            sym2 = e2->right.sym;
            if ((e1->right.sym->flags & SYMBOL_CONST) && (sym2->flags & SYMBOL_CONST))
                return sym2 != e1->right.sym ? expr_alloc_comp(E_EQUAL, sym1, sym2) : expr_alloc_symbol(&symbol_no);
        }
        if (e1->type == E_UNEQUAL && e2->type == E_UNEQUAL &&
            ((e1->right.sym == &symbol_yes && e2->right.sym == &symbol_no) ||
             (e1->right.sym == &symbol_no && e2->right.sym == &symbol_yes)))
            return expr_alloc_comp(E_EQUAL, sym1, &symbol_mod);
        if (e1->type == E_UNEQUAL && e2->type == E_UNEQUAL &&
            ((e1->right.sym == &symbol_yes && e2->right.sym == &symbol_mod) ||
             (e1->right.sym == &symbol_mod && e2->right.sym == &symbol_yes)))
            return expr_alloc_comp(E_EQUAL, sym1, &symbol_no);
        if (e1->type == E_UNEQUAL && e2->type == E_UNEQUAL &&
            ((e1->right.sym == &symbol_mod && e2->right.sym == &symbol_no) ||
             (e1->right.sym == &symbol_no && e2->right.sym == &symbol_mod)))
            return expr_alloc_comp(E_EQUAL, sym1, &symbol_yes);
        if ((e1->type == E_SYMBOL && e2->type == E_EQUAL && e2->right.sym == &symbol_mod) ||
            (e2->type == E_SYMBOL && e1->type == E_EQUAL && e1->right.sym == &symbol_mod) ||
            (e1->type == E_SYMBOL && e2->type == E_UNEQUAL && e2->right.sym == &symbol_yes) ||
            (e2->type == E_SYMBOL && e1->type == E_UNEQUAL && e1->right.sym == &symbol_yes))
            return nullptr;
    }
    return nullptr;
}

void expr_eliminate_dups1(expr_type type, expr **ep1, expr **ep2) {
#define e1 (*ep1)
#define e2 (*ep2)
    if (e1->type == type) { expr_eliminate_dups1(type, &e1->left.e, &e2); expr_eliminate_dups1(type, &e1->right.e, &e2); return; }
    if (e2->type == type) { expr_eliminate_dups1(type, &e1, &e2->left.e); expr_eliminate_dups1(type, &e1, &e2->right.e); return; }
    if (e1 == e2) return;
    switch (e1->type) { case E_OR: case E_AND: expr_eliminate_dups1(e1->type, &e1, &e1); default: ; }
    expr *tmp;
    switch (type) {
        case E_OR:
            tmp = expr_join_or(e1, e2);
            if (tmp) { expr_free(e1); expr_free(e2); e1 = expr_alloc_symbol(&symbol_no); e2 = tmp; trans_count++; }
            break;
        case E_AND:
            tmp = expr_join_and(e1, e2);
            if (tmp) { expr_free(e1); expr_free(e2); e1 = expr_alloc_symbol(&symbol_yes); e2 = tmp; trans_count++; }
            break;
        default: ;
    }
#undef e1
#undef e2
}

expr *expr_eliminate_dups(expr *e) {
    if (!e) return e;
    int oldcount = trans_count;
    while (1) {
        trans_count = 0;
        switch (e->type) { case E_OR: case E_AND: expr_eliminate_dups1(e->type, &e, &e); default: ; }
        if (!trans_count) break;
        e = expr_eliminate_yn(e);
    }
    trans_count = oldcount;
    return e;
}

expr *expr_transform(expr *e) {
    if (!e) return nullptr;
    expr *tmp;
    switch (e->type) {
        case E_EQUAL: case E_GEQ: case E_GTH: case E_LEQ: case E_LTH: case E_UNEQUAL: case E_SYMBOL: case E_LIST: break;
        default: e->left.e = expr_transform(e->left.e); e->right.e = expr_transform(e->right.e);
    }
    switch (e->type) {
        case E_EQUAL:
            if (e->left.sym->type != S_BOOLEAN) break;
            if (e->right.sym == &symbol_no) { e->type = E_NOT; e->left.e = expr_alloc_symbol(e->left.sym); e->right.sym = nullptr; break; }
            if (e->right.sym == &symbol_mod) { printf("boolean symbol %s tested for 'm'? test forced to 'n'\n", e->left.sym->name.c_str()); e->type = E_SYMBOL; e->left.sym = &symbol_no; e->right.sym = nullptr; break; }
            if (e->right.sym == &symbol_yes) { e->type = E_SYMBOL; e->right.sym = nullptr; break; }
            break;
        case E_UNEQUAL:
            if (e->left.sym->type != S_BOOLEAN) break;
            if (e->right.sym == &symbol_no) { e->type = E_SYMBOL; e->right.sym = nullptr; break; }
            if (e->right.sym == &symbol_mod) { printf("boolean symbol %s tested for 'm'? test forced to 'y'\n", e->left.sym->name.c_str()); e->type = E_SYMBOL; e->left.sym = &symbol_yes; e->right.sym = nullptr; break; }
            if (e->right.sym == &symbol_yes) { e->type = E_NOT; e->left.e = expr_alloc_symbol(e->left.sym); e->right.sym = nullptr; break; }
            break;
        case E_NOT: switch (e->left.e->type) {
            case E_NOT: tmp = e->left.e->left.e; delete e->left.e; delete e; e = tmp; e = expr_transform(e); break;
            case E_EQUAL: case E_UNEQUAL: tmp = e->left.e; delete e; e = tmp; e->type = e->type == E_EQUAL ? E_UNEQUAL : E_EQUAL; break;
            case E_LEQ: case E_GEQ: tmp = e->left.e; delete e; e = tmp; e->type = e->type == E_LEQ ? E_GTH : E_LTH; break;
            case E_LTH: case E_GTH: tmp = e->left.e; delete e; e = tmp; e->type = e->type == E_LTH ? E_GEQ : E_LEQ; break;
            case E_OR:
                tmp = e->left.e; e->type = E_AND;
                e->right.e = expr_alloc_one(E_NOT, tmp->right.e);
                tmp->type = E_NOT; tmp->right.e = nullptr;
                e = expr_transform(e); break;
            case E_AND:
                tmp = e->left.e; e->type = E_OR;
                e->right.e = expr_alloc_one(E_NOT, tmp->right.e);
                tmp->type = E_NOT; tmp->right.e = nullptr;
                e = expr_transform(e); break;
            case E_SYMBOL:
                if (e->left.e->left.sym == &symbol_yes) { tmp = e->left.e; delete e; e = tmp; e->type = E_SYMBOL; e->left.sym = &symbol_no; break; }
                if (e->left.e->left.sym == &symbol_mod) { tmp = e->left.e; delete e; e = tmp; e->type = E_SYMBOL; e->left.sym = &symbol_mod; break; }
                if (e->left.e->left.sym == &symbol_no) { tmp = e->left.e; delete e; e = tmp; e->type = E_SYMBOL; e->left.sym = &symbol_yes; break; }
                break;
            default: ;
        } break;
        default: ;
    }
    return e;
}

int expr_contains_symbol(expr *dep, symbol *sym) {
    if (!dep) return 0;
    switch (dep->type) {
        case E_AND: case E_OR: return expr_contains_symbol(dep->left.e, sym) || expr_contains_symbol(dep->right.e, sym);
        case E_SYMBOL: return dep->left.sym == sym;
        case E_EQUAL: case E_GEQ: case E_GTH: case E_LEQ: case E_LTH: case E_UNEQUAL: return dep->left.sym == sym || dep->right.sym == sym;
        case E_NOT: return expr_contains_symbol(dep->left.e, sym);
        default: ;
    }
    return 0;
}

bool expr_depends_symbol(expr *dep, symbol *sym) {
    if (!dep) return false;
    switch (dep->type) {
        case E_AND: return expr_depends_symbol(dep->left.e, sym) || expr_depends_symbol(dep->right.e, sym);
        case E_SYMBOL: return dep->left.sym == sym;
        case E_EQUAL: if (dep->left.sym == sym) { if (dep->right.sym == &symbol_yes || dep->right.sym == &symbol_mod) return true; } break;
        case E_UNEQUAL: if (dep->left.sym == sym) { if (dep->right.sym == &symbol_no) return true; } break;
        default: ;
    }
    return false;
}

expr *expr_trans_compare(expr *e, expr_type type, symbol *sym) {
    if (!e) {
        e = expr_alloc_symbol(sym);
        if (type == E_UNEQUAL) e = expr_alloc_one(E_NOT, e);
        return e;
    }
    expr *e1, *e2;
    switch (e->type) {
        case E_AND:
            e1 = expr_trans_compare(e->left.e, E_EQUAL, sym);
            e2 = expr_trans_compare(e->right.e, E_EQUAL, sym);
            if (sym == &symbol_yes) e = expr_alloc_two(E_AND, e1, e2);
            if (sym == &symbol_no) e = expr_alloc_two(E_OR, e1, e2);
            if (type == E_UNEQUAL) e = expr_alloc_one(E_NOT, e);
            return e;
        case E_OR:
            e1 = expr_trans_compare(e->left.e, E_EQUAL, sym);
            e2 = expr_trans_compare(e->right.e, E_EQUAL, sym);
            if (sym == &symbol_yes) e = expr_alloc_two(E_OR, e1, e2);
            if (sym == &symbol_no) e = expr_alloc_two(E_AND, e1, e2);
            if (type == E_UNEQUAL) e = expr_alloc_one(E_NOT, e);
            return e;
        case E_NOT: return expr_trans_compare(e->left.e, type == E_EQUAL ? E_UNEQUAL : E_EQUAL, sym);
        case E_UNEQUAL: case E_LTH: case E_LEQ: case E_GTH: case E_GEQ: case E_EQUAL:
            if (type == E_EQUAL) { if (sym == &symbol_yes) return expr_copy(e); if (sym == &symbol_mod) return expr_alloc_symbol(&symbol_no); if (sym == &symbol_no) return expr_alloc_one(E_NOT, expr_copy(e)); }
            else { if (sym == &symbol_yes) return expr_alloc_one(E_NOT, expr_copy(e)); if (sym == &symbol_mod) return expr_alloc_symbol(&symbol_yes); if (sym == &symbol_no) return expr_copy(e); }
            break;
        case E_SYMBOL: return expr_alloc_comp(type, e->left.sym, sym);
        case E_LIST: case E_RANGE: case E_NONE: ;
    }
    return nullptr;
}

enum string_value_kind { k_string, k_signed, k_unsigned };
union string_value { unsigned long long u; signed long long s; };

string_value_kind expr_parse_string(const char *str, symbol_type type, string_value *val) {
    char *tail;
    errno = 0;
    switch (type) {
        case S_BOOLEAN: case S_TRISTATE:
            val->s = !strcmp(str, "n") ? 0 : !strcmp(str, "m") ? 1 : !strcmp(str, "y") ? 2 : -1;
            return k_signed;
        case S_INT: val->s = strtoll(str, &tail, 10); break;
        case S_HEX: val->u = strtoull(str, &tail, 16); return !errno && !*tail && tail > str && isxdigit(tail[-1]) ? k_unsigned : k_string;
        default: val->s = strtoll(str, &tail, 0); break;
    }
    return !errno && !*tail && tail > str && isxdigit(tail[-1]) ? k_signed : k_string;
}

tristate expr_calc_value(expr *e) {
    if (!e) return yes;
    tristate val1, val2;
    const char *str1, *str2;
    string_value_kind k1 = k_string, k2 = k_string;
    string_value lval = {}, rval = {};
    int res;
    switch (e->type) {
        case E_SYMBOL: sym_calc_value(e->left.sym); return e->left.sym->curr.tri;
        case E_AND: val1 = expr_calc_value(e->left.e); val2 = expr_calc_value(e->right.e); return (tristate)EXPR_AND(val1, val2);
        case E_OR: val1 = expr_calc_value(e->left.e); val2 = expr_calc_value(e->right.e); return (tristate)EXPR_OR(val1, val2);
        case E_NOT: val1 = expr_calc_value(e->left.e); return (tristate)EXPR_NOT(val1);
        case E_EQUAL: case E_GEQ: case E_GTH: case E_LEQ: case E_LTH: case E_UNEQUAL: break;
        default: printf("expr_calc_value: %d?\n", (int)e->type); return no;
    }
    sym_calc_value(e->left.sym); sym_calc_value(e->right.sym);
    str1 = sym_get_string_value(e->left.sym); str2 = sym_get_string_value(e->right.sym);
    if (e->left.sym->type != S_STRING || e->right.sym->type != S_STRING) {
        k1 = expr_parse_string(str1, e->left.sym->type, &lval);
        k2 = expr_parse_string(str2, e->right.sym->type, &rval);
    }
    if (k1 == k_string || k2 == k_string) res = strcmp(str1, str2);
    else if (k1 == k_unsigned || k2 == k_unsigned) res = (lval.u > rval.u) - (lval.u < rval.u);
    else res = (lval.s > rval.s) - (lval.s < rval.s);
    switch(e->type) {
        case E_EQUAL: return res ? no : yes;
        case E_GEQ: return res >= 0 ? yes : no;
        case E_GTH: return res > 0 ? yes : no;
        case E_LEQ: return res <= 0 ? yes : no;
        case E_LTH: return res < 0 ? yes : no;
        case E_UNEQUAL: return res ? yes : no;
        default: printf("expr_calc_value: relation %d?\n", (int)e->type); return no;
    }
}

int expr_compare_type(expr_type t1, expr_type t2) {
    if (t1 == t2) return 0;
    switch (t1) {
        case E_LEQ: case E_LTH: case E_GEQ: case E_GTH: if (t2 == E_EQUAL || t2 == E_UNEQUAL) return 1;
        case E_EQUAL: case E_UNEQUAL: if (t2 == E_NOT) return 1;
        case E_NOT: if (t2 == E_AND) return 1;
        case E_AND: if (t2 == E_OR) return 1;
        case E_OR: if (t2 == E_LIST) return 1;
        case E_LIST: if (t2 == 0) return 1;
        default: return -1;
    }
}

void expr_print(expr *e, void (*fn)(void *, symbol *, const char *), void *data, int prevtoken) {
    if (!e) { fn(data, nullptr, "y"); return; }
    if (expr_compare_type((expr_type)prevtoken, e->type) > 0) fn(data, nullptr, "(");
    switch (e->type) {
        case E_SYMBOL:
            fn(data, e->left.sym, e->left.sym->name.empty() ? "<choice>" : e->left.sym->name.c_str()); break;
        case E_NOT: fn(data, nullptr, "!"); expr_print(e->left.e, fn, data, E_NOT); break;
        case E_EQUAL:
            fn(data, e->left.sym, e->left.sym->name.c_str()); fn(data, nullptr, "=");
            fn(data, e->right.sym, e->right.sym->name.c_str()); break;
        case E_LEQ: case E_LTH:
            fn(data, e->left.sym, e->left.sym->name.c_str());
            fn(data, nullptr, e->type == E_LEQ ? "<=" : "<");
            fn(data, e->right.sym, e->right.sym->name.c_str()); break;
        case E_GEQ: case E_GTH:
            fn(data, e->left.sym, e->left.sym->name.c_str());
            fn(data, nullptr, e->type == E_GEQ ? ">=" : ">");
            fn(data, e->right.sym, e->right.sym->name.c_str()); break;
        case E_UNEQUAL:
            fn(data, e->left.sym, e->left.sym->name.c_str()); fn(data, nullptr, "!=");
            fn(data, e->right.sym, e->right.sym->name.c_str()); break;
        case E_OR: expr_print(e->left.e, fn, data, E_OR); fn(data, nullptr, " || "); expr_print(e->right.e, fn, data, E_OR); break;
        case E_AND: expr_print(e->left.e, fn, data, E_AND); fn(data, nullptr, " && "); expr_print(e->right.e, fn, data, E_AND); break;
        case E_LIST:
            fn(data, e->right.sym, e->right.sym->name.c_str());
            if (e->left.e) { fn(data, nullptr, " ^ "); expr_print(e->left.e, fn, data, E_LIST); } break;
        case E_RANGE:
            fn(data, nullptr, "["); fn(data, e->left.sym, e->left.sym->name.c_str());
            fn(data, nullptr, " "); fn(data, e->right.sym, e->right.sym->name.c_str());
            fn(data, nullptr, "]"); break;
        default: { char buf[32]; sprintf(buf, "<unknown type %d>", (int)e->type); fn(data, nullptr, buf); break; }
    }
    if (expr_compare_type((expr_type)prevtoken, e->type) > 0) fn(data, nullptr, ")");
}

void expr_print_file_helper(void *data, symbol *, const char *str) { fwrite(str, strlen(str), 1, (FILE*)data); }
void expr_fprint(expr *e, FILE *out) { expr_print(e, expr_print_file_helper, out, E_NONE); }

void expr_print_gstr_helper(void *data, symbol *sym, const char *str) {
    gstr *gs = (gstr*)data;
    const char *sym_str = sym ? sym_get_string_value(sym) : nullptr;
    if (gs->max_width) {
        unsigned extra = strlen(str);
        const char *last_cr = strrchr(gs->s, '\n');
        if (sym_str) extra += 4 + strlen(sym_str);
        if (!last_cr) last_cr = gs->s;
        unsigned line_len = strlen(gs->s) - (last_cr - gs->s);
        if ((line_len + extra) > (unsigned)gs->max_width) str_append(gs, "\\\n");
    }
    str_append(gs, str);
    if (sym && sym->type != S_UNKNOWN) str_printf(gs, " [=%s]", sym_str);
}

void expr_gstr_print(expr *e, gstr *gs) { expr_print(e, expr_print_gstr_helper, gs, E_NONE); }

void expr_print_revdep(expr *e, void (*fn)(void *, symbol *, const char *), void *data, tristate pr_type, const char **title) {
    if (e->type == E_OR) { expr_print_revdep(e->left.e, fn, data, pr_type, title); expr_print_revdep(e->right.e, fn, data, pr_type, title); }
    else if (expr_calc_value(e) == pr_type) {
        if (*title) { fn(data, nullptr, *title); *title = nullptr; }
        fn(data, nullptr, "  - "); expr_print(e, fn, data, E_NONE); fn(data, nullptr, "\n");
    }
}

void expr_gstr_print_revdep(expr *e, gstr *gs, tristate pr_type, const char *title) {
    expr_print_revdep(e, expr_print_gstr_helper, gs, pr_type, &title);
}

// ============================================================
// symbol.c
// ============================================================

const char *sym_type_name(symbol_type type) {
    switch (type) { case S_BOOLEAN: return "bool"; case S_TRISTATE: return "tristate"; case S_INT: return "integer"; case S_HEX: return "hex"; case S_STRING: return "string"; case S_UNKNOWN: return "unknown"; } return "???";
}

symbol_type sym_get_type(symbol *sym) {
    symbol_type type = sym->type;
    if (type == S_TRISTATE) { if (sym_is_choice_value(sym) && sym->visible == yes) type = S_BOOLEAN; else if (modules_val == no) type = S_BOOLEAN; }
    return type;
}

property *sym_get_choice_prop(symbol *sym) {
    for (property *prop = sym->prop; prop; prop = prop->next) if (prop->type == P_CHOICE) return prop;
    return nullptr;
}

property *sym_get_default_prop(symbol *sym) {
    for (property *prop = sym->prop; prop; prop = prop->next) {
        if (prop->type != P_DEFAULT) continue;
        prop->visible.tri = expr_calc_value(prop->visible.expr);
        if (prop->visible.tri != no) return prop;
    }
    return nullptr;
}

property *sym_get_range_prop(symbol *sym) {
    for (property *prop = sym->prop; prop; prop = prop->next) {
        if (prop->type != P_RANGE) continue;
        prop->visible.tri = expr_calc_value(prop->visible.expr);
        if (prop->visible.tri != no) return prop;
    }
    return nullptr;
}

long long sym_get_range_val(symbol *sym, int base) {
    sym_calc_value(sym);
    switch (sym->type) { case S_INT: base = 10; break; case S_HEX: base = 16; break; default: break; }
    return strtoll((const char*)static_cast<const char*>(sym->curr.val), nullptr, base);
}

void sym_validate_range(symbol *sym) {
    int base;
    switch (sym->type) { case S_INT: base = 10; break; case S_HEX: base = 16; break; default: return; }
    property *prop = sym_get_range_prop(sym); if (!prop) return;
    long long val = strtoll((const char*)static_cast<const char*>(sym->curr.val), nullptr, base);
    long long val2 = sym_get_range_val(prop->expr->left.sym, base);
    if (val >= val2) { val2 = sym_get_range_val(prop->expr->right.sym, base); if (val <= val2) return; }
    char str[64];
    if (sym->type == S_INT) sprintf(str, "%lld", val2); else sprintf(str, "0x%llx", val2);
    free(sym->curr.val); sym->curr.val = strdup(str);
}

void sym_set_changed(symbol *sym) {
    sym->flags |= SYMBOL_CHANGED;
    for (property *prop = sym->prop; prop; prop = prop->next) if (prop->menu) prop->menu->flags |= MENU_CHANGED;
}

void sym_set_all_changed(void) { for (int i = 0; i < SYMBOL_HASHSIZE; i++) for (symbol *s = symbol_hash[i]; s; s = s->next) sym_set_changed(s); }

void sym_calc_visibility(symbol *sym) {
    symbol *choice_sym = nullptr; tristate tri = no;
    if (sym_is_choice_value(sym)) choice_sym = prop_get_symbol(sym_get_choice_prop(sym));
    for (property *prop = sym->prop; prop; prop = prop->next) {
        if (!prop->text.empty()) {
            prop->visible.tri = expr_calc_value(prop->visible.expr);
            if (choice_sym && sym->type == S_TRISTATE && prop->visible.tri == mod && choice_sym->curr.tri == yes) prop->visible.tri = no;
            tri = (tristate)EXPR_OR(tri, prop->visible.tri);
        }
    }
    if (tri == mod && (sym->type != S_TRISTATE || modules_val == no)) tri = yes;
    if (sym->visible != tri) { sym->visible = tri; sym_set_changed(sym); }
    if (sym_is_choice_value(sym)) return;
    tri = yes; if (sym->dir_dep.expr) tri = expr_calc_value(sym->dir_dep.expr);
    if (tri == mod && sym_get_type(sym) == S_BOOLEAN) tri = yes;
    if (sym->dir_dep.tri != tri) { sym->dir_dep.tri = tri; sym_set_changed(sym); }
    tri = no; if (sym->rev_dep.expr) tri = expr_calc_value(sym->rev_dep.expr);
    if (tri == mod && sym_get_type(sym) == S_BOOLEAN) tri = yes;
    if (sym->rev_dep.tri != tri) { sym->rev_dep.tri = tri; sym_set_changed(sym); }
    tri = no; if (sym->implied.expr) tri = expr_calc_value(sym->implied.expr);
    if (tri == mod && sym_get_type(sym) == S_BOOLEAN) tri = yes;
    if (sym->implied.tri != tri) { sym->implied.tri = tri; sym_set_changed(sym); }
}

symbol *sym_choice_default(symbol *sym) {
    for (property *prop = sym->prop; prop; prop = prop->next) {
        if (prop->type != P_DEFAULT) continue;
        prop->visible.tri = expr_calc_value(prop->visible.expr); if (prop->visible.tri == no) continue;
        symbol *def_sym = prop_get_symbol(prop); if (def_sym->visible != no) return def_sym;
    }
    property *prop = sym_get_choice_prop(sym); if (!prop) return nullptr;
    for (expr *e = prop->expr; e; e = e->left.e) {
        symbol *def_sym = e->right.sym; if (def_sym && def_sym->visible != no) return def_sym;
    }
    return nullptr;
}

symbol *sym_calc_choice(symbol *sym) {
    int flags = sym->flags;
    property *prop = sym_get_choice_prop(sym);
    for (expr *e = prop->expr; e; e = e->left.e) {
        sym_calc_visibility(e->right.sym); if (e->right.sym->visible != no) flags &= e->right.sym->flags;
    }
    sym->flags &= flags | ~SYMBOL_DEF_USER;
    symbol *def_sym = (symbol*)sym->def[S_DEF_USER].val;
    if (def_sym && def_sym->visible != no) return def_sym;
    def_sym = sym_choice_default(sym);
    if (!def_sym) sym->curr.tri = no;
    return def_sym;
}

void sym_warn_unmet_dep(symbol *sym) {
    gstr gs = str_new();
    str_printf(&gs, "\nWARNING: unmet direct dependencies detected for %s\n", sym->name.c_str());
    str_printf(&gs, "  Depends on [%c]: ", sym->dir_dep.tri == mod ? 'm' : 'n');
    expr_gstr_print(sym->dir_dep.expr, &gs); str_printf(&gs, "\n");
    expr_gstr_print_revdep(sym->rev_dep.expr, &gs, yes, "  Selected by [y]:\n");
    expr_gstr_print_revdep(sym->rev_dep.expr, &gs, mod, "  Selected by [m]:\n");
    fputs(str_get(&gs), stderr); str_free(&gs);
}

void sym_calc_value(symbol *sym) {
    if (!sym) return; if (sym->flags & SYMBOL_VALID) return;
    if (sym_is_choice_value(sym) && sym->flags & SYMBOL_NEED_SET_CHOICE_VALUES) {
        sym->flags &= ~SYMBOL_NEED_SET_CHOICE_VALUES;
        sym_calc_value(prop_get_symbol(sym_get_choice_prop(sym)));
    }
    sym->flags |= SYMBOL_VALID;
    symbol_value oldval = sym->curr, newval;
    switch (sym->type) {
        case S_INT: case S_HEX: case S_STRING: newval = symbol_empty.curr; break;
        case S_BOOLEAN: case S_TRISTATE: newval = symbol_no.curr; break;
        default: sym->curr.val = (void*)sym->name.c_str(); sym->curr.tri = no; return;
    }
    sym->flags &= ~SYMBOL_WRITE; sym_calc_visibility(sym);
    if (sym->visible != no) sym->flags |= SYMBOL_WRITE; sym->curr = newval;
    switch (sym_get_type(sym)) {
        case S_BOOLEAN: case S_TRISTATE:
            if (sym_is_choice_value(sym) && sym->visible == yes) {
                property *prop = sym_get_choice_prop(sym);
                newval.tri = (prop_get_symbol(prop)->curr.val == sym) ? yes : no;
            } else {
                if (sym->visible != no) { if (sym_has_value(sym)) { newval.tri = (tristate)EXPR_AND(sym->def[S_DEF_USER].tri, sym->visible); goto calc_newval; } }
                if (sym->rev_dep.tri != no) sym->flags |= SYMBOL_WRITE;
                if (!sym_is_choice(sym)) {
                    property *prop = sym_get_default_prop(sym);
                    if (prop) { newval.tri = (tristate)EXPR_AND(expr_calc_value(prop->expr), prop->visible.tri); if (newval.tri != no) sym->flags |= SYMBOL_WRITE; }
                    if (sym->implied.tri != no) { sym->flags |= SYMBOL_WRITE; newval.tri = (tristate)EXPR_OR(newval.tri, sym->implied.tri); newval.tri = (tristate)EXPR_AND(newval.tri, sym->dir_dep.tri); }
                }
            calc_newval:
                if (sym->dir_dep.tri < sym->rev_dep.tri) sym_warn_unmet_dep(sym);
                newval.tri = (tristate)EXPR_OR(newval.tri, sym->rev_dep.tri);
            }
            if (newval.tri == mod && sym_get_type(sym) == S_BOOLEAN) newval.tri = yes;
            break;
        case S_STRING: case S_HEX: case S_INT:
            if (sym->visible != no && sym_has_value(sym)) { newval.val = sym->def[S_DEF_USER].val; break; }
            { property *prop = sym_get_default_prop(sym); if (prop) { symbol *ds = prop_get_symbol(prop); if (ds) { sym->flags |= SYMBOL_WRITE; sym_calc_value(ds); newval.val = ds->curr.val; } } } break;
        default: ;
    }
    sym->curr = newval;
    if (sym_is_choice(sym) && newval.tri == yes) sym->curr.val = sym_calc_choice(sym);
    sym_validate_range(sym);
    if (memcmp(&oldval, &sym->curr, sizeof(oldval))) { sym_set_changed(sym); if (modules_sym == sym) { sym_set_all_changed(); modules_val = modules_sym->curr.tri; } }
    if (sym_is_choice(sym)) {
        property *prop = sym_get_choice_prop(sym);
        for (expr *e = prop->expr; e; e = e->left.e) {
            if ((sym->flags & SYMBOL_WRITE) && e->right.sym->visible != no) e->right.sym->flags |= SYMBOL_WRITE;
            if (sym->flags & SYMBOL_CHANGED) sym_set_changed(e->right.sym);
        }
    }
    if (sym->flags & SYMBOL_NO_WRITE) sym->flags &= ~SYMBOL_WRITE;
    if (sym->flags & SYMBOL_NEED_SET_CHOICE_VALUES) set_all_choice_values(sym);
}

void sym_clear_all_valid(void) { for (int i = 0; i < SYMBOL_HASHSIZE; i++) for (symbol *s = symbol_hash[i]; s; s = s->next) s->flags &= ~SYMBOL_VALID; sym_add_change_count(1); sym_calc_value(modules_sym); }

bool sym_tristate_within_range(symbol *sym, tristate val) {
    int type = sym_get_type(sym); if (sym->visible == no) return false; if (type != S_BOOLEAN && type != S_TRISTATE) return false;
    if (type == S_BOOLEAN && val == mod) return false; if (sym->visible <= sym->rev_dep.tri) return false;
    if (sym_is_choice_value(sym) && sym->visible == yes) return val == yes;
    return val >= sym->rev_dep.tri && val <= sym->visible;
}

bool sym_set_tristate_value(symbol *sym, tristate val) {
    tristate oldval = sym_get_tristate_value(sym); if (oldval != val && !sym_tristate_within_range(sym, val)) return false;
    if (!(sym->flags & SYMBOL_DEF_USER)) { sym->flags |= SYMBOL_DEF_USER; sym_set_changed(sym); }
    if (sym_is_choice_value(sym) && val == yes) {
        symbol *cs = prop_get_symbol(sym_get_choice_prop(sym)); cs->def[S_DEF_USER].val = sym; cs->flags |= SYMBOL_DEF_USER;
        property *prop = sym_get_choice_prop(cs);
        for (expr *e = prop->expr; e; e = e->left.e) { if (e->right.sym->visible != no) e->right.sym->flags |= SYMBOL_DEF_USER; }
    }
    sym->def[S_DEF_USER].tri = val; if (oldval != val) sym_clear_all_valid();
    return true;
}

tristate sym_toggle_tristate_value(symbol *sym) {
    tristate oldval = sym_get_tristate_value(sym), newval = oldval;
    do { switch (newval) { case no: newval = mod; break; case mod: newval = yes; break; case yes: newval = no; break; } if (sym_set_tristate_value(sym, newval)) break; } while (oldval != newval);
    return newval;
}

bool sym_string_valid(symbol *sym, const char *str) {
    signed char ch;
    switch (sym->type) {
        case S_STRING: return true;
        case S_INT: ch = *str++; if (ch == '-') ch = *str++; if (!isdigit(ch)) return false; if (ch == '0' && *str != 0) return false; while ((ch = *str++)) { if (!isdigit(ch)) return false; } return true;
        case S_HEX: if (str[0] == '0' && (str[1] == 'x' || str[1] == 'X')) str += 2; ch = *str++; do { if (!isxdigit(ch)) return false; } while ((ch = *str++)); return true;
        case S_BOOLEAN: case S_TRISTATE: switch (str[0]) { case 'y': case 'Y': case 'm': case 'M': case 'n': case 'N': return true; } return false;
        default: return false;
    }
}

bool sym_string_within_range(symbol *sym, const char *str) {
    switch (sym->type) {
        case S_STRING: return sym_string_valid(sym, str);
        case S_INT: if (!sym_string_valid(sym, str)) return false; { property *prop = sym_get_range_prop(sym); if (!prop) return true; long long val = strtoll(str, nullptr, 10); return val >= sym_get_range_val(prop->expr->left.sym, 10) && val <= sym_get_range_val(prop->expr->right.sym, 10); }
        case S_HEX: if (!sym_string_valid(sym, str)) return false; { property *prop = sym_get_range_prop(sym); if (!prop) return true; long long val = strtoll(str, nullptr, 16); return val >= sym_get_range_val(prop->expr->left.sym, 16) && val <= sym_get_range_val(prop->expr->right.sym, 16); }
        case S_BOOLEAN: case S_TRISTATE: switch (str[0]) { case 'y': case 'Y': return sym_tristate_within_range(sym, yes); case 'm': case 'M': return sym_tristate_within_range(sym, mod); case 'n': case 'N': return sym_tristate_within_range(sym, no); } return false;
        default: return false;
    }
}

bool sym_set_string_value(symbol *sym, const char *newval) {
    switch (sym->type) {
        case S_BOOLEAN: case S_TRISTATE: switch (newval[0]) { case 'y': case 'Y': return sym_set_tristate_value(sym, yes); case 'm': case 'M': return sym_set_tristate_value(sym, mod); case 'n': case 'N': return sym_set_tristate_value(sym, no); } return false;
        default: ;
    }
    if (!sym_string_within_range(sym, newval)) return false;
    if (!(sym->flags & SYMBOL_DEF_USER)) { sym->flags |= SYMBOL_DEF_USER; sym_set_changed(sym); }
    const char *oldval = (const char*)sym->def[S_DEF_USER].val;
    int size = strlen(newval) + 1; char *val;
    if (sym->type == S_HEX && (newval[0] != '0' || (newval[1] != 'x' && newval[1] != 'X'))) {
        size += 2; val = new char[size]; val[0] = '0'; val[1] = 'x'; strcpy(val + 2, newval); sym->def[S_DEF_USER].val = val;
    } else if (!oldval || strcmp(oldval, newval)) { val = new char[size]; strcpy(val, newval); sym->def[S_DEF_USER].val = val; }
    else return true;
    free((void*)oldval); sym_clear_all_valid();
    return true;
}

const char *sym_get_string_default(symbol *sym) {
    sym_calc_visibility(sym); sym_calc_value(modules_sym);
    tristate val = symbol_no.curr.tri; const char *str = (const char*)symbol_empty.curr.val;
    property *prop = sym_get_default_prop(sym);
    if (prop) { switch (sym->type) { case S_BOOLEAN: case S_TRISTATE: val = (tristate)EXPR_AND(expr_calc_value(prop->expr), prop->visible.tri); break; default: { symbol *ds = prop_get_symbol(prop); if (ds) { sym_calc_value(ds); str = (const char*)ds->curr.val; } } } }
    val = (tristate)EXPR_OR(val, sym->rev_dep.tri);
    if (val == mod && !sym_is_choice_value(sym) && modules_sym->curr.tri == no) val = yes;
    if (sym->type == S_BOOLEAN && val == mod) val = yes;
    if (val < sym->implied.tri) val = sym->implied.tri;
    switch (sym->type) { case S_BOOLEAN: case S_TRISTATE: switch (val) { case no: return "n"; case mod: return "m"; case yes: return "y"; } case S_INT: case S_HEX: case S_STRING: return str; case S_UNKNOWN: break; } return "";
}

const char *sym_get_string_value(symbol *sym) {
    switch (sym->type) { case S_BOOLEAN: case S_TRISTATE: switch (sym_get_tristate_value(sym)) { case no: return "n"; case mod: sym_calc_value(modules_sym); return (modules_sym->curr.tri == no) ? "n" : "m"; case yes: return "y"; } break; default: ; }
    return (const char*)static_cast<const char*>(sym->curr.val);
}

bool sym_is_changeable(symbol *sym) { return sym->visible > sym->rev_dep.tri; }

unsigned strhash(const char *s) { unsigned h = 2166136261U; for (; *s; s++) h = (h ^ *s) * 0x01000193; return h; }

symbol *sym_lookup(const char *name, int flags) {
    if (name) {
        if (name[0] && !name[1]) { switch (name[0]) { case 'y': return &symbol_yes; case 'm': return &symbol_mod; case 'n': return &symbol_no; } }
        int hash = strhash(name) % SYMBOL_HASHSIZE;
        for (symbol *s = symbol_hash[hash]; s; s = s->next) { if (!s->name.empty() && s->name == name && (flags ? s->flags & flags : !(s->flags & (SYMBOL_CONST|SYMBOL_CHOICE)))) return s; }
        auto *sym = new symbol{}; sym->name = name; sym->type = S_UNKNOWN; sym->flags = flags;
        sym->next = symbol_hash[hash]; symbol_hash[hash] = sym; return sym;
    } else { auto *sym = new symbol{}; sym->type = S_UNKNOWN; sym->flags = flags; sym->next = symbol_hash[0]; symbol_hash[0] = sym; return sym; }
}

symbol *sym_find(const char *name) {
    if (!name) return nullptr; if (name[0] && !name[1]) { switch (name[0]) { case 'y': return &symbol_yes; case 'm': return &symbol_mod; case 'n': return &symbol_no; } }
    int hash = strhash(name) % SYMBOL_HASHSIZE;
    for (symbol *s = symbol_hash[hash]; s; s = s->next) { if (!s->name.empty() && s->name == name && !(s->flags & SYMBOL_CONST)) return s; }
    return nullptr;
}

const char *sym_escape_string_value(const char *in) {
    size_t reslen = strlen(in) + strlen("\"\"") + 1;
    for (const char *p = in; ;) { size_t l = strcspn(p, "\"\\"); p += l; if (p[0] == '\0') break; reslen++; p++; }
    char *res = new char[reslen]; res[0] = '\0'; strcat(res, "\"");
    for (const char *p = in; ;) { size_t l = strcspn(p, "\"\\"); strncat(res, p, l); p += l; if (p[0] == '\0') break; strcat(res, "\\"); strncat(res, p++, 1); }
    strcat(res, "\""); return res;
}

struct sym_match { symbol *sym; off_t so, eo; };
int sym_rel_comp(const void *s1, const void *s2) { auto *m1 = (const sym_match*)s1; auto *m2 = (const sym_match*)s2; int e1 = (m1->eo - m1->so) == (int)strlen(m1->sym->name.c_str()); int e2 = (m2->eo - m2->so) == (int)strlen(m2->sym->name.c_str()); if (e1 && !e2) return -1; if (!e1 && e2) return 1; return strcmp(m1->sym->name.c_str(), m2->sym->name.c_str()); }

symbol **sym_re_search(const char *pattern) {
    sym_match *arr = nullptr; int cnt = 0, size = 0; regex_t re; regmatch_t match[1];
    if (!strlen(pattern)) return nullptr; if (regcomp(&re, pattern, REG_EXTENDED|REG_ICASE)) return nullptr;
    for (int i = 0; i < SYMBOL_HASHSIZE; i++) for (symbol *s = symbol_hash[i]; s; s = s->next) {
        if (s->flags & SYMBOL_CONST || s->name.empty()) continue; if (regexec(&re, s->name.c_str(), 1, match, 0)) continue;
        if (cnt >= size) { size += 16; arr = (sym_match*)realloc(arr, size * sizeof(sym_match)); if (!arr) { regfree(&re); return nullptr; } }
        sym_calc_value(s); arr[cnt].so = match[0].rm_so; arr[cnt].eo = match[0].rm_eo; arr[cnt++].sym = s;
    }
    if (arr) { qsort(arr, cnt, sizeof(sym_match), sym_rel_comp); auto **sa = (symbol**)malloc((cnt+1)*sizeof(symbol*)); if (!sa) { free(arr); regfree(&re); return nullptr; } for (int i = 0; i < cnt; i++) sa[i] = arr[i].sym; sa[cnt] = nullptr; free(arr); regfree(&re); return sa; }
    regfree(&re); return nullptr;
}

struct dep_stack { dep_stack *prev, *next; symbol *sym; property *prop; expr **expr; };
dep_stack *check_top;

void dep_stack_insert(dep_stack *stack, symbol *sym) { memset(stack, 0, sizeof(*stack)); if (check_top) check_top->next = stack; stack->prev = check_top; stack->sym = sym; check_top = stack; }
void dep_stack_remove(void) { check_top = check_top->prev; if (check_top) check_top->next = nullptr; }

void sym_check_print_recursive(symbol *last_sym) {
    dep_stack *stack; symbol *sym, *next_sym; menu *mn = nullptr; property *prop; dep_stack cv_stack;
    if (sym_is_choice_value(last_sym)) { dep_stack_insert(&cv_stack, last_sym); last_sym = prop_get_symbol(sym_get_choice_prop(last_sym)); }
    for (stack = check_top; stack; stack = stack->prev) if (stack->sym == last_sym) break;
    if (!stack) { fprintf(stderr, "unexpected recursive dependency error\n"); return; }
    for (; stack; stack = stack->next) {
        sym = stack->sym; next_sym = stack->next ? stack->next->sym : last_sym; prop = stack->prop; if (!prop) prop = stack->sym->prop;
        if (sym_is_choice(sym) || sym_is_choice_value(sym)) for (prop = sym->prop; prop; prop = prop->next) { mn = prop->menu; if (prop->menu) break; }
        if (stack->sym == last_sym) fprintf(stderr, "%s:%d:error: recursive dependency detected!\n", prop->file->name.c_str(), prop->lineno);
        auto nm = [&](symbol *x) { return x->name.empty() ? "<choice>" : x->name.c_str(); };
        if (sym_is_choice(sym)) fprintf(stderr, "%s:%d:\tchoice %s contains symbol %s\n", mn->file->name.c_str(), mn->lineno, nm(sym), nm(next_sym));
        else if (sym_is_choice_value(sym)) fprintf(stderr, "%s:%d:\tsymbol %s is part of choice %s\n", mn->file->name.c_str(), mn->lineno, nm(sym), nm(next_sym));
        else if (stack->expr == &sym->dir_dep.expr) fprintf(stderr, "%s:%d:\tsymbol %s depends on %s\n", prop->file->name.c_str(), prop->lineno, nm(sym), nm(next_sym));
        else if (stack->expr == &sym->rev_dep.expr) fprintf(stderr, "%s:%d:\tsymbol %s is selected by %s\n", prop->file->name.c_str(), prop->lineno, nm(sym), nm(next_sym));
        else if (stack->expr == &sym->implied.expr) fprintf(stderr, "%s:%d:\tsymbol %s is implied by %s\n", prop->file->name.c_str(), prop->lineno, nm(sym), nm(next_sym));
        else if (stack->expr) fprintf(stderr, "%s:%d:\tsymbol %s %s value contains %s\n", prop->file->name.c_str(), prop->lineno, nm(sym), prop_get_type_name(prop->type), nm(next_sym));
        else fprintf(stderr, "%s:%d:\tsymbol %s %s is visible depending on %s\n", prop->file->name.c_str(), prop->lineno, nm(sym), prop_get_type_name(prop->type), nm(next_sym));
    }
    fprintf(stderr, "For a resolution refer to Documentation/kbuild/kconfig-language.rst\nsubsection \"Kconfig recursive dependency limitations\"\n\n");
    if (check_top == &cv_stack) dep_stack_remove();
}

symbol *sym_check_expr_deps(expr *e) {
    if (!e) return nullptr;
    switch (e->type) { case E_OR: case E_AND: { symbol *s = sym_check_expr_deps(e->left.e); if (s) return s; return sym_check_expr_deps(e->right.e); } case E_NOT: return sym_check_expr_deps(e->left.e); case E_EQUAL: case E_GEQ: case E_GTH: case E_LEQ: case E_LTH: case E_UNEQUAL: { symbol *s = sym_check_deps(e->left.sym); if (s) return s; return sym_check_deps(e->right.sym); } case E_SYMBOL: return sym_check_deps(e->left.sym); default: ; }
    fprintf(stderr, "Oops! How to check %d?\n", (int)e->type); return nullptr;
}

symbol *sym_check_sym_deps(symbol *sym) {
    dep_stack stack; dep_stack_insert(&stack, sym); symbol *sym2;
    stack.expr = &sym->dir_dep.expr; sym2 = sym_check_expr_deps(sym->dir_dep.expr); if (sym2) goto out;
    stack.expr = &sym->rev_dep.expr; sym2 = sym_check_expr_deps(sym->rev_dep.expr); if (sym2) goto out;
    stack.expr = &sym->implied.expr; sym2 = sym_check_expr_deps(sym->implied.expr); if (sym2) goto out;
    stack.expr = nullptr;
    for (property *prop = sym->prop; prop; prop = prop->next) { if (prop->type == P_CHOICE || prop->type == P_SELECT || prop->type == P_IMPLY) continue; stack.prop = prop; sym2 = sym_check_expr_deps(prop->visible.expr); if (sym2) break; if (prop->type != P_DEFAULT || sym_is_choice(sym)) continue; stack.expr = &prop->expr; sym2 = sym_check_expr_deps(prop->expr); if (sym2) break; stack.expr = nullptr; }
out: dep_stack_remove(); return sym2;
}

symbol *sym_check_choice_deps(symbol *choice) {
    dep_stack stack; dep_stack_insert(&stack, choice); symbol *sym2;
    property *prop = sym_get_choice_prop(choice); for (expr *e = prop->expr; e; e = e->left.e) e->right.sym->flags |= (SYMBOL_CHECK|SYMBOL_CHECKED);
    choice->flags |= (SYMBOL_CHECK|SYMBOL_CHECKED); sym2 = sym_check_sym_deps(choice); choice->flags &= ~SYMBOL_CHECK; if (sym2) goto out;
    for (expr *e = prop->expr; e; e = e->left.e) { sym2 = sym_check_sym_deps(e->right.sym); if (sym2) break; }
out: for (expr *e = prop->expr; e; e = e->left.e) e->right.sym->flags &= ~SYMBOL_CHECK;
    if (sym2 && sym_is_choice_value(sym2) && prop_get_symbol(sym_get_choice_prop(sym2)) == choice) sym2 = choice;
    dep_stack_remove(); return sym2;
}

symbol *sym_check_deps(symbol *sym) {
    if (sym->flags & SYMBOL_CHECK) { sym_check_print_recursive(sym); return sym; } if (sym->flags & SYMBOL_CHECKED) return nullptr;
    if (sym_is_choice_value(sym)) { dep_stack stack; dep_stack_insert(&stack, sym); symbol *s = sym_check_deps(prop_get_symbol(sym_get_choice_prop(sym))); dep_stack_remove(); return s; }
    else if (sym_is_choice(sym)) return sym_check_choice_deps(sym);
    else { sym->flags |= (SYMBOL_CHECK|SYMBOL_CHECKED); symbol *s = sym_check_sym_deps(sym); sym->flags &= ~SYMBOL_CHECK; return s; }
}

symbol *prop_get_symbol(property *prop) { if (prop->expr && (prop->expr->type == E_SYMBOL || prop->expr->type == E_LIST)) return prop->expr->left.sym; return nullptr; }

const char *prop_get_type_name(prop_type type) {
    switch (type) { case P_PROMPT: return "prompt"; case P_COMMENT: return "comment"; case P_MENU: return "menu"; case P_DEFAULT: return "default"; case P_CHOICE: return "choice"; case P_SELECT: return "select"; case P_IMPLY: return "imply"; case P_RANGE: return "range"; case P_SYMBOL: return "symbol"; case P_UNKNOWN: break; } return "unknown";
}

static char *menu_dep = nullptr;

bool menu_is_visible(menu *m) {
    if (!m) return false;
    if (m == &rootmenu) return true;
    if (!m->prompt) return false;
    if (m->visibility) {
        if (expr_calc_value(m->visibility) == no) return false;
    }
    symbol *sym = m->sym;
    if (sym) {
        sym_calc_value(sym);
        if (sym->visible == no) return false;
    }
    if (!sym && m->list) {
        for (menu *child = m->list; child; child = child->next)
            if (menu_is_visible(child)) return true;
        return false;
    }
    return true;
}

const char *menu_get_prompt(menu *m) {
    if (!m || !m->prompt) return "";
    return m->prompt->text.c_str();
}

void env_write_dep(FILE *f, const char *autoconfig_name) {
    // Write dependency info for auto.conf tracking
    fprintf(f, "deps_config := \\\n");
    fprintf(f, "\t$(Kconfig)\n\n");
    fprintf(f, "%s: $(deps_config)\n\n", autoconfig_name);
    fprintf(f, "$(deps_config): ;\n");
}