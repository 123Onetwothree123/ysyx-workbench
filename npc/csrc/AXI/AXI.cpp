#include "AXI.hpp"
#include <cstdio>
static constexpr std::uint32_t AlignWord(std::uint32_t address) noexcept
{
    return address & ~0x3u;
}
// AXI在SoC模式下不再需要Memory，由Verilog仿真内存
void AXI::reset(VysyxSoCFull &CPU)
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
    Cycles = 0;
    mmio.Reset();
}
void AXI::HandleReadAR(VysyxSoCFull &CPU) noexcept
{
    CPU.io_MemoryBus_AR_ARREADY = !ReadPending;
    if (CPU.io_MemoryBus_AR_ARVALID && CPU.io_MemoryBus_AR_ARREADY)
    {
        ReadAddress = CPU.io_MemoryBus_AR_ARADDR;
        ReadPending = true;
    }
}
void AXI::HandleReadR(VysyxSoCFull &CPU) noexcept
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
    if (auto data = mmio.LoadWord(address, Cycles))
    {
        CPU.io_MemoryBus_R_RDATA = *data;
    }
    else
    {
        // SoC模式下内存由Verilog仿真，非MMIO地址不应到达这里
        CPU.io_MemoryBus_R_RDATA = 0;
    }
    CPU.io_MemoryBus_R_RRESP = 0;
    if (CPU.io_MemoryBus_R_RREADY)
    {
        ReadPending = false;
    }
}
void AXI::HandleWriteAW_W(VysyxSoCFull &CPU) noexcept
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
            // SoC模式下内存由Verilog仿真，不需要C++端写内存了
        }
        DataWriteAddressPending = false;
        DataWriteDataPending = false;
        DataWriteResponsePending = true;
    }
}
void AXI::HandleWriteB(VysyxSoCFull &CPU) noexcept
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
void AXI::eval(VysyxSoCFull &CPU)
{
    HandleReadAR(CPU);
    HandleWriteAW_W(CPU);
    HandleWriteB(CPU);
    HandleReadR(CPU);
    ++Cycles;
}
