#ifndef NVBOARDLEDCHASER_HPP
#define NVBOARDLEDCHASER_HPP
#include <cstdint>
#include <cstddef>
extern "C" void nvboard_led_chaser(std::uintptr_t LEDRegister,std::size_t LEDCount,std::uint32_t StepDelayMs,std::size_t rounds);
#endif
