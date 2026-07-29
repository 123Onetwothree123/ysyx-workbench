module;
#include <cstdint>
export module BPAlgorithmBase;
import std;
import BTB;
import BPConfig;
export class BPAlgorithmBase
{
protected:
    BTB btb;
public:
    explicit BPAlgorithmBase(const BPConfig& config) : btb{config} {}
    virtual ~BPAlgorithmBase() = default;
    BPAlgorithmBase(const BPAlgorithmBase &) = default;
    BPAlgorithmBase(BPAlgorithmBase &&) = default;
    BPAlgorithmBase &operator=(const BPAlgorithmBase &) = default;
    BPAlgorithmBase &operator=(BPAlgorithmBase &&) = default;
    virtual bool predict(uint32_t pc) const = 0; // 预测是否跳转
    // taken是否跳转，然后target看看跳转目标，然后用真实执行结果更新内部状态
    virtual void update(uint32_t pc, bool taken, uint32_t target) = 0;
    [[nodiscard]] virtual std::string_view GetName() const = 0; // 返回算法名字，后面做表格和输出会用到
};