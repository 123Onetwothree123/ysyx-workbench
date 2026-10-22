module;
#include <cstdint>
export module BTFN;
import std;
import BPAlgorithmBase;
import BPConfig;
import BranchRecord;
export class BTFN : public BPAlgorithmBase
{
private:
    std::string name{"BTFN"};
public:
    explicit BTFN(const BPConfig& config);
    ~BTFN() override = default;
    Prediction predict(Addr pc) const override;
    void update(Addr pc, bool taken, Addr target, BranchKind kind,
                std::uint32_t jalr_immediate) override;
    [[nodiscard]] std::string_view GetName() const override;
};
