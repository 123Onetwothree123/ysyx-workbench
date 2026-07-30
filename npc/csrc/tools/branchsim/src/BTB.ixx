export module BTB;
import std;
import BPConfig;
import BranchRecord;
export class BTB
{
public:
    // 表项: valid + tag + target + 类型位
    // (jal BTB里: Jal/Call表项命中即taken且目标静态; Ret表项标记"该PC是ret", 目标交给RAS)
    struct entry
    {
        bool valid;           // 1代表有数据
        std::uint32_t tag;    // 分支指令的PC>>2，因为RISC-V指令地址必4对齐
        std::uint32_t target; // 跳转目标地址
        BranchKind kind;      // 表项类型(Branch/Jal/Call/Ret)
    };
private:
    // 行=sets_数量=2^index_bits，每行ways项，举个例子：btb_bits=4，ways=2就是16行×2项=32个entry
    std::vector<std::vector<entry>> sets;
    std::vector<std::size_t> repl_ptr; // 每组一个轮转指针, 组满时指向受害者way(与硬件BTB行为一致)
    std::size_t index_bits; // 索引位数，2底数，也就是log2(sets)
    std::size_t ways;       // 相联度
public:
    BTB() = default;
    ~BTB() = default;
    explicit BTB(const BPConfig &config);
    // 直接指定容量/相联度, 供独立jal BTB使用(Kconfig的JAL_BTB_*参数)
    BTB(std::size_t bits, std::size_t ways);
    std::optional<entry> lookup(std::uint32_t pc) const; // 命中失败就直接返回空
    void update(std::uint32_t pc, std::uint32_t target, BranchKind kind);
};
