#ifndef MINIRVEMU_H
#define MINIRVEMU_H

#include <stdint.h>
#include <am.h>
#include "Decoder.h"
#include "ImmGen.h"
#include "VGA.h"

class minirvEMU
{
private:
    uint32_t PC;
    uint32_t R[16];
    uint32_t M[262144]; // 1MB 内存
    Decoder decoder;
    ImmGen immGen;
    VGA vga;
    bool halted;

public:
    minirvEMU(); // 构造函数声明
    void init_vga();
    void update_vga();
    uint32_t GetPC() const;
    void write_word(uint32_t addr, uint32_t value);
    uint32_t read_word(uint32_t addr);
    void write_byte(uint32_t addr, uint8_t value);
    uint8_t read_byte(uint32_t addr);
    void step();
    bool IsHalted() const;
};
#endif