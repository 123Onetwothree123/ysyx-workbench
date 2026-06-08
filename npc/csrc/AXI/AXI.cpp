#include "AXI.hpp"
#include <cstdio>
static constexpr std::uint32_t AlignWord(std::uint32_t address) noexcept
{
    return address & ~0x3u;
}
AXI::AXI(Memory &memory) : memory(memory) {}
void AXI::reset(VRV32I &CPU)
{
    CPU.io_MemoryBus_AR_ARREADY = 0;
    CPU.io_MemoryBus_R_RVALID = 0;
    CPU.io_MemoryBus_R_RDATA = 0;
    CPU.io_MemoryBus_R_RRESP = 0;
    CPU.io_MemoryBus_AW_AWREADY = 0;
    CPU.io_MemoryBus_W_WREADY = 0;
    CPU.io_MemoryBus_B_BVALID = 0;
    CPU.io_MemoryBus_B_BRESP = 0;
    ReadAddress = 0;
    ReadPending = false;
    DataWriteAddress = 0;
    DataWriteData = 0;
    DataWriteMask = 0;
    DataWriteAddressPending = false;
    DataWriteDataPending = false;
    DataWriteResponsePending = false;
}
void AXI::HandleReadAR(VRV32I &CPU) noexcept
{
    CPU.io_MemoryBus_AR_ARREADY = !ReadPending;
    if (CPU.io_MemoryBus_AR_ARVALID && CPU.io_MemoryBus_AR_ARREADY)
    {
        ReadAddress = CPU.io_MemoryBus_AR_ARADDR;
        ReadPending = true;
    }
}
void AXI::HandleReadR(VRV32I &CPU) noexcept
{
    CPU.io_MemoryBus_R_RVALID = false;
    CPU.io_MemoryBus_R_RDATA = 0;
    CPU.io_MemoryBus_R_RRESP = 0;
    if (!ReadPending)
    {
        return;
    }
    CPU.io_MemoryBus_R_RVALID = true;
    auto address{AlignWord(ReadAddress)};
    if (auto data = mmio.LoadWord(address))
    {
        CPU.io_MemoryBus_R_RDATA = *data;
    }
    else
    {
        auto result{memory.LoadWord(address)};
        CPU.io_MemoryBus_R_RDATA = result.value_or(0);
    }
    CPU.io_MemoryBus_R_RRESP = 0;
    if (CPU.io_MemoryBus_R_RREADY)
    {
        ReadPending = false;
    }
}
void AXI::HandleWriteAW_W(VRV32I &CPU) noexcept
{
    CPU.io_MemoryBus_AW_AWREADY = !DataWriteAddressPending && !DataWriteResponsePending;
    CPU.io_MemoryBus_W_WREADY = !DataWriteDataPending && !DataWriteResponsePending;
    if (CPU.io_MemoryBus_AW_AWVALID && CPU.io_MemoryBus_AW_AWREADY)
    {
        DataWriteAddress = CPU.io_MemoryBus_AW_AWADDR;
        DataWriteAddressPending = true;
    }
    if (CPU.io_MemoryBus_W_WVALID && CPU.io_MemoryBus_W_WREADY)
    {
        DataWriteData = CPU.io_MemoryBus_W_WDATA;
        DataWriteMask = static_cast<std::uint8_t>(CPU.io_MemoryBus_W_WSTRB);
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
            bool WriteByte0{(mask & 0b0001) != 0};
            bool WriteByte1{(mask & 0b0010) != 0};
            bool WriteByte2{(mask & 0b0100) != 0};
            bool WriteByte3{(mask & 0b1000) != 0};
            auto Byte0{static_cast<std::uint8_t>(value >> 0)};
            auto Byte1{static_cast<std::uint8_t>(value >> 8)};
            auto Byte2{static_cast<std::uint8_t>(value >> 16)};
            auto Byte3{static_cast<std::uint8_t>(value >> 24)};
            if (WriteByte0)
            {
                (void)memory.StoreByte(base_address, Byte0);
            }
            if (WriteByte1)
            {
                (void)memory.StoreByte(base_address + 1, Byte1);
            }
            if (WriteByte2)
            {
                (void)memory.StoreByte(base_address + 2, Byte2);
            }
            if (WriteByte3)
            {
                (void)memory.StoreByte(base_address + 3, Byte3);
            }
        }
        DataWriteAddressPending = false;
        DataWriteDataPending = false;
        DataWriteResponsePending = true;
    }
}
void AXI::HandleWriteB(VRV32I &CPU) noexcept
{
    CPU.io_MemoryBus_B_BVALID = false;
    CPU.io_MemoryBus_B_BRESP = 0;
    if (!DataWriteResponsePending)
    {
        return;
    }
    CPU.io_MemoryBus_B_BVALID = true;
    CPU.io_MemoryBus_B_BRESP = 0;
    if (CPU.io_MemoryBus_B_BREADY)
    {
        DataWriteResponsePending = false;
    }
}
void AXI::eval(VRV32I &CPU)
{
    HandleReadAR(CPU);
    HandleWriteAW_W(CPU);
    HandleWriteB(CPU);
    HandleReadR(CPU);
}
