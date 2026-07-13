#include "NVBoardLEDChaser.hpp"
#include <am.h>
#include <klib-macros.h>

static void DelayMs(std::uint32_t Ms)
{
    const std::uint64_t Target{io_read(AM_TIMER_UPTIME).us + static_cast<std::uint64_t>(Ms) * 1000ULL};
    while (io_read(AM_TIMER_UPTIME).us < Target)
    {
    }
}

extern "C" void nvboard_led_chaser(std::uintptr_t LEDRegister, std::size_t LEDCount, std::uint32_t StepDelayMs, std::size_t rounds)
{
    auto *const LED{reinterpret_cast<volatile std::uint32_t *>(LEDRegister)};
    for (std::size_t Round{0}; rounds == 0 || Round < rounds; ++Round)
    {
        for (std::size_t Index{0}; Index < LEDCount; ++Index)
        {
            *LED = static_cast<std::uint32_t>(1U) << Index;
            DelayMs(StepDelayMs);
        }
    }
}
