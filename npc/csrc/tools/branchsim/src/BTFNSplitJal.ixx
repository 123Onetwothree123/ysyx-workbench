module;
#include <cstdint>
export module BTFNSplitJal;
import std;
import BPAlgorithmBase;
import BPConfig;
import BranchRecord;
import BTB;
// jal 独立小 BTB，取指时两表同时查询。
export class BTFNSplitJal : public BPAlgorithmBase
{
private:
    std::string name{"BTFN+SplitJal"};
    BTB jal_btb;
public:
    explicit BTFNSplitJal(const BPConfig& config);
    ~BTFNSplitJal() override = default;
    Prediction predict(Addr pc) const override;
    void update(Addr pc, bool taken, Addr target, BranchKind kind,
                std::uint32_t jalr_immediate) override;
    [[nodiscard]] std::string_view GetName() const override;
};
