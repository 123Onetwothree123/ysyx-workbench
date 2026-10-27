export module npc.sdb.command.WatchpointPool;
import std;
import npc.config;
import npc.sdb.command.Watchpoint;
import npc.sdb.EvaluationContext;
import npc.expressions.expressions;

export class WatchpointPool
{
private:
    static constexpr std::size_t MaxWatchpoints{npc::WatchpointNum};
    static_assert(MaxWatchpoints > 0, "监视点数量上限必须为正数");
    std::vector<Watchpoint> watchpoints;
    std::inplace_vector<std::size_t, MaxWatchpoints> FreeWatchpointIndices; // 存储空闲监视点编号的栈
    std::inplace_vector<std::size_t, MaxWatchpoints> UsedWatchpointIndices; // 存储已使用监视点编号的列表
public:
    WatchpointPool();
    ~WatchpointPool() = default;
    bool DeleteWatchpoint(std::size_t NO);
    Watchpoint *CreateWatchpoint(const std::string &expression, std::size_t InitialValue);
    Watchpoint *GetWatchpoint(std::size_t NO);
    const std::vector<Watchpoint> &GetAllWatchpoints() const noexcept;
    std::size_t GetMaxWatchpoints() const noexcept;
    bool CheckAll(const EvaluationContext &context); // 遍历所有活跃监视点，重新求值表达式，对比旧数值，如果触发了就更新然后返回真
    void PrintAllWatchpoints(const EvaluationContext &context) const;
};

export WatchpointPool &GetGlobalWatchpointPool();
