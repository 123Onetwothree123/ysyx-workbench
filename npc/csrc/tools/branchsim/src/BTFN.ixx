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
    bool predict(uint32_t pc) const override;
    void update(uint32_t pc, bool taken, uint32_t target, BranchKind kind) override;
    [[nodiscard]] std::string_view GetName() const override;
};
