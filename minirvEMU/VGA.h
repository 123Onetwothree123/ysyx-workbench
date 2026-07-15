#ifndef VGA_H
#define VGA_H
#include <cstdint>
#include <vector>
#include <stdint.h>
#include <klib-macros.h>
class VGA
{
private:
    std::vector<uint32_t> vmem; // 显存，存储像素 (通常是 ARGB8888)
    int32_t screen_w;
    int32_t screen_h;

public:
    static constexpr uint32_t FB_ADDR = 0x20000000;
    static constexpr uint32_t FB_SIZE = 0x00040000; // 256KB, 对应 [0x20000000, 0x20040000)
    bool is_vga_addr(uint32_t addr) const;
    void write_word(uint32_t addr, uint32_t data);
    void update_screen(); // 调用 AM API 同步到屏幕
    VGA();
    ~VGA() = default;
};
#endif