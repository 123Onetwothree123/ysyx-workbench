#ifndef MINIRVEMU_H
#define MINIRVEMU_H
#include <iostream>
#include <cstdint>
#include <string>
#include <cstring>
#include <array>
#include <vector>
#include <initializer_list>
#include <iomanip>
#include "Decoder.h"
#include "ImmGen.h"
#include "VGA.h"
class minirvEMU
{
private:
    uint32_t PC{0};
    std::array<uint32_t, 16> R{};
    // std::array<uint32_t, UINT32_MAX> M{};
    std::vector<uint32_t> M;
    Decoder decoder;
    ImmGen immGen;
    bool halted{false};
    // 自动扩容内存到指定的字索引
    void ensure_memory(uint32_t word_idx);
    static uint8_t get_rd(uint32_t inst);
    static uint8_t get_rs1(uint32_t inst);
    static uint8_t get_rs2(uint32_t inst);
    VGA vga;

public:
    // minirvEMU() = default;
    minirvEMU();
    ~minirvEMU() = default;
    // 重置虚拟机状态
    void reset();
    // 获取和设置程序计数器
    uint32_t GetPC() const;
    void SetPC(uint32_t value);
    // 获取和设置寄存器值
    uint32_t GetRegister(size_t index) const;
    void SetRegister(size_t index, uint32_t value);
    // 获取和设置内存值
    uint32_t GetMemory(size_t address) const;
    void SetMemory(size_t address, uint32_t value);
    // 获取内存大小
    size_t GetMemorySize() const;
    // 获取寄存器数量
    size_t GetRegisterCount() const;
    // 递增程序计数器
    void IncrementPC();
    // 加载程序到内存
    void LoadProgram(const std::vector<uint32_t> &program);
    void LoadProgram(const std::initializer_list<uint32_t> &program);
    // 打印虚拟机状态
    void PrintState() const;
    // 删除拷贝构造函数和赋值运算符，主要还是为了防止意外拷贝
    minirvEMU(const minirvEMU &) = delete;
    minirvEMU &operator=(const minirvEMU &) = delete;
    // 内存访问接口，顺便解决下32位存储与字节访问的矛盾
    // 写32位字
    void write_word(uint32_t addr, uint32_t value);
    // 读32位字
    uint32_t read_word(uint32_t addr);
    // 写字节(SB 指令使用)
    void write_byte(uint32_t addr, uint8_t value);
    // 读字节(LBU 指令使用)
    uint8_t read_byte(uint32_t addr);
    // 执行逻辑
    void step();
    bool IsHalted() const;
    // a0 对应 x10 寄存器
    static constexpr int REG_A0 = 10;
    void UpdateVGA();
};
#endif