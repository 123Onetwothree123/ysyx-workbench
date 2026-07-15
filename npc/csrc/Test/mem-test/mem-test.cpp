#include "mem-test.hpp"
#include <am.h>
#include <cstdint>

template <typename T>
std::expected<void, std::uintptr_t> test_width(std::uintptr_t begin, std::size_t length)
{
    for (std::size_t offset{0}; offset + sizeof(T) <= length; offset += sizeof(T))
    {
        const std::uintptr_t address{begin + offset};
        auto *memory{reinterpret_cast<volatile T *>(address)};
        *memory = static_cast<T>(address);
    }
    for (std::size_t offset{0}; offset + sizeof(T) <= length; offset += sizeof(T))
    {
        const std::uintptr_t address{begin + offset};
        auto *memory{reinterpret_cast<volatile T *>(address)};
        const auto expected{static_cast<T>(address)};
        const auto actual{*memory};
        if (actual != expected)
        {
            return std::unexpected(address);
        }
    }
    return {};
}

extern "C" std::expected<void, std::uintptr_t> mem_test(std::uintptr_t begin, std::size_t length)
{
    if (auto result{test_width<std::uint8_t>(begin, length)}; !result)
    {
        return result;
    }
    if (auto result{test_width<std::uint16_t>(begin, length)}; !result)
    {
        return result;
    }
    if (auto result{test_width<std::uint32_t>(begin, length)}; !result)
    {
        return result;
    }
    return test_width<std::uint64_t>(begin, length);
}
