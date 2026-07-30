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
    Prediction predict(uint32_t pc) const override;
    void update(uint32_t pc, bool taken, uint32_t target, BranchKind kind) override;
    [[nodiscard]] std::string_view GetName() const override;
};
