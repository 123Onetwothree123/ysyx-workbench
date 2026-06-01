#include "DUTControl.hpp"
#include "memory.hpp"
#include <VRV32I.h>
#include <cstdio>
#include <cstdlib>
#include <cstdint>

namespace
{
constexpr uint32_t SERIAL_PORT{0x10000000u};

uint32_t AlignWord(uint32_t addr)
{
    return addr & ~0x3u;
}

uint32_t ReadPmemWord(uint32_t addr)
{
    const uint32_t base{AlignWord(addr)};
    if (!CheckPmemRange(base, 4))
    {
        return 0;
    }

    const auto offset{GuestToHost(base)};
    return static_cast<uint32_t>(pmem[offset + 0]) |
           (static_cast<uint32_t>(pmem[offset + 1]) << 8) |
           (static_cast<uint32_t>(pmem[offset + 2]) << 16) |
           (static_cast<uint32_t>(pmem[offset + 3]) << 24);
}

void WritePmemMasked(uint32_t addr, uint32_t data, uint32_t mask)
{
    const uint32_t base{AlignWord(addr)};
    if (!CheckPmemRange(base, 4))
    {
        return;
    }

    const auto offset{GuestToHost(base)};
    if (mask & 0x1) pmem[offset + 0] = static_cast<uint8_t>(data & 0xff);
    if (mask & 0x2) pmem[offset + 1] = static_cast<uint8_t>((data >> 8) & 0xff);
    if (mask & 0x4) pmem[offset + 2] = static_cast<uint8_t>((data >> 16) & 0xff);
    if (mask & 0x8) pmem[offset + 3] = static_cast<uint8_t>((data >> 24) & 0xff);
}
}

DUTControl::DUTControl() : Top{std::make_unique<VRV32I>()}
{
}
DUTControl::~DUTControl()
{
    Final();
}
VRV32I &DUTControl::GetTop() noexcept
{
    return *Top;
}
const VRV32I &DUTControl::GetTop() const noexcept
{
    return *Top;
}
void DUTControl::Reset()
{
    Top->clock = 0;
    Top->reset = 1;
    Top->io_InstructionReadDATA = 0;
    Top->io_MemoryReadDATA = 0;
    Top->eval();
    Top->clock = 1;
    Top->eval();
    Top->clock = 0;
    Top->reset = 0;
    Top->eval();
}

void DUTControl::Step()
{
    Top->clock = 0;
    //以下这大段是临时加的，先把程序跑起来再说，后面会直接删掉的
    Top->io_InstructionReadDATA = ReadPmemWord(Top->io_InstructionAddress);
    // 先让取指/译码/ALU组合逻辑稳定，才能拿到本周期真正的数据访存地址。
    Top->eval();//让verilator重新计算逻辑
    //临时的，后面接上AXI的时候就换掉
    const bool mem_valid{static_cast<bool>(Top->io_MemValid)};
    const bool mem_we{static_cast<bool>(Top->io_MemWE)};
    const uint32_t addr{Top->io_MemAddr};
    Top->io_MemoryReadDATA = (mem_valid && !mem_we && AlignWord(addr) != SERIAL_PORT) ? ReadPmemWord(addr) : 0;
    // 再让load数据通过LSU/WBU组合逻辑，保证上升沿写回寄存器的是新读出的值。
    Top->eval();
    const uint32_t waddr{Top->io_MemAddr};
    const uint32_t wdata{Top->io_MemWriteDATA};
    const uint32_t wmask{Top->io_MemWriteMask};
    Top->clock = 1;
    Top->eval();
    if (mem_valid && mem_we)
    {
        const uint32_t base{AlignWord(waddr)};
        if (base == SERIAL_PORT)
        {
            for (uint32_t i = 0; i < 4; i++)
            {
                if ((wmask & (1u << i)) && (base + i == SERIAL_PORT))
                {
                    std::putchar((wdata >> (8 * i)) & 0xff);
                    std::fflush(stdout);
                }
            }
        }
        else
        {
            WritePmemMasked(waddr, wdata, wmask);
        }
    }
    Top->clock = 0;
    Top->eval();
}
void DUTControl::Final()
{
    if (!Finalized)
    {
        Top->final();
        Finalized = true;
    }
}
