module;
#include <cstdint>
export module BTFNSharedJal;
import std;
import BPAlgorithmBase;
import BPConfig;
import BranchRecord;
// 方案A: jal与条件分支共享同一张BTB, 表项带类型位
// jal表项命中即taken(目标静态), 分支表项仍按BTFN(target<pc)判方向
export class BTFNSharedJal : public BPAlgorithmBase
{
private:
    std::string name{"BTFN+SharedJal"};
public:
    explicit BTFNSharedJal(const BPConfig& config);
    ~BTFNSharedJal() override = default;
    Prediction predict(uint32_t pc) const override;
    void update(uint32_t pc, bool taken, uint32_t target, BranchKind kind) override;
    [[nodiscard]] std::string_view GetName() const override;
};
