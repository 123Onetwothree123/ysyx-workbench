#ifndef WATCH_POINT_POOL_HPP
#define WATCH_POINT_POOL_HPP
#include "Watchpoint.hpp"
#include <print>
#include <cstdint>
#include <vector>
class WatchpointPool
{
private:
    std::vector<Watchpoint> watchpoints;
    std::vector<std::size_t> FreeWatchpointIndices; // 存储空闲监视点编号的栈
    std::vector<std::size_t> UsedWatchpointIndices; // 存储已使用监视点编号的列表
public:
    explicit WatchpointPool(const std::size_t InputMaxWatchpoints = 32);
    ~WatchpointPool() = default;
    bool DeleteWatchpoint(std::size_t NO);
    Watchpoint *CreateWatchpoint(const std::string &expression, std::size_t InitialValue);
    Watchpoint *GetWatchpoint(std::size_t NO);
    const std::vector<Watchpoint> &GetAllWatchpoints() const noexcept;
    std::size_t GetMaxWatchpoints() const noexcept;
    bool CheckAll();                  // 遍历所有活跃监视点，重新求职表达式，对比旧数值，如果触发了就更新然后返回真
    void PrintAllWatchpoints() const; // 目前打算和NEMU一样输出表格的，但是还不知道怎么设计，打算给AI设计了
};
inline WatchpointPool &GetGlobalWatchpointPool()
{
    static WatchpointPool GlobalWatchpointPool;
    return GlobalWatchpointPool;
}
#endif