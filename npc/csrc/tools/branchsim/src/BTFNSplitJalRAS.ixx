module;
#include <cstdint>
export module BTFNSplitJalRAS;
import std;
import BPAlgorithmBase;
import BPConfig;
import BranchRecord;
import BTB;
import RAS;
// Ret 目标取 RAS 栈顶；call 压 pc+4，ret 弹栈。
// EXU 提交点更新非投机，无需检查点恢复。
export class BTFNSplitJalRAS : public BPAlgorithmBase
{
private:
    std::string name{"BTFN+SplitJal+RAS"};
    BTB jal_btb;
    RAS ras;
public:
    explicit BTFNSplitJalRAS(const BPConfig& config);
    ~BTFNSplitJalRAS() override = default;
    Prediction predict(Addr pc) const override;
    void update(Addr pc, bool taken, Addr target, BranchKind kind,
                std::uint32_t jalr_immediate) override;
    [[nodiscard]] std::string_view GetName() const override;
};
