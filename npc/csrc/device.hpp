#ifndef NPC_HPP
#define NPC_HPP
#include <cstdint>
namespace device
{
    inline constexpr std::uint32_t DEVICE_BASE{0xa0000000};
    namespace RTC
    {
        inline constexpr std::uint32_t RTC_ADDR{DEVICE_BASE + 0x0000048};
        inline constexpr std::uint32_t RTC_YEAR_ADDR{RTC_ADDR + 0x10};
        inline constexpr std::uint32_t RTC_MONTH_ADDR{RTC_ADDR + 0x14};
        inline constexpr std::uint32_t RTC_DAY_ADDR{RTC_ADDR + 0x18};
        inline constexpr std::uint32_t RTC_HOUR_ADDR{RTC_ADDR + 0x1C};
        inline constexpr std::uint32_t RTC_MINUTE_ADDR{RTC_ADDR + 0x20};
        inline constexpr std::uint32_t RTC_SECOND_ADDR{RTC_ADDR + 0x24};
    }
    namespace SerialPort
    {
        inline constexpr std::uint32_t SerialPort{0x10000000};
    }
}
#endif
