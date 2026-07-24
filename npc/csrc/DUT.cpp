module;
#ifdef VRISCV32E_NPC
#include "Vriscv32e_npc_SimTop.h"
#define TOP_MODULE Vriscv32e_npc_SimTop
#else
#include "VysyxSoCFull.h"
#define TOP_MODULE VysyxSoCFull
#endif
#ifdef CONFIG_TRACE_VCD
#include <verilated_vcd_c.h>
#endif
#ifdef CONFIG_TRACE_FST
#include <verilated_fst_c.h>
#endif
module npc.DUT;
import npc.trace.itrace;
import npc.trace.disasm;
import npc.trace.mtrace;
import npc.trace.ftrace;
import npc.difftest.difftest;
import npc.ysyxSoC;
#ifdef CONFIG_TRACE_VCD
static VerilatedVcdC tfp;
#endif
#ifdef CONFIG_TRACE_FST
static VerilatedFstC tfp;
#endif
#ifndef CONFIG_TRACE_FILE
#define CONFIG_TRACE_FILE "waveform.vcd"
#endif
#ifndef CONFIG_MBASE
#define CONFIG_MBASE 0x30000000
#endif
#ifndef CONFIG_MSIZE
#define CONFIG_MSIZE 0x10000000
#endif
DUT::DUT() : dut{std::make_unique<TOP_MODULE>()}
{
    dut->debug_gpr_raddr = 0;
#ifdef CONFIG_ITRACE
    init_disasm();
#endif
#if defined(CONFIG_TRACE_VCD) || defined(CONFIG_TRACE_FST)
    Verilated::traceEverOn(true);
#if !defined(CONFIG_TRACE_DEPTH) || CONFIG_TRACE_DEPTH == 0
    dut->trace(&tfp, 99);
#else
    dut->trace(&tfp, CONFIG_TRACE_DEPTH);
#endif
    auto parent = std::filesystem::path{CONFIG_TRACE_FILE}.parent_path();
    if (!parent.empty()) std::filesystem::create_directories(parent);
    tfp.open(CONFIG_TRACE_FILE);
#endif
}
TOP_MODULE &DUT::operator*()
{
    return *dut;
}
TOP_MODULE *DUT::operator->()
{
    return dut.get();
}
void DUT::eval()
{
    dut->eval();
}
void DUT::final()
{
#if defined(CONFIG_TRACE_VCD) || defined(CONFIG_TRACE_FST)
    tfp.close();
#endif
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
#ifdef CONFIG_PERF_STATS
    instructions = 0;
    instruction_fetch_count = 0;
    execution_complete_count = 0;
    load_data_count = 0;
    store_data_count = 0;
    arithmetic_operation_count = 0;
    memory_access_operation_count = 0;
    control_status_register_operation_count = 0;
    branch_operation_count = 0;
    instruction_fetch_stall_pipeline_count = 0;
    instruction_fetch_stall_axi_count = 0;
    instruction_fetch_stall_ar_count = 0;
    instruction_fetch_stall_r_count = 0;
    instruction_fetch_stall_redirect_count = 0;
    instruction_fetch_stall_idle_count = 0;
    execution_active_cycle_count = 0;
    exu_stall_lsu_count = 0;
    arithmetic_operation_active_cycle_count = 0;
    memory_access_operation_active_cycle_count = 0;
    control_status_register_operation_active_cycle_count = 0;
    branch_operation_active_cycle_count = 0;
    load_store_unit_active_cycle_count = 0;
    load_store_unit_load_active_cycle_count = 0;
    load_store_unit_store_active_cycle_count = 0;
    lsu_stall_read_ar_count = 0;
    lsu_stall_read_r_count = 0;
    lsu_stall_write_req_count = 0;
    lsu_stall_write_b_count = 0;
    icache_hit_count = 0;
    icache_miss_count = 0;
#endif
}
void DUT::step()
{
    dut->clock = 0;
    dut->eval();
#if defined(CONFIG_TRACE_VCD) || defined(CONFIG_TRACE_FST)
    tfp.dump(cycle * 2);
#endif
    dut->clock = 1;
    dut->eval();
#if defined(CONFIG_TRACE_VCD) || defined(CONFIG_TRACE_FST)
    tfp.dump(cycle * 2 + 1);
#endif
    ++cycle;
#ifdef CONFIG_PERF_STATS
    if (dut->debug_commit)
    {
        ++instructions;
    }
    if (dut->perf_ifu_fetch)
    {
        ++instruction_fetch_count;
#ifdef CONFIG_ITRACE_WRITE_FILE
        { static auto fp = std::ofstream("itrace.txt", std::ios::app); fp << std::hex << "0x" << static_cast<uint32_t>(dut->debug_pc) << "\n" << std::dec; }
#endif
    }
    if (dut->perf_exu_done)
    {
        ++execution_complete_count;
    }
    if (dut->perf_lsu_load)
    {
        ++load_data_count;
    }
    if (dut->perf_lsu_store)
    {
        ++store_data_count;
        auto addr = static_cast<std::uint32_t>(dut->debug_mtrace_addr);
        auto data = static_cast<std::uint32_t>(dut->debug_mtrace_wdata);
        if (addr == 0xa0001380)
            std::print("{}", static_cast<char>(data & 0xff));
    }
    if (dut->perf_exu_done)
    {
        if (dut->perf_alu_op)
        {
            ++arithmetic_operation_count;
        }
        if (dut->perf_mem_op)
        {
            ++memory_access_operation_count;
        }
        if (dut->perf_csr_op)
        {
            ++control_status_register_operation_count;
        }
        if (dut->perf_branch_op)
        {
            ++branch_operation_count;
        }
    }
    if (dut->perf_ifu_stall_pipeline)
    {
        ++instruction_fetch_stall_pipeline_count;
    }
    if (dut->perf_ifu_stall_axi)
    {
        ++instruction_fetch_stall_axi_count;
    }
    if (dut->perf_ifu_stall_ar)
    {
        ++instruction_fetch_stall_ar_count;
    }
    if (dut->perf_ifu_stall_r)
    {
        ++instruction_fetch_stall_r_count;
    }
    if (dut->perf_ifu_stall_redirect)
    {
        ++instruction_fetch_stall_redirect_count;
    }
    if (dut->perf_ifu_stall_idle)
    {
        ++instruction_fetch_stall_idle_count;
    }
    if (dut->perf_execution_active)
    {
        ++execution_active_cycle_count;
    }
    if (dut->perf_exu_stall_lsu)
    {
        ++exu_stall_lsu_count;
    }
    if (dut->perf_execution_active && dut->perf_alu_op)
    {
        ++arithmetic_operation_active_cycle_count;
    }
    if (dut->perf_execution_active && dut->perf_mem_op)
    {
        ++memory_access_operation_active_cycle_count;
    }
    if (dut->perf_execution_active && dut->perf_csr_op)
    {
        ++control_status_register_operation_active_cycle_count;
    }
    if (dut->perf_execution_active && dut->perf_branch_op)
    {
        ++branch_operation_active_cycle_count;
    }
    if (dut->perf_lsu_active)
    {
        ++load_store_unit_active_cycle_count;
    }
    if (dut->perf_lsu_load_active)
    {
        ++load_store_unit_load_active_cycle_count;
    }
    if (dut->perf_lsu_store_active)
    {
        ++load_store_unit_store_active_cycle_count;
    }
    if (dut->perf_lsu_stall_read_ar)
    {
        ++lsu_stall_read_ar_count;
    }
    if (dut->perf_lsu_stall_read_r)
    {
        ++lsu_stall_read_r_count;
    }
    if (dut->perf_lsu_stall_write_req)
    {
        ++lsu_stall_write_req_count;
    }
    if (dut->perf_lsu_stall_write_b)
    {
        ++lsu_stall_write_b_count;
    }
    if (dut->perf_icache_hit)
    {
        ++icache_hit_count;
    }
    if (dut->perf_icache_miss)
    {
        ++icache_miss_count;
    }
#endif
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
    // Step-by-step difftest disabled due to multi-cycle timing issue.
    // Comparison is done at the end via DiftestFinalCheck().
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
    {
        static int n = 0;
        if (n < 5) { n++; std::print("[SV={}]", static_cast<int>(dut->semihost_valid)); }
    }
    static uint8_t last_char = 0xff;
    if (dut->semihost_valid) {
        auto ch = static_cast<uint8_t>(dut->semihost_char);
        if (ch != last_char) {
            std::print("{}", static_cast<char>(ch));
            last_char = ch;
        }
    }
}
std::size_t DUT::GetCycle() const
{
    return cycle;
}
std::size_t DUT::GetInstructions() const
{
    return instructions;
}
std::size_t DUT::GetInstructionFetchCount() const
{
    return instruction_fetch_count;
}
std::size_t DUT::GetExecutionCompleteCount() const
{
    return execution_complete_count;
}
std::size_t DUT::GetLoadDataCount() const
{
    return load_data_count;
}
std::size_t DUT::GetStoreDataCount() const
{
    return store_data_count;
}
std::size_t DUT::GetArithmeticOperationCount() const
{
    return arithmetic_operation_count;
}
std::size_t DUT::GetMemoryAccessOperationCount() const
{
    return memory_access_operation_count;
}
std::size_t DUT::GetControlStatusRegisterOperationCount() const
{
    return control_status_register_operation_count;
}
std::size_t DUT::GetBranchOperationCount() const
{
    return branch_operation_count;
}
std::size_t DUT::GetInstructionFetchStallPipelineCount() const
{
    return instruction_fetch_stall_pipeline_count;
}
std::size_t DUT::GetInstructionFetchStallAXICount() const
{
    return instruction_fetch_stall_axi_count;
}
std::size_t DUT::GetInstructionFetchStallARCount() const
{
    return instruction_fetch_stall_ar_count;
}
std::size_t DUT::GetInstructionFetchStallRCount() const
{
    return instruction_fetch_stall_r_count;
}
std::size_t DUT::GetInstructionFetchStallRedirectCount() const
{
    return instruction_fetch_stall_redirect_count;
}
std::size_t DUT::GetInstructionFetchStallIdleCount() const
{
    return instruction_fetch_stall_idle_count;
}
std::size_t DUT::GetExecutionActiveCycleCount() const
{
    return execution_active_cycle_count;
}
std::size_t DUT::GetEXUStallLSUCount() const
{
    return exu_stall_lsu_count;
}
std::size_t DUT::GetArithmeticOperationActiveCycleCount() const
{
    return arithmetic_operation_active_cycle_count;
}
std::size_t DUT::GetMemoryAccessOperationActiveCycleCount() const
{
    return memory_access_operation_active_cycle_count;
}
std::size_t DUT::GetControlStatusRegisterOperationActiveCycleCount() const
{
    return control_status_register_operation_active_cycle_count;
}
std::size_t DUT::GetBranchOperationActiveCycleCount() const
{
    return branch_operation_active_cycle_count;
}
std::size_t DUT::GetLoadStoreUnitActiveCycleCount() const
{
    return load_store_unit_active_cycle_count;
}
std::size_t DUT::GetLoadStoreUnitLoadActiveCycleCount() const
{
    return load_store_unit_load_active_cycle_count;
}
std::size_t DUT::GetLoadStoreUnitStoreActiveCycleCount() const
{
    return load_store_unit_store_active_cycle_count;
}
std::size_t DUT::GetLSUStallReadARCount() const
{
    return lsu_stall_read_ar_count;
}
std::size_t DUT::GetLSUStallReadRCount() const
{
    return lsu_stall_read_r_count;
}
std::size_t DUT::GetLSUStallWriteReqCount() const
{
    return lsu_stall_write_req_count;
}
std::size_t DUT::GetLSUStallWriteBCount() const
{
    return lsu_stall_write_b_count;
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
    constexpr std::uint32_t FLASH_BASE{CONFIG_MBASE};
    constexpr std::uint32_t FLASH_SIZE{CONFIG_MSIZE};
    if (addr >= FLASH_BASE && addr + size <= FLASH_BASE + FLASH_SIZE)
    {
        auto offset{addr - FLASH_BASE};
        if (offset + size > FlashMemory.size())
        {
            return std::unexpected{std::format("Flash 地址越界：0x{:08x}", addr)};
        }
        std::uint32_t value{0};
        for (std::size_t i{0}; i < size; ++i)
        {
            value |= static_cast<std::uint32_t>(FlashMemory[offset + i]) << (i * 8);
        }
        return value;
    }
    return std::unexpected{std::format("地址 0x{:08x} 不在可读范围内（目前仅支持 Flash 0x{:08x}-0x{:08x}）", addr, FLASH_BASE, FLASH_BASE + FLASH_SIZE)};
}
std::size_t DUT::GetICacheHitCount() const
{
    return icache_hit_count;
}
std::size_t DUT::GetICacheMissCount() const
{
    return icache_miss_count;
}
