// SPDX-License-Identifier: GPL-2.0
// C++23 Module Implementation for Kconfig preprocessing (variables, expansion)

module;
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cctype>
#include <string>
#include <vector>
module npc.kconfig;

struct variable {
    variable *next;
    const char *name;
    char *value;
    variable_flavor flavor;
    bool expanded;
};

static variable *var_list = nullptr;

void variable_add(const char *name, const char *value, variable_flavor flavor) {
    char *v = strdup(value ? value : "");
    for (variable *var = var_list; var; var = var->next) {
        if (!strcmp(var->name, name)) {
            free(var->value);
            var->value = v;
            return;
        }
    }
    auto *newvar = new variable{var_list, strdup(name), v, flavor, false};
    var_list = newvar;
}

char *variable_expand(const char *name) {
    for (variable *var = var_list; var; var = var->next)
        if (!strcmp(var->name, name))
            return strdup(var->value);
    return strdup("");
}

void variable_all_del(void) {
    while (var_list) {
        variable *next = var_list->next;
        free((void*)var_list->name);
        free(var_list->value);
        delete var_list;
        var_list = next;
    }
}

static const char *find_variable(const char *name) {
    for (variable *var = var_list; var; var = var->next)
        if (!strcmp(var->name, name))
            return var->value;
    return "";
}

char *expand_dollar(const char **str) {
    const char *s = *str;
    const char *p;
    char name[256];
    int len;
    if (*s == '(') { s++; p = strchr(s, ')'); if (!p) return strdup(""); len = p - s; }
    else if (*s == '{') { s++; p = strchr(s, '}'); if (!p) return strdup(""); len = p - s; }
    else { p = s; while (isalnum((unsigned char)*p) || *p == '_') p++; len = p - s; }
    if (len >= 256) return strdup("");
    memcpy(name, s, len); name[len] = 0;
    *str = (*p) ? p + 1 : p;
    return strdup(find_variable(name));
}

char *expand_one_token(const char **str) {
    const char *s = *str;
    char buf[4096];
    int i = 0;
    while (*s && !isspace((unsigned char)*s) && i < 4095) {
        if (*s == '$' && (s[1] == '(' || s[1] == '{' || isalnum((unsigned char)s[1]) || s[1] == '_')) {
            s++;
            char *expanded = expand_dollar(&s);
            int n = strlen(expanded);
            if (i + n < 4095) { memcpy(buf + i, expanded, n); i += n; }
            free(expanded);
        } else {
            buf[i++] = *s++;
        }
    }
    buf[i] = 0;
    *str = s;
    return strdup(buf);
}
