#ifndef SOC_MEMORY_MAP_HPP
#define SOC_MEMORY_MAP_HPP
#include <array>
#include <cstdint>
#include <optional>
#include <string_view>
#include "AddressRange.hpp"
class SoCMemoryMap
{
public:
    SoCMemoryMap() = delete;
    ~SoCMemoryMap() = default;
    static inline const AddressRange MROM{"mrom", 0x20000000u, 0x1000u};
    static inline const AddressRange UART{"uart", 0x10000000u, 0x1000u};
    static inline const AddressRange SRAM{"sram", 0x80000000u, 0x400000u};
    // 看属于soc的地址空间的哪一块区域的
    static std::optional<AddressRange> find(std::uint32_t address, std::uint32_t length = 1);

private:
    inline static const std::array Ranges{MROM, UART, SRAM};
};
#endif