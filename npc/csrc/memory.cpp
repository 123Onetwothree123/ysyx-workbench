#include "memory.hpp"
#include <print>
#include <iostream>
#include <fstream>
#include <cstdint>
#include <array>
#include <chrono>
#include <cstdio>
#include <expected>
#include <string>
#include <format>
std::array<std::uint8_t, PMEM_SIZE> pmem{};              // 抄am拿数组当内存
static auto boot_time{std::chrono::steady_clock::now()}; // 启动时间
bool CheckPmemRange(std::uint32_t addr, std::size_t len)
{
    return addr >= CONFIG_MBASE && (addr - CONFIG_MBASE + len) <= PMEM_SIZE;
}
std::size_t GuestToHost(std::uint32_t gaddr)
{
    return static_cast<std::size_t>(gaddr - CONFIG_MBASE);
}
bool CheckPmemSafeAddress(std::uint32_t address, std::size_t len)
{
    return address >= CONFIG_MBASE && (address - CONFIG_MBASE + len) <= PMEM_SIZE;
}
std::size_t LoadBuiltinImage()
{
    static constexpr std::array<std::uint32_t, 5> BuiltinImage{
        0x00000297, // auipc t0, 0
        0x00028823, // sb zero, 16(t0)
        0x0102c503, // lbu a0, 16(t0)
        0x00100073, // ebreak
        0xdeadbeef,
    };
    auto Offset{GuestToHost(RESET_VECTOR)};
    for (const auto Word : BuiltinImage)
    {
        pmem[Offset++] = static_cast<std::uint8_t>((Word >> 0) & 0xffu);
        pmem[Offset++] = static_cast<std::uint8_t>((Word >> 8) & 0xffu);
        pmem[Offset++] = static_cast<std::uint8_t>((Word >> 16) & 0xffu);
        pmem[Offset++] = static_cast<std::uint8_t>((Word >> 24) & 0xffu);
    }
    return BuiltinImage.size() * sizeof(BuiltinImage.front());
}
std::expected<std::size_t, std::string> LoadFile(const std::filesystem::path &FilePath)
{
    if (!std::filesystem::exists(FilePath))
    {
        return std::unexpected{std::format("他妈的文件路径没找到文件{0}", FilePath.string())};
    }
    if (!std::filesystem::is_regular_file(FilePath))
    {
        return std::unexpected{std::format("fuck，不是普通文件{0}", FilePath.string())};
    }
    auto FileSize{std::filesystem::file_size(FilePath)};
    if (FileSize > PMEM_SIZE)
    {
        return std::unexpected{std::format("文件太大了{0}", FilePath.string())};
    }
    std::ifstream ifs(FilePath, std::ios::binary);
    if (!ifs)
    {
        return std::unexpected{std::format("文件打不开{0}", FilePath.string())};
    }
    ifs.read(reinterpret_cast<char *>(pmem.data()), static_cast<std::streamsize>(FileSize));
    if (!ifs)
    {
        return std::unexpected{std::format("读文件失败{0}", FilePath.string())};
    }
    return static_cast<std::size_t>(FileSize);
}
