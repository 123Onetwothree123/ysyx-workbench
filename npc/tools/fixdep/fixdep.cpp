/*
 * "Optimize" a list of dependencies as spit out by gcc -MD
 * for the kernel build
 * ===========================================================================
 *
 * Author       Kai Germaschewski
 * Copyright    2002 by Kai Germaschewski  <kai.germaschewski@gmx.de>
 *
 * This software may be used and distributed according to the terms
 * of the GNU General Public License, incorporated herein by reference.
 *
 * C++23 rewrite for npc build system:
 *   std::println, std::string_view, std::unordered_set, std::filesystem
 */

#include <print>
#include <string>
#include <string_view>
#include <unordered_set>
#include <fstream>
#include <sstream>
#include <filesystem>
#include <cctype>
#include <cstdlib>

using namespace std::literals;

static void print_dep(std::string_view name)
{
	std::string out = "    $(wildcard include/config/";
	int prev_c = '/';
	for (char c : name) {
		if (c == '_')
			c = '/';
		else
			c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
		if (c != '/' || prev_c != '/')
			out += c;
		prev_c = c;
	}
	out += ".h) \\";
	std::println("{}", out);
}

static bool str_ends_with(std::string_view s, std::string_view suffix)
{
	return s.size() >= suffix.size() &&
	       s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

static void parse_config_file(std::string_view buf, std::unordered_set<std::string>& seen)
{
	size_t pos = 0;
	while (true) {
		size_t p = buf.find("CONFIG_"sv, pos);
		if (p == std::string_view::npos)
			break;

		if (p > 0 && (std::isalnum(static_cast<unsigned char>(buf[p - 1])) || buf[p - 1] == '_')) {
			pos = p + 7;
			continue;
		}

		pos = p + 7;
		size_t q = pos;
		while (q < buf.size() && (std::isalnum(static_cast<unsigned char>(buf[q])) || buf[q] == '_'))
			q++;

		size_t r = q;
		if (str_ends_with(buf.substr(pos, q - pos), "_MODULE"sv))
			r = q - 7;

		if (r > pos) {
			auto name = std::string(buf.substr(pos, r - pos));
			if (auto [it, inserted] = seen.insert(name); inserted)
				print_dep(name);
		}

		pos = q;
	}
}

static std::string read_file(const std::filesystem::path& filename)
{
	std::ifstream f(filename, std::ios::binary);
	if (!f) {
		std::println(stderr, "fixdep: error opening file: {}", filename.string());
		std::exit(2);
	}
	std::ostringstream ss;
	ss << f.rdbuf();
	return ss.str();
}

static bool is_ignored_file(std::string_view name)
{
	return str_ends_with(name, "include/generated/autoconf.h"sv) ||
	       str_ends_with(name, "include/generated/autoksyms.h"sv);
}

static void parse_dep_file(std::string_view content, std::string_view target,
			   std::unordered_set<std::string>& seen)
{
	size_t p = 0;
	bool is_first_dep = false;
	bool saw_any_target = false;

	while (p < content.size()) {
		while (p < content.size() && (content[p] == ' ' || content[p] == '\\' || content[p] == '\n'))
			p++;
		if (p >= content.size())
			break;

		size_t q = p;
		while (q < content.size() && content[q] != ' ' && content[q] != '\\' && content[q] != '\n')
			q++;

		bool is_last = (q == content.size());
		bool is_target = (q > p && content[q - 1] == ':');

		if (is_target) {
			is_first_dep = true;
		} else {
			auto token = content.substr(p, q - p);
			if (!is_ignored_file(token)) {
				if (is_first_dep) {
					if (!saw_any_target) {
						saw_any_target = true;
						std::println("source_{} := {}", target, token);
						std::println();
						std::println("deps_{} := \\", target);
					}
					is_first_dep = false;
				} else {
					std::println("  {} \\", token);
				}

				auto file_content = read_file(std::string(token));
				parse_config_file(file_content, seen);
			}
		}

		if (is_last)
			break;
		p = q + 1;
	}

	if (!saw_any_target) {
		std::println(stderr, "fixdep: parse error; no targets found");
		std::exit(1);
	}

	std::println();
	std::println("{}: $(deps_{})", target, target);
	std::println();
	std::println("$(deps_{}):", target);
}

int main(int argc, char *argv[])
{
	if (argc != 4) {
		std::println(stderr, "Usage: fixdep <depfile> <target> <cmdline>");
		return 1;
	}

	std::string depfile(argv[1]);
	std::string target(argv[2]);
	std::string cmdline(argv[3]);

	std::println("cmd_{} := {}", target, cmdline);
	std::println();

	std::unordered_set<std::string> seen;
	auto buf = read_file(depfile);
	parse_dep_file(buf, target, seen);

	return 0;
}
