#include <cstdint>
#include "VGA.hpp"
#include <am.hpp>

VGA::VGA()
{
    vmem.resize(FB_SIZE / 4, 0);
    AM_GPU_CONFIG_T config = io_read(AM_GPU_CONFIG);
    screen_w = config.width;
    screen_h = config.height;
}
void VGA::write_word(std::uint32_t addr, std::uint32_t data)
{
    std::uint32_t offset{(addr - FB_ADDR) / 4};
    if (offset < vmem.size())
        vmem[offset] = data;
}
void VGA::update_screen()
{
    int draw_w{256};
    int draw_h{256};
    io_write(AM_GPU_FBDRAW, 0, 0, vmem.data(), draw_w, draw_h, true);
}
