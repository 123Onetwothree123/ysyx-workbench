#ifndef MEMORY_HPP
#define MEMORY_HPP
#include <array>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <expected>
#include <string>
#include "npc.hpp"
constexpr std::size_t PMEM_SIZE{128 * 1024 * 1024}; // 抄AM的
constexpr std::uint32_t CONFIG_MBASE{0x80000000};   // 内存基地址
constexpr std::uint32_t RESET_VECTOR{CONFIG_MBASE};
extern std::array<std::uint8_t, PMEM_SIZE> pmem;    // 抄am拿数组当内存
// 检查地址是否在pmem范围内
inline bool check_pmem_range(std::uint32_t addr, std::size_t len = 1)
{
    return addr >= CONFIG_MBASE && (addr - CONFIG_MBASE + len) <= PMEM_SIZE;
}
// 将guest物理地址转换为host地址（pmem数组索引）
inline std::size_t guest_to_host(std::uint32_t gaddr)
{
    return static_cast<std::size_t>(gaddr - CONFIG_MBASE);
}
bool check_pmem_safe_address(std::uint32_t address, std::size_t len = 1);
std::size_t load_builtin_image();
std::expected<std::size_t, std::string> load_file(const std::filesystem::path &FilePath);
#endif
