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

#include <fcntl.h>
#include <unistd.h>

#include "lkc.hpp"

/* return true if 'path' exists, false otherwise */
static bool is_present(const char *path)
{
	std::error_code ec;

	return std::filesystem::exists(path, ec);
}

/* return true if 'path' exists and it is a directory, false otherwise */
static bool is_dir(const char *path)
{
	std::error_code ec;

	return std::filesystem::is_directory(path, ec);
}

/* return true if the given two files are the same, false otherwise */
static bool is_same(const char *file1, const char *file2)
{
	std::ifstream f1(file1, std::ios::binary);
	if (!f1.is_open())
		return false;

	std::ifstream f2(file2, std::ios::binary);
	if (!f2.is_open())
		return false;

	std::string s1((std::istreambuf_iterator<char>(f1)),
		       std::istreambuf_iterator<char>());
	std::string s2((std::istreambuf_iterator<char>(f2)),
		       std::istreambuf_iterator<char>());

	return s1 == s2;
}

/*
 * Create the parent directory of the given path.
 *
 * For example, if 'include/config/auto.conf' is given, create 'include/config'.
 */
static int make_parent_dir(const char *path)
{
	std::filesystem::path parent = std::filesystem::path(path).parent_path();

	/* Just return if there is no parent directory to create */
	if (parent.empty())
		return 0;

	std::error_code ec;
	std::filesystem::create_directories(parent, ec);

	return ec ? -1 : 0;
}

static char depfile_path[PATH_MAX];
static size_t depfile_prefix_len;

/* touch depfile for symbol 'name' */
static int conf_touch_dep(const char *name)
{
	int fd, ret;
	const char *s;
	char *d, c;

	/* check overflow: prefix + name + ".h" + '\0' must fit in buffer. */
	if (depfile_prefix_len + strlen(name) + 3 > sizeof(depfile_path))
		return -1;

	d = depfile_path + depfile_prefix_len;
	s = name;

	while ((c = *s++))
		*d++ = (c == '_') ? '/' : tolower(static_cast<unsigned char>(c));
	strcpy(d, ".h");

	/* Assume directory path already exists. */
	fd = open(depfile_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
	if (fd == -1) {
		if (errno != ENOENT)
			return -1;

		ret = make_parent_dir(depfile_path);
		if (ret)
			return ret;

		/* Try it again. */
		fd = open(depfile_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
		if (fd == -1)
			return -1;
	}
	close(fd);

	return 0;
}

struct conf_printer {
	void (*print_symbol)(FILE *, struct symbol *, const char *, bool);
	void (*print_comment)(FILE *, const char *, bool);
};

static void conf_warning(const char *fmt, ...)
	__attribute__ ((format (printf, 1, 2)));

static void conf_message(const char *fmt, ...)
	__attribute__ ((format (printf, 1, 2)));

static const char *conf_filename;
static int conf_lineno, conf_warnings;

static void conf_warning(const char *fmt, ...)
{
	va_list ap;
	va_start(ap, fmt);
	fprintf(stderr, "%s:%d:warning: ", conf_filename, conf_lineno);
	vfprintf(stderr, fmt, ap);
	fprintf(stderr, "\n");
	va_end(ap);
	conf_warnings++;
}

static void conf_default_message_callback(const char *s)
{
	printf("#\n# ");
	printf("%s", s);
	printf("\n#\n");
}

static void (*conf_message_callback)(const char *s) =
	conf_default_message_callback;
void conf_set_message_callback(void (*fn)(const char *s))
{
	conf_message_callback = fn;
}

static void conf_message(const char *fmt, ...)
{
	va_list ap, ap2;
	int len;

	if (!conf_message_callback)
		return;

	va_start(ap, fmt);
	va_copy(ap2, ap);
	len = vsnprintf(nullptr, 0, fmt, ap2);
	va_end(ap2);
	if (len < 0) {
		va_end(ap);
		return;
	}

	std::string buf(len, '\0');
	vsnprintf(buf.data(), buf.size() + 1, fmt, ap);
	va_end(ap);

	conf_message_callback(buf.c_str());
}

static bool changed_input_warning_suppressed;

/* randconfig 的"用户值"是随机赋的，被约束改写属预期，不属于
 * "用户提供的值被改写"，调用方（conf.cpp randconfig 分支）用此关闭告警 */
void conf_suppress_changed_input_warning(void)
{
	changed_input_warning_suppressed = true;
}

static void conf_changed_input_warning(const char *s)
{
	if (changed_input_warning_suppressed)
		return;
	fputs(s, stderr);
}

static const char *sym_get_user_value_string(struct symbol *sym)
{
	switch (sym->type) {
	case S_BOOLEAN:
	case S_TRISTATE:
		switch (sym->def[S_DEF_USER].tri) {
		case yes:
			return "y";
		case mod:
			return "m";
		default:
			return "n";
		}
	default:
		return sym->def[S_DEF_USER].val.str ?
			sym->def[S_DEF_USER].val.str : "";
	}
}

static bool sym_user_value_changed(struct symbol *sym)
{
	if (!sym_has_value(sym) || sym->type == S_UNKNOWN)
		return false;

	switch (sym->type) {
	case S_BOOLEAN:
	case S_TRISTATE:
		return sym->def[S_DEF_USER].tri != sym_get_tristate_value(sym);
	default:
		return strcmp(sym_get_user_value_string(sym),
			      sym_get_string_value(sym)) != 0;
	}
}

static void conf_clear_written_flags(void)
{
	struct symbol *sym;
	int i;

	for_all_symbols(i, sym)
		sym->flags &= ~SYMBOL_WRITTEN;
}

static void conf_append_changed_input_warning(std::string &msg,
					      struct symbol *sym,
					      bool &changed_input_found)
{
	if (!sym_user_value_changed(sym))
		return;

	if (!changed_input_found) {
		msg += "warning: user-provided values changed by Kconfig:\n";
		changed_input_found = true;
	}

	msg += std::string("  ") + CONFIG_ + sym->name + ": " +
	       sym_get_user_value_string(sym) + " -> " +
	       sym_get_string_value(sym) + "\n";
}

const char *conf_get_configname(void)
{
	char *name = getenv("KCONFIG_CONFIG");

	return name ? name : ".config";
}

static const char *conf_get_autoconfig_name(void)
{
	char *name = getenv("KCONFIG_AUTOCONFIG");

	return name ? name : "include/config/auto.conf";
}

static int conf_set_sym_val(struct symbol *sym, int def, int def_flags, char *p)
{
	char *p2;

	switch (sym->type) {
	case S_TRISTATE:
		if (p[0] == 'm') {
			sym->def[def].tri = mod;
			sym->flags |= def_flags;
			break;
		}
		/* fall through */
	case S_BOOLEAN:
		if (p[0] == 'y') {
			sym->def[def].tri = yes;
			sym->flags |= def_flags;
			break;
		}
		if (p[0] == 'n') {
			sym->def[def].tri = no;
			sym->flags |= def_flags;
			break;
		}
		if (def != S_DEF_AUTO)
			conf_warning("symbol value '%s' invalid for %s",
				     p, sym->name);
		return 1;
	case S_STRING:
		if (*p++ != '"')
			break;
		for (p2 = p; (p2 = strpbrk(p2, "\"\\")); p2++) {
			if (*p2 == '"') {
				*p2 = 0;
				break;
			}
			memmove(p2, p2 + 1, strlen(p2));
		}
		if (!p2) {
			if (def != S_DEF_AUTO)
				conf_warning("invalid string found");
			return 1;
		}
		/* fall through */
	case S_INT:
	case S_HEX:
		if (sym_string_valid(sym, p)) {
			sym->def[def].val.str = xstrdup(p);
			sym->flags |= def_flags;
		} else {
			if (def != S_DEF_AUTO)
				conf_warning("symbol value '%s' invalid for %s",
					     p, sym->name);
			return 1;
		}
		break;
	default:
		;
	}
	return 0;
}

/*
 * Try to open the specified file with the following names:
 * ./name
 * $(srctree)/name
 * (mirrors zconf_fopen() in the lexer)
 */
static std::ifstream conf_open_file(const char *name)
{
	std::ifstream f(name);

	if (!f.is_open() && name && name[0] != '/') {
		const char *env = getenv(SRCTREE);
		if (env)
			f.open(std::string(env) + "/" + name);
	}

	return f;
}

int conf_read_simple(const char *name, int def)
{
	std::ifstream in;
	std::string line;
	char *lp, *p;
	struct symbol *sym;
	int i, def_flags;

	if (name) {
		in = conf_open_file(name);
	} else {
		struct property *prop;

		name = conf_get_configname();
		in = conf_open_file(name);
		if (in.is_open())
			goto load;
		sym_add_change_count(1);
		if (!sym_defconfig_list)
			return 1;

		for_all_defaults(sym_defconfig_list, prop) {
			if (expr_calc_value(prop->visible.expr) == no ||
			    prop->expr->type != E_SYMBOL)
				continue;
			sym_calc_value(prop->expr->left.sym);
			name = sym_get_string_value(prop->expr->left.sym);
			in = conf_open_file(name);
			if (in.is_open()) {
				conf_message("using defaults found in %s",
					 name);
				goto load;
			}
		}
	}
	if (!in.is_open())
		return 1;

load:
	conf_filename = name;
	conf_lineno = 0;
	conf_warnings = 0;

	def_flags = SYMBOL_DEF << def;
	for_all_symbols(i, sym) {
		sym->flags |= SYMBOL_CHANGED;
		sym->flags &= ~(def_flags|SYMBOL_VALID);
		if (sym_is_choice(sym))
			sym->flags |= def_flags;
		switch (sym->type) {
		case S_INT:
		case S_HEX:
		case S_STRING:
			if (sym->def[def].val.str)
				/* allocated by xstrdup() in conf_set_sym_val() */
				free(const_cast<char *>(sym->def[def].val.str));
			/* fall through */
		default:
			sym->def[def].val.str = nullptr;
			sym->def[def].tri = no;
		}
	}

	while (std::getline(in, line)) {
		conf_lineno++;
		/* tolerate CRLF line endings */
		if (!line.empty() && line.back() == '\r')
			line.pop_back();
		lp = line.data();
		sym = nullptr;
		if (lp[0] == '#') {
			if (line.size() < 2 + strlen(CONFIG_) ||
			    memcmp(lp + 2, CONFIG_, strlen(CONFIG_)))
				continue;
			p = strchr(lp + 2 + strlen(CONFIG_), ' ');
			if (!p)
				continue;
			*p++ = 0;
			if (strncmp(p, "is not set", 10))
				continue;
			if (def == S_DEF_USER) {
				sym = sym_find(lp + 2 + strlen(CONFIG_));
				if (!sym) {
					sym_add_change_count(1);
					continue;
				}
			} else {
				sym = sym_lookup(lp + 2 + strlen(CONFIG_), 0);
				if (sym->type == S_UNKNOWN)
					sym->type = S_BOOLEAN;
			}
			if (sym->flags & def_flags) {
				conf_warning("override: reassigning to symbol %s", sym->name);
			}
			switch (sym->type) {
			case S_BOOLEAN:
			case S_TRISTATE:
				sym->def[def].tri = no;
				sym->flags |= def_flags;
				break;
			default:
				;
			}
		} else if (line.size() >= strlen(CONFIG_) &&
			   !memcmp(lp, CONFIG_, strlen(CONFIG_))) {
			p = strchr(lp + strlen(CONFIG_), '=');
			if (!p)
				continue;
			*p++ = 0;

			sym = sym_find(lp + strlen(CONFIG_));
			if (!sym) {
				if (def == S_DEF_AUTO)
					/*
					 * Reading from include/config/auto.conf
					 * If CONFIG_FOO previously existed in
					 * auto.conf but it is missing now,
					 * include/config/foo.h must be touched.
					 */
					conf_touch_dep(lp + strlen(CONFIG_));
				else
					sym_add_change_count(1);
				continue;
			}

			if (sym->flags & def_flags) {
				conf_warning("override: reassigning to symbol %s", sym->name);
			}
			if (conf_set_sym_val(sym, def, def_flags, p))
				continue;
		} else {
			if (lp[0] != '\0')
				conf_warning("unexpected data: %s", lp);

			continue;
		}

		if (sym && sym_is_choice_value(sym)) {
			struct symbol *cs = prop_get_symbol(sym_get_choice_prop(sym));
			switch (sym->def[def].tri) {
			case no:
				break;
			case mod:
				if (cs->def[def].tri == yes) {
					conf_warning("%s creates inconsistent choice state", sym->name);
					cs->flags &= ~def_flags;
				}
				break;
			case yes:
				if (cs->def[def].tri != no)
					conf_warning("override: %s changes choice state", sym->name);
				cs->def[def].val.sym = sym;
				break;
			}
			cs->def[def].tri = EXPR_OR(cs->def[def].tri, sym->def[def].tri);
		}
	}
	return 0;
}

int conf_read(const char *name)
{
	struct symbol *sym;
	int conf_unsaved = 0;
	int i;

	sym_set_change_count(0);

	if (conf_read_simple(name, S_DEF_USER)) {
		sym_calc_value(modules_sym);
		return 1;
	}

	sym_calc_value(modules_sym);

	for_all_symbols(i, sym) {
		sym_calc_value(sym);
		if (sym_is_choice(sym) || (sym->flags & SYMBOL_NO_WRITE))
			continue;
		if (sym_has_value(sym) && (sym->flags & SYMBOL_WRITE)) {
			/* check that calculated value agrees with saved value */
			switch (sym->type) {
			case S_BOOLEAN:
			case S_TRISTATE:
				if (sym->def[S_DEF_USER].tri == sym_get_tristate_value(sym))
					continue;
				break;
			default:
				if (!strcmp(sym->curr.val.str, sym->def[S_DEF_USER].val.str))
					continue;
				break;
			}
		} else if (!sym_has_value(sym) && !(sym->flags & SYMBOL_WRITE))
			/* no previous value and not saved */
			continue;
		conf_unsaved++;
		/* maybe print value in verbose mode... */
	}

	for_all_symbols(i, sym) {
		if (sym_has_value(sym) && !sym_is_choice_value(sym)) {
			/* Reset values of generates values, so they'll appear
			 * as new, if they should become visible, but that
			 * doesn't quite work if the Kconfig and the saved
			 * configuration disagree.
			 */
			if (sym->visible == no && !conf_unsaved)
				sym->flags &= ~SYMBOL_DEF_USER;
			switch (sym->type) {
			case S_STRING:
			case S_INT:
			case S_HEX:
				/* Reset a string value if it's out of range */
				if (sym_string_within_range(sym, sym->def[S_DEF_USER].val.str))
					break;
				sym->flags &= ~(SYMBOL_VALID|SYMBOL_DEF_USER);
				conf_unsaved++;
				break;
			default:
				break;
			}
		}
	}

	sym_add_change_count(conf_warnings || conf_unsaved);

	return 0;
}

/*
 * Kconfig configuration printer
 *
 * This printer is used when generating the resulting configuration after
 * kconfig invocation and `defconfig' files. Unset symbol might be omitted by
 * passing skip_unset=true to the printer.
 *
 */
static void
kconfig_print_symbol(FILE *fp, struct symbol *sym, const char *value, bool skip_unset)
{

	switch (sym->type) {
	case S_BOOLEAN:
	case S_TRISTATE:
		if (*value == 'n') {
			if (!skip_unset)
				fprintf(fp, "# %s%s is not set\n",
				    CONFIG_, sym->name);
			return;
		}
		break;
	default:
		break;
	}

	fprintf(fp, "%s%s=%s\n", CONFIG_, sym->name, value);
}

static void
kconfig_print_comment(FILE *fp, const char *value, bool skip_unset)
{
	const char *p = value;
	size_t l;

	for (;;) {
		l = strcspn(p, "\n");
		fprintf(fp, "#");
		if (l) {
			fprintf(fp, " ");
			xfwrite(p, l, 1, fp);
			p += l;
		}
		fprintf(fp, "\n");
		if (*p++ == '\0')
			break;
	}
}

static struct conf_printer kconfig_printer_cb =
{
	.print_symbol = kconfig_print_symbol,
	.print_comment = kconfig_print_comment,
};

/*
 * Header printer
 *
 * This printer is used when generating the `include/generated/autoconf.h' file.
 */
static void
header_print_symbol(FILE *fp, struct symbol *sym, const char *value, bool skip_unset)
{

	switch (sym->type) {
	case S_BOOLEAN:
	case S_TRISTATE: {
		const char *suffix = "";

		switch (*value) {
		case 'n':
			break;
		case 'm':
			suffix = "_MODULE";
			/* fall through */
		default:
			fprintf(fp, "#define %s%s%s 1\n",
			    CONFIG_, sym->name, suffix);
		}
		break;
	}
	case S_HEX: {
		const char *prefix = "";

		if (value[0] != '0' || (value[1] != 'x' && value[1] != 'X'))
			prefix = "0x";
		fprintf(fp, "#define %s%s %s%s\n",
		    CONFIG_, sym->name, prefix, value);
		break;
	}
	case S_STRING:
	case S_INT:
		fprintf(fp, "#define %s%s %s\n",
		    CONFIG_, sym->name, value);
		break;
	default:
		break;
	}

}

static void
header_print_comment(FILE *fp, const char *value, bool skip_unset)
{
	const char *p = value;
	size_t l;

	fprintf(fp, "/*\n");
	for (;;) {
		l = strcspn(p, "\n");
		fprintf(fp, " *");
		if (l) {
			fprintf(fp, " ");
			xfwrite(p, l, 1, fp);
			p += l;
		}
		fprintf(fp, "\n");
		if (*p++ == '\0')
			break;
	}
	fprintf(fp, " */\n");
}

static struct conf_printer header_printer_cb =
{
	.print_symbol = header_print_symbol,
	.print_comment = header_print_comment,
};

static void conf_write_symbol(FILE *fp, struct symbol *sym,
			      struct conf_printer *printer, bool skip_unset)
{
	const char *str;

	switch (sym->type) {
	case S_UNKNOWN:
		break;
	case S_STRING: {
		const std::string escaped =
			sym_escape_string_value(sym_get_string_value(sym));

		printer->print_symbol(fp, sym, escaped.c_str(), skip_unset);
		break;
	}
	default:
		str = sym_get_string_value(sym);
		printer->print_symbol(fp, sym, str, skip_unset);
	}
}

static void
conf_write_heading(FILE *fp, struct conf_printer *printer)
{
	char buf[256];

	snprintf(buf, sizeof(buf),
	    "\n"
	    "Automatically generated file; DO NOT EDIT.\n"
	    "%s\n",
	    rootmenu.prompt->text);

	printer->print_comment(fp, buf, false);
}

/*
 * Write out a minimal config.
 * All values that has default values are skipped as this is redundant.
 */
int conf_write_defconfig(const char *filename)
{
	struct symbol *sym;
	struct menu *menu;
	FILE *out;
	bool changed_input_found = false;
	std::string changed_input_warning;

	out = fopen(filename, "w");
	if (!out)
		return 1;

	sym_clear_all_valid();

	/* Traverse all menus to find all relevant symbols */
	menu = rootmenu.list;

	while (menu != nullptr)
	{
		sym = menu->sym;
		if (sym == nullptr) {
			if (!menu_is_visible(menu))
				goto next_menu;
		} else if (!sym_is_choice(sym) && !(sym->flags & SYMBOL_WRITTEN)) {
			sym_calc_value(sym);
			conf_append_changed_input_warning(changed_input_warning,
							  sym, changed_input_found);
			sym->flags |= SYMBOL_WRITTEN;
			if (!(sym->flags & SYMBOL_WRITE))
				goto next_menu;
			sym->flags &= ~SYMBOL_WRITE;
			/* If we cannot change the symbol - skip */
			if (!sym_is_changeable(sym))
				goto next_menu;
			/* If symbol equals to default value - skip */
			if (strcmp(sym_get_string_value(sym), sym_get_string_default(sym)) == 0)
				goto next_menu;

			/*
			 * If symbol is a choice value and equals to the
			 * default for a choice - skip.
			 * But only if value is bool and equal to "y" and
			 * choice is not "optional".
			 * (If choice is "optional" then all values can be "n")
			 */
			if (sym_is_choice_value(sym)) {
				struct symbol *cs;
				struct symbol *ds;

				cs = prop_get_symbol(sym_get_choice_prop(sym));
				ds = sym_choice_default(cs);
				if (!sym_is_optional(cs) && sym == ds) {
					if ((sym->type == S_BOOLEAN) &&
					    sym_get_tristate_value(sym) == yes)
						goto next_menu;
				}
			}
			conf_write_symbol(out, sym, &kconfig_printer_cb, false);
		}
next_menu:
		if (menu->list != nullptr) {
			menu = menu->list;
		}
		else if (menu->next != nullptr) {
			menu = menu->next;
		} else {
			while ((menu = menu->parent)) {
				if (menu->next != nullptr) {
					menu = menu->next;
					break;
				}
			}
		}
	}
	fclose(out);

	conf_clear_written_flags();

	if (changed_input_found)
		conf_changed_input_warning(changed_input_warning.c_str());

	return 0;
}

int conf_write(const char *name)
{
	FILE *out;
	struct symbol *sym;
	struct menu *menu;
	const char *str;
	char tmpname[PATH_MAX + 1], oldname[PATH_MAX + 1];
	char *env;
	bool need_newline = false;
	bool changed_input_found = false;
	std::string changed_input_warning;

	if (!name)
		name = conf_get_configname();

	if (!*name) {
		fprintf(stderr, "config name is empty\n");
		return 1;
	}

	if (is_dir(name)) {
		fprintf(stderr, "%s: Is a directory\n", name);
		return 1;
	}

	if (make_parent_dir(name)) {
		fprintf(stderr, "cannot create parent directory of %s\n", name);
		return 1;
	}

	env = getenv("KCONFIG_OVERWRITECONFIG");
	if (env && *env) {
		*tmpname = 0;
		out = fopen(name, "w");
	} else {
		snprintf(tmpname, sizeof(tmpname), "%s.%d.tmp",
			 name, (int)getpid());
		out = fopen(tmpname, "w");
	}
	if (!out)
		return 1;

	conf_write_heading(out, &kconfig_printer_cb);

	if (!conf_get_changed())
		sym_clear_all_valid();

	menu = rootmenu.list;
	while (menu) {
		sym = menu->sym;
		if (!sym) {
			if (!menu_is_visible(menu))
				goto next;
			str = menu_get_prompt(menu);
			fprintf(out, "\n"
				     "#\n"
				     "# %s\n"
				     "#\n", str);
			need_newline = false;
		} else if (!(sym->flags & SYMBOL_CHOICE) &&
			   !(sym->flags & SYMBOL_WRITTEN)) {
			sym_calc_value(sym);
			conf_append_changed_input_warning(changed_input_warning,
							  sym, changed_input_found);
			sym->flags |= SYMBOL_WRITTEN;
			if (!(sym->flags & SYMBOL_WRITE))
				goto next;
			if (need_newline) {
				fprintf(out, "\n");
				need_newline = false;
			}
			conf_write_symbol(out, sym, &kconfig_printer_cb, false);
		}

next:
		if (menu->list) {
			menu = menu->list;
			continue;
		}
		if (menu->next)
			menu = menu->next;
		else while ((menu = menu->parent)) {
			if (!menu->sym && menu_is_visible(menu) &&
			    menu != &rootmenu) {
				str = menu_get_prompt(menu);
				fprintf(out, "# end of %s\n", str);
				need_newline = true;
			}
			if (menu->next) {
				menu = menu->next;
				break;
			}
		}
	}
	fclose(out);

	conf_clear_written_flags();

	if (changed_input_found)
		conf_changed_input_warning(changed_input_warning.c_str());

	if (*tmpname) {
		if (::is_same(name, tmpname)) {
			conf_message("No change to %s", name);
			unlink(tmpname);
			sym_set_change_count(0);
			return 0;
		}

		snprintf(oldname, sizeof(oldname), "%s.old", name);
		if (rename(name, oldname) && errno != ENOENT)
			fprintf(stderr, "warning: cannot back up %s to %s: %s\n",
				name, oldname, strerror(errno));
		if (rename(tmpname, name)) {
			fprintf(stderr, "cannot rename %s to %s: %s\n",
				tmpname, name, strerror(errno));
			unlink(tmpname);
			return 1;
		}
	}

	conf_message("configuration written to %s", name);

	sym_set_change_count(0);

	return 0;
}

/* write a dependency file as used by kbuild to track dependencies */
static int conf_write_dep(const char *name)
{
	struct file *file;
	FILE *out;

	out = fopen("..config.tmp", "w");
	if (!out)
		return 1;
	fprintf(out, "deps_config := \\\n");
	for (file = file_list; file; file = file->next) {
		if (file->next)
			fprintf(out, "\t%s \\\n", file->name);
		else
			fprintf(out, "\t%s\n", file->name);
	}
	fprintf(out, "\n%s: \\\n"
		     "\t$(deps_config)\n\n", conf_get_autoconfig_name());

	env_write_dep(out, conf_get_autoconfig_name());

	fprintf(out, "\n$(deps_config): ;\n");
	fclose(out);

	if (make_parent_dir(name)) {
		remove("..config.tmp");
		return 1;
	}
	if (rename("..config.tmp", name)) {
		remove("..config.tmp");
		return 1;
	}
	return 0;
}

static int conf_touch_deps(void)
{
	const char *name;
	struct symbol *sym;
	int res, i;

	name = conf_get_autoconfig_name();

	/* derive the depfile directory from the autoconfig path */
	std::string prefix = name;
	size_t slash = prefix.find_last_of('/');
	if (slash != std::string::npos)
		prefix.resize(slash + 1);
	else
		prefix.clear();

	if (prefix.size() >= sizeof(depfile_path))
		return -1;
	strcpy(depfile_path, prefix.c_str());
	depfile_prefix_len = strlen(depfile_path);

	conf_read_simple(name, S_DEF_AUTO);
	sym_calc_value(modules_sym);

	for_all_symbols(i, sym) {
		sym_calc_value(sym);
		if ((sym->flags & SYMBOL_NO_WRITE) || !sym->name)
			continue;
		if (sym->flags & SYMBOL_WRITE) {
			if (sym->flags & SYMBOL_DEF_AUTO) {
				/*
				 * symbol has old and new value,
				 * so compare them...
				 */
				switch (sym->type) {
				case S_BOOLEAN:
				case S_TRISTATE:
					if (sym_get_tristate_value(sym) ==
					    sym->def[S_DEF_AUTO].tri)
						continue;
					break;
				case S_STRING:
				case S_HEX:
				case S_INT:
					if (!strcmp(sym_get_string_value(sym),
						    sym->def[S_DEF_AUTO].val.str))
						continue;
					break;
				default:
					break;
				}
			} else {
				/*
				 * If there is no old value, only 'no' (unset)
				 * is allowed as new value.
				 */
				switch (sym->type) {
				case S_BOOLEAN:
				case S_TRISTATE:
					if (sym_get_tristate_value(sym) == no)
						continue;
					break;
				default:
					break;
				}
			}
		} else if (!(sym->flags & SYMBOL_DEF_AUTO))
			/* There is neither an old nor a new value. */
			continue;
		/* else
		 *	There is an old value, but no new value ('no' (unset)
		 *	isn't saved in auto.conf, so the old value is always
		 *	different from 'no').
		 */

		res = conf_touch_dep(sym->name);
		if (res)
			return res;
	}

	return 0;
}

int conf_write_autoconf(int overwrite)
{
	struct symbol *sym;
	const char *name;
	const char *autoconf_name = conf_get_autoconfig_name();
	FILE *out, *out_h;
	int i;

	if (!overwrite && is_present(autoconf_name))
		return 0;

	conf_write_dep("include/config/auto.conf.cmd");

	if (conf_touch_deps())
		return 1;

	out = fopen(".tmpconfig", "w");
	if (!out)
		return 1;

	out_h = fopen(".tmpconfig.h", "w");
	if (!out_h) {
		fclose(out);
		remove(".tmpconfig");
		return 1;
	}

	conf_write_heading(out, &kconfig_printer_cb);
	conf_write_heading(out_h, &header_printer_cb);

	for_all_symbols(i, sym) {
		sym_calc_value(sym);
		if (!(sym->flags & SYMBOL_WRITE) || !sym->name)
			continue;

		/* write symbols to auto.conf and autoconf.h */
		conf_write_symbol(out, sym, &kconfig_printer_cb, true);
		conf_write_symbol(out_h, sym, &header_printer_cb, false);
	}
	fclose(out);
	fclose(out_h);

	name = getenv("KCONFIG_AUTOHEADER");
	if (!name)
		name = "include/generated/autoconf.h";
	if (make_parent_dir(name)) {
		remove(".tmpconfig");
		remove(".tmpconfig.h");
		return 1;
	}
	if (rename(".tmpconfig.h", name)) {
		remove(".tmpconfig");
		remove(".tmpconfig.h");
		return 1;
	}

	if (make_parent_dir(autoconf_name)) {
		remove(".tmpconfig");
		return 1;
	}
	/*
	 * This must be the last step, kbuild has a dependency on auto.conf
	 * and this marks the successful completion of the previous steps.
	 */
	if (rename(".tmpconfig", autoconf_name)) {
		remove(".tmpconfig");
		return 1;
	}

	return 0;
}

static int sym_change_count;
static void (*conf_changed_callback)(void);

void sym_set_change_count(int count)
{
	int _sym_change_count = sym_change_count;
	sym_change_count = count;
	if (conf_changed_callback &&
	    (bool)_sym_change_count != (bool)count)
		conf_changed_callback();
}

void sym_add_change_count(int count)
{
	sym_set_change_count(count + sym_change_count);
}

bool conf_get_changed(void)
{
	return sym_change_count;
}

void conf_set_changed_callback(void (*fn)(void))
{
	conf_changed_callback = fn;
}

static std::mt19937 &conf_rng(void)
{
	static std::mt19937 gen(std::random_device{}());

	return gen;
}

/*
 * Seed the PRNG used by randconfig, following upstream
 * set_randconfig_seed() semantics: use $KCONFIG_SEED (parsed with
 * strtol base 0) if it is valid, otherwise keep a non-deterministic
 * seed. The seed actually used is always printed to stdout. Called
 * once per randconfig run from conf.cpp, so the message appears only
 * for randconfig, as upstream does.
 */
void conf_set_randconfig_seed(void)
{
	unsigned int seed;
	const char *env = getenv("KCONFIG_SEED");
	bool seed_set = false;

	if (env && *env) {
		char *endp;

		seed = static_cast<unsigned int>(strtol(env, &endp, 0));
		if (*endp == '\0')
			seed_set = true;
	}

	if (!seed_set)
		seed = std::random_device{}();

	printf("KCONFIG_SEED=0x%X\n", seed);
	conf_rng().seed(seed);
}

static bool randomize_choice_values(struct symbol *csym)
{
	struct property *prop;
	struct symbol *sym;
	struct expr *e;
	int cnt, def;

	/*
	 * If choice is mod then we may have more items selected
	 * and if no then no-one.
	 * In both cases stop.
	 */
	if (csym->curr.tri != yes)
		return false;

	prop = sym_get_choice_prop(csym);

	/* count entries in choice block */
	cnt = 0;
	expr_list_for_each_sym(prop->expr, e, sym)
		cnt++;

	if (cnt == 0)
		return false;

	/*
	 * find a random value and set it to yes,
	 * set the rest to no so we have only one set
	 */
	def = std::uniform_int_distribution<int>(0, cnt - 1)(conf_rng());

	cnt = 0;
	expr_list_for_each_sym(prop->expr, e, sym) {
		if (def == cnt++) {
			sym->def[S_DEF_USER].tri = yes;
			csym->def[S_DEF_USER].val.sym = sym;
		}
		else {
			sym->def[S_DEF_USER].tri = no;
		}
		sym->flags |= SYMBOL_DEF_USER;
		/* clear VALID to get value calculated */
		sym->flags &= ~SYMBOL_VALID;
	}
	csym->flags |= SYMBOL_DEF_USER;
	/* clear VALID to get value calculated */
	csym->flags &= ~(SYMBOL_VALID);

	return true;
}

void set_all_choice_values(struct symbol *csym)
{
	struct property *prop;
	struct symbol *sym;
	struct expr *e;

	prop = sym_get_choice_prop(csym);

	/*
	 * Set all non-assinged choice values to no
	 */
	expr_list_for_each_sym(prop->expr, e, sym) {
		if (!sym_has_value(sym))
			sym->def[S_DEF_USER].tri = no;
	}
	csym->flags |= SYMBOL_DEF_USER;
	/* clear VALID to get value calculated */
	csym->flags &= ~(SYMBOL_VALID | SYMBOL_NEED_SET_CHOICE_VALUES);
}

bool conf_set_all_new_symbols(enum conf_def_mode mode)
{
	struct symbol *sym, *csym;
	int i, cnt, pby, pty, ptm;	/* pby: probability of bool     = y
					 * pty: probability of tristate = y
					 * ptm: probability of tristate = m
					 */

	pby = 50; pty = ptm = 33; /* can't go as the default in switch-case
				   * below, otherwise gcc whines about
				   * -Wmaybe-uninitialized */
	if (mode == def_random) {
		int n, p[3];
		char *env = getenv("KCONFIG_PROBABILITY");
		n = 0;
		while( env && *env ) {
			char *endp;
			int tmp = strtol( env, &endp, 10 );
			if( tmp >= 0 && tmp <= 100 ) {
				p[n++] = tmp;
			} else {
				errno = ERANGE;
				perror( "KCONFIG_PROBABILITY" );
				exit( 1 );
			}
			env = (*endp == ':') ? endp+1 : endp;
			if( n >=3 ) {
				break;
			}
		}
		switch( n ) {
		case 1:
			pby = p[0]; ptm = pby/2; pty = pby-ptm;
			break;
		case 2:
			pty = p[0]; ptm = p[1]; pby = pty + ptm;
			break;
		case 3:
			pby = p[0]; pty = p[1]; ptm = p[2];
			break;
		}

		if( pty+ptm > 100 ) {
			errno = ERANGE;
			perror( "KCONFIG_PROBABILITY" );
			exit( 1 );
		}
	}
	bool has_changed = false;

	for_all_symbols(i, sym) {
		if (sym_has_value(sym) || (sym->flags & SYMBOL_VALID))
			continue;
		switch (sym_get_type(sym)) {
		case S_BOOLEAN:
		case S_TRISTATE:
			has_changed = true;
			switch (mode) {
			case def_yes:
				sym->def[S_DEF_USER].tri = yes;
				break;
			case def_mod:
				sym->def[S_DEF_USER].tri = mod;
				break;
			case def_no:
				if (sym->flags & SYMBOL_ALLNOCONFIG_Y)
					sym->def[S_DEF_USER].tri = yes;
				else
					sym->def[S_DEF_USER].tri = no;
				break;
			case def_random:
				sym->def[S_DEF_USER].tri = no;
				cnt = std::uniform_int_distribution<int>(0, 99)(conf_rng());
				if (sym->type == S_TRISTATE) {
					if (cnt < pty)
						sym->def[S_DEF_USER].tri = yes;
					else if (cnt < (pty+ptm))
						sym->def[S_DEF_USER].tri = mod;
				} else if (cnt < pby)
					sym->def[S_DEF_USER].tri = yes;
				break;
			default:
				continue;
			}
			if (!(sym_is_choice(sym) && mode == def_random))
				sym->flags |= SYMBOL_DEF_USER;
			break;
		default:
			break;
		}

	}

	sym_clear_all_valid();

	/*
	 * We have different type of choice blocks.
	 * If curr.tri equals to mod then we can select several
	 * choice symbols in one block.
	 * In this case we do nothing.
	 * If curr.tri equals yes then only one symbol can be
	 * selected in a choice block and we set it to yes,
	 * and the rest to no.
	 */
	if (mode != def_random) {
		for_all_symbols(i, csym) {
			if ((sym_is_choice(csym) && !sym_has_value(csym)) ||
			    sym_is_choice_value(csym))
				csym->flags |= SYMBOL_NEED_SET_CHOICE_VALUES;
		}
	}

	for_all_symbols(i, csym) {
		if (sym_has_value(csym) || !sym_is_choice(csym))
			continue;

		sym_calc_value(csym);
		if (mode == def_random)
			has_changed |= randomize_choice_values(csym);
		else {
			set_all_choice_values(csym);
			has_changed = true;
		}
	}

	return has_changed;
}

void conf_rewrite_mod_or_yes(enum conf_def_mode mode)
{
	struct symbol *sym;
	int i;
	tristate old_val = (mode == def_y2m) ? yes : mod;
	tristate new_val = (mode == def_y2m) ? mod : yes;

	for_all_symbols(i, sym) {
		if (sym_get_type(sym) == S_TRISTATE &&
		    sym->def[S_DEF_USER].tri == old_val)
			sym->def[S_DEF_USER].tri = new_val;
	}
	sym_clear_all_valid();
}
