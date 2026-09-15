#ifdef __clang__
import std;
#else
#include <bits/stdc++.h>
#endif
using namespace std;

// SPDX-License-Identifier: GPL-2.0
/*
 * Copyright (C) 2002-2005 Roman Zippel <zippel@linux-m68k.org>
 * Copyright (C) 2002-2005 Sam Ravnborg <sam@ravnborg.org>
 */

#include "lkc.hpp"

/* file already present in list? If not add it */
struct file *file_lookup(const char *name)
{
	struct file *file;

	for (file = file_list; file; file = file->next) {
		if (!strcmp(name, file->name)) {
			return file;
		}
	}

	file = static_cast<struct file*>(xmalloc(sizeof(*file)));
	memset(file, 0, sizeof(*file));
	file->name = xstrdup(name);
	file->next = file_list;
	file_list = file;
	return file;
}

/* Allocate initial growable string */
struct gstr str_new(void)
{
	struct gstr gs;
	gs.s = static_cast<char*>(xmalloc(sizeof(char) * 64));
	gs.len = 64;
	gs.max_width = 0;
	gs.s[0] = '\0';
	return gs;
}

/* Free storage for growable string */
void str_free(struct gstr *gs)
{
	if (gs->s)
		free(gs->s);
	gs->s = nullptr;
	gs->len = 0;
}

/* Append to growable string */
void str_append(struct gstr *gs, const char *s)
{
	size_t l;
	if (s) {
		l = strlen(gs->s) + strlen(s) + 1;
		if (l > gs->len) {
			gs->s = static_cast<char*>(xrealloc(gs->s, l));
			gs->len = l;
		}
		strcat(gs->s, s);
	}
}

/* Append printf formatted string to growable string */
void str_printf(struct gstr *gs, const char *fmt, ...)
{
	va_list ap, aq;
	int len;

	va_start(ap, fmt);
	va_copy(aq, ap);
	len = vsnprintf(nullptr, 0, fmt, ap);
	va_end(ap);
	if (len < 0) {
		va_end(aq);
		return;
	}

	std::string s(len, '\0');
	vsnprintf(s.data(), len + 1, fmt, aq);
	va_end(aq);

	str_append(gs, s.c_str());
}

/* Retrieve value of growable string */
const char *str_get(struct gstr *gs)
{
	return gs->s;
}

[[nodiscard]] void *xmalloc(size_t size)
{
	void *p = malloc(size);
	if (p)
		return p;
	fprintf(stderr, "Out of memory.\n");
	exit(1);
}

[[nodiscard]] void *xcalloc(size_t nmemb, size_t size)
{
	void *p = calloc(nmemb, size);
	if (p)
		return p;
	fprintf(stderr, "Out of memory.\n");
	exit(1);
}

[[nodiscard]] void *xrealloc(void *p, size_t size)
{
	p = realloc(p, size);
	if (p)
		return p;
	fprintf(stderr, "Out of memory.\n");
	exit(1);
}

[[nodiscard]] char *xstrdup(const char *s)
{
	char *p;

	p = strdup(s);
	if (p)
		return p;
	fprintf(stderr, "Out of memory.\n");
	exit(1);
}

[[nodiscard]] char *xstrndup(const char *s, size_t n)
{
	char *p;

	p = strndup(s, n);
	if (p)
		return p;
	fprintf(stderr, "Out of memory.\n");
	exit(1);
}
int yydebug = 0;
