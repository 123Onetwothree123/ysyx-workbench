#ifndef MEMORY_HPP
#define MEMORY_HPP
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <expected>
#include <string>
#include <vector>
static constexpr std::size_t MemorySize{42'9496'7296}; // 4GiB的内存
class Memory
{
private:
    std::vector<std::byte> memory; // C++17居然有这玩意，那看来可以把u8改成byte了
    std::expected<void, std::string> CheckMemoryRange(std::size_t address, std::size_t bytes) const noexcept;

public:
    Memory();
    ~Memory() = default;
    [[nodiscard]] std::expected<void, std::string> StoreByte(std::size_t address, std::uint8_t value) noexcept;
    [[nodiscard]] std::expected<void, std::string> StoreWord(std::size_t address, std::uint32_t value) noexcept;
    [[nodiscard]] std::expected<std::uint32_t, std::string> LoadWord(std::size_t address) const noexcept;
};
#endif