export module BTB;
import std;
import BPConfig;
import BranchRecord;
export class BTB
{
public:
    // jal BTB 中 Jal/Call 命中即 taken；Ret 表项目标交给 RAS。
    struct entry
    {
        bool valid;
        Addr tag;    // 分支 PC（完整比较），宽度随 XLEN
        Addr target;
        BranchKind kind;
    };
private:
    // 行=组数=2^index_bits，每行 ways 项。
    std::vector<entry> sets_flat;
    std::mdspan<entry, std::dextents<std::size_t, 2>> sets;
    std::vector<std::size_t> repl_ptr; // 每组轮转指针，与硬件 BTB 一致
    std::size_t index_bits;
    std::size_t ways;
public:
    BTB() = default;
    ~BTB() = default;
    explicit BTB(const BPConfig &config);
    // 用于独立 jal BTB（JAL_BTB_* 参数）。
    BTB(std::size_t bits, std::size_t ways);
    std::optional<entry> lookup(Addr pc) const;
    void update(Addr pc, Addr target, BranchKind kind);
};
