#ifndef SDB_MEMORY_HPP
#define SDB_MEMORY_HPP
#include <cstddef>
#include <cstdint>
#include <optional>
std::uint32_t NPCMemoryRead(std::uint32_t Addr, std::size_t Len = 4);
std::optional<std::uint32_t> NPCMemoryReadSafe(std::uint32_t Addr, std::size_t Len = 4);
void NPCMemoryScan(std::uint32_t Addr, std::size_t Count);
#endif
