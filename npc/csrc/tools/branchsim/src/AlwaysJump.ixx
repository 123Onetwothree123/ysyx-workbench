module;
#include <cstdint>

export module AlwaysJump;
import std;
import BPAlgorithmBase;

export class AlwaysJump : public BPAlgorithmBase
{
private:
    std::string name_{"AlwaysJump"};
public:
    AlwaysJump() = default;
    ~AlwaysJump() override = default;

    auto predict(uint32_t pc) const -> bool override { return true; }
    void update(uint32_t pc, bool taken, uint32_t target) override {}
    [[nodiscard]] auto name() const -> std::string_view override { return name_; }
};