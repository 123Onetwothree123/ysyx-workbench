module;
#include <cstdint>
export module BPAlgorithmBase;
import std;
import BTB;
import BPConfig;
import BranchRecord;
// target_known=false 时只比较方向；RAS 目标必须置 true 校验。
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
    virtual Prediction predict(Addr pc) const = 0;
    // jalr_immediate 用于训练 Ret/Coroutine 表项，不记录动态目标。
    virtual void update(Addr pc, bool taken, Addr target, BranchKind kind,
                        std::uint32_t jalr_immediate) = 0;
    [[nodiscard]] virtual std::string_view GetName() const = 0;
};
