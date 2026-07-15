#ifndef MINIRVEMU_HPP
#define MINIRVEMU_HPP
#include <print>
#include <cstdint>
#include <array>
#include <vector>
#include <initializer_list>
#include "Decoder.hpp"
#include "ImmGen.hpp"
#include "VGA.hpp"

class minirvEMU
{
private:
    std::uint32_t PC{0};
    std::array<std::uint32_t, 16> R{};
    std::vector<std::uint32_t> M;
    Decoder decoder;
    ImmGen immGen;
    bool halted{false};
    void ensure_memory(std::uint32_t word_idx);
    static std::uint8_t get_rd(std::uint32_t inst);
    static std::uint8_t get_rs1(std::uint32_t inst);
    static std::uint8_t get_rs2(std::uint32_t inst);
    VGA vga;

public:
    minirvEMU();
    ~minirvEMU() = default;
    void reset();
    std::uint32_t GetPC() const;
    void SetPC(std::uint32_t value);
    std::uint32_t GetRegister(std::size_t index) const;
    void SetRegister(std::size_t index, std::uint32_t value);
    std::uint32_t GetMemory(std::size_t address) const;
    void SetMemory(std::size_t address, std::uint32_t value);
    std::size_t GetMemorySize() const;
    std::size_t GetRegisterCount() const;
    void IncrementPC();
    void LoadProgram(const std::vector<std::uint32_t> &program);
    void LoadProgram(const std::initializer_list<std::uint32_t> &program);
    void PrintState() const;
    minirvEMU(const minirvEMU &) = delete;
    minirvEMU &operator=(const minirvEMU &) = delete;
    void write_word(std::uint32_t addr, std::uint32_t value);
    std::uint32_t read_word(std::uint32_t addr);
    void write_byte(std::uint32_t addr, std::uint8_t value);
    std::uint8_t read_byte(std::uint32_t addr);
    void step();
    bool IsHalted() const;
    static constexpr int REG_A0 = 10;
    void UpdateVGA();
};
#endif
