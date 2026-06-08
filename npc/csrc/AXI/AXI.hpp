#ifndef AXI_HPP
#define AXI_HPP
#include <cstdint>
#include "../Memory/Memory.hpp"
#include "../MMIO/MMIO.hpp"
#include "VRV32I.h"
class AXI
{
private:
    Memory &memory;
    // AR到R
    std::uint32_t InstructionReadAddress{0};
    std::uint32_t DataReadAddress{0};
    bool InstructionReadPending{false}; // 发了地址，等数据回来
    bool DataReadPending{false};        // 同上
    void HandleInstructionAR(VRV32I &cpu) noexcept;
    void HandleInstructionR(VRV32I &cpu) noexcept;
    void HandleDataAR(VRV32I &cpu) noexcept;
    void HandleDataR(VRV32I &cpu) noexcept;
    // AW+W到B
    bool DataWritePending{false}; // 发了地址和数据，等B回复
    void HandleDataAW_W(VRV32I &cpu) noexcept;
    void HandleDataB(VRV32I &cpu) noexcept;
    // 新加的，为了接时间器
    MMIO mmio;
public:
    AXI() = delete;
    ~AXI() = default;
    AXI(Memory &memory);
    void reset(VRV32I &CPU);
    void eval(VRV32I &CPU);
};
#endif
