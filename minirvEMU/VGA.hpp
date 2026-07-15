#ifndef VGA_HPP
#define VGA_HPP
#include <cstdint>
#include <vector>
#include <stdint.hpp>
#include <klib-macros.hpp>

class VGA
{
private:
    std::vector<std::uint32_t> vmem;
    std::int32_t screen_w;
    std::int32_t screen_h;

public:
    static constexpr std::uint32_t FB_ADDR = 0x20000000;
    static constexpr std::uint32_t FB_SIZE = 0x00040000;
    bool is_vga_addr(std::uint32_t addr) const;
    void write_word(std::uint32_t addr, std::uint32_t data);
    void update_screen();
    VGA();
    ~VGA() = default;
};
#endif
