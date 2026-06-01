#include "DUTControl.hpp"
#include "memory.hpp"
#include <VRV32I.h>
#include <cstdio>
#include <cstdlib>
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

static std::size_t debug_cycles{0};
void DUTControl::Step()
{
    Top->clock = 1;
    // 临时加的，后面会删掉，临时用C++加载程序，取指令桥接的
    uint32_t pc{Top->io_InstructionAddress};
    if (CheckPmemRange(pc, 4))
        Top->io_InstructionReadDATA = *(uint32_t *)&pmem[GuestToHost(pc)];
    // 数据读桥接
    uint32_t addr = Top->io_MemAddr;
    if (CheckPmemRange(addr, 4))
        Top->io_MemoryReadDATA = *(uint32_t *)&pmem[GuestToHost(addr)];
    Top->eval();
    // 临时调试：持续输出
    std::fprintf(stderr, "[cycle %zu] PC=0x%08x instr=0x%08x MemWE=%d MemAddr=0x%08x\n",
                 debug_cycles, pc, Top->io_InstructionReadDATA,
                 Top->io_MemWE, Top->io_MemAddr);
    if (Top->io_MemWE)
    {
        std::fprintf(stderr, "[WRITE cycle %zu] addr=0x%08x data=0x%08x mask=0x%x\n",
                     debug_cycles, Top->io_MemAddr, Top->io_MemWriteDATA, Top->io_MemWriteMask);
    }
    // 处理数据写
    if (Top->io_MemWE)
    {
        const uint32_t waddr{Top->io_MemAddr};
        const uint32_t wdata{Top->io_MemWriteDATA};
        const uint32_t wmask{Top->io_MemWriteMask};
        // MMIO: 串口地址 0x10000000
        if (waddr == 0x10000000)
        {
            std::putchar(wdata & 0xff);
            std::fflush(stdout);
        }
        else if (CheckPmemRange(waddr, 4))
        {
            auto offset{GuestToHost(waddr)};
            if (wmask & 0x1) pmem[offset + 0] = static_cast<uint8_t>(wdata & 0xff);
            if (wmask & 0x2) pmem[offset + 1] = static_cast<uint8_t>((wdata >> 8) & 0xff);
            if (wmask & 0x4) pmem[offset + 2] = static_cast<uint8_t>((wdata >> 16) & 0xff);
            if (wmask & 0x8) pmem[offset + 3] = static_cast<uint8_t>((wdata >> 24) & 0xff);
        }
    }

    Top->eval();
    Top->clock = 0;
    Top->eval();
    debug_cycles++;
}
void DUTControl::Final()
{
    if (!Finalized)
    {
        Top->final();
        Finalized = true;
    }
}
