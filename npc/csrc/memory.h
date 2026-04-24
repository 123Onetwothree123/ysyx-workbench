#ifndef MEMORY_H
#define MEMORY_H
#include <cstdint>
#include <array>
#include <filesystem>
#include <expected>
#include <string>
#include "npc.h"
constexpr uint32_t PMEM_SIZE = 128 * 1024 * 1024; // 抄AM的
constexpr uint32_t CONFIG_MBASE = 0x80000000;     // 内存基地址
extern std::array<uint8_t, PMEM_SIZE> pmem;       // 抄am拿数组当内存
// 检查地址是否在pmem范围内
inline bool check_pmem_range(uint32_t addr, size_t len = 1)
{
    return addr >= CONFIG_MBASE && (addr - CONFIG_MBASE + len) <= PMEM_SIZE;
}
// 将guest物理地址转换为host地址（pmem数组索引）
inline uint32_t guest_to_host(uint32_t gaddr)
{
    return gaddr - CONFIG_MBASE;
}
bool check_pmem_safe_address(uint32_t address, size_t len = 1);
std::expected<std::size_t, std::string_view> load_file(const std::filesystem::path &FilePath);
#endif
