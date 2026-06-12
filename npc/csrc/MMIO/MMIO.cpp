#include "MMIO.hpp"
#include "../device.hpp"
#include <chrono>
#include <iostream>
#include <print>
MMIO::MMIO() : BootTime(std::chrono::steady_clock::now()) {}
void MMIO::Reset() noexcept
{
    BootTime = std::chrono::steady_clock::now();
}
std::optional<std::uint32_t> MMIO::LoadWord(std::uint32_t address, std::uint64_t cycles) const noexcept
{
    address &= ~0x3u; // 4字节对齐
    if (address == device::RTC::RTC_ADDR || address == device::RTC::RTC_ADDR + 4)
    {
        const auto NowMoment = std::chrono::steady_clock::now(); // 怕后面忘记，先标记一下，这是把这个时刻记录下来，是不是该叫TimeNow更好啊
        const auto TimeInterval_us = static_cast<std::uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(NowMoment - BootTime).count());
        if (address == device::RTC::RTC_ADDR)
        {
            return static_cast<std::uint32_t>(TimeInterval_us);
        }
        return static_cast<std::uint32_t>(TimeInterval_us >> 32);
    }
    if (address == device::SIM::FREQ_ADDR || address == device::SIM::FREQ_ADDR + 4)
    {
        const auto now = std::chrono::steady_clock::now();
        const auto elapsed_us = static_cast<std::uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(now - BootTime).count());
        std::uint64_t SimFreq = 1;
        if (elapsed_us != 0 && cycles != 0)
        {
            SimFreq = cycles * 1000000ULL / elapsed_us;
            if (SimFreq == 0)
            {
                SimFreq = 1;
            }
        }
        if (address == device::SIM::FREQ_ADDR)
        {
            return static_cast<std::uint32_t>(SimFreq);
        }
        return static_cast<std::uint32_t>(SimFreq >> 32);
    }
    if (address == device::RTC::RTC_YEAR_ADDR || address == device::RTC::RTC_MONTH_ADDR || address == device::RTC::RTC_DAY_ADDR || address == device::RTC::RTC_HOUR_ADDR || address == device::RTC::RTC_MINUTE_ADDR || address == device::RTC::RTC_SECOND_ADDR)
    {
        const auto now = std::chrono::system_clock::now();
        const auto days = std::chrono::floor<std::chrono::days>(now);
        const auto ymd = std::chrono::year_month_day{days};
        const auto hms = std::chrono::hh_mm_ss{now - days};
        if (address == device::RTC::RTC_YEAR_ADDR)
        {
            return static_cast<std::uint32_t>(static_cast<int>(ymd.year()));
        }
        if (address == device::RTC::RTC_MONTH_ADDR)
        {
            return static_cast<std::uint32_t>(static_cast<unsigned>(ymd.month()));
        }
        if (address == device::RTC::RTC_DAY_ADDR)
        {
            return static_cast<std::uint32_t>(static_cast<unsigned>(ymd.day()));
        }
        if (address == device::RTC::RTC_HOUR_ADDR)
        {
            return static_cast<std::uint32_t>(hms.hours().count());
        }
        if (address == device::RTC::RTC_MINUTE_ADDR)
        {
            return static_cast<std::uint32_t>(hms.minutes().count());
        }
        if (address == device::RTC::RTC_SECOND_ADDR)
        {
            return static_cast<std::uint32_t>(hms.seconds().count());
        }
        std::println("寄了，鬼知道在成功接收了RTC的各个地址后，进入后，为什么又没被捕获");
        std::abort();
    }
    if (address == device::SerialPort::SerialPort)
    {
        std::println(std::cerr, "访问个毛线的串行，串行现在只能写还不能读，地址address = 0x{:08x}", address);
        std::abort();
    }
    return std::nullopt;
}
bool MMIO::StoreWord(std::uint32_t address, std::uint32_t data, std::uint8_t mask) noexcept
{
    address &= ~0x3u;
    if (address == device::SerialPort::SerialPort)
    {
        if (mask & 0x1) // mask的最低位，最低字节才有效，我真的不知道为什么NEMU要这么设计，NPC也只能仿照这种办法设计
        {
            std::cout << static_cast<char>(data & 0xFF) << std::flush;
        }
        return true;
    }
    return false;
}
