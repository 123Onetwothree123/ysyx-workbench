module BTB;
import std;
import BPConfig;
BTB::BTB(const BPConfig &config)
    : BTB{config.get_btb_bits(), config.get_btb_ways()}
{
}
BTB::BTB(std::size_t bits, std::size_t ways)
    : index_bits{bits}, ways{ways}
{
    auto SetsNumber{std::size_t{1} << index_bits};
    sets.resize(SetsNumber);
    repl_ptr.assign(SetsNumber, 0);
    for (auto &set : sets)
    {
        set.resize(ways); // 把entry其全部初始化为0
    }
}
std::optional<BTB::entry> BTB::lookup(std::uint32_t pc) const
{
    auto index{(pc >> 2) & ((std::size_t{1} << index_bits) - 1)}; // 取低index_bits位作为书架号
    for (const auto &entry : sets[index])
    {
        if (entry.valid && entry.tag == pc)
        {
            return entry;
        }
    }
    return std::nullopt;
}
void BTB::update(std::uint32_t pc, std::uint32_t target, bool is_jal)
{
    auto index{(pc >> 2) & ((std::size_t{1} << index_bits) - 1)}; // 同上
    auto &set{sets[index]};                                       // 拿到对应书架的所有表项引用
    //下面这段是AI写的了，确实是写不出来
    for (auto &e : set)
    { // 第1轮：找是否有同 tag 的旧记录
        if (e.valid && e.tag == pc)
        {                        //   找到了——说明这条分支之前执行过
            e.target = target;   //   更新跳转目标（可能没变，也可能变了）
            e.is_jal = is_jal;   //   类型位一并刷新(同一PC类型不会变, 仅为保险)
            return;              //   只更新不新建
        }
    }
    for (auto &e : set)
    { // 第2轮：找空闲槽位
        if (!e.valid)
        {                      //   槽位无效=未被使用
            e.valid = true;    //   标记为有效
            e.tag = pc;        //   写入当前 PC
            e.target = target; //   写入跳转目标
            e.is_jal = is_jal; //   写入类型位
            return;
        }
    }
    // 第3轮：书架满了，全都有效
    // 按轮转指针选受害者, 替换后指针自增回绕(与硬件BTB的repl_ptr行为一致)
    auto &victim{set[repl_ptr[index]]};
    victim.tag = pc;
    victim.target = target;
    victim.is_jal = is_jal;
    victim.valid = true;             // 确保有效位置 1
    repl_ptr[index] = (repl_ptr[index] + 1) % ways;
}
