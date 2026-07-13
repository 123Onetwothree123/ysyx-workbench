#include "NVBoardDIPSwitch.hpp"
void nvboard_dip_switch_password(std::uintptr_t SwitchRegister, std::uint16_t Password)
{
    auto *const SW{reinterpret_cast<volatile std::uint32_t *>(SwitchRegister)};
    while ((*SW & 0xFFFFU) != Password)
    {
    }
}