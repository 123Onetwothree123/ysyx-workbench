module BTB;
import std;
import BPConfig;
import BranchRecord;
BTB::BTB(const BPConfig &config)
    : BTB{config.get_btb_bits(), config.get_btb_ways()}
{
}
BTB::BTB(std::size_t bits, std::size_t ways)
    : index_bits{bits}, ways{ways}
{
    auto SetsNumber{std::size_t{1} << index_bits};
    // 单块连续内存 + mdspan 二维视图 (组数 × 相联度)
    sets_flat.resize(SetsNumber * ways);
    sets = std::mdspan{sets_flat.data(), SetsNumber, ways}; // entry全部零初始化
    repl_ptr.assign(SetsNumber, 0);
}
std::optional<BTB::entry> BTB::lookup(std::uint32_t pc) const
{
    auto index{(pc >> 2) & ((std::size_t{1} << index_bits) - 1)}; // 取低index_bits位作为书架号
    const std::span<const entry> set{sets.data_handle() + index * ways, ways};
    const auto it{std::ranges::find_if(set, [pc](const entry& e) { return e.valid && e.tag == pc; })};
    if (it != set.end())
    {
        return *it;
    }
    return std::nullopt;
}
void BTB::update(std::uint32_t pc, std::uint32_t target, BranchKind kind)
{
    auto index{(pc >> 2) & ((std::size_t{1} << index_bits) - 1)}; // 同上
    const std::span<entry> set{sets.data_handle() + index * ways, ways}; // 对应书架的所有表项
    //下面这段是AI写的了，确实是写不出来
    if (const auto it{std::ranges::find_if(set, [pc](const entry& e) { return e.valid && e.tag == pc; })}; it != set.end())
    { // 第1轮：找是否有同 tag 的旧记录
        it->target = target; //   更新跳转目标（可能没变，也可能变了）
        it->kind = kind;     //   类型位一并刷新(同一PC类型不会变, 仅为保险)
        return;              //   只更新不新建
    }
    if (const auto it{std::ranges::find_if(set, [](const entry& e) { return !e.valid; })}; it != set.end())
    {                        // 第2轮：找空闲槽位
        it->valid = true;    //   标记为有效
        it->tag = pc;        //   写入当前 PC
        it->target = target; //   写入跳转目标
        it->kind = kind;     //   写入类型位
        return;
    }
    // 第3轮：书架满了，全都有效
    // 按轮转指针选受害者, 替换后指针自增回绕(与硬件BTB的repl_ptr行为一致)
    auto &victim{set[repl_ptr[index]]};
    victim.tag = pc;
    victim.target = target;
    victim.kind = kind;
    victim.valid = true;             // 确保有效位置 1
    repl_ptr[index] = (repl_ptr[index] + 1) % ways;
}
