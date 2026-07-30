module;
#include <cstdint>
export module BTFNSplitJalRAS;
import std;
import BPAlgorithmBase;
import BPConfig;
import BranchRecord;
import BTB;
import RAS;
// 方案B+RAS: 独立jal BTB + 返回地址栈
// jal BTB的Ret表项只标记"该PC是ret", 预测目标取RAS栈顶;
// call(jal ra/jalr ra)提交时压栈pc+4, ret提交时弹栈.
// EXU提交点更新天然非投机(错路指令到不了EXU), 无需检查点恢复
export class BTFNSplitJalRAS : public BPAlgorithmBase
{
private:
    std::string name{"BTFN+SplitJal+RAS"};
    BTB jal_btb;
    RAS ras;
public:
    explicit BTFNSplitJalRAS(const BPConfig& config);
    ~BTFNSplitJalRAS() override = default;
    Prediction predict(uint32_t pc) const override;
    void update(uint32_t pc, bool taken, uint32_t target, BranchKind kind) override;
    [[nodiscard]] std::string_view GetName() const override;
};
