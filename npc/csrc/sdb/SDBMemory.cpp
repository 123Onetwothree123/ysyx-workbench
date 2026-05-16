#include "SDBMemory.hpp"

#include "memory.hpp"

#include <iostream>
#include <print>

std::uint32_t NPCMemoryRead(std::uint32_t Addr, std::size_t Len)
{
    if (Len != 1 && Len != 2 && Len != 4)
    {
        std::println(std::cerr, "NPCMemoryRead：不支持的长度 {}", Len);
        return 0;
    }
    if (!check_pmem_range(Addr, Len))
    {
        std::println(std::cerr, "NPCMemoryRead:：地址越界 0x{:08x}, len={}", Addr, Len);
        return 0;
    }
    const auto HostAddr{guest_to_host(Addr)};
    auto Data{std::uint32_t{0}};
    for (std::size_t i{0}; i < Len; i++)
    {
        Data |= static_cast<std::uint32_t>(pmem[HostAddr + i]) << (i * 8);
    }
    return Data;
}
std::optional<std::uint32_t> NPCMemoryReadSafe(std::uint32_t Addr, std::size_t Len)
{
    if (Len != 1 && Len != 2 && Len != 4)
    {
        return std::nullopt;
    }
    if (!check_pmem_range(Addr, Len))
    {
        return std::nullopt;
    }
    return NPCMemoryRead(Addr, Len);
}
void NPCMemoryScan(std::uint32_t Addr, std::size_t Count)
{
    Addr &= ~0x3u; // 4字节对齐
    for (std::size_t i{0}; i < Count; i++)
    {
        const auto Current{Addr + static_cast<std::uint32_t>(i * 4)};
        if (!check_pmem_range(Current, 4))
        {
            std::println(std::cerr, "NPCMemoryScan：地址越界 0x{:08x}", Current);
            break;
        }
        const auto Value{NPCMemoryRead(Current, 4)};
        if (i % 4 == 0) // 每4个一组
        {
            std::print("0x{:08x}:", Current);
        }
        std::print(" 0x{:08x}", Value);
        if (i % 4 == 3 || i == Count - 1) // 一组满了四个或者到了最后一个就直接就换行
        {
            std::println("");
        }
    }
}
