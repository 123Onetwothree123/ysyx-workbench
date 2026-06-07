#include "AXI.hpp"
#include <cstdio>
static constexpr std::uint32_t AlignWord(std::uint32_t address) noexcept
{
    return address & ~0x3u;
}

AXI::AXI(Memory &memory) : memory(memory) {}
void AXI::reset(VRV32I &CPU)
{
    CPU.io_InstructionsBus_AR_ARREADY = 0;
    CPU.io_InstructionsBus_R_RVALID = 0;
    CPU.io_InstructionsBus_R_RDATA = 0;
    CPU.io_InstructionsBus_R_RRESP = 0;
    CPU.io_InstructionsBus_AW_AWREADY = 0;
    CPU.io_InstructionsBus_W_WREADY = 0;
    CPU.io_InstructionsBus_B_BVALID = 0;
    CPU.io_InstructionsBus_B_BRESP = 0;
    CPU.io_DataBus_AR_ARREADY = 0;
    CPU.io_DataBus_R_RVALID = 0;
    CPU.io_DataBus_R_RDATA = 0;
    CPU.io_DataBus_R_RRESP = 0;
    CPU.io_DataBus_AW_AWREADY = 0;
    CPU.io_DataBus_W_WREADY = 0;
    CPU.io_DataBus_B_BVALID = 0;
    CPU.io_DataBus_B_BRESP = 0;
    InstructionReadPending = false;
    DataReadPending = false;
    DataWritePending = false;
}
void AXI::HandleInstructionAR(VRV32I &CPU)
{
    CPU.io_InstructionsBus_AR_ARREADY = 1;
    if (CPU.io_InstructionsBus_AR_ARVALID && CPU.io_InstructionsBus_AR_ARREADY)
    {
        InstructionReadAddress = CPU.io_InstructionsBus_AR_ARADDR;
        InstructionReadPending = true;
    }
}
void AXI::HandleInstructionR(VRV32I &CPU)
{
    if (!InstructionReadPending) return;
    auto result{memory.LoadWord(InstructionReadAddress)};
    CPU.io_InstructionsBus_R_RVALID = true;
    CPU.io_InstructionsBus_R_RDATA = result.value_or(0);
    CPU.io_InstructionsBus_R_RRESP = 0;
    if (CPU.io_InstructionsBus_R_RREADY)
    {
        InstructionReadPending = false;
    }
}
void AXI::HandleDataAR(VRV32I &CPU)
{
    CPU.io_DataBus_AR_ARREADY = true;
    if (CPU.io_DataBus_AR_ARVALID && CPU.io_DataBus_AR_ARREADY)
    {
        DataReadAddress = CPU.io_DataBus_AR_ARADDR;
        DataReadPending = true;
    }
}
void AXI::HandleDataR(VRV32I &CPU)
{
    if (!DataReadPending) return;
    CPU.io_DataBus_R_RVALID = true;
    auto result{memory.LoadWord(AlignWord(DataReadAddress))};
    CPU.io_DataBus_R_RDATA = result.value_or(0);
    CPU.io_DataBus_R_RRESP = 0;
    if (CPU.io_DataBus_R_RREADY)
    {
        DataReadPending = false;
    }
}
void AXI::HandleDataAW_W(VRV32I &CPU)
{
    CPU.io_DataBus_AW_AWREADY = true;
    CPU.io_DataBus_W_WREADY = true;
    if (CPU.io_DataBus_AW_AWVALID && CPU.io_DataBus_AW_AWREADY && CPU.io_DataBus_W_WVALID && CPU.io_DataBus_W_WREADY)
    {
        auto address{CPU.io_DataBus_AW_AWADDR};
        auto base_address{AlignWord(address)};
        auto value{CPU.io_DataBus_W_WDATA};
        auto mask{static_cast<std::uint8_t>(CPU.io_DataBus_W_WSTRB)};

        // 串口输出，AM的putch往0x10000000写字符
        static constexpr std::uint32_t SerialPort = 0x10000000;
        if (address == SerialPort) {
            putchar(static_cast<char>(value & 0xFF));
            fflush(stdout);
        }

        bool WriteByte0{mask & 0b0001};
        bool WriteByte1{mask & 0b0010};
        bool WriteByte2{mask & 0b0100};
        bool WriteByte3{mask & 0b1000};
        uint8_t Byte0 = static_cast<uint8_t>(value >> 0);
        uint8_t Byte1 = static_cast<uint8_t>(value >> 8);
        uint8_t Byte2 = static_cast<uint8_t>(value >> 16);
        uint8_t Byte3 = static_cast<uint8_t>(value >> 24);
        if (WriteByte0) memory.StoreByte(base_address, Byte0);
        if (WriteByte1) memory.StoreByte(base_address + 1, Byte1);
        if (WriteByte2) memory.StoreByte(base_address + 2, Byte2);
        if (WriteByte3) memory.StoreByte(base_address + 3, Byte3);
        DataWritePending = true;
    }
}
void AXI::HandleDataB(VRV32I &CPU)
{
    if (!DataWritePending) return;
    CPU.io_DataBus_B_BVALID = true;
    CPU.io_DataBus_B_BRESP = 0;
    if (CPU.io_DataBus_B_BREADY)
    {
        DataWritePending = false;
    }
}
void AXI::eval(VRV32I &CPU)
{
    HandleDataAR(CPU);
    HandleDataAW_W(CPU);
    HandleDataB(CPU);
    HandleDataR(CPU);
    HandleInstructionAR(CPU);
    HandleInstructionR(CPU);
}
