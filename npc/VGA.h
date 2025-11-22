#ifndef VGA_H
#define VGA_H
#include <am.h>

class VGA
{
public:
    static constexpr uint32_t FB_ADDR = 0x20000000;
    static constexpr uint32_t FB_SIZE = 0x00040000;
    void init()
    {
        for (int i = 0; i < 256 * 256; i++)
        {
            vmem[i] = 0;
        }
    }
    bool is_vga_addr(uint32_t addr) const
    {
        return addr >= FB_ADDR && addr < FB_ADDR + FB_SIZE;
    }
    void write_word(uint32_t addr, uint32_t data)
    {
        uint32_t offset = (addr - FB_ADDR) / 4;
        if (offset < 256 * 256)
        {
            vmem[offset] = data;
        }
    }
    // 必须有这个，否则 sb 指令黑屏
    void write_byte(uint32_t addr, uint8_t value)
    {
        uint32_t offset = addr - FB_ADDR;
        if (offset < 256 * 256 * 4)
        {
            ((uint8_t *)vmem)[offset] = value;
        }
    }
    void update_screen()
    {
        AM_GPU_FBDRAW_T draw;
        draw.x = 0;
        draw.y = 0;
        draw.w = 256;
        draw.h = 256;
        draw.pixels = vmem;
        draw.sync = true;
        ioe_write(AM_GPU_FBDRAW, &draw);
    }

private:
    uint32_t vmem[256 * 256];
};
#endif