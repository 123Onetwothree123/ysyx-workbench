module;
#include <cstdint>

export module BTFN;
import std;
import BPAlgorithmBase;

export class BTFN : public BPAlgorithmBase
{
private:
    std::string name_{"BTFN"};
public:
    BTFN() = default;
    ~BTFN() override = default;

    auto predict(uint32_t pc) const -> bool override { return false; }
    void update(uint32_t pc, bool taken, uint32_t target) override {}
    [[nodiscard]] auto name() const -> std::string_view override { return name_; }
};