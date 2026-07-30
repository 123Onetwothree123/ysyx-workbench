module;
#include <cstdint>
export module BTFNSplitJal;
import std;
import BPAlgorithmBase;
import BPConfig;
import BranchRecord;
import BTB;
// 方案B: jal单独使用一张小BTB(容量/相联度由Kconfig的JAL_BTB_*配置), 与分支表零干扰
// 取指时两张表同时查: jal表命中即taken, 否则分支表命中按BTFN判方向
export class BTFNSplitJal : public BPAlgorithmBase
{
private:
    std::string name{"BTFN+SplitJal"};
    BTB jal_btb;
public:
    explicit BTFNSplitJal(const BPConfig& config);
    ~BTFNSplitJal() override = default;
    bool predict(uint32_t pc) const override;
    void update(uint32_t pc, bool taken, uint32_t target, BranchKind kind) override;
    [[nodiscard]] std::string_view GetName() const override;
};
