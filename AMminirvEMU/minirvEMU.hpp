#include <cstdint>
#ifndef MINIRVEMU_HPP
#define MINIRVEMU_HPP

#include <stdint.hpp>
#include <am.hpp>
#include "Decoder.hpp"
#include "ImmGen.hpp"
#include "VGA.hpp"

class minirvEMU {
private:
    std::uint32_t PC;
    std::uint32_t R[16];
    std::uint32_t M[262144]; // 1MB 内存
    Decoder decoder;
    ImmGen immGen;
    VGA vga;
    bool halted;

public:
    minirvEMU(); // 构造函数声明
    void init_vga();
    void update_vga();
    std::uint32_t GetPC() const;

    void write_word(std::uint32_t addr, std::uint32_t value);
    std::uint32_t read_word(std::uint32_t addr);
    void write_byte(std::uint32_t addr, std::uint8_t value);
    std::uint8_t read_byte(std::uint32_t addr);
    
    void step();
    bool IsHalted() const;
};
#endif