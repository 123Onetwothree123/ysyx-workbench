#include "command/infoCommand.hpp"
#include "command/WatchpointPool.hpp"
#include "../SDBCommandContext.hpp"
#include "../NPCEvaluationContext.hpp"
#include "../TablePrinter.hpp"
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <format>
#include <print>
#include <string>
#include <vector>

static constexpr std::string_view get_reg_abi_name(std::size_t idx) noexcept
{
    constexpr std::array abi{
        "zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2",
        "s0",   "s1", "a0", "a1", "a2", "a3", "a4", "a5",
        "a6",   "a7", "s2", "s3", "s4", "s5", "s6", "s7",
        "s8",   "s9", "s10", "s11", "t3", "t4", "t5", "t6",
    };
    if (idx < abi.size())
    {
        return abi[idx];
    }
    return {};
}
static const char *get_reg_desc(const char *arch_name, std::string_view abi_name)
{
    if (std::strcmp(arch_name, "pc") == 0)
    {
        return "程序计数器";
    }
    if (std::strcmp(arch_name, "x0") == 0)
    {
        return "零寄存器";
    }
    if (abi_name == "ra")
    {
        return "返回地址";
    }
    if (abi_name == "sp")
    {
        return "栈指针";
    }
    if (abi_name == "gp")
    {
        return "全局指针";
    }
    if (abi_name == "tp")
    {
        return "线程指针";
    }
    if (!abi_name.empty() && abi_name[0] == 'a')
    {
        return "参数寄存器";
    }
    if (!abi_name.empty() && abi_name[0] == 's')
    {
        return "保存寄存器";
    }
    if (!abi_name.empty() && abi_name[0] == 't')
    {
        return "临时寄存器";
    }
    return "通用寄存器";
}
static constexpr const char *get_reg_header_color(std::string_view title) noexcept
{
    constexpr std::array<std::pair<const char *, const char *>, 5> colors{{
        {"编号", ANSI::FG_CYAN},
        {"寄存器", ANSI::FG_BLUE},
        {"十进制", ANSI::FG_WHITE},
        {"十六进制", ANSI::FG_GREEN},
        {"说明", ANSI::FG_YELLOW},
    }};
    for (const auto &[key, color] : colors)
    {
        if (title == key)
        {
            return color;
        }
    }
    return ANSI::FG_WHITE;
}
static const char *get_reg_row_color(const char *arch_name, std::string_view abi_name)
{
    if (std::strcmp(arch_name, "pc") == 0)
    {
        return ANSI::FG_YELLOW;
    }
    if (std::strcmp(arch_name, "x0") == 0 || abi_name == "zero")
    {
        return ANSI::FG_WHITE;
    }
    if (abi_name == "ra")
    {
        return ANSI::FG_CYAN;
    }
    if (abi_name == "sp")
    {
        return ANSI::FG_YELLOW;
    }
    if (abi_name == "gp")
    {
        return ANSI::FG_BLUE;
    }
    if (abi_name == "tp")
    {
        return ANSI::FG_MAGENTA;
    }
    if (!abi_name.empty() && abi_name[0] == 'a')
    {
        return ANSI::FG_GREEN;
    }
    if (!abi_name.empty() && abi_name[0] == 's')
    {
        return ANSI::FG_CYAN;
    }
    if (!abi_name.empty() && abi_name[0] == 't')
    {
        return ANSI::FG_MAGENTA;
    }
    return ANSI::FG_WHITE;
}
static void PrintGPR(DUT &dut)
{
    struct RegRow
    {
        std::string arch_name;
        std::string_view abi_name;
        std::uint32_t value;
        const char *desc;
    };
    constexpr auto nr_rows{std::size_t{33}};
    RegRow rows[nr_rows];
    for (std::size_t i{0}; i < nr_rows; i++)
    {
        if (i == 0)
        {
            rows[i].arch_name = "pc";
            rows[i].abi_name = "pc";
            auto result{dut.ReadPC()};
            rows[i].value = result ? *result : 0;
        }
        else
        {
            auto reg_idx{i - 1};
            rows[i].arch_name = std::format("x{}", reg_idx);
            rows[i].abi_name = get_reg_abi_name(reg_idx);
            auto result{dut.ReadGPR(static_cast<std::uint32_t>(reg_idx))};
            rows[i].value = result ? *result : 0;
        }
        rows[i].desc = get_reg_desc(rows[i].arch_name.c_str(), rows[i].abi_name);
    }
    auto id_width{display_width("编号")};
    auto name_width{display_width("寄存器")};
    auto dec_width{display_width("十进制")};
    auto hex_width{display_width("十六进制")};
    auto desc_width{display_width("说明")};
    for (std::size_t i{0}; i < nr_rows; i++)
    {
        auto w{display_width(rows[i].arch_name)};
        if (w > id_width)
        {
            id_width = w;
        }
        w = display_width(rows[i].abi_name);
        if (w > name_width)
        {
            name_width = w;
        }
        w = display_width(std::to_string(rows[i].value));
        if (w > dec_width)
        {
            dec_width = w;
        }
        w = display_width(std::format("0x{:08x}", rows[i].value));
        if (w > hex_width)
        {
            hex_width = w;
        }
        w = display_width(rows[i].desc);
        if (w > desc_width)
        {
            desc_width = w;
        }
    }
    std::vector col_widths{id_width, name_width, dec_width, hex_width, desc_width};
    std::println("寄存器状态：");
    print_border(col_widths);
    std::print("{}|{}", ANSI::FG_BLUE, ANSI::NONE);
    print_cell_colored(std::format("{}编号{}", get_reg_header_color("编号"), ANSI::NONE), id_width, true);
    print_cell_colored(std::format("{}寄存器{}", get_reg_header_color("寄存器"), ANSI::NONE), name_width, false);
    print_cell_colored(std::format("{}十进制{}", get_reg_header_color("十进制"), ANSI::NONE), dec_width, true);
    print_cell_colored(std::format("{}十六进制{}", get_reg_header_color("十六进制"), ANSI::NONE), hex_width, true);
    print_cell_colored(std::format("{}说明{}", get_reg_header_color("说明"), ANSI::NONE), desc_width, false);
    std::print("\n");
    print_border(col_widths);
    for (std::size_t i{0}; i < nr_rows; i++)
    {
        const auto row_color{get_reg_row_color(rows[i].arch_name.c_str(), rows[i].abi_name)};
        auto id_str{std::format("{}{}{}", row_color, rows[i].arch_name, ANSI::NONE)};
        auto name_str{std::format("{}{}{}", row_color, rows[i].abi_name, ANSI::NONE)};
        auto dec_str{std::format("{}{}{}", ANSI::FG_WHITE, rows[i].value, ANSI::NONE)};
        auto hex_str{std::format("{}0x{:08x}{}", row_color, rows[i].value, ANSI::NONE)};
        auto desc_str{std::format("{}{}{}", row_color, rows[i].desc, ANSI::NONE)};
        std::print("{}|{}", ANSI::FG_BLUE, ANSI::NONE);
        print_cell_colored(id_str, id_width, true);
        print_cell_colored(name_str, name_width, false);
        print_cell_colored(dec_str, dec_width, true);
        print_cell_colored(hex_str, hex_width, true);
        print_cell_colored(desc_str, desc_width, false);
        std::print("\n");
        print_border(col_widths);
    }
}

std::string_view infoCommand::name() const noexcept
{
    return "info";
}
SDBCommandUsageList infoCommand::usage() const noexcept
{
    static const SDBCommandUsage entries[]{
        {"r", "打印通用寄存器"},
        {"w", "打印监视点状态"}};
    return entries;
}
SDBCommandResult infoCommand::execute(SDBCommandContext &context, std::string_view args)
{
    if (args == "r")
    {
        PrintGPR(context.GetDUT());
        return SDBCommandResult::Continue;
    }
    if (args == "w")
    {
        NPCEvaluationContext EvalContext{context.GetDUT()};
        GetGlobalWatchpointPool().PrintAllWatchpoints(EvalContext);
        return SDBCommandResult::Continue;
    }
    std::println("用法：info r 或 info w");
    return SDBCommandResult::Continue;
}
