module;
#include <cstdint>
export module AlwaysJump;
import std;
import BPAlgorithmBase;
import BPConfig;
import BranchRecord;
export class AlwaysJump : public BPAlgorithmBase
{
private:
    std::string name{"AlwaysJump"};
public:
    explicit AlwaysJump(const BPConfig& config);
    ~AlwaysJump() override = default;
    Prediction predict(Addr pc) const override;
    void update(Addr pc, bool taken, Addr target, BranchKind kind,
                std::uint32_t jalr_immediate) override;
    [[nodiscard]] std::string_view GetName() const override;
};
