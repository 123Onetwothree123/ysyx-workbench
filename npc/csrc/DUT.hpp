#ifndef DUT_HPP
#define DUT_HPP
#include <memory>
#include <cstdint>
#include <expected>
#include <string>
#include "VysyxSoCFull.h"

class DUT
{
private:
    std::unique_ptr<VysyxSoCFull> dut;
    std::size_t cycle{0};

public:
    DUT();
    ~DUT() = default;
    // 运算符重载，少写点代码
    VysyxSoCFull &operator*();
    VysyxSoCFull *operator->();
    void eval();
    void final();
    void step();
    void reset();
    std::size_t GetCycle() const;
    // 给sdb的
    [[nodiscard]] std::expected<std::uint32_t, std::string> ReadGPR(std::uint32_t index);
    [[nodiscard]] std::expected<std::uint32_t, std::string> ReadPC();
    [[nodiscard]] std::expected<std::uint32_t, std::string> ReadMemory(std::uint32_t addr, std::size_t size);
};
#endif
