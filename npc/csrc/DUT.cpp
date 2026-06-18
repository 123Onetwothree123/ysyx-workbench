#include "DUT.hpp"
#include <cstdint>
#include <format>
#include <iostream>
#include <print>
#include <vector>
#ifdef CONFIG_ITRACE
#include "trace/itrace.hpp"
#endif
#ifdef CONFIG_MTRACE
#include "trace/mtrace.hpp"
#endif
#ifdef CONFIG_FTRACE
#include "trace/ftrace.hpp"
#endif
#ifdef CONFIG_DIFFTEST
#include "difftest/difftest.hpp"
#endif
DUT::DUT() : dut{std::make_unique<VysyxSoCFull>()}
{
    dut->debug_gpr_raddr = 0;
#ifdef CONFIG_ITRACE
    init_disasm();
#endif
}
VysyxSoCFull &DUT::operator*()
{
    return *dut;
}
VysyxSoCFull *DUT::operator->()
{
    return dut.get();
}
void DUT::eval()
{
    dut->eval();
}
void DUT::final()
{
    dut->final();
}
void DUT::reset()
{
    dut->clock = 0;
    dut->reset = 1;
    dut->debug_gpr_raddr = 0;
    // 同步复位必须有时钟边沿才能生效，先拉高 reset 跑几个周期
    for (int i = 0; i < 5; ++i)
    {
        dut->clock = 0;
        dut->eval();
        dut->clock = 1;
        dut->eval();
    }
    dut->reset = 0;
    cycle = 0;
}
void DUT::step()
{
    dut->clock = 0;
    dut->eval();
    dut->clock = 1;
    dut->eval();
    ++cycle;
#ifdef CONFIG_ITRACE
    Iringbuf.push(dut->debug_pc, dut->debug_instructions, 4);
#endif
#ifdef CONFIG_FTRACE
    {
        static bool HasPreviousStep{false};
        static std::uint32_t PreviousPC{0};
        static std::uint32_t PreviousInstructions{0};
        auto CurrentPC{static_cast<std::uint32_t>(dut->debug_pc)};
        if (HasPreviousStep)
        {
            GlobalFtrace.OnInstruction(PreviousPC, PreviousInstructions, CurrentPC);
        }
        PreviousPC = CurrentPC;
        PreviousInstructions = static_cast<std::uint32_t>(dut->debug_instructions);
        HasPreviousStep = true;
    }
#endif
#ifdef CONFIG_MTRACE
    if (dut->debug_mtrace_valid)
    {
        MtraceRecord(
            dut->debug_pc,
            dut->debug_mtrace_addr,
            dut->debug_mtrace_wdata,
            dut->debug_mtrace_rdata,
            dut->debug_mtrace_width,
            dut->debug_mtrace_wen);
    }
#endif
#ifdef CONFIG_DIFFTEST
    DifftestStep(*this);
#endif
    if (dut->debug_access_fault)
    {
        auto resp{static_cast<unsigned>(dut->debug_access_fault_resp)};
        auto pc{static_cast<std::uint32_t>(dut->debug_pc)};
        if (resp == 2)
        {
            std::println(std::cerr, "Access Fault [SLVERR] at PC=0x{:08x}, cycle={}", pc, cycle);
            std::println(std::cerr, "  从设备报错了，可能是访问了不该访问的偏移或者往只读的地方写东西了");
        }
        else if (resp == 3)
        {
            std::println(std::cerr, "Access Fault [DECERR] at PC=0x{:08x}, cycle={}", pc, cycle);
            std::println(std::cerr, "  地址译码错误，鬼知道你访问了什么地址，AXI总线根本找不到对应的从设备");
        }
        else
        {
            std::println(std::cerr, "Access Fault [RESP={}] at PC=0x{:08x}, cycle={}", resp, pc, cycle);
            std::println(std::cerr, "  这什么AXI响应码，我也不认识");
        }
    }
}
std::size_t DUT::GetCycle() const
{
    return cycle;
}
std::expected<std::uint32_t, std::string> DUT::ReadGPR(std::uint32_t index)
{
    if (index >= 32)
    {
        return std::unexpected{std::format("GPR编号都超31号了，跑个毛线啊", index)};
    }
    dut->debug_gpr_raddr = static_cast<CData>(index);
    dut->eval();
    return static_cast<std::uint32_t>(dut->debug_gpr_rdata);
}
std::expected<std::uint32_t, std::string> DUT::ReadPC()
{
    dut->eval();
    return static_cast<std::uint32_t>(dut->debug_pc);
}
std::expected<std::uint32_t, std::string> DUT::ReadMemory(std::uint32_t addr, std::size_t size)
{
    if (size != 1 && size != 2 && size != 4)
    {
        return std::unexpected{std::format("不支持的内存读取长度：{}", size)};
    }
    extern std::vector<std::uint8_t> mrom;
    constexpr std::uint32_t MROM_BASE{0x20000000};
    constexpr std::uint32_t MROM_SIZE{0x1000};
    if (addr >= MROM_BASE && addr + size <= MROM_BASE + MROM_SIZE)
    {
        auto offset{addr - MROM_BASE};
        if (offset + size > mrom.size())
        {
            return std::unexpected{std::format("MROM 地址越界：0x{:08x}", addr)};
        }
        std::uint32_t value{0};
        for (std::size_t i{0}; i < size; ++i)
        {
            value |= static_cast<std::uint32_t>(mrom[offset + i]) << (i * 8);
        }
        return value;
    }
    return std::unexpected{std::format("地址 0x{:08x} 不在可读范围内（目前仅支持 MROM 0x20000000-0x20001000）", addr)};
}
