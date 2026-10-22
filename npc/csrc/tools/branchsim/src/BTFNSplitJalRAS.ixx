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
// call(jal/jalr的rd=x1/x5)提交时压栈pc+4；jalr的rs1=x1/x5按
// hint表弹栈；rd/rs1是不同link register时同拍pop-then-push。
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
    Prediction predict(Addr pc) const override;
    void update(Addr pc, bool taken, Addr target, BranchKind kind,
                std::uint32_t jalr_immediate) override;
    [[nodiscard]] std::string_view GetName() const override;
};
