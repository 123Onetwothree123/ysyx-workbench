#include "WatchpointPool.hpp"
#include "tools/Expressions/Expressions.hpp"
#include "NPCEvaluationContext.hpp"
#include "SDBDPI.hpp"
#include <algorithm>
#include <cstddef>
#include <cctype>
#include <cstring>
#include <format>
// ANSI颜色宏（参考NEMU设计）
#define ANSI_NONE "\033[0m"
#define ANSI_FG_BLACK "\033[1;30m"
#define ANSI_FG_RED "\033[1;31m"
#define ANSI_FG_GREEN "\033[1;32m"
#define ANSI_FG_YELLOW "\033[1;33m"
#define ANSI_FG_BLUE "\033[1;34m"
#define ANSI_FG_MAGENTA "\033[1;35m"
#define ANSI_FG_CYAN "\033[1;36m"
#define ANSI_FG_WHITE "\033[1;37m"
WatchpointPool::WatchpointPool(std::size_t InputMaxWatchpoints)
    : watchpoints(InputMaxWatchpoints)
{
    for (std::size_t i{InputMaxWatchpoints}; i > 0; --i)
    {
        const auto NO{i - 1};
        watchpoints[NO].SetNO(NO);
        FreeWatchpointIndices.push_back(NO);
    }
}
bool WatchpointPool::DeleteWatchpoint(std::size_t NO)
{
    // 删除这个编号的监视点
    if (NO >= watchpoints.size())
    {
        std::println("DeleteWatchpoint监视点编号{0}超出范围", NO);
        return false;
    }
    if (!watchpoints[NO].IsEnabled())
    {
        std::println("DeleteWatchpoint: 监视点{0}不在使用中", NO);
        return false;
    }
    // watchpoints[NO] = Watchpoint(NO, false, 0, false); ai说不安全，建议别这么写，所以我手动清除
    watchpoints[NO].SetEnabled(false);
    watchpoints[NO].SetExpression("");
    watchpoints[NO].SetOldValue(0);
    watchpoints[NO].SetPC(0);
    watchpoints[NO].SetHasPC(0);
    auto it{std::ranges::find(UsedWatchpointIndices, NO)};
    if (it != UsedWatchpointIndices.end())
    {
        UsedWatchpointIndices.erase(it);
    }
    FreeWatchpointIndices.push_back(NO);
    return true;
}
Watchpoint *WatchpointPool::CreateWatchpoint(const std::string &expression, std::size_t InitialValue)
{
    if (FreeWatchpointIndices.empty())
    {
        std::println("CreateWatchpoint失败，没有空闲的监视点槽位了");
        return nullptr;
    }
    auto NO{FreeWatchpointIndices.back()};
    FreeWatchpointIndices.pop_back();
    UsedWatchpointIndices.push_back(NO);
    Watchpoint &wp{watchpoints[NO]};
    wp.SetNO(NO);
    wp.SetExpression(expression);
    wp.SetOldValue(InitialValue);
    wp.SetPC(0);
    wp.SetHasPC(false);
    wp.SetEnabled(true);
    return &wp;
}
Watchpoint *WatchpointPool::GetWatchpoint(std::size_t NO)
{
    if (NO >= watchpoints.size())
    {
        std::println("GetWatchpoint监视点编号{0}超出范围", NO);
        return nullptr;
    }
    return &watchpoints[NO];
}
const std::vector<Watchpoint> &WatchpointPool::GetAllWatchpoints() const noexcept
{
    return watchpoints;
}
std::size_t WatchpointPool::GetMaxWatchpoints() const noexcept
{
    return watchpoints.size();
}
bool WatchpointPool::CheckAll()
{
    Expressions expression;
    NPCEvaluationContext context;
    const auto CurrentPC{CPP_NPCGetPC()};
    bool WPTriggered{false}; // 是否有监视点触发
    for (std::size_t NO : UsedWatchpointIndices)
    {
        auto &wp{watchpoints[NO]};
        if (!wp.IsEnabled())
        {
            continue;
        }
        auto result{expression.Evaluate(wp.GetExpression(), context)};
        if (!result)
        {
            std::println("我也不知道为什么，反正表达式求值失败了");
            std::println("监视点={0}，表达式={1}", NO, result.error());
            continue;
        }
        auto NewValue{result.value()};
        if (NewValue != static_cast<std::uint32_t>(wp.GetOldValue()))
        {
            wp.SetOldValue(NewValue);
            wp.SetPC(CurrentPC);
            wp.SetHasPC(true);
            WPTriggered = true;
            std::println("监视点{0}出发了，表达式：{1}=0x{2:08x}，PC=0x{3:08x}", NO, wp.GetExpression(), NewValue, CurrentPC);
        }
    }
    return WPTriggered;
}
// 辅助函数：计算字符串显示宽度（ASCII=1, CJK等=2）
static int display_width(std::string_view s)
{
    auto w{0};
    for (std::size_t i{0}; i < s.size();)
    {
        const auto c{static_cast<unsigned char>(s[i])};
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
// 辅助函数：跳过前导空格
static const char *skip_leading_spaces(const char *s)
{
    while (*s != '\0' && std::isspace(static_cast<unsigned char>(*s)))
    {
        s++;
    }
    return s;
}
// 辅助函数：获取监视点类型名称（参考NEMU）
static const char *get_watchpoint_type_name(const char *expr)
{
    auto p{skip_leading_spaces(expr)};
    if (*p == '*')
    {
        return "解引用";
    }
    if (*p == '$')
    {
        p++;
        while (*p != '\0' && (std::isalnum(static_cast<unsigned char>(*p)) || *p == '_'))
        {
            p++;
        }
        p = skip_leading_spaces(p);
        return (*p == '\0') ? "寄存器" : "表达式";
    }
    for (; *p != '\0'; p++)
    {
        if (std::strchr("+-*/()=!&|%^<>", *p) != nullptr)
        {
            return "表达式";
        }
    }
    return "常量";
}
// 辅助函数：根据类型名获取颜色
static const char *get_type_color(const char *type_name)
{
    if (std::strcmp(type_name, "寄存器") == 0)
    {
        return ANSI_FG_CYAN;
    }
    if (std::strcmp(type_name, "解引用") == 0)
    {
        return ANSI_FG_YELLOW;
    }
    if (std::strcmp(type_name, "表达式") == 0)
    {
        return ANSI_FG_MAGENTA;
    }
    return ANSI_FG_GREEN;
}
// 辅助函数：打印表格边框（带颜色）
static void print_border(const std::vector<int> &widths)
{
    std::print("{0}+{1}", ANSI_FG_BLUE, ANSI_NONE);
    for (int w : widths)
    {
        for (int i{0}; i < w + 2; i++)
        {
            std::print("{0}-{1}", ANSI_FG_BLUE, ANSI_NONE);
        }
        std::print("{0}+{1}", ANSI_FG_BLUE, ANSI_NONE);
    }
    std::print("\n");
}
// 辅助函数：打印表格单元格（支持颜色内容，按纯文本计算宽度）
static void print_cell_colored(std::string_view raw_content, int width, bool center)
{
    // raw_content可能包含ANSI转义码，计算显示宽度时需要跳过
    auto content_width{0};
    for (std::size_t i{0}; i < raw_content.size();)
    {
        if (raw_content[i] == '\033' && i + 1 < raw_content.size() && raw_content[i + 1] == '[')
        {
            // 跳过ANSI转义序列 \033[ ... m
            i += 2;
            while (i < raw_content.size() && !(raw_content[i] >= 0x40 && raw_content[i] <= 0x7e))
            {
                i++;
            }
            if (i < raw_content.size())
            {
                i++;
            }
            continue;
        }
        const auto c{static_cast<unsigned char>(raw_content[i])};
        if (c < 0x80)
        {
            content_width += 1;
            i += 1;
        }
        else if ((c & 0xE0) == 0xC0)
        {
            content_width += 2;
            i += 2;
        }
        else if ((c & 0xF0) == 0xE0)
        {
            content_width += 2;
            i += 3;
        }
        else if ((c & 0xF8) == 0xF0)
        {
            content_width += 2;
            i += 4;
        }
        else
        {
            i += 1;
        }
    }
    auto pad{width - content_width};
    if (pad < 0)
    {
        pad = 0;
    }
    auto left_pad{center ? pad / 2 : 0};
    auto right_pad{pad - left_pad};
    std::print(" ");
    for (int i{0}; i < left_pad; i++)
    {
        std::print(" ");
    }
    std::print("{0}", raw_content);
    for (int i{0}; i < right_pad; i++)
    {
        std::print(" ");
    }
    std::print(" {0}|{1}", ANSI_FG_BLUE, ANSI_NONE);
}
void WatchpointPool::PrintAllWatchpoints() const
{
    if (UsedWatchpointIndices.empty())
    {
        std::println("没有监视点。");
        return;
    }
    // 收集所有已使用的监视点
    std::vector<const Watchpoint *> wps;
    wps.reserve(UsedWatchpointIndices.size());
    for (std::size_t no : UsedWatchpointIndices)
    {
        wps.push_back(&watchpoints[no]);
    }
    // 按编号升序排序
    std::ranges::sort(wps, {}, &Watchpoint::GetNO);
    // 先统一求值，避免调试日志插入表格中间
    Expressions expr_eval;
    NPCEvaluationContext context;
    struct EvalResult
    {
        bool success{false};
        std::uint32_t current_val{0};
        bool changed{false};
    };
    std::vector<EvalResult> evals(wps.size());
    for (std::size_t i{0}; i < wps.size(); i++)
    {
        const auto *wp{wps[i]};
        if (!wp->IsEnabled())
        {
            evals[i] = {false, static_cast<std::uint32_t>(wp->GetOldValue()), false};
            continue;
        }
        auto result{expr_eval.Evaluate(wp->GetExpression(), context)};
        if (result)
        {
            const auto val{result.value()};
            evals[i] = {true, val, val != static_cast<std::uint32_t>(wp->GetOldValue())};
        }
        else
        {
            evals[i] = {false, static_cast<std::uint32_t>(wp->GetOldValue()), false};
        }
    }
    // 计算各列宽度
    const int val_width{10};   // 0x00000000
    const int delta_width{11}; // +0x00000000或 -0x00000000
    const int trigger_width{10};
    int no_width{display_width("编号")};
    int type_width{display_width("类型")};
    int enable_width{display_width("启用状态")};
    int status_width{display_width("状态")};
    int expr_width{display_width("表达式")};
    if (display_width("寄存器") > type_width)
    {
        type_width = display_width("寄存器");
    }
    if (display_width("解引用") > type_width)
    {
        type_width = display_width("解引用");
    }
    if (display_width("表达式") > type_width)
    {
        type_width = display_width("表达式");
    }
    if (display_width("常量") > type_width)
    {
        type_width = display_width("常量");
    }
    if (display_width("启用") > enable_width)
    {
        enable_width = display_width("启用");
    }
    if (display_width("禁用") > enable_width)
    {
        enable_width = display_width("禁用");
    }
    if (display_width("已变化") > status_width)
    {
        status_width = display_width("已变化");
    }
    if (display_width("正常") > status_width)
    {
        status_width = display_width("正常");
    }
    if (display_width("无效") > status_width)
    {
        status_width = display_width("无效");
    }
    if (display_width("停用") > status_width)
    {
        status_width = display_width("停用");
    }
    for (const auto *wp : wps)
    {
        int w{display_width(std::to_string(wp->GetNO()))};
        if (w > no_width)
        {
            no_width = w;
        }
        w = display_width(get_watchpoint_type_name(wp->GetExpression().c_str()));
        if (w > type_width)
        {
            type_width = w;
        }
        w = display_width(wp->GetExpression());
        if (w > expr_width)
        {
            expr_width = w;
        }
    }
    std::vector<int> col_widths{
        no_width, type_width, val_width, val_width, delta_width,
        enable_width, status_width, trigger_width, expr_width};
    // 打印表头（带颜色）
    print_border(col_widths);
    std::print("{0}|{1}", ANSI_FG_BLUE, ANSI_NONE);
    print_cell_colored(std::format("{0}编号{1}", ANSI_FG_CYAN, ANSI_NONE), no_width, true);
    print_cell_colored(std::format("{0}类型{1}", ANSI_FG_BLUE, ANSI_NONE), type_width, true);
    print_cell_colored(std::format("{0}旧值{1}", ANSI_FG_WHITE, ANSI_NONE), val_width, true);
    print_cell_colored(std::format("{0}新值{1}", ANSI_FG_GREEN, ANSI_NONE), val_width, true);
    print_cell_colored(std::format("{0}变化量{1}", ANSI_FG_YELLOW, ANSI_NONE), delta_width, true);
    print_cell_colored(std::format("{0}启用状态{1}", ANSI_FG_CYAN, ANSI_NONE), enable_width, true);
    print_cell_colored(std::format("{0}状态{1}", ANSI_FG_MAGENTA, ANSI_NONE), status_width, true);
    print_cell_colored(std::format("{0}触发位置{1}", ANSI_FG_YELLOW, ANSI_NONE), trigger_width, true);
    print_cell_colored(std::format("{0}表达式{1}", ANSI_FG_MAGENTA, ANSI_NONE), expr_width, false);
    std::print("\n");
    print_border(col_widths);
    // 打印每一行
    for (std::size_t i{0}; i < wps.size(); i++)
    {
        const auto *wp{wps[i]};
        const auto &eval{evals[i]};
        const auto type_name{get_watchpoint_type_name(wp->GetExpression().c_str())};
        const auto type_color{get_type_color(type_name)};
        // 编号颜色
        auto no_color{ANSI_FG_CYAN};
        if (!wp->IsEnabled())
        {
            no_color = ANSI_FG_WHITE;
        }
        else if (!eval.success)
        {
            no_color = ANSI_FG_RED;
        }
        else if (eval.changed)
        {
            no_color = ANSI_FG_YELLOW;
        }
        std::string no_str{std::format("{0}{1}{2}", no_color, wp->GetNO(), ANSI_NONE)};
        std::string type_str{std::format("{0}{1}{2}", type_color, type_name, ANSI_NONE)};
        std::string old_str{std::format("{0}0x{1:08x}{2}", ANSI_FG_WHITE, wp->GetOldValue(), ANSI_NONE)};
        std::string cur_str;
        std::string delta_str;
        std::string enable_str;
        std::string status_str;
        std::string trigger_str;
        // 表达式颜色
        auto expr_color{type_color};
        if (!wp->IsEnabled())
        {
            expr_color = ANSI_FG_WHITE;
        }
        else if (!eval.success)
        {
            expr_color = ANSI_FG_RED;
        }
        else if (eval.changed)
        {
            expr_color = ANSI_FG_YELLOW;
        }
        std::string expr_str{std::format("{0}{1}{2}", expr_color, wp->GetExpression(), ANSI_NONE)};
        if (!wp->IsEnabled())
        {
            cur_str = std::format("{0}-{1}", ANSI_FG_WHITE, ANSI_NONE);
            enable_str = std::format("{0}禁用{1}", ANSI_FG_RED, ANSI_NONE);
            status_str = std::format("{0}停用{1}", ANSI_FG_MAGENTA, ANSI_NONE);
            delta_str = std::format("{0}N/A{1}", ANSI_FG_RED, ANSI_NONE);
            trigger_str = std::format("{0}-{1}", ANSI_FG_WHITE, ANSI_NONE);
        }
        else if (!eval.success)
        {
            cur_str = std::format("{0}N/A{1}", ANSI_FG_RED, ANSI_NONE);
            enable_str = std::format("{0}启用{1}", ANSI_FG_GREEN, ANSI_NONE);
            status_str = std::format("{0}无效{1}", ANSI_FG_RED, ANSI_NONE);
            delta_str = std::format("{0}N/A{1}", ANSI_FG_RED, ANSI_NONE);
            if (wp->HasValidPC())
            {
                trigger_str = std::format("{0}0x{1:08x}{2}", ANSI_FG_BLUE, wp->GetPC(), ANSI_NONE);
            }
            else
            {
                trigger_str = std::format("{0}-{1}", ANSI_FG_WHITE, ANSI_NONE);
            }
        }
        else
        {
            enable_str = std::format("{0}启用{1}", ANSI_FG_GREEN, ANSI_NONE);
            if (eval.changed)
            {
                cur_str = std::format("{0}0x{1:08x}{2}", ANSI_FG_YELLOW, eval.current_val, ANSI_NONE);
                status_str = std::format("{0}已变化{1}", ANSI_FG_YELLOW, ANSI_NONE);
            }
            else
            {
                cur_str = std::format("{0}0x{1:08x}{2}", ANSI_FG_GREEN, eval.current_val, ANSI_NONE);
                status_str = std::format("{0}正常{1}", ANSI_FG_GREEN, ANSI_NONE);
            }
            const auto old_val{static_cast<std::uint32_t>(wp->GetOldValue())};
            if (eval.current_val >= old_val)
            {
                const auto d{eval.current_val - old_val};
                if (d == 0)
                {
                    delta_str = std::format("{0}+0x{1:08x}{2}", ANSI_FG_BLUE, d, ANSI_NONE);
                }
                else
                {
                    delta_str = std::format("{0}+0x{1:08x}{2}", ANSI_FG_YELLOW, d, ANSI_NONE);
                }
            }
            else
            {
                const auto d{old_val - eval.current_val};
                delta_str = std::format("{0}-0x{1:08x}{2}", ANSI_FG_MAGENTA, d, ANSI_NONE);
            }
            if (wp->HasValidPC())
            {
                trigger_str = std::format("{0}0x{1:08x}{2}", ANSI_FG_BLUE, wp->GetPC(), ANSI_NONE);
            }
            else
            {
                trigger_str = std::format("{0}-{1}", ANSI_FG_WHITE, ANSI_NONE);
            }
        }
        std::print("{0}|{1}", ANSI_FG_BLUE, ANSI_NONE);
        print_cell_colored(no_str, no_width, true);
        print_cell_colored(type_str, type_width, true);
        print_cell_colored(old_str, val_width, true);
        print_cell_colored(cur_str, val_width, true);
        print_cell_colored(delta_str, delta_width, true);
        print_cell_colored(enable_str, enable_width, true);
        print_cell_colored(status_str, status_width, true);
        print_cell_colored(trigger_str, trigger_width, true);
        print_cell_colored(expr_str, expr_width, false);
        std::print("\n");
        print_border(col_widths);
    }
}
// 供infoCommand等调用的全局函数
void PrintWatchpoints()
{
    GetGlobalWatchpointPool().PrintAllWatchpoints();
}
