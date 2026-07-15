#include "VGA.h"
#include <am.h>
VGA::VGA()
{
    vmem.resize(FB_SIZE / 4, 0);
    // 获取 AM 的屏幕信息
    AM_GPU_CONFIG_T config = io_read(AM_GPU_CONFIG);
    screen_w = config.width;
    screen_h = config.height;
}
void VGA::write_word(uint32_t addr, uint32_t data)
{
    uint32_t offset = (addr - FB_ADDR) / 4;
    if (offset < vmem.size())
    {
        vmem[offset] = data;
    }
}
void VGA::update_screen()
{
    int draw_w = 256;
    int draw_h = 256;
    io_write(AM_GPU_FBDRAW, 0, 0, vmem.data(), draw_w, draw_h, true);
}