#ifndef MINIRVEMU_H
#define MINIRVEMU_H
#include <iostream>
#include <cstdint>
#include <string>
#include <cstring>
#include <array>
#include <vector>
#include <deque>
#include <initializer_list>
#include <iomanip>
#include <fstream>
#include <filesystem>
#include "Decoder.h"
#include "ImmGen.h"
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
    struct MemoryTraceEntry
    {
        const char *access;
        const char *width;
        uint32_t physical_addr_shifted;
        uint32_t virtual_addr_raw;
        uint32_t value;
    };
    bool trace_enabled{false};
    bool trace_step_active{false};
    uint64_t trace_step_counter{0};
    uint32_t trace_step_pc{0};
    uint32_t trace_step_inst{0};
    std::ofstream trace_stream;
    std::vector<MemoryTraceEntry> trace_memory_entries;
    std::deque<uint64_t> state_signature_history;
    // 自动扩容内存到指定的字索引
    void ensure_memory(uint32_t word_idx);
    static uint8_t get_rd(uint32_t inst);
    static uint8_t get_rs1(uint32_t inst);
    static uint8_t get_rs2(uint32_t inst);
    void trace_memory_access(const char *access, const char *width, uint32_t virtual_addr_raw, uint32_t value);
    void trace_step_begin();
    void trace_step_end();
    uint64_t build_state_signature() const;
    bool detect_state_cycle(size_t &period);
    static constexpr size_t LOOP_MAX_PERIOD = 8;
    static constexpr size_t LOOP_REPEAT_TIMES = 4;
    static constexpr size_t LOOP_HISTORY_LIMIT = LOOP_MAX_PERIOD * LOOP_REPEAT_TIMES * 4;

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
    // 启用运行时 trace 输出
    bool EnableTrace(const std::filesystem::path &trace_file_path);
    // 关闭 trace 输出
    void DisableTrace();
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
