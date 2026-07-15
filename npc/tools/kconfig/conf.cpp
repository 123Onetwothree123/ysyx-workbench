// SPDX-License-Identifier: GPL-2.0
/*
 * Copyright (C) 2002 Roman Zippel <zippel@linux-m68k.org>
 *
 * C++ conversion of conf.c
 */

#include <cctype>
#include <climits>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <unistd.h>
#include <getopt.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <cerrno>
#include <print>
#include <string>
#include <string_view>

import npc.kconfig;

constexpr int LOCAL_PATH_MAX = 4096;
constexpr std::string_view CONFIG_ = "CONFIG_";

static void conf(menu *pmenu);
static void check_conf(menu *pmenu);

enum class InputMode {
	oldaskconfig,
	syncconfig,
	oldconfig,
	allnoconfig,
	allyesconfig,
	allmodconfig,
	alldefconfig,
	randconfig,
	defconfig,
	savedefconfig,
	listnewconfig,
	helpnewconfig,
	olddefconfig,
	yes2modconfig,
	mod2yesconfig,
};

static InputMode input_mode = InputMode::oldaskconfig;

static int indent = 1;
static int tty_stdio;
static int sync_kconfig;
static int conf_cnt;
static char line[LOCAL_PATH_MAX];
static menu *rootEntry;

static void print_help(menu *pmenu)
{
	gstr help = str_new();

	menu_get_ext_help(pmenu, &help);

	std::println("\n{}", str_get(&help));
	str_free(&help);
}

static void strip(char *str)
{
	char *p = str;
	size_t l;

	while (isspace(*p))
		p++;
	l = strlen(p);
	if (p != str)
		memmove(str, p, l + 1);
	if (!l)
		return;
	p = str + l - 1;
	while (isspace(*p))
		*p-- = 0;
}

static void xfgets(char *str, int size, FILE *in)
{
	if (!fgets(str, size, in))
		std::println(stderr, "\nError in reading or end of file.");

	if (!tty_stdio)
		std::print("{}", str);
}

static int conf_askvalue(symbol *sym, const char *def)
{
	enum symbol_type type = sym_get_type(sym);

	if (!sym_has_value(sym))
		std::print("(NEW) ");

	line[0] = '\n';
	line[1] = 0;

	if (!sym_is_changeable(sym)) {
		std::println("{}", def);
		line[0] = '\n';
		line[1] = 0;
		return 0;
	}

	switch (input_mode) {
	case InputMode::oldconfig:
	case InputMode::syncconfig:
		if (sym_has_value(sym)) {
			std::println("{}", def);
			return 0;
		}
		[[fallthrough]];
	case InputMode::oldaskconfig:
		fflush(stdout);
		xfgets(line, sizeof(line), stdin);
		return 1;
	default:
		break;
	}

	switch (type) {
	case S_INT:
	case S_HEX:
	case S_STRING:
		std::println("{}", def);
		return 1;
	default:
		;
	}
	std::print("{}", line);
	return 1;
}

static int conf_string(menu *pmenu)
{
	symbol *sym = pmenu->sym;
	const char *def;

	while (true) {
		std::print("{:{}} {} ", "", indent - 1, pmenu->prompt->text);
		std::print("({}) ", sym->name);
		def = sym_get_string_value(sym);
		if (sym_get_string_value(sym))
			std::print("[{}] ", def);
		if (!conf_askvalue(sym, def))
			return 0;
		switch (line[0]) {
		case '\n':
			break;
		case '?':
			if (line[1] == '\n') {
				print_help(pmenu);
				def = nullptr;
				break;
			}
			[[fallthrough]];
		default:
			line[strlen(line) - 1] = 0;
			def = line;
		}
		if (def && sym_set_string_value(sym, def))
			return 0;
	}
}

static int conf_sym(menu *pmenu)
{
	symbol *sym = pmenu->sym;
	tristate oldval, newval;

	while (true) {
		std::print("{:{}} {} ", "", indent - 1, pmenu->prompt->text);
		if (!sym->name.empty())
			std::print("({}) ", sym->name);
		putchar('[');
		oldval = sym_get_tristate_value(sym);
		switch (oldval) {
		case no:
			putchar('N');
			break;
		case mod:
			putchar('M');
			break;
		case yes:
			putchar('Y');
			break;
		}
		if (oldval != no && sym_tristate_within_range(sym, no))
			std::print("/n");
		if (oldval != mod && sym_tristate_within_range(sym, mod))
			std::print("/m");
		if (oldval != yes && sym_tristate_within_range(sym, yes))
			std::print("/y");
		std::print("/?] ");
		if (!conf_askvalue(sym, sym_get_string_value(sym)))
			return 0;
		strip(line);

		switch (line[0]) {
		case 'n':
		case 'N':
			newval = no;
			if (!line[1] || !strcmp(&line[1], "o"))
				break;
			continue;
		case 'm':
		case 'M':
			newval = mod;
			if (!line[1])
				break;
			continue;
		case 'y':
		case 'Y':
			newval = yes;
			if (!line[1] || !strcmp(&line[1], "es"))
				break;
			continue;
		case 0:
			newval = oldval;
			break;
		case '?':
			goto help;
		default:
			continue;
		}
		if (sym_set_tristate_value(sym, newval))
			return 0;
help:
		print_help(pmenu);
	}
}

static int conf_choice(menu *pmenu)
{
	symbol *sym, *def_sym;
	menu *child;
	bool is_new;

	sym = pmenu->sym;
	is_new = !sym_has_value(sym);
	if (sym_is_changeable(sym)) {
		conf_sym(pmenu);
		sym_calc_value(sym);
		switch (sym_get_tristate_value(sym)) {
		case no:
			return 1;
		case mod:
			return 0;
		case yes:
			break;
		}
	} else {
		switch (sym_get_tristate_value(sym)) {
		case no:
			return 1;
		case mod:
			std::println("{:{}} {}", "", indent - 1, menu_get_prompt(pmenu));
			return 0;
		case yes:
			break;
		}
	}

	while (true) {
		int cnt, def;

		std::println("{:{}} {}", "", indent - 1, menu_get_prompt(pmenu));
		def_sym = sym_get_choice_value(sym);
		cnt = def = 0;
		line[0] = 0;
		for (child = pmenu->list; child; child = child->next) {
			if (!menu_is_visible(child))
				continue;
			if (!child->sym) {
				std::println("{:{}}{} {}", "", indent - 1, '*', menu_get_prompt(child));
				continue;
			}
			cnt++;
			if (child->sym == def_sym) {
				def = cnt;
				std::print("{:{}}", "", indent - 1);
				std::print(">");
			} else
 std::print("{:{}}", "", indent);
			std::print(" {}. {}", cnt, menu_get_prompt(child));
			if (!child->sym->name.empty())
				std::print(" ({})", child->sym->name);
			if (!sym_has_value(child->sym))
				std::print(" (NEW)");
			std::println("");
		}
		std::print("{:{}}choice", "", indent - 1);
		if (cnt == 1) {
			std::println("[1]: 1");
			goto conf_childs;
		}
		std::print("[1-{}?]: ", cnt);
		switch (input_mode) {
		case InputMode::oldconfig:
		case InputMode::syncconfig:
			if (!is_new) {
				cnt = def;
				std::println("{}", cnt);
				break;
			}
			[[fallthrough]];
		case InputMode::oldaskconfig:
			fflush(stdout);
			xfgets(line, sizeof(line), stdin);
			strip(line);
			if (line[0] == '?') {
				print_help(pmenu);
				continue;
			}
			if (!line[0])
				cnt = def;
			else if (isdigit(line[0]))
				cnt = atoi(line);
			else
				continue;
			break;
		default:
			break;
		}

	conf_childs:
		for (child = pmenu->list; child; child = child->next) {
			if (!child->sym || !menu_is_visible(child))
				continue;
			if (!--cnt)
				break;
		}
		if (!child)
			continue;
		if (line[0] && line[strlen(line) - 1] == '?') {
			print_help(child);
			continue;
		}
		sym_set_choice_value(sym, child->sym);
		for (child = child->list; child; child = child->next) {
			indent += 2;
			conf(child);
			indent -= 2;
		}
		return 1;
	}
}

static void conf(menu *pmenu)
{
	symbol *sym;
	property *prop;
	menu *child;

	if (!menu_is_visible(pmenu))
		return;

	sym = pmenu->sym;
	prop = pmenu->prompt;
	if (prop) {
		const char *prompt;

		switch (prop->type) {
		case P_MENU:
			if (input_mode != InputMode::oldaskconfig && rootEntry != pmenu) {
				check_conf(pmenu);
				return;
			}
			[[fallthrough]];
		case P_COMMENT:
			prompt = menu_get_prompt(pmenu);
			if (prompt)
				std::println("{:{}}\n{:{}} {}\n{:{}}",
					"", indent, '*',
					"", indent, '*', prompt,
					"", indent, '*');
		default:
			;
		}
	}

	if (!sym)
		goto conf_childs;

	if (sym_is_choice(sym)) {
		conf_choice(pmenu);
		if (sym->curr.tri != mod)
			return;
		goto conf_childs;
	}

	switch (sym->type) {
	case S_INT:
	case S_HEX:
	case S_STRING:
		conf_string(pmenu);
		break;
	default:
		conf_sym(pmenu);
		break;
	}

conf_childs:
	if (sym)
		indent += 2;
	for (child = pmenu->list; child; child = child->next)
		conf(child);
	if (sym)
		indent -= 2;
}

static void check_conf(menu *pmenu)
{
	symbol *sym;
	menu *child;

	if (!menu_is_visible(pmenu))
		return;

	sym = pmenu->sym;
	if (sym && !sym_has_value(sym)) {
		if (sym_is_changeable(sym) ||
		    (sym_is_choice(sym) && sym_get_tristate_value(sym) == yes)) {
			if (input_mode == InputMode::listnewconfig) {
				if (!sym->name.empty()) {
					const char *str;

					if (sym->type == S_STRING) {
						str = sym_get_string_value(sym);
						str = sym_escape_string_value(str);
						std::println("{}{}={}", CONFIG_, sym->name, str);
						free((void *)str);
					} else {
						str = sym_get_string_value(sym);
						std::println("{}{}={}", CONFIG_, sym->name, str);
					}
				}
			} else if (input_mode == InputMode::helpnewconfig) {
				std::println("-----");
				print_help(pmenu);
				std::println("-----");
			} else {
				if (!conf_cnt++)
					std::println("*\n* Restart config...\n*");
				rootEntry = menu_get_parent_menu(pmenu);
				conf(rootEntry);
			}
		}
	}

	for (child = pmenu->list; child; child = child->next)
		check_conf(child);
}

static struct option long_opts[] = {
	{"oldaskconfig",    no_argument,       nullptr, (int)InputMode::oldaskconfig},
	{"oldconfig",       no_argument,       nullptr, (int)InputMode::oldconfig},
	{"syncconfig",      no_argument,       nullptr, (int)InputMode::syncconfig},
	{"defconfig",       required_argument, nullptr, (int)InputMode::defconfig},
	{"savedefconfig",   required_argument, nullptr, (int)InputMode::savedefconfig},
	{"allnoconfig",     no_argument,       nullptr, (int)InputMode::allnoconfig},
	{"allyesconfig",    no_argument,       nullptr, (int)InputMode::allyesconfig},
	{"allmodconfig",    no_argument,       nullptr, (int)InputMode::allmodconfig},
	{"alldefconfig",    no_argument,       nullptr, (int)InputMode::alldefconfig},
	{"randconfig",      no_argument,       nullptr, (int)InputMode::randconfig},
	{"listnewconfig",   no_argument,       nullptr, (int)InputMode::listnewconfig},
	{"helpnewconfig",   no_argument,       nullptr, (int)InputMode::helpnewconfig},
	{"olddefconfig",    no_argument,       nullptr, (int)InputMode::olddefconfig},
	{"yes2modconfig",   no_argument,       nullptr, (int)InputMode::yes2modconfig},
	{"mod2yesconfig",   no_argument,       nullptr, (int)InputMode::mod2yesconfig},
	{nullptr, 0, nullptr, 0}
};

static void conf_usage(const char *progname)
{
	std::println("Usage: {} [-s] [option] <kconfig-file>", progname);
	std::println("[option] is _one_ of the following:");
	std::println("  --listnewconfig         List new options");
	std::println("  --helpnewconfig         List new options and help text");
	std::println("  --oldaskconfig          Start a new configuration using a line-oriented program");
	std::println("  --oldconfig             Update a configuration using a provided .config as base");
	std::println("  --syncconfig            Similar to oldconfig but generates configuration in\n"
	       "                          include/{{generated/,config/}}");
	std::println("  --olddefconfig          Same as oldconfig but sets new symbols to their default value");
	std::println("  --defconfig <file>      New config with default defined in <file>");
	std::println("  --savedefconfig <file>  Save the minimal current configuration to <file>");
	std::println("  --allnoconfig           New config where all options are answered with no");
	std::println("  --allyesconfig          New config where all options are answered with yes");
	std::println("  --allmodconfig          New config where all options are answered with mod");
	std::println("  --alldefconfig          New config with all symbols set to default");
	std::println("  --randconfig            New config with random answer to all options");
	std::println("  --yes2modconfig         Change answers from yes to mod if possible");
	std::println("  --mod2yesconfig         Change answers from mod to yes if possible");
}

int main(int ac, char **av)
{
	const char *progname = av[0];
	int opt;
	const char *name = nullptr, *defconfig_file = nullptr;
	int no_conf_write = 0;

	tty_stdio = isatty(0) && isatty(1);

	while ((opt = getopt_long(ac, av, "s", long_opts, nullptr)) != -1) {
		if (opt == 's') {
			conf_set_message_callback(nullptr);
			continue;
		}
		input_mode = static_cast<InputMode>(opt);
		switch (opt) {
		case (int)InputMode::syncconfig:
			conf_set_message_callback(nullptr);
			sync_kconfig = 1;
			break;
		case (int)InputMode::defconfig:
		case (int)InputMode::savedefconfig:
			defconfig_file = optarg;
			break;
		case (int)InputMode::randconfig:
		{
			struct timeval now;
			unsigned int seed;

			gettimeofday(&now, nullptr);
			seed = (unsigned int)((now.tv_sec + 1) * (now.tv_usec + 1));

			char *seed_env = getenv("KCONFIG_SEED");
			if (seed_env && *seed_env) {
				char *endp;
				int tmp = (int)strtol(seed_env, &endp, 0);
				if (*endp == '\0') {
					seed = tmp;
				}
			}
			std::println(stderr, "KCONFIG_SEED=0x{:X}", seed);
			srand(seed);
			break;
		}
		case (int)InputMode::oldaskconfig:
		case (int)InputMode::oldconfig:
		case (int)InputMode::allnoconfig:
		case (int)InputMode::allyesconfig:
		case (int)InputMode::allmodconfig:
		case (int)InputMode::alldefconfig:
		case (int)InputMode::listnewconfig:
		case (int)InputMode::helpnewconfig:
		case (int)InputMode::olddefconfig:
		case (int)InputMode::yes2modconfig:
		case (int)InputMode::mod2yesconfig:
			break;
		case '?':
			conf_usage(progname);
			exit(1);
			break;
		}
	}
	if (ac == optind) {
		std::println(stderr, "{}: Kconfig file missing", av[0]);
		conf_usage(progname);
		exit(1);
	}
	name = av[optind];
	conf_parse(name);

	switch (input_mode) {
	case InputMode::defconfig:
		if (conf_read(defconfig_file)) {
			std::println(stderr,
				"***\n"
				"*** Can't find default configuration \"{}\"!\n"
				"***",
				defconfig_file);
			exit(1);
		}
		break;
	case InputMode::savedefconfig:
	case InputMode::syncconfig:
	case InputMode::oldaskconfig:
	case InputMode::oldconfig:
	case InputMode::listnewconfig:
	case InputMode::helpnewconfig:
	case InputMode::olddefconfig:
	case InputMode::yes2modconfig:
	case InputMode::mod2yesconfig:
		conf_read(nullptr);
		break;
	case InputMode::allnoconfig:
	case InputMode::allyesconfig:
	case InputMode::allmodconfig:
	case InputMode::alldefconfig:
	case InputMode::randconfig:
		name = getenv("KCONFIG_ALLCONFIG");
		if (!name)
			break;
		if ((strcmp(name, "") != 0) && (strcmp(name, "1") != 0)) {
			if (conf_read_simple(name, S_DEF_USER)) {
				std::println(stderr,
					"*** Can't read seed configuration \"{}\"!\n",
					name);
				exit(1);
			}
			break;
		}
		switch (input_mode) {
		case InputMode::allnoconfig:	name = "allno.config"; break;
		case InputMode::allyesconfig:	name = "allyes.config"; break;
		case InputMode::allmodconfig:	name = "allmod.config"; break;
		case InputMode::alldefconfig:	name = "alldef.config"; break;
		case InputMode::randconfig:	name = "allrandom.config"; break;
		default: break;
		}
		if (conf_read_simple(name, S_DEF_USER) &&
		    conf_read_simple("all.config", S_DEF_USER)) {
			std::println(stderr,
				"*** KCONFIG_ALLCONFIG set, but no \"{}\" or \"all.config\" file found\n",
				name);
			exit(1);
		}
		break;
	default:
		break;
	}

	if (sync_kconfig) {
		name = getenv("KCONFIG_NOSILENTUPDATE");
		if (name && *name) {
			if (conf_get_changed()) {
				std::println(stderr,
					"\n*** The configuration requires explicit update.\n");
				return 1;
			}
			no_conf_write = 1;
		}
	}

	switch (input_mode) {
	case InputMode::allnoconfig:
		conf_set_all_new_symbols(def_no);
		break;
	case InputMode::allyesconfig:
		conf_set_all_new_symbols(def_yes);
		break;
	case InputMode::allmodconfig:
		conf_set_all_new_symbols(def_mod);
		break;
	case InputMode::alldefconfig:
		conf_set_all_new_symbols(def_default);
		break;
	case InputMode::randconfig:
		while (conf_set_all_new_symbols(def_random)) ;
		break;
	case InputMode::defconfig:
		conf_set_all_new_symbols(def_default);
		break;
	case InputMode::savedefconfig:
		break;
	case InputMode::yes2modconfig:
		conf_rewrite_mod_or_yes(def_y2m);
		break;
	case InputMode::mod2yesconfig:
		conf_rewrite_mod_or_yes(def_m2y);
		break;
	case InputMode::oldaskconfig:
		rootEntry = &rootmenu;
		conf(&rootmenu);
		input_mode = InputMode::oldconfig;
		[[fallthrough]];
	case InputMode::oldconfig:
	case InputMode::listnewconfig:
	case InputMode::helpnewconfig:
	case InputMode::syncconfig:
		do {
			conf_cnt = 0;
			check_conf(&rootmenu);
		} while (conf_cnt);
		break;
	case InputMode::olddefconfig:
	default:
		break;
	}

	if (input_mode == InputMode::savedefconfig) {
		if (conf_write_defconfig(defconfig_file)) {
			std::println(stderr, "n*** Error while saving defconfig to: {}\n",
				defconfig_file);
			return 1;
		}
	} else if (input_mode != InputMode::listnewconfig && input_mode != InputMode::helpnewconfig) {
		if (!no_conf_write && conf_write(nullptr)) {
			std::println(stderr, "\n*** Error during writing of the configuration.\n");
			exit(1);
		}

		if (conf_write_autoconf(sync_kconfig) && sync_kconfig) {
			std::println(stderr,
				"\n*** Error during sync of the configuration.\n");
			return 1;
		}
	}
	return 0;
}
