module;
#include <cstdint>
#include <string_view>

export module BPAlgorithmBase;
import std;

export class BPAlgorithmBase
{
public:
    BPAlgorithmBase() = default;
    virtual ~BPAlgorithmBase() = default;

    BPAlgorithmBase(const BPAlgorithmBase&) = default;
    BPAlgorithmBase(BPAlgorithmBase&&) = default;
    BPAlgorithmBase& operator=(const BPAlgorithmBase&) = default;
    BPAlgorithmBase& operator=(BPAlgorithmBase&&) = default;

    virtual auto predict(uint32_t pc) const -> bool = 0;
    virtual void update(uint32_t pc, bool taken, uint32_t target) = 0;
    [[nodiscard]] virtual auto name() const -> std::string_view = 0;
};