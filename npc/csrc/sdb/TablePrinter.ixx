export module npc.sdb.TablePrinter;
import std;

export namespace ANSI
{
    constexpr const char *NONE{"\033[0m"};
    constexpr const char *FG_RED{"\033[1;31m"};
    constexpr const char *FG_GREEN{"\033[1;32m"};
    constexpr const char *FG_YELLOW{"\033[1;33m"};
    constexpr const char *FG_BLUE{"\033[1;34m"};
    constexpr const char *FG_MAGENTA{"\033[1;35m"};
    constexpr const char *FG_CYAN{"\033[1;36m"};
    constexpr const char *FG_WHITE{"\033[1;37m"};
}

export int display_width(std::string_view s);
export void print_border(const std::vector<int> &widths);
export void print_cell_colored(std::string_view raw_content, int width, bool center);
