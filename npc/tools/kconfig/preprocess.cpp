#ifdef __clang__
import std;
#else
#include <bits/stdc++.h>
#endif
using namespace std;

// SPDX-License-Identifier: GPL-2.0
//
// Copyright (C) 2018 Masahiro Yamada <yamada.masahiro@socionext.com>



#include "lkc.hpp"

static char *expand_string_with_args(const char *in, int argc, char *argv[]);
static char *expand_string(const char *in);

[[noreturn]] __attribute__((format(printf, 1, 2)))
static void pperror(const char *format, ...)
{
	va_list ap;

	fprintf(stderr, "%s:%d: ", current_file->name, yylineno);
	va_start(ap, format);
	vfprintf(stderr, format, ap);
	va_end(ap);
	fprintf(stderr, "\n");

	exit(1);
}

/*
 * Environment variables
 */
struct env {
	std::string name;
	std::string value;
};

/* std::list: push_back does not invalidate pointers to existing elements */
static std::list<struct env> env_list;

static void env_add(const char *name, const char *value)
{
	env_list.push_back({name, value});
}

/* The returned pointer must be freed when done */
static char *env_expand(const char *name)
{
	const char *value;

	if (!*name)
		return nullptr;

	for (const struct env &e : env_list) {
		if (name == e.name)
			return xstrdup(e.value.c_str());
	}

	value = getenv(name);
	if (!value)
		return nullptr;

	/*
	 * We need to remember all referenced environment variables.
	 * They will be written out to include/config/auto.conf.cmd
	 */
	env_add(name, value);

	return xstrdup(value);
}

void env_write_dep(FILE *f, const char *autoconfig_name)
{
	for (const struct env &e : env_list) {
		fprintf(f, "ifneq \"$(%s)\" \"%s\"\n", e.name.c_str(), e.value.c_str());
		fprintf(f, "%s: FORCE\n", autoconfig_name);
		fprintf(f, "endif\n");
	}
	env_list.clear();
}

/*
 * Built-in functions
 */
struct kconfig_func {
	const char *name;
	unsigned int min_args;
	unsigned int max_args;
	char *(*func)(int argc, char *argv[]);
};

static char *do_error_if(int argc, char *argv[])
{
	if (!strcmp(argv[0], "y"))
		pperror("%s", argv[1]);

	return xstrdup("");
}

static char *do_filename(int argc, char *argv[])
{
	return xstrdup(current_file->name);
}

static char *do_info(int argc, char *argv[])
{
	printf("%s\n", argv[0]);

	return xstrdup("");
}

static char *do_lineno(int argc, char *argv[])
{
	return xstrdup(std::to_string(yylineno).c_str());
}

static char *do_shell(int argc, char *argv[])
{
	FILE *p;
	char buf[256];
	char *cmd;
	size_t nread;
	size_t i;

	cmd = argv[0];

	p = popen(cmd, "r");
	if (!p) {
		perror(cmd);
		exit(1);
	}

	nread = fread(buf, 1, sizeof(buf), p);
	if (nread == sizeof(buf))
		nread--;

	/* remove trailing new lines */
	while (nread > 0 && buf[nread - 1] == '\n')
		nread--;

	buf[nread] = 0;

	/* replace a new line with a space */
	for (i = 0; i < nread; i++) {
		if (buf[i] == '\n')
			buf[i] = ' ';
	}

	if (pclose(p) == -1) {
		perror(cmd);
		exit(1);
	}

	return xstrdup(buf);
}

static char *do_warning_if(int argc, char *argv[])
{
	if (!strcmp(argv[0], "y"))
		fprintf(stderr, "%s:%d: %s\n",
			current_file->name, yylineno, argv[1]);

	return xstrdup("");
}

static const struct kconfig_func function_table[] = {
	/* Name		MIN	MAX	Function */
	{ "error-if",	2,	2,	do_error_if },
	{ "filename",	0,	0,	do_filename },
	{ "info",	1,	1,	do_info },
	{ "lineno",	0,	0,	do_lineno },
	{ "shell",	1,	1,	do_shell },
	{ "warning-if",	2,	2,	do_warning_if },
};

#define FUNCTION_MAX_ARGS		16

static char *function_expand(const char *name, int argc, char *argv[])
{
	const struct kconfig_func *f;

	for (size_t i = 0; i < std::size(function_table); i++) {
		f = &function_table[i];
		if (strcmp(f->name, name))
			continue;

		if (argc < f->min_args)
			pperror("too few function arguments passed to '%s'",
				name);

		if (argc > f->max_args)
			pperror("too many function arguments passed to '%s'",
				name);

		return f->func(argc, argv);
	}

	return nullptr;
}

/*
 * Variables (and user-defined functions)
 */
struct variable {
	std::string name;
	std::string value;
	enum variable_flavor flavor;
	int exp_count = 0;
};

/* std::list: push_back does not invalidate pointers to existing elements */
static std::list<struct variable> variable_list;

static struct variable *variable_lookup(const char *name)
{
	for (struct variable &v : variable_list) {
		if (name == v.name)
			return &v;
	}

	return nullptr;
}

static char *variable_expand(const char *name, int argc, char *argv[])
{
	struct variable *v;
	char *res;

	v = variable_lookup(name);
	if (!v)
		return nullptr;

	if (argc == 0 && v->exp_count)
		pperror("Recursive variable '%s' references itself (eventually)",
			name);

	if (v->exp_count > 1000)
		pperror("Too deep recursive expansion");

	v->exp_count++;

	if (v->flavor == VAR_RECURSIVE)
		res = expand_string_with_args(v->value.c_str(), argc, argv);
	else
		res = xstrdup(v->value.c_str());

	v->exp_count--;

	return res;
}

void variable_add(const char *name, const char *value,
		  enum variable_flavor flavor)
{
	struct variable *v;
	std::string new_value;
	bool append = false;

	v = variable_lookup(name);
	if (v) {
		/* For defined variables, += inherits the existing flavor */
		if (flavor == VAR_APPEND) {
			flavor = v->flavor;
			append = true;
		}
	} else {
		/* For undefined variables, += assumes the recursive flavor */
		if (flavor == VAR_APPEND)
			flavor = VAR_RECURSIVE;

		variable_list.push_back({name, "", VAR_RECURSIVE, 0});
		v = &variable_list.back();
	}

	v->flavor = flavor;

	if (flavor == VAR_SIMPLE) {
		char *expanded = expand_string(value);

		new_value = expanded;
		free(expanded);
	} else {
		new_value = value;
	}

	if (append) {
		v->value += ' ';
		v->value += new_value;
	} else {
		v->value = std::move(new_value);
	}
}

void variable_all_del(void)
{
	variable_list.clear();
}

/*
 * Evaluate a clause with arguments.  argc/argv are arguments from the upper
 * function call.
 *
 * Returned string must be freed when done
 */
static char *eval_clause(const char *str, size_t len, int argc, char *argv[])
{
	char *tmp, *name, *res, *endptr, *prev, *p;
	int new_argc = 0;
	char *new_argv[FUNCTION_MAX_ARGS];
	int nest = 0;
	int i;
	unsigned long n;

	tmp = xstrndup(str, len);

	/*
	 * If variable name is '1', '2', etc.  It is generally an argument
	 * from a user-function call (i.e. local-scope variable).  If not
	 * available, then look-up global-scope variables.
	 */
	n = strtoul(tmp, &endptr, 10);
	if (!*endptr && n > 0 && n <= argc) {
		res = xstrdup(argv[n - 1]);
		goto free_tmp;
	}

	prev = p = tmp;

	/*
	 * Split into tokens
	 * The function name and arguments are separated by a comma.
	 * For example, if the function call is like this:
	 *   $(foo,$(x),$(y))
	 *
	 * The input string for this helper should be:
	 *   foo,$(x),$(y)
	 *
	 * and split into:
	 *   new_argv[0] = 'foo'
	 *   new_argv[1] = '$(x)'
	 *   new_argv[2] = '$(y)'
	 */
	while (*p) {
		if (nest == 0 && *p == ',') {
			*p = 0;
			if (new_argc >= FUNCTION_MAX_ARGS)
				pperror("too many function arguments");
			new_argv[new_argc++] = prev;
			prev = p + 1;
		} else if (*p == '(') {
			nest++;
		} else if (*p == ')') {
			nest--;
		}

		p++;
	}
	if (new_argc >= FUNCTION_MAX_ARGS)
		pperror("too many function arguments");
	new_argv[new_argc++] = prev;

	/*
	 * Shift arguments
	 * new_argv[0] represents a function name or a variable name.  Put it
	 * into 'name', then shift the rest of the arguments.  This simplifies
	 * 'const' handling.
	 */
	name = expand_string_with_args(new_argv[0], argc, argv);
	new_argc--;
	for (i = 0; i < new_argc; i++)
		new_argv[i] = expand_string_with_args(new_argv[i + 1],
						      argc, argv);

	/* Search for variables */
	res = variable_expand(name, new_argc, new_argv);
	if (res)
		goto free;

	/* Look for built-in functions */
	res = function_expand(name, new_argc, new_argv);
	if (res)
		goto free;

	/* Last, try environment variable */
	if (new_argc == 0) {
		res = env_expand(name);
		if (res)
			goto free;
	}

	res = xstrdup("");
free:
	for (i = 0; i < new_argc; i++)
		free(new_argv[i]);
	free(name);
free_tmp:
	free(tmp);

	return res;
}

/*
 * Expand a string that follows '$'
 *
 * For example, if the input string is
 *     ($(FOO)$($(BAR)))$(BAZ)
 * this helper evaluates
 *     $($(FOO)$($(BAR)))
 * and returns a new string containing the expansion (note that the string is
 * recursively expanded), also advancing 'str' to point to the next character
 * after the corresponding closing parenthesis, in this case, *str will be
 *     $(BAR)
 */
static char *expand_dollar_with_args(const char **str, int argc, char *argv[])
{
	const char *p = *str;
	const char *q;
	int nest = 0;

	/*
	 * In Kconfig, variable/function references always start with "$(".
	 * Neither single-letter variables as in $A nor curly braces as in ${CC}
	 * are supported.  '$' not followed by '(' loses its special meaning.
	 */
	if (*p != '(') {
		*str = p;
		return xstrdup("$");
	}

	p++;
	q = p;
	while (*q) {
		if (*q == '(') {
			nest++;
		} else if (*q == ')') {
			if (nest-- == 0)
				break;
		}
		q++;
	}

	if (!*q)
		pperror("unterminated reference to '%s': missing ')'", p);

	/* Advance 'str' to after the expanded initial portion of the string */
	*str = q + 1;

	return eval_clause(p, q - p, argc, argv);
}

char *expand_dollar(const char **str)
{
	return expand_dollar_with_args(str, 0, nullptr);
}

static char *__expand_string(const char **str, bool (*is_end)(char c),
			     int argc, char *argv[])
{
	const char *in, *p;
	std::string out;

	p = in = *str;

	while (1) {
		if (*p == '$') {
			char *expansion;

			out.append(in, p);
			p++;
			expansion = expand_dollar_with_args(&p, argc, argv);
			out += expansion;
			free(expansion);
			in = p;
			continue;
		}

		if (is_end(*p))
			break;

		p++;
	}

	out.append(in, p);

	/* Advance 'str' to the end character */
	*str = p;

	return xstrdup(out.c_str());
}

static bool is_end_of_str(char c)
{
	return !c;
}

/*
 * Expand variables and functions in the given string.  Undefined variables
 * expand to an empty string.
 * The returned string must be freed when done.
 */
static char *expand_string_with_args(const char *in, int argc, char *argv[])
{
	return __expand_string(&in, is_end_of_str, argc, argv);
}

static char *expand_string(const char *in)
{
	return expand_string_with_args(in, 0, nullptr);
}

static bool is_end_of_token(char c)
{
	return !(isalnum(static_cast<unsigned char>(c)) || c == '_' || c == '-');
}

/*
 * Expand variables in a token.  The parsing stops when a token separater
 * (in most cases, it is a whitespace) is encountered.  'str' is updated to
 * point to the next character.
 *
 * The returned string must be freed when done.
 */
char *expand_one_token(const char **str)
{
	return __expand_string(str, is_end_of_token, 0, nullptr);
}
