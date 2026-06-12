#ifndef MMIO_HPP
#define MMIO_HPP
#include <chrono>
#include <cstdint>
#include <optional>
class MMIO
{
private:
    std::chrono::steady_clock::time_point BootTime;

public:
    MMIO();
    void Reset() noexcept;
    [[nodiscard]] std::optional<std::uint32_t> LoadWord(std::uint32_t address, std::uint64_t cycles) const noexcept;
    [[nodiscard]] bool StoreWord(std::uint32_t address, std::uint32_t data, std::uint8_t mask) noexcept;
};

#endif
