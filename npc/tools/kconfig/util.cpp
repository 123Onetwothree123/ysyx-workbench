module;
#ifdef __clang__
#else
#include <bits/stdc++.h>
#endif
#ifdef __clang__
#include "kconfig_clang_compat.h"
#endif
module kconfig.core;
#ifdef __clang__
import std;
#endif
using namespace std;

// SPDX-License-Identifier: GPL-2.0
/*
 * Copyright (C) 2002-2005 Roman Zippel <zippel@linux-m68k.org>
 * Copyright (C) 2002-2005 Sam Ravnborg <sam@ravnborg.org>
 */


namespace kconfig {

/* 按包含顺序（最旧在前）返回所有被解析的 Kconfig 文件 */
const std::vector<struct file *> &kconfig_files(void)
{
	return kconf.file_order;
}

/* file already present in list? If not add it */
struct file *file_lookup(const char *name, const char *parent_name,
			 int parent_lineno)
{
	auto it = kconf.file_index.find(name);
	if (it != kconf.file_index.end()) {
		struct file *file = it->second;

		if (!parent_name)
			return file;

		/* 同一文件被重复包含（对齐新版 Linux：直接报错） */
		fprintf(stderr,
			"%s:%d: error: repeated inclusion of %s\n",
			parent_name, parent_lineno, file->name);
		if (file->parent_name)
			fprintf(stderr,
				"%s:%d: note: location of first inclusion of %s\n",
				file->parent_name, file->parent_lineno,
				file->name);
		throw FatalError();
	}

	auto owned = std::make_unique<struct file>();
	owned->name = xstrdup(name);
	owned->parent_name = parent_name ? xstrdup(parent_name) : nullptr;
	owned->parent_lineno = parent_lineno;

	struct file *file = owned.get();
	kconf.files.push_back(std::move(owned));
	kconf.file_index.emplace(file->name, file);
	kconf.file_order.push_back(file);
	return file;
}

/* Allocate initial growable string */
struct gstr str_new(void)
{
	return {};
}

/* Free storage for growable string（RAII 后为无操作，保留 API 兼容） */
void str_free(struct gstr *gs)
{
	gs->s.clear();
	gs->max_width = 0;
}

/* Append to growable string */
void str_append(struct gstr *gs, const char *s)
{
	if (s)
		gs->s += s;
}

/* Append std::string to growable string */
void str_append(struct gstr *gs, const std::string &s)
{
	gs->s += s;
}

/* Append printf formatted string to growable string */
void str_printf(struct gstr *gs, const char *fmt, ...)
{
	va_list ap;
	int len;

	va_start(ap, fmt);
	len = vsnprintf(nullptr, 0, fmt, ap);
	va_end(ap);
	if (len < 0)
		return;

	const size_t old = gs->s.size();
	gs->s.resize(old + len + 1);
	va_start(ap, fmt);
	vsnprintf(gs->s.data() + old, len + 1, fmt, ap);
	va_end(ap);
	gs->s.resize(old + len); /* 去掉 vsnprintf 写入的结尾 '\0' */
}

/* Retrieve value of growable string */
const char *str_get(struct gstr *gs)
{
	return gs->s.c_str();
}

[[nodiscard]] void *xmalloc(size_t size)
{
	void *p = malloc(size);
	if (p)
		return p;
	fprintf(stderr, "Out of memory.\n");
	throw FatalError();
}

[[nodiscard]] void *xcalloc(size_t nmemb, size_t size)
{
	void *p = calloc(nmemb, size);
	if (p)
		return p;
	fprintf(stderr, "Out of memory.\n");
	throw FatalError();
}

[[nodiscard]] void *xrealloc(void *p, size_t size)
{
	p = realloc(p, size);
	if (p)
		return p;
	fprintf(stderr, "Out of memory.\n");
	throw FatalError();
}

[[nodiscard]] char *xstrdup(const char *s)
{
	char *p;

	p = strdup(s);
	if (p)
		return p;
	fprintf(stderr, "Out of memory.\n");
	throw FatalError();
}

[[nodiscard]] char *xstrndup(const char *s, size_t n)
{
	char *p;

	p = strndup(s, n);
	if (p)
		return p;
	fprintf(stderr, "Out of memory.\n");
	throw FatalError();
}
int yydebug = 0;

} /* namespace kconfig */
