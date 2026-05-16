#include "SDBDPI.hpp"
#include <svdpi.h>
#include <array>
#include <cstring>
#include <format>
#include <iostream>
#include <print>
#include <string>
#include <vector>

extern "C" int NPCGetGPR(int RegNum);
extern "C" int NPCGetPC();

namespace
{
    std::string DPIInstanceScope{"TOP"};
    std::string DPITopScope{"TOP"};

// ANSI 颜色宏（与 NEMU 保持一致）
#define ANSI_NONE "\033[0m"
#define ANSI_FG_BLACK "\033[1;30m"
#define ANSI_FG_RED "\033[1;31m"
#define ANSI_FG_GREEN "\033[1;32m"
#define ANSI_FG_YELLOW "\033[1;33m"
#define ANSI_FG_BLUE "\033[1;34m"
#define ANSI_FG_MAGENTA "\033[1;35m"
#define ANSI_FG_CYAN "\033[1;36m"
#define ANSI_FG_WHITE "\033[1;37m"

    /**
     * @brief 将 Verilated 模型名称转换为 DPI scope 名称
     * @param ModelName 模型名称（通常以 V 开头）
     * @return 去掉前导 V 后的 scope 名称
     */
    std::string VerilatedModelNameToScope(std::string_view ModelName)
    {
        std::string ScopeName{ModelName};
        if (ScopeName.size() > 1 && ScopeName.front() == 'V')
        {
            ScopeName.erase(0, 1);
        }
        return ScopeName;
    }
    /**
     * @brief 设置当前 DPI 调用的 SystemVerilog 作用域
     * @param SubScope 目标子模块名称，如 "SDB" 或 "PC_DPI"
     * @return 若成功找到并设置作用域则返回 true，否则返回 false
     *
     * 依次尝试以下候选作用域：
     *   - DPITopScope + "." + SubScope
     *   - DPIInstanceScope + "." + DPITopScope + "." + SubScope
     *   - DPIInstanceScope + "." + SubScope
     *   - SubScope 本身
     */
    bool SetDPIScope(const char *SubScope)
    {
        // 这段是AI给的建议写的，就算用数组来构建候选作用域，因为verilator生成的层次路径是不固定的，所以覆盖各种可能的路径组合
        const std::array ScopeCandidates{
            DPITopScope + "." + SubScope,
            DPIInstanceScope + "." + DPITopScope + "." + SubScope,
            DPIInstanceScope + "." + SubScope,
            std::string{SubScope},
        };
        // 每一个都去查
        for (const auto &ScopeName : ScopeCandidates)
        {
            const svScope Scope{svGetScopeFromName(ScopeName.c_str())};
            if (Scope != nullptr)
            {
                svSetScope(Scope);
                return true;
            }
        }
        std::println(std::cerr, "找不到DPI scope：{}", ScopeCandidates[0]);
        return false;
    }
} // namespace
/**
 * @brief 设置 DPI 的顶层作用域
 * @param InstanceScope Verilator 实例的作用域名称
 * @param ModelName 顶层模块名称
 *
 * 根据实例作用域和模型名称设置 DPI 的顶层作用域，以便 DPI 函数能够直接访问 SDB 模块。
 */
void SDBDPISetTopScope(std::string_view InstanceScope, std::string_view ModelName)
{
    DPIInstanceScope = std::string{InstanceScope};
    DPITopScope = VerilatedModelNameToScope(ModelName);
}
/**
 * @brief 获取指定通用寄存器的值
 * @param RegNum 寄存器编号，范围为 0-31
 * @return 寄存器的 32 位值；若 RegNum 不合法或无法获取 DPI scope 则返回 0
 */
std::uint32_t CPP_NPCGetGPR(int RegNum)
{
    if (RegNum < 0 || RegNum >= 32)
    {
        return 0;
    }
    if (!SetDPIScope("SDB"))
    {
        return 0;
    }
    return static_cast<std::uint32_t>(NPCGetGPR(RegNum));
}
/**
 * @brief 获取当前 PC（程序计数器）的值
 * @return 当前 PC 的 32 位值；若无法获取 DPI scope 则返回 0
 */
std::uint32_t CPP_NPCGetPC()
{
    if (!SetDPIScope("PC_DPI"))
    {
        return 0;
    }
    return static_cast<std::uint32_t>(NPCGetPC());
}

// ---------------------------------------------------------------------------
// 寄存器表格辅助函数（与 NEMU 风格一致，支持 ANSI 颜色）
// ---------------------------------------------------------------------------

/**
 * @brief 计算字符串的终端显示宽度（跳过 ANSI 转义序列）
 * @param s 输入字符串
 * @return 显示宽度（ASCII 字符计 1，CJK 等宽字符计 2）
 */
static int display_width(std::string_view s)
{
    int w{0};
    for (size_t i{0}; i < s.size();)
    {
        // 跳过 ANSI 转义序列 \033[ ... m
        if (s[i] == '\033' && i + 1 < s.size() && s[i + 1] == '[')
        {
            i += 2;
            while (i < s.size() && !(s[i] >= 0x40 && s[i] <= 0x7e))
            {
                i++;
            }
            if (i < s.size())
            {
                i++;
            }
            continue;
        }
        unsigned char c{static_cast<unsigned char>(s[i])};
        if (c < 0x80)
        {
            w += 1;
            i += 1;
        }
        else if ((c & 0xE0) == 0xC0)
        {
            w += 2;
            i += 2;
        }
        else if ((c & 0xF0) == 0xE0)
        {
            w += 2;
            i += 3;
        }
        else if ((c & 0xF8) == 0xF0)
        {
            w += 2;
            i += 4;
        }
        else
        {
            i += 1;
        }
    }
    return w;
}

/**
 * @brief 打印 ASCII 表格边框（带颜色）
 * @param widths 各列宽度数组
 */
static void print_border(const std::vector<int> &widths)
{
    std::print("{}+{}", ANSI_FG_BLUE, ANSI_NONE);
    for (int w : widths)
    {
        for (int i{0}; i < w + 2; i++)
        {
            std::print("{}-{}", ANSI_FG_BLUE, ANSI_NONE);
        }
        std::print("{}+{}", ANSI_FG_BLUE, ANSI_NONE);
    }
    std::print("\n");
}

/**
 * @brief 打印表格单元格（支持颜色内容，按纯文本计算宽度）
 * @param raw_content 单元格文本内容（可能包含 ANSI 转义码）
 * @param width 单元格显示宽度
 * @param center 是否居中对齐（true 居中，false 左对齐）
 */
static void print_cell_colored(std::string_view raw_content, int width, bool center)
{
    int content_width{display_width(raw_content)};
    int pad{width - content_width};
    if (pad < 0)
    {
        pad = 0;
    }
    int left_pad{center ? pad / 2 : 0};
    int right_pad{pad - left_pad};
    std::print(" ");
    for (int i{0}; i < left_pad; i++)
    {
        std::print(" ");
    }
    std::print("{}", raw_content);
    for (int i{0}; i < right_pad; i++)
    {
        std::print(" ");
    }
    std::print(" {}|{}", ANSI_FG_BLUE, ANSI_NONE);
}

/**
 * @brief 获取 RISC-V 通用寄存器的 ABI 名称
 * @param idx 寄存器编号（0-31）
 * @return ABI 名称（如 zero, ra, sp 等）；编号非法时返回空字符串
 */
static constexpr std::string_view get_reg_abi_name(int idx) noexcept
{
    // 直接用CTAD自动推导了
    constexpr std::array abi{
        "zero",
        "ra",
        "sp",
        "gp",
        "tp",
        "t0",
        "t1",
        "t2",
        "s0",
        "s1",
        "a0",
        "a1",
        "a2",
        "a3",
        "a4",
        "a5",
        "a6",
        "a7",
        "s2",
        "s3",
        "s4",
        "s5",
        "s6",
        "s7",
        "s8",
        "s9",
        "s10",
        "s11",
        "t3",
        "t4",
        "t5",
        "t6",
    };
    if (idx >= 0 && static_cast<std::size_t>(idx) < abi.size())
    {
        return abi[idx];
    }
    return {};
}

/**
 * @brief 获取寄存器的中文功能描述
 * @param arch_name 架构名称（如 pc, x0, x1 等）
 * @param abi_name ABI 名称（如 pc, zero, ra 等）
 * @return 中文描述字符串（如"程序计数器"、"栈指针"等）
 */
static const char *get_reg_desc(const char *arch_name, std::string_view abi_name)
{
    if (strcmp(arch_name, "pc") == 0)
    {
        return "程序计数器";
    }
    if (strcmp(arch_name, "x0") == 0)
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

/**
 * @brief 获取表头列的颜色（与 NEMU 一致）
 */
static const char *get_reg_header_color(const char *title)
{
    if (strcmp(title, "编号") == 0)
    {
        return ANSI_FG_CYAN;
    }
    if (strcmp(title, "寄存器") == 0)
    {
        return ANSI_FG_BLUE;
    }
    if (strcmp(title, "十进制") == 0)
    {
        return ANSI_FG_WHITE;
    }
    if (strcmp(title, "十六进制") == 0)
    {
        return ANSI_FG_GREEN;
    }
    if (strcmp(title, "说明") == 0)
    {
        return ANSI_FG_YELLOW;
    }
    return ANSI_FG_WHITE;
}

/**
 * @brief 获取寄存器行的颜色（与 NEMU 一致）
 */
static const char *get_reg_row_color(const char *arch_name, std::string_view abi_name)
{
    if (strcmp(arch_name, "pc") == 0)
    {
        return ANSI_FG_YELLOW;
    }
    if (strcmp(arch_name, "x0") == 0 || abi_name == "zero")
    {
        return ANSI_FG_WHITE;
    }
    if (abi_name == "ra")
    {
        return ANSI_FG_CYAN;
    }
    if (abi_name == "sp")
    {
        return ANSI_FG_YELLOW;
    }
    if (abi_name == "gp")
    {
        return ANSI_FG_BLUE;
    }
    if (abi_name == "tp")
    {
        return ANSI_FG_MAGENTA;
    }
    if (!abi_name.empty() && abi_name[0] == 'a')
    {
        return ANSI_FG_GREEN;
    }
    if (!abi_name.empty() && abi_name[0] == 's')
    {
        return ANSI_FG_CYAN;
    }
    if (!abi_name.empty() && abi_name[0] == 't')
    {
        return ANSI_FG_MAGENTA;
    }
    return ANSI_FG_WHITE;
}

/**
 * @brief 打印所有 32 个通用寄存器及 PC 的值（NEMU 风格彩色表格）
 */
void PrintGPR()
{
    struct RegRow
    {
        std::string arch_name;
        std::string_view abi_name;
        std::uint32_t value;
        const char *desc;
    };

    const int nr_rows{33}; // x0-x31 + pc
    RegRow rows[nr_rows];

    for (int i{0}; i < nr_rows; i++)
    {
        if (i == 0)
        {
            rows[i].arch_name = "pc";
            rows[i].abi_name = "pc";
            rows[i].value = CPP_NPCGetPC();
        }
        else
        {
            int reg_idx{i - 1};
            rows[i].arch_name = std::format("x{}", reg_idx);
            rows[i].abi_name = get_reg_abi_name(reg_idx);
            rows[i].value = CPP_NPCGetGPR(reg_idx);
        }
        rows[i].desc = get_reg_desc(rows[i].arch_name.c_str(), rows[i].abi_name);
    }

    // 计算列宽
    int id_width{display_width("编号")};
    int name_width{display_width("寄存器")};
    int dec_width{display_width("十进制")};
    int hex_width{display_width("十六进制")};
    int desc_width{display_width("说明")};

    for (int i{0}; i < nr_rows; i++)
    {
        int w{display_width(rows[i].arch_name)};
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

    std::vector<int> col_widths{id_width, name_width, dec_width, hex_width, desc_width};

    // 打印表头（带颜色）
    std::println("寄存器状态：");
    print_border(col_widths);
    std::print("{}|{}", ANSI_FG_BLUE, ANSI_NONE);
    print_cell_colored(std::format("{}编号{}", get_reg_header_color("编号"), ANSI_NONE), id_width, true);
    print_cell_colored(std::format("{}寄存器{}", get_reg_header_color("寄存器"), ANSI_NONE), name_width, false);
    print_cell_colored(std::format("{}十进制{}", get_reg_header_color("十进制"), ANSI_NONE), dec_width, true);
    print_cell_colored(std::format("{}十六进制{}", get_reg_header_color("十六进制"), ANSI_NONE), hex_width, true);
    print_cell_colored(std::format("{}说明{}", get_reg_header_color("说明"), ANSI_NONE), desc_width, false);
    std::print("\n");
    print_border(col_widths);

    // 打印每一行（带颜色）
    for (int i{0}; i < nr_rows; i++)
    {
        const char *row_color{get_reg_row_color(rows[i].arch_name.c_str(), rows[i].abi_name)};

        std::string id_str{std::format("{}{}{}", row_color, rows[i].arch_name, ANSI_NONE)};
        std::string name_str{std::format("{}{}{}", row_color, rows[i].abi_name, ANSI_NONE)};
        std::string dec_str{std::format("{}{}{}", ANSI_FG_WHITE, rows[i].value, ANSI_NONE)};
        std::string hex_str{std::format("{}0x{:08x}{}", row_color, rows[i].value, ANSI_NONE)};
        std::string desc_str{std::format("{}{}{}", row_color, rows[i].desc, ANSI_NONE)};

        std::print("{}|{}", ANSI_FG_BLUE, ANSI_NONE);
        print_cell_colored(id_str, id_width, true);
        print_cell_colored(name_str, name_width, false);
        print_cell_colored(dec_str, dec_width, true);
        print_cell_colored(hex_str, hex_width, true);
        print_cell_colored(desc_str, desc_width, false);
        std::print("\n");
        print_border(col_widths);
    }
}
