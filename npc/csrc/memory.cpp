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

std::array<uint8_t, PMEM_SIZE> pmem{};                   // 抄am拿数组当内存
static auto boot_time{std::chrono::steady_clock::now()}; // 启动时间

extern "C" int pmem_read(int raddr)
{
    // 总是读取地址为`raddr & ~0x3u`的4字节返回
    auto addr{static_cast<uint32_t>(raddr)};
    addr &= ~0x3u; // 4字节对齐
    if (addr == RTC_ADDR || addr == RTC_ADDR + 4)
    {
        auto now{std::chrono::steady_clock::now()};
        auto us{std::chrono::duration_cast<std::chrono::microseconds>(now - boot_time).count()};
        if (addr == RTC_ADDR)
        {
            return static_cast<int>(us & 0xFFFFFFFFu); // 低32bit
        }
        if (addr == RTC_ADDR + 4)
        {
            return static_cast<int>((us >> 32) & 0xFFFFFFFFu); // 高32bit
        }
    }
    // 年月日时分秒
    if (addr == RTC_YEAR_ADDR || addr == RTC_MONTH_ADDR || addr == RTC_DAY_ADDR || addr == RTC_HOUR_ADDR || addr == RTC_MINUTE_ADDR || addr == RTC_SECOND_ADDR)
    {
        auto now{std::chrono::system_clock::now()};
        auto days{std::chrono::floor<std::chrono::days>(now)};
        auto ymd{std::chrono::year_month_day{days}};
        auto hms{std::chrono::hh_mm_ss{now - days}};
        if (addr == RTC_YEAR_ADDR)
        {
            return static_cast<int>(ymd.year());
        }
        if (addr == RTC_MONTH_ADDR)
        {
            return static_cast<int>(static_cast<unsigned>(ymd.month()));
        }
        if (addr == RTC_DAY_ADDR)
        {
            return static_cast<int>(static_cast<unsigned>(ymd.day()));
        }
        if (addr == RTC_HOUR_ADDR)
        {
            return static_cast<int>(hms.hours().count());
        }
        if (addr == RTC_MINUTE_ADDR)
        {
            return static_cast<int>(hms.minutes().count());
        }
        if (addr == RTC_SECOND_ADDR)
        {
            return static_cast<int>(hms.seconds().count());
        }
    }
    if (addr == SERIAL_PORT)
    {
        return 0; // 串口状态，返回0表示空闲和可写
    }
    // 检查地址是否在有效范围内
    if (!check_pmem_range(addr, 4))
    {
        std::println(std::cerr, "pmem读取越界，guest_addr = 0x{:x}", addr);
        std::abort();
    }
    // 地址转换：将guest地址转换为pmem数组索引
    auto host_addr = guest_to_host(addr);
    uint32_t data{0};
    data |= static_cast<uint32_t>(pmem[host_addr + 0]) << 0;
    data |= static_cast<uint32_t>(pmem[host_addr + 1]) << 8;
    data |= static_cast<uint32_t>(pmem[host_addr + 2]) << 16;
    data |= static_cast<uint32_t>(pmem[host_addr + 3]) << 24;
    return static_cast<int>(data);
}
extern "C" void pmem_write(int waddr, int wdata, char wmask)
{
    // 总是往地址为`waddr & ~0x3u`的4字节按写掩码`wmask`写入`wdata`
    // `wmask`中每比特表示`wdata`中1个字节的掩码,
    // 如`wmask = 0x3`代表只写入最低2个字节, 内存中的其它字节保持不变
    auto guest_addr{static_cast<uint32_t>(waddr & ~0x3u)};
    auto data{static_cast<uint32_t>(wdata)};
    auto mask{static_cast<uint8_t>(wmask)};
    if (guest_addr == SERIAL_PORT)
    {
        /*
        putchar(static_cast<char>(wdata & 0xff)); //&0xff是为了提取最低字节，屏蔽掉最高24bit
        fflush(stdout);
        */
        std::fputc(static_cast<char>(wdata & 0xFF), stdout);
        std::fflush(stdout);
        return;
    }
    // 检查地址是否在有效范围内
    if (!check_pmem_range(guest_addr, 4))
    {
        std::println(std::cerr, "pmem写入越界，guest_addr = 0x{:x}", guest_addr);
        std::abort();
    }
    // 地址转换：将guest物理地址转换为host地址（pmem数组索引）
    auto host_addr = guest_to_host(guest_addr);
    // 妈的实在是背不下来掩码了，直接注释标记了
    //  检查掩码的第0位，决定是否写入最低字节
    if (mask & 0x01)
    {
        pmem[host_addr + 0] = static_cast<uint8_t>(data & 0xFF);
    }
    // 检查掩码的第1位，决定是否写入第1字节
    if (mask & 0x02)
    {
        pmem[host_addr + 1] = static_cast<uint8_t>((data >> 8) & 0xFF);
    }
    // 检查掩码的第2位，决定是否写入第2字节
    if (mask & 0x04)
    {
        pmem[host_addr + 2] = static_cast<uint8_t>((data >> 16) & 0xFF);
    }
    // 检查掩码的第3位，决定是否写入第3字节
    if (mask & 0x08)
    {
        pmem[host_addr + 3] = static_cast<uint8_t>((data >> 24) & 0xFF);
    }
}
bool check_pmem_safe_address(uint32_t address, size_t len)
{
    return address >= CONFIG_MBASE && (address - CONFIG_MBASE + len) <= PMEM_SIZE;
}

std::size_t load_builtin_image()
{
    static constexpr std::array<std::uint32_t, 5> BuiltinImage{
        0x00000297, // auipc t0, 0
        0x00028823, // sb zero, 16(t0)
        0x0102c503, // lbu a0, 16(t0)
        0x00100073, // ebreak
        0xdeadbeef,
    };

    auto Offset{guest_to_host(RESET_VECTOR)};
    for (const auto Word : BuiltinImage)
    {
        pmem[Offset++] = static_cast<std::uint8_t>((Word >> 0) & 0xffu);
        pmem[Offset++] = static_cast<std::uint8_t>((Word >> 8) & 0xffu);
        pmem[Offset++] = static_cast<std::uint8_t>((Word >> 16) & 0xffu);
        pmem[Offset++] = static_cast<std::uint8_t>((Word >> 24) & 0xffu);
    }
    return BuiltinImage.size() * sizeof(BuiltinImage.front());
}

std::expected<std::size_t, std::string> load_file(const std::filesystem::path &FilePath)
{
    if (!std::filesystem::exists(FilePath))
    {
        return std::unexpected{std::format("他妈的文件路径没找到文件{}", FilePath.string())};
    }
    if (!std::filesystem::is_regular_file(FilePath))
    {
        return std::unexpected{std::format("fuck，不是普通文件{}", FilePath.string())};
    }
    auto FileSize{std::filesystem::file_size(FilePath)};
    if (FileSize > PMEM_SIZE)
    {
        return std::unexpected{std::format("文件太大了{}", FilePath.string())};
    }
    std::ifstream ifs(FilePath, std::ios::binary);
    if (!ifs)
    {
        return std::unexpected{std::format("文件打不开{}", FilePath.string())};
    }
    ifs.read(reinterpret_cast<char *>(pmem.data()), static_cast<std::streamsize>(FileSize));
    if (!ifs)
    {
        return std::unexpected{std::format("读文件失败{}", FilePath.string())};
    }
    return static_cast<std::size_t>(FileSize);
}
