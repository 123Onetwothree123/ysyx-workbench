#ifndef NVBOARDDIPSWITCH_HPP
#define NVBOARDDIPSWITCH_HPP
#include <cstdint>
extern "C" void nvboard_dip_switch_password(std::uintptr_t SwitchRegister, std::uint16_t Password);
#endif