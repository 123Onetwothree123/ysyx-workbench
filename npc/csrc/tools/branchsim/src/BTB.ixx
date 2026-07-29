export module BTB;
import std;
import BPConfig;
export class BTB
{
private:
    struct entry
    {
        bool valid;           // 1代表有数据
        std::uint32_t tag;    // 分支指令的PC>>2，因为RISC-V指令地址必4对齐
        std::uint32_t target; // 跳转目标地址
    };
    // 行=sets_数量=2^index_bits，每行ways项，举个例子：btb_bits=4，ways=2就是16行×2项=32个entry
    std::vector<std::vector<entry>> sets;
    std::size_t index_bits; // 索引位数，2底数，也就是log2(sets)
public:
    BTB() = default;
    ~BTB() = default;
    explicit BTB(const BPConfig &config);
    std::optional<std::uint32_t> lookup(std::uint32_t pc) const; // 命中失败就直接返回空
    void update(std::uint32_t pc, std::uint32_t target);
};