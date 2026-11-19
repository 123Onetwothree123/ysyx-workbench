module;
#include <cstdint>
export module BTFNSharedJal;
import std;
import BPAlgorithmBase;
import BPConfig;
import BranchRecord;
// jal 与条件分支共享 BTB；条件分支按 target<pc 判方向。
export class BTFNSharedJal : public BPAlgorithmBase
{
private:
    std::string name{"BTFN+SharedJal"};
public:
    explicit BTFNSharedJal(const BPConfig& config);
    ~BTFNSharedJal() override = default;
    Prediction predict(Addr pc) const override;
    void update(Addr pc, bool taken, Addr target, BranchKind kind,
                std::uint32_t jalr_immediate) override;
    [[nodiscard]] std::string_view GetName() const override;
};
