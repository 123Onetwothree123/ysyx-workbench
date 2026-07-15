// SPDX-License-Identifier: GPL-2.0
/* C++ conversion for npc.kconfig module */

module;

#include <sys/mman.h>
#include <sys/stat.h>
#include <cctype>
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <iostream>
#include <fstream>
#include <filesystem>
#include <print>
#include <string>
#include <string_view>
#include <vector>

#include <fcntl.h>
#include <limits.h>
#include <unistd.h>

extern "C" {
    FILE *zconf_fopen(const char *name);
    int yyparse(void);
}

// Macros from expr.h (can't be in module)
#define for_all_symbols(i, sym) for (int i = 0; i < SYMBOL_HASHSIZE; i++) for (symbol *sym = symbol_hash[i]; sym; sym = sym->next)
#define for_all_properties(sym, st, tok) for (st = sym->prop; st; st = st->next) if (st->type == (tok))
#define for_all_defaults(sym, st) for_all_properties(sym, st, P_DEFAULT)
#define for_all_prompts(sym, st) for_all_properties(sym, st, P_PROMPT)

#define CONFIG_ "CONFIG_"

#define expr_list_for_each_sym(l, e, s) for (e = l; e; e = e->next) if (e->type == E_SYMBOL) for (s = e->left.sym; s; )
#define end_expr_list_for_each_sym_sym

module npc.kconfig;

#define for_all_symbols(i, sym) for (int i = 0; i < SYMBOL_HASHSIZE; i++) for (symbol *sym = symbol_hash[i]; sym; sym = sym->next)
#define for_all_properties(sym, st, tok) for (st = sym->prop; st; st = st->next) if (st->type == (tok))
#define for_all_defaults(sym, st) for_all_properties(sym, st, P_DEFAULT)
#define for_all_prompts(sym, st) for_all_properties(sym, st, P_PROMPT)

using std::string;
using std::string_view;
namespace fs = std::filesystem;

bool is_present(const char *path)
{
	struct stat st;
	return !stat(path, &st);
}

bool is_dir(const char *path)
{
	struct stat st;
	if (stat(path, &st))
		return false;
	return S_ISDIR(st.st_mode);
}

bool is_same(const char *file1, const char *file2)
{
	int fd1 = open(file1, O_RDONLY);
	if (fd1 < 0)
		return false;

	int fd2 = open(file2, O_RDONLY);
	if (fd2 < 0) {
		close(fd1);
		return false;
	}

	struct stat st1, st2;
	if (fstat(fd1, &st1) || fstat(fd2, &st2)) {
		close(fd2);
		close(fd1);
		return false;
	}

	if (st1.st_size != st2.st_size) {
		close(fd2);
		close(fd1);
		return false;
	}

	void *map1 = mmap(NULL, st1.st_size, PROT_READ, MAP_PRIVATE, fd1, 0);
	if (map1 == MAP_FAILED) {
		close(fd2);
		close(fd1);
		return false;
	}

	void *map2 = mmap(NULL, st2.st_size, PROT_READ, MAP_PRIVATE, fd2, 0);
	if (map2 == MAP_FAILED) {
		munmap(map1, st1.st_size);
		close(fd2);
		close(fd1);
		return false;
	}

	bool ret = (memcmp(map1, map2, st1.st_size) == 0);

	munmap(map2, st2.st_size);
	munmap(map1, st1.st_size);
	close(fd2);
	close(fd1);

	return ret;
}

int make_parent_dir(const char *path)
{
	string p(path);
	auto pos = p.rfind('/');
	if (pos == string::npos)
		return 0;
	p = p.substr(0, pos);

	std::error_code ec;
	fs::create_directories(p, ec);
	return ec ? -1 : 0;
}

string depfile_path = "include/config/";
size_t depfile_prefix_len;

int conf_touch_dep(const char *name)
{
	if (depfile_prefix_len + strlen(name) + 3 > depfile_path.size())
		return -1;

	string path = depfile_path;
	for (const char *s = name; *s; s++) {
		char c = *s;
		path += (c == '_') ? '/' : (char)tolower(c);
	}
	path += ".h";

	int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
	if (fd == -1) {
		if (errno != ENOENT)
			return -1;
		if (make_parent_dir(path.c_str()))
			return -1;
		fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
		if (fd == -1)
			return -1;
	}
	close(fd);
	return 0;
}

struct conf_printer {
	void (*print_symbol)(FILE *, symbol *, const char *, void *);
	void (*print_comment)(FILE *, const char *, void *);
};

const char *conf_filename;
int conf_lineno, conf_warnings;

void conf_warning(const char *fmt, ...)
	__attribute__((format(printf, 1, 2)));

void conf_warning(const char *fmt, ...)
{
	va_list ap;
	va_start(ap, fmt);
	fprintf(stderr, "%s:%d:warning: ", conf_filename, conf_lineno);
	vfprintf(stderr, fmt, ap);
	fprintf(stderr, "\n");
	va_end(ap);
	conf_warnings++;
}

void conf_default_message_callback(const char *s)
{
	printf("#\n# ");
	printf("%s", s);
	printf("\n#\n");
}

void (*conf_message_callback)(const char *s) =
	conf_default_message_callback;

void conf_set_message_callback(void (*fn)(const char *s))
{
	conf_message_callback = fn;
}

void conf_message(const char *fmt, ...)
{
	va_list ap;
	char buf[4096];

	if (!conf_message_callback)
		return;

	va_start(ap, fmt);
	vsnprintf(buf, sizeof(buf), fmt, ap);
	conf_message_callback(buf);
	va_end(ap);
}

const char *conf_get_configname(void)
{
	char *name = getenv("KCONFIG_CONFIG");
	return name ? name : ".config";
}

const char *conf_get_autoconfig_name(void)
{
	char *name = getenv("KCONFIG_AUTOCONFIG");
	return name ? name : "include/config/auto.conf";
}

int conf_set_sym_val(symbol *sym, int def, int def_flags, char *p)
{
	char *p2;

	switch (sym->type) {
	case S_TRISTATE:
		if (p[0] == 'm') {
			sym->def[def].tri = mod;
			sym->flags |= def_flags;
			break;
		}
		[[fallthrough]];
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
				     p, sym->name.c_str());
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
		[[fallthrough]];
	case S_INT:
	case S_HEX:
		if (sym_string_valid(sym, p)) {
			sym->def[def].val = xstrdup(p);
			sym->flags |= def_flags;
		} else {
			if (def != S_DEF_AUTO)
				conf_warning("symbol value '%s' invalid for %s",
					     p, sym->name.c_str());
			return 1;
		}
		break;
	default:
		;
	}
	return 0;
}

int conf_read_simple(const char *name, int def)
{
	FILE *in = nullptr;
	symbol *sym;
	int i, def_flags;

	if (name) {
		in = zconf_fopen(name);
	} else {
		property *prop;

		name = conf_get_configname();
		in = zconf_fopen(name);
		if (in)
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
			in = zconf_fopen(name);
			if (in) {
				conf_message("using defaults found in %s",
					 name);
				goto load;
			}
		}
	}
	if (!in)
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
			if (sym->def[def].val)
				free(sym->def[def].val);
			[[fallthrough]];
		default:
			sym->def[def].val = nullptr;
			sym->def[def].tri = no;
		}
	}

	string linebuf;
	linebuf.reserve(4096);
	while (true) {
		char lbuf[4096];
		if (!fgets(lbuf, sizeof(lbuf), in))
			break;
		linebuf = lbuf;
		conf_lineno++;
		sym = nullptr;

		char *line = linebuf.data();
		size_t len = linebuf.size();
		while (len > 0 && (line[len-1] == '\n' || line[len-1] == '\r'))
			line[--len] = '\0';

		if (len == 0 || line[0] == '#') {
			if (len == 0 || memcmp(line + 2, CONFIG_, strlen(CONFIG_)))
				continue;
			char *p = strchr(line + 2 + strlen(CONFIG_), ' ');
			if (!p)
				continue;
			*p++ = '\0';
			if (strncmp(p, "is not set", 10))
				continue;
			if (def == S_DEF_USER) {
				sym = sym_find(line + 2 + strlen(CONFIG_));
				if (!sym) {
					sym_add_change_count(1);
					continue;
				}
			} else {
				sym = sym_lookup(line + 2 + strlen(CONFIG_), 0);
				if (sym->type == S_UNKNOWN)
					sym->type = S_BOOLEAN;
			}
			if (sym->flags & def_flags) {
				conf_warning("override: reassigning to symbol %s", sym->name.c_str());
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
		} else if (memcmp(line, CONFIG_, strlen(CONFIG_)) == 0) {
			char *p = strchr(line + strlen(CONFIG_), '=');
			if (!p)
				continue;
			*p++ = '\0';

			sym = sym_find(line + strlen(CONFIG_));
			if (!sym) {
				if (def == S_DEF_AUTO)
					conf_touch_dep(line + strlen(CONFIG_));
				else
					sym_add_change_count(1);
				continue;
			}

			if (sym->flags & def_flags) {
				conf_warning("override: reassigning to symbol %s", sym->name.c_str());
			}
			if (conf_set_sym_val(sym, def, def_flags, p))
				continue;
		} else {
			if (len > 0 && line[0] != '\r' && line[0] != '\n')
				conf_warning("unexpected data: %s", line);
			continue;
		}

		if (sym && sym_is_choice_value(sym)) {
			symbol *cs = prop_get_symbol(sym_get_choice_prop(sym));
			switch (sym->def[def].tri) {
			case no:
				break;
			case mod:
				if (cs->def[def].tri == yes) {
					conf_warning("%s creates inconsistent choice state", sym->name.c_str());
					cs->flags &= ~def_flags;
				}
				break;
			case yes:
				if (cs->def[def].tri != no)
					conf_warning("override: %s changes choice state", sym->name.c_str());
				cs->def[def].val = sym;
				break;
			}
			cs->def[def].tri = static_cast<tristate>(EXPR_OR(static_cast<int>(cs->def[def].tri), static_cast<int>(sym->def[def].tri)));
		}
	}
	fclose(in);
	return 0;
}

int conf_read(const char *name)
{
	symbol *sym;
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
			switch (sym->type) {
			case S_BOOLEAN:
			case S_TRISTATE:
				if (sym->def[S_DEF_USER].tri == sym_get_tristate_value(sym))
					continue;
				break;
			default:
				if (!strcmp(static_cast<const char*>(sym->curr.val), static_cast<const char*>(sym->def[S_DEF_USER].val)))
					continue;
				break;
			}
		} else if (!sym_has_value(sym) && !(sym->flags & SYMBOL_WRITE))
			continue;
		conf_unsaved++;
	}

	for_all_symbols(i, sym) {
		if (sym_has_value(sym) && !sym_is_choice_value(sym)) {
			if (sym->visible == no && !conf_unsaved)
				sym->flags &= ~SYMBOL_DEF_USER;
			switch (sym->type) {
			case S_STRING:
			case S_INT:
			case S_HEX:
				if (sym_string_within_range(sym, static_cast<const char*>(sym->def[S_DEF_USER].val)))
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

void
kconfig_print_symbol(FILE *fp, symbol *sym, const char *value, void *arg)
{
	switch (sym->type) {
	case S_BOOLEAN:
	case S_TRISTATE:
		if (*value == 'n') {
			bool skip_unset = (arg != nullptr);
			if (!skip_unset)
				fprintf(fp, "# %s%s is not set\n",
				    CONFIG_, sym->name.c_str());
			return;
		}
		break;
	default:
		break;
	}

	fprintf(fp, "%s%s=%s\n", CONFIG_, sym->name.c_str(), value);
}

void
kconfig_print_comment(FILE *fp, const char *value, void *)
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

struct conf_printer kconfig_printer_cb =
{
	.print_symbol = kconfig_print_symbol,
	.print_comment = kconfig_print_comment,
};

void
header_print_symbol(FILE *fp, symbol *sym, const char *value, void *)
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
			[[fallthrough]];
		default:
			fprintf(fp, "#define %s%s%s 1\n",
			    CONFIG_, sym->name.c_str(), suffix);
		}
		break;
	}
	case S_HEX: {
		const char *prefix = "";

		if (value[0] != '0' || (value[1] != 'x' && value[1] != 'X'))
			prefix = "0x";
		fprintf(fp, "#define %s%s %s%s\n",
		    CONFIG_, sym->name.c_str(), prefix, value);
		break;
	}
	case S_STRING:
	case S_INT:
		fprintf(fp, "#define %s%s %s\n",
		    CONFIG_, sym->name.c_str(), value);
		break;
	default:
		break;
	}
}

void
header_print_comment(FILE *fp, const char *value, void *)
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

struct conf_printer header_printer_cb =
{
	.print_symbol = header_print_symbol,
	.print_comment = header_print_comment,
};

void conf_write_symbol(FILE *fp, symbol *sym,
			      struct conf_printer *printer, void *printer_arg)
{
	const char *str;

	switch (sym->type) {
	case S_UNKNOWN:
		break;
	case S_STRING:
		str = sym_get_string_value(sym);
		str = sym_escape_string_value(str);
		printer->print_symbol(fp, sym, str, printer_arg);
		free((void *)str);
		break;
	default:
		str = sym_get_string_value(sym);
		printer->print_symbol(fp, sym, str, printer_arg);
	}
}

void
conf_write_heading(FILE *fp, struct conf_printer *printer, void *printer_arg)
{
	char buf[256];

	snprintf(buf, sizeof(buf),
	    "\n"
	    "Automatically generated file; DO NOT EDIT.\n"
	    "%s\n",
	    rootmenu.prompt->text.c_str());

	printer->print_comment(fp, buf, printer_arg);
}

int conf_write_defconfig(const char *filename)
{
	symbol *sym;
	menu *menu;
	FILE *out;

	out = fopen(filename, "w");
	if (!out)
		return 1;

	sym_clear_all_valid();

	menu = rootmenu.list;

	while (menu != nullptr) {
		sym = menu->sym;
		if (sym == nullptr) {
			if (!menu_is_visible(menu))
				goto next_menu;
		} else if (!sym_is_choice(sym)) {
			sym_calc_value(sym);
			if (!(sym->flags & SYMBOL_WRITE))
				goto next_menu;
			sym->flags &= ~SYMBOL_WRITE;
			if (!sym_is_changeable(sym))
				goto next_menu;
			if (strcmp(sym_get_string_value(sym), sym_get_string_default(sym)) == 0)
				goto next_menu;

			if (sym_is_choice_value(sym)) {
				symbol *cs;
				symbol *ds;

				cs = prop_get_symbol(sym_get_choice_prop(sym));
				ds = sym_choice_default(cs);
				if (!sym_is_optional(cs) && sym == ds) {
					if ((sym->type == S_BOOLEAN) &&
					    sym_get_tristate_value(sym) == yes)
						goto next_menu;
				}
			}
			conf_write_symbol(out, sym, &kconfig_printer_cb, nullptr);
		}
next_menu:
		if (menu->list != nullptr) {
			menu = menu->list;
		} else if (menu->next != nullptr) {
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
	return 0;
}

int conf_write(const char *name)
{
	FILE *out;
	symbol *sym;
	menu *menu;
	const char *str;
	char tmpname[PATH_MAX + 1];
	char oldname[PATH_MAX + 1];
	int i;
	bool need_newline = false;

	tmpname[0] = '\0';
	oldname[0] = '\0';

	if (!name)
		name = conf_get_configname();

	if (!*name) {
		fprintf(stderr, "config name is empty\n");
		return -1;
	}

	if (is_dir(name)) {
		fprintf(stderr, "%s: Is a directory\n", name);
		return -1;
	}

	if (make_parent_dir(name))
		return -1;

	char *env = getenv("KCONFIG_OVERWRITECONFIG");
	if (env && *env) {
		out = fopen(name, "w");
	} else {
		snprintf(tmpname, sizeof(tmpname), "%s.%d.tmp",
			 name, (int)getpid());
		out = fopen(tmpname, "w");
	}
	if (!out)
		return 1;

	conf_write_heading(out, &kconfig_printer_cb, nullptr);

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
			if (!(sym->flags & SYMBOL_WRITE))
				goto next;
			if (need_newline) {
				fprintf(out, "\n");
				need_newline = false;
			}
			sym->flags |= SYMBOL_WRITTEN;
			conf_write_symbol(out, sym, &kconfig_printer_cb, nullptr);
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

	for_all_symbols(i, sym)
		sym->flags &= ~SYMBOL_WRITTEN;

	if (tmpname[0] != '\0') {
		if (is_same(name, tmpname)) {
			conf_message("No change to %s", name);
			unlink(tmpname);
			sym_set_change_count(0);
			return 0;
		}

		snprintf(oldname, sizeof(oldname), "%s.old", name);
		rename(name, oldname);
		if (rename(tmpname, name))
			return 1;
	}

	conf_message("configuration written to %s", name);

	sym_set_change_count(0);

	return 0;
}

int conf_write_dep(const char *name)
{
	struct file *file;
	FILE *out;

	out = fopen("..config.tmp", "w");
	if (!out)
		return 1;
	fprintf(out, "deps_config := \\\n");
	for (file = file_list; file; file = file->next) {
		if (file->next)
			fprintf(out, "\t%s \\\n", file->name.c_str());
		else
			fprintf(out, "\t%s\n", file->name.c_str());
	}
	fprintf(out, "\n%s: \\\n"
		     "\t$(deps_config)\n\n", conf_get_autoconfig_name());

	env_write_dep(out, conf_get_autoconfig_name());

	fprintf(out, "\n$(deps_config): ;\n");
	fclose(out);

	if (make_parent_dir(name))
		return 1;
	rename("..config.tmp", name);
	return 0;
}

int conf_touch_deps(void)
{
	return 0; // TODO: implement per-symbol dependency tracking (was broken)
}

int conf_write_autoconf(int overwrite)
{
	symbol *sym;
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
		return 1;
	}

	conf_write_heading(out, &kconfig_printer_cb, nullptr);
	conf_write_heading(out_h, &header_printer_cb, nullptr);

	for_all_symbols(i, sym) {
		sym_calc_value(sym);
		if (!(sym->flags & SYMBOL_WRITE) || sym->name.empty())
			continue;

		conf_write_symbol(out, sym, &kconfig_printer_cb, (void *)1);
		conf_write_symbol(out_h, sym, &header_printer_cb, nullptr);
	}
	fclose(out);
	fclose(out_h);

	name = getenv("KCONFIG_AUTOHEADER");
	if (!name)
		name = "include/generated/autoconf.h";
	if (make_parent_dir(name))
		return 1;
	if (rename(".tmpconfig.h", name))
		return 1;

	if (make_parent_dir(autoconf_name))
		return 1;
	if (rename(".tmpconfig", autoconf_name))
		return 1;

	return 0;
}

int sym_change_count;
void (*conf_changed_callback)(void);

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

bool randomize_choice_values(symbol *csym)
{
	property *prop;
	symbol *sym;
	expr *e;
	int cnt, def;

	if (csym->curr.tri != yes)
		return false;

	prop = sym_get_choice_prop(csym);

	cnt = 0;
	expr_list_for_each_sym(prop->expr, e, sym)
		cnt++;

	def = (rand() % cnt);

	cnt = 0;
	expr_list_for_each_sym(prop->expr, e, sym) {
		if (def == cnt++) {
			sym->def[S_DEF_USER].tri = yes;
			csym->def[S_DEF_USER].val = sym;
		} else {
			sym->def[S_DEF_USER].tri = no;
		}
		sym->flags |= SYMBOL_DEF_USER;
		sym->flags &= ~SYMBOL_VALID;
	}
	csym->flags |= SYMBOL_DEF_USER;
	csym->flags &= ~(SYMBOL_VALID);

	return true;
}

void set_all_choice_values(symbol *csym)
{
	property *prop;
	symbol *sym;
	expr *e;

	prop = sym_get_choice_prop(csym);

	expr_list_for_each_sym(prop->expr, e, sym) {
		if (!sym_has_value(sym))
			sym->def[S_DEF_USER].tri = no;
	}
	csym->flags |= SYMBOL_DEF_USER;
	csym->flags &= ~(SYMBOL_VALID | SYMBOL_NEED_SET_CHOICE_VALUES);
}

bool conf_set_all_new_symbols(enum conf_def_mode mode)
{
	symbol *sym, *csym;
	int i, cnt, pby, pty, ptm;

	pby = 50; pty = ptm = 33;
	if (mode == def_random) {
		int n, p[3];
		char *env = getenv("KCONFIG_PROBABILITY");
		n = 0;
		while (env && *env) {
			char *endp;
			int tmp = strtol(env, &endp, 10);
			if (tmp >= 0 && tmp <= 100) {
				p[n++] = tmp;
			} else {
				errno = ERANGE;
				perror("KCONFIG_PROBABILITY");
				exit(1);
			}
			env = (*endp == ':') ? endp+1 : endp;
			if (n >= 3)
				break;
		}
		switch (n) {
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

		if (pty+ptm > 100) {
			errno = ERANGE;
			perror("KCONFIG_PROBABILITY");
			exit(1);
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
				cnt = rand() % 100;
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
	symbol *sym;
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
