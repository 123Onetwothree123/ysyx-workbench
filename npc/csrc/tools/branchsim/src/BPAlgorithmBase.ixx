module;
#include <cstdint>
export module BPAlgorithmBase;
import std;
import BTB;
import BPConfig;
import BranchRecord;
// 预测结果: taken=是否预测跳转; target_known=目标是否可信
// (BTB类算法命中即知目标且目标静态必正确; AlwaysJump等方向型算法置false只做方向评判;
//  ret的RAS目标可能错, 必须置true接受目标校验)
export struct Prediction
{
    bool taken;
    bool target_known;
    Addr target;
};
export class BPAlgorithmBase
{
protected:
    BTB btb;
public:
    explicit BPAlgorithmBase(const BPConfig& config);
    virtual ~BPAlgorithmBase() = default;
    BPAlgorithmBase(const BPAlgorithmBase &) = default;
    BPAlgorithmBase(BPAlgorithmBase &&) = default;
    BPAlgorithmBase &operator=(const BPAlgorithmBase &) = default;
    BPAlgorithmBase &operator=(BPAlgorithmBase &&) = default;
    virtual Prediction predict(Addr pc) const = 0; // 预测是否跳转及目标
    // taken/target是真实执行结果；jalr_immediate用于与RTL一样训练
    // Ret/Coroutine表项，而不是错误地记住某一次动态返回目标。
    virtual void update(Addr pc, bool taken, Addr target, BranchKind kind,
                        std::uint32_t jalr_immediate) = 0;
    [[nodiscard]] virtual std::string_view GetName() const = 0; // 返回算法名字，后面做表格和输出会用到
};
