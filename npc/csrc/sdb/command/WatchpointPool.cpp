module npc.sdb.command.WatchpointPool;
import npc.unicode;
import npc.sdb.TablePrinter;

WatchpointPool &GetGlobalWatchpointPool()
{
    static WatchpointPool GlobalWatchpointPool;
    return GlobalWatchpointPool;
}

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
Watchpoint *WatchpointPool::GetWatchpoint(std::size_t NO)
{
    if (NO >= watchpoints.size())
    {
        std::println("GetWatchpoint的参数的监视点编号{0}都直接超出范围了", NO);
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
Watchpoint *WatchpointPool::CreateWatchpoint(const std::string &expression, std::size_t InitialValue)
{
    if (FreeWatchpointIndices.empty())
    {
        std::println("CreateWatchpoint失败了，因为vector里面没有空闲的监视点槽位了");
        return nullptr;
    }
    auto NO{FreeWatchpointIndices.back()};
    Watchpoint &wp{watchpoints[NO]};
    wp.SetNO(NO);
    wp.SetExpression(expression);
    wp.SetOldValue(InitialValue);
    wp.SetPC(0);
    wp.SetHasPC(false);
    wp.SetEnabled(true);
    FreeWatchpointIndices.pop_back();
    UsedWatchpointIndices.push_back(NO);
    return &wp;
}
bool WatchpointPool::DeleteWatchpoint(std::size_t NO)
{
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
    // 保险起见，手动复位
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
bool WatchpointPool::CheckAll(const EvaluationContext &context)
{
    expressions expression;
    auto CurrentPC{context.GetPC()};
    bool triggered{false};
    for (std::size_t NO : UsedWatchpointIndices
                          | std::views::filter([&](std::size_t no) { return watchpoints[no].IsEnabled(); }))
    {
        auto &wp{watchpoints[NO]};
        auto result{expression.evaluate(wp.GetExpression(), context)};
        if (!result)
        {
            std::println("监视点{}表达式求值失败：{}", NO, result.error());
            continue;
        }
        auto newValue{*result};
        if (newValue != static_cast<std::uint32_t>(wp.GetOldValue()))
        {
            std::println("监视点{}触发：{} = 0x{:08x}（旧值 0x{:08x}）", NO, wp.GetExpression(), newValue, static_cast<std::uint32_t>(wp.GetOldValue()));
            wp.SetOldValue(newValue);
            wp.SetPC(CurrentPC);
            wp.SetHasPC(true);
            triggered = true;
        }
    }
    return triggered;
}
static const char *skip_leading_spaces(const char *s)
{
    while (*s != '\0' && std::isspace(static_cast<unsigned char>(*s)))
    {
        s++;
    }
    return s;
}
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
static const char *get_type_color(std::string_view type_name)
{
    if (type_name == "寄存器")
    {
        return ANSI::FG_CYAN;
    }
    if (type_name == "解引用")
    {
        return ANSI::FG_YELLOW;
    }
    if (type_name == "表达式")
    {
        return ANSI::FG_MAGENTA;
    }
    return ANSI::FG_GREEN;
}
void WatchpointPool::PrintAllWatchpoints(const EvaluationContext &context) const
{
    if (UsedWatchpointIndices.empty())
    {
        std::println("没有监视点。");
        return;
    }
    std::vector<const Watchpoint *> wps;
    wps.reserve(UsedWatchpointIndices.size());
    for (std::size_t no : UsedWatchpointIndices)
    {
        wps.push_back(&watchpoints[no]);
    }
    std::ranges::sort(wps, {}, [](const Watchpoint *wp)
                      { return wp->GetNO(); });
    expressions ExpressionEvaluate;
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
        auto result{ExpressionEvaluate.evaluate(wp->GetExpression(), context)};
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
    const int val_width{10};
    const int delta_width{11};
    const int trigger_width{10};
    int no_width{display_width("编号")};
    int type_width{std::max({display_width("类型"), display_width("寄存器"), display_width("解引用"), display_width("表达式"), display_width("常量")})};
    int enable_width{std::max({display_width("启用状态"), display_width("启用"), display_width("禁用")})};
    int status_width{std::max({display_width("状态"), display_width("已变化"), display_width("正常"), display_width("无效"), display_width("停用")})};
    int expr_width{display_width("表达式")};
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
    print_border(col_widths);
    std::print("{0}|{1}", ANSI::FG_BLUE, ANSI::NONE);
    print_cell_colored(std::format("{0}编号{1}", ANSI::FG_CYAN, ANSI::NONE), no_width, true);
    print_cell_colored(std::format("{0}类型{1}", ANSI::FG_BLUE, ANSI::NONE), type_width, true);
    print_cell_colored(std::format("{0}旧值{1}", ANSI::FG_WHITE, ANSI::NONE), val_width, true);
    print_cell_colored(std::format("{0}新值{1}", ANSI::FG_GREEN, ANSI::NONE), val_width, true);
    print_cell_colored(std::format("{0}变化量{1}", ANSI::FG_YELLOW, ANSI::NONE), delta_width, true);
    print_cell_colored(std::format("{0}启用状态{1}", ANSI::FG_CYAN, ANSI::NONE), enable_width, true);
    print_cell_colored(std::format("{0}状态{1}", ANSI::FG_MAGENTA, ANSI::NONE), status_width, true);
    print_cell_colored(std::format("{0}触发位置{1}", ANSI::FG_YELLOW, ANSI::NONE), trigger_width, true);
    print_cell_colored(std::format("{0}表达式{1}", ANSI::FG_MAGENTA, ANSI::NONE), expr_width, false);
    std::print("\n");
    print_border(col_widths);
    for (std::size_t i{0}; i < wps.size(); i++)
    {
        const auto *wp{wps[i]};
        const auto &eval{evals[i]};
        const auto type_name{get_watchpoint_type_name(wp->GetExpression().c_str())};
        const auto type_color{get_type_color(type_name)};
        auto no_color{ANSI::FG_CYAN};
        if (!wp->IsEnabled())
        {
            no_color = ANSI::FG_WHITE;
        }
        else if (!eval.success)
        {
            no_color = ANSI::FG_RED;
        }
        else if (eval.changed)
        {
            no_color = ANSI::FG_YELLOW;
        }
        std::string no_str{std::format("{0}{1}{2}", no_color, wp->GetNO(), ANSI::NONE)};
        std::string type_str{std::format("{0}{1}{2}", type_color, type_name, ANSI::NONE)};
        std::string old_str{std::format("{0}0x{1:08x}{2}", ANSI::FG_WHITE, wp->GetOldValue(), ANSI::NONE)};
        std::string cur_str;
        std::string delta_str;
        std::string enable_str;
        std::string status_str;
        std::string trigger_str;
        auto expr_color{type_color};
        if (!wp->IsEnabled())
        {
            expr_color = ANSI::FG_WHITE;
        }
        else if (!eval.success)
        {
            expr_color = ANSI::FG_RED;
        }
        else if (eval.changed)
        {
            expr_color = ANSI::FG_YELLOW;
        }
        std::string expr_str{std::format("{0}{1}{2}", expr_color, wp->GetExpression(), ANSI::NONE)};
        if (!wp->IsEnabled())
        {
            cur_str = std::format("{0}-{1}", ANSI::FG_WHITE, ANSI::NONE);
            enable_str = std::format("{0}禁用{1}", ANSI::FG_RED, ANSI::NONE);
            status_str = std::format("{0}停用{1}", ANSI::FG_MAGENTA, ANSI::NONE);
            delta_str = std::format("{0}N/A{1}", ANSI::FG_RED, ANSI::NONE);
            trigger_str = std::format("{0}-{1}", ANSI::FG_WHITE, ANSI::NONE);
        }
        else if (!eval.success)
        {
            cur_str = std::format("{0}N/A{1}", ANSI::FG_RED, ANSI::NONE);
            enable_str = std::format("{0}启用{1}", ANSI::FG_GREEN, ANSI::NONE);
            status_str = std::format("{0}无效{1}", ANSI::FG_RED, ANSI::NONE);
            delta_str = std::format("{0}N/A{1}", ANSI::FG_RED, ANSI::NONE);
            if (wp->HasValidPC())
            {
                trigger_str = std::format("{0}0x{1:08x}{2}", ANSI::FG_BLUE, wp->GetPC(), ANSI::NONE);
            }
            else
            {
                trigger_str = std::format("{0}-{1}", ANSI::FG_WHITE, ANSI::NONE);
            }
        }
        else
        {
            enable_str = std::format("{0}启用{1}", ANSI::FG_GREEN, ANSI::NONE);
            if (eval.changed)
            {
                cur_str = std::format("{0}0x{1:08x}{2}", ANSI::FG_YELLOW, eval.current_val, ANSI::NONE);
                status_str = std::format("{0}已变化{1}", ANSI::FG_YELLOW, ANSI::NONE);
            }
            else
            {
                cur_str = std::format("{0}0x{1:08x}{2}", ANSI::FG_GREEN, eval.current_val, ANSI::NONE);
                status_str = std::format("{0}正常{1}", ANSI::FG_GREEN, ANSI::NONE);
            }
            const auto old_val{static_cast<std::uint32_t>(wp->GetOldValue())};
            if (eval.current_val >= old_val)
            {
                const auto d{eval.current_val - old_val};
                if (d == 0)
                {
                    delta_str = std::format("{0}+0x{1:08x}{2}", ANSI::FG_BLUE, d, ANSI::NONE);
                }
                else
                {
                    delta_str = std::format("{0}+0x{1:08x}{2}", ANSI::FG_YELLOW, d, ANSI::NONE);
                }
            }
            else
            {
                const auto d{old_val - eval.current_val};
                delta_str = std::format("{0}-0x{1:08x}{2}", ANSI::FG_MAGENTA, d, ANSI::NONE);
            }
            if (wp->HasValidPC())
            {
                trigger_str = std::format("{0}0x{1:08x}{2}", ANSI::FG_BLUE, wp->GetPC(), ANSI::NONE);
            }
            else
            {
                trigger_str = std::format("{0}-{1}", ANSI::FG_WHITE, ANSI::NONE);
            }
        }
        std::print("{0}|{1}", ANSI::FG_BLUE, ANSI::NONE);
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
