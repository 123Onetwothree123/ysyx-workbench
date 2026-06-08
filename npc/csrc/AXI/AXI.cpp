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
    DataWriteAddress = 0;
    DataWriteData = 0;
    DataWriteMask = 0;
    DataWriteAddressPending = false;
    DataWriteDataPending = false;
    DataWriteResponsePending = false;
}
void AXI::HandleInstructionAR(VRV32I &CPU) noexcept
{
    CPU.io_InstructionsBus_AR_ARREADY = 1;
    if (CPU.io_InstructionsBus_AR_ARVALID && CPU.io_InstructionsBus_AR_ARREADY)
    {
        InstructionReadAddress = CPU.io_InstructionsBus_AR_ARADDR;
        InstructionReadPending = true;
    }
}
void AXI::HandleInstructionR(VRV32I &CPU) noexcept
{
    if (!InstructionReadPending)
    {
        return;
    }
    auto result{memory.LoadWord(InstructionReadAddress)};
    CPU.io_InstructionsBus_R_RVALID = true;
    CPU.io_InstructionsBus_R_RDATA = result.value_or(0);
    CPU.io_InstructionsBus_R_RRESP = 0;
    if (CPU.io_InstructionsBus_R_RREADY)
    {
        InstructionReadPending = false;
    }
}
void AXI::HandleDataAR(VRV32I &CPU) noexcept
{
    CPU.io_DataBus_AR_ARREADY = true;
    if (CPU.io_DataBus_AR_ARVALID && CPU.io_DataBus_AR_ARREADY)
    {
        DataReadAddress = CPU.io_DataBus_AR_ARADDR;
        DataReadPending = true;
    }
}
void AXI::HandleDataR(VRV32I &CPU) noexcept
{
    if (!DataReadPending)
    {
        return;
    }
    CPU.io_DataBus_R_RVALID = true;
    auto address{AlignWord(DataReadAddress)};
    if (auto data = mmio.LoadWord(address))
    {
        CPU.io_DataBus_R_RDATA = *data;
    }
    else
    {
        auto result{memory.LoadWord(AlignWord(DataReadAddress))};
        CPU.io_DataBus_R_RDATA = result.value_or(0);
    }
    CPU.io_DataBus_R_RRESP = 0;
    if (CPU.io_DataBus_R_RREADY)
    {
        DataReadPending = false;
    }
}
void AXI::HandleDataAW_W(VRV32I &CPU) noexcept
{
    CPU.io_DataBus_AW_AWREADY = !DataWriteAddressPending && !DataWriteResponsePending;
    CPU.io_DataBus_W_WREADY = !DataWriteDataPending && !DataWriteResponsePending;
    if (CPU.io_DataBus_AW_AWVALID && CPU.io_DataBus_AW_AWREADY)
    {
        DataWriteAddress = CPU.io_DataBus_AW_AWADDR;
        DataWriteAddressPending = true;
    }
    if (CPU.io_DataBus_W_WVALID && CPU.io_DataBus_W_WREADY)
    {
        DataWriteData = CPU.io_DataBus_W_WDATA;
        DataWriteMask = static_cast<std::uint8_t>(CPU.io_DataBus_W_WSTRB);
        DataWriteDataPending = true;
    }
    if (DataWriteAddressPending && DataWriteDataPending && !DataWriteResponsePending)
    {
        auto address{DataWriteAddress};
        auto base_address{AlignWord(address)};
        auto value{DataWriteData};
        auto mask{DataWriteMask};
        /*
        这个是AM的putch函数
        void putch(char ch)
{
  volatile char *serial_port = (volatile char *)0x10000000;
  *serial_port = ch;
}
        */
        // 先看看是不是MMIO
        auto IsMMIOWrite{mmio.StoreWord(base_address, value, mask)};
        if (!IsMMIOWrite)
        {
            bool WriteByte0{mask & 0b0001};
            bool WriteByte1{mask & 0b0010};
            bool WriteByte2{mask & 0b0100};
            bool WriteByte3{mask & 0b1000};
            auto Byte0{static_cast<std::uint8_t>(value >> 0)};
            auto Byte1{static_cast<std::uint8_t>(value >> 8)};
            auto Byte2{static_cast<std::uint8_t>(value >> 16)};
            auto Byte3{static_cast<std::uint8_t>(value >> 24)};
            if (WriteByte0)
            {
                memory.StoreByte(base_address, Byte0);
            }
            if (WriteByte1)
            {
                memory.StoreByte(base_address + 1, Byte1);
            }
            if (WriteByte2)
            {
                memory.StoreByte(base_address + 2, Byte2);
            }
            if (WriteByte3)
            {
                memory.StoreByte(base_address + 3, Byte3);
            }
        }
        DataWriteAddressPending = false;
        DataWriteDataPending = false;
        DataWriteResponsePending = true;
    }
}
void AXI::HandleDataB(VRV32I &CPU) noexcept
{
    CPU.io_DataBus_B_BVALID = false;
    CPU.io_DataBus_B_BRESP = 0;
    if (!DataWriteResponsePending)
    {
        return;
    }
    CPU.io_DataBus_B_BVALID = true;
    CPU.io_DataBus_B_BRESP = 0;
    if (CPU.io_DataBus_B_BREADY)
    {
        DataWriteResponsePending = false;
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
