#include "WatchpointPool.hpp"
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
    for (std::size_t NO : UsedWatchpointIndices)
    {
        auto &wp{watchpoints[NO]};
        if (!wp.IsEnabled())
        {
            continue;
        }
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
void WatchpointPool::PrintAllWatchpoints()const{
    //后面再写吧
}