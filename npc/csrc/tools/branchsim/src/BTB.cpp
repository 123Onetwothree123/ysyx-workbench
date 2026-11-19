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
    sets_flat.resize(SetsNumber * ways);
    sets = std::mdspan{sets_flat.data(), SetsNumber, ways};
    repl_ptr.assign(SetsNumber, 0);
}
std::optional<BTB::entry> BTB::lookup(Addr pc) const
{
    auto index{(pc >> 2) & ((std::size_t{1} << index_bits) - 1)};
    const std::span<const entry> set{sets.data_handle() + index * ways, ways};
    const auto it{std::ranges::find_if(set, [pc](const entry& e) { return e.valid && e.tag == pc; })};
    if (it != set.end())
    {
        return *it;
    }
    return std::nullopt;
}
void BTB::update(Addr pc, Addr target, BranchKind kind)
{
    auto index{(pc >> 2) & ((std::size_t{1} << index_bits) - 1)};
    const std::span<entry> set{sets.data_handle() + index * ways, ways};
    if (const auto it{std::ranges::find_if(set, [pc](const entry& e) { return e.valid && e.tag == pc; })}; it != set.end())
    {
        it->target = target;
        it->kind = kind;
        return;
    }
    if (const auto it{std::ranges::find_if(set, [](const entry& e) { return !e.valid; })}; it != set.end())
    {
        it->valid = true;
        it->tag = pc;
        it->target = target;
        it->kind = kind;
        return;
    }
    // 组满时轮转替换，指针自增回绕。
    auto &victim{set[repl_ptr[index]]};
    victim.tag = pc;
    victim.target = target;
    victim.kind = kind;
    victim.valid = true;
    repl_ptr[index] = (repl_ptr[index] + 1) % ways;
}
