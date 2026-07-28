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
// 注意: 这里故意泄漏堆上的trace对象. 此前用静态对象, 退出时其析构函数调用
// Verilated::removeFlushCb, 在Verilator 5.050下会访问已损毁的回调链表而段错误
#ifdef CONFIG_TRACE_VCD
static VerilatedVcdC &tfp = *new VerilatedVcdC;
#endif
#ifdef CONFIG_TRACE_FST
static VerilatedFstC &tfp = *new VerilatedFstC;
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
DUT::DUT()
{
#if defined(CONFIG_TRACE_VCD) || defined(CONFIG_TRACE_FST)
    Verilated::traceEverOn(true);
#endif
    dut = std::make_unique<TOP_MODULE>();
    dut->debug_gpr_raddr = 0;
#ifdef CONFIG_ITRACE
    init_disasm();
#endif
#if defined(CONFIG_TRACE_VCD) || defined(CONFIG_TRACE_FST)
#if !defined(CONFIG_TRACE_DEPTH) || CONFIG_TRACE_DEPTH == 0
    dut->trace(&tfp, 99);
#else
    dut->trace(&tfp, CONFIG_TRACE_DEPTH);
#endif
    auto parent = std::filesystem::path{CONFIG_TRACE_FILE}.parent_path();
    if (!parent.empty())
        std::filesystem::create_directories(parent);
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
    // 同步复位必须有时钟边沿才能生效，先拉高 reset 跑几个周期。
    // 注意: SoC 的 cpu_reset_chain 有 10 级移位寄存器(Verilator 随机初始化),
    // 复位保持时间必须明显超过 10 拍, 否则链中的随机初始值会形成滞后的
    // 伪复位脉冲, 在取指进行中复位 CPU, 并导致 AXI 突发拍序错乱
    for (int i = 0; i < 20; ++i)
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
    if (cycle < 5) {
        std::println("[C++ DUT] step cycle={}", cycle);
    }
    // 调试保险丝: 每10万周期打印一次流水线卡住原因 + 硬周期上限, 防止卡死时空转刷爆磁盘
    if (cycle % 100000 == 99 || (dut->trap_valid && cycle > 100)) {
        std::println("[HB c={}] pc=0x{:08x} lsu_st(wreq={} wb={} rar={} rr={}) lsu_act(ld={} st={}) ifu_st(ar={} r={} pipe={})",
            cycle, static_cast<uint32_t>(dut->debug_pc),
            dut->perf_lsu_stall_write_req, dut->perf_lsu_stall_write_b,
            dut->perf_lsu_stall_read_ar, dut->perf_lsu_stall_read_r,
            dut->perf_lsu_load_active, dut->perf_lsu_store_active,
            dut->perf_ifu_stall_ar, dut->perf_ifu_stall_r, dut->perf_ifu_stall_pipeline);
    }
    if (cycle > 2000000000) {
        std::println("[DUT] 超过200万周期, 判定卡死, 强制结束. pc=0x{:08x}", static_cast<uint32_t>(dut->debug_pc));
        std::abort();
    }
    if (dut->debug_mtrace_valid) {
        std::println("[MTRACE] valid wen={} addr=0x{:08x} wdata=0x{:08x} rdata=0x{:08x}",
            dut->debug_mtrace_wen, dut->debug_mtrace_addr, dut->debug_mtrace_wdata, dut->debug_mtrace_rdata);
    }
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
#ifndef VRISCV32E_NPC
    if (vga_check)
    {
        const bool hs{dut->externalPins_vga_hsync};
        const bool vs{dut->externalPins_vga_vsync};
        const bool vv{dut->externalPins_vga_valid};
        if (hs && !vga_prev_hsync)
        {
            const auto period{cycle - vga_last_hsync_cycle};
            if (vga_line_period_count > 0)
            {
                vga_line_period_sum += period;
                if (period != 800) ++vga_line_period_bad;
            }
            ++vga_line_period_count;
            vga_last_hsync_cycle = cycle;
            vga_x = 97;
            ++vga_y;
        }
        else if (vga_x >= 0)
        {
            ++vga_x;
            if (vga_x > 800) vga_x = 1;
        }
        if (vs && !vga_prev_vsync)
        {
            const auto period{cycle - vga_last_vsync_cycle};
            if (vga_frames > 0 && period != 420000) ++vga_frame_period_bad;
            ++vga_frames;
            vga_last_vsync_cycle = cycle;
            vga_last_frame_valid_pixels = vga_valid_pixels;
            vga_valid_pixels = 0;
            vga_y = 3;
        }
        if (vv)
        {
            ++vga_valid_pixels;
            const int px{vga_x - 145};
            const int py{vga_y - 36};
            if (px < 0 || px >= 640 || py < 0 || py >= 480)
            {
                ++vga_pos_errors;
            }
            else
            {
                const auto idx{(static_cast<std::size_t>(py) * 640 + static_cast<std::size_t>(px)) * 3};
                vga_frame[idx + 0] = dut->externalPins_vga_r;
                vga_frame[idx + 1] = dut->externalPins_vga_g;
                vga_frame[idx + 2] = dut->externalPins_vga_b;
            }
        }
        vga_prev_hsync = hs;
        vga_prev_vsync = vs;
    }
#endif
#ifdef CONFIG_PERF_STATS
    if (dut->debug_commit)
    {
        ++instructions;
    }
    if (dut->perf_ifu_fetch)
    {
        ++instruction_fetch_count;
#ifdef CONFIG_ITRACE_WRITE_FILE
        {
            static auto fp = std::ofstream("itrace.txt", std::ios::app);
            fp << std::hex << "0x" << static_cast<uint32_t>(dut->debug_pc) << "\n"
               << std::dec;
        }
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
#ifndef VRISCV32E_NPC
    if (dut->perf_idu_stall_raw)
    {
        ++idu_stall_raw_count;
    }
    if (dut->perf_idu_stall_raw_loaduse)
    {
        ++idu_stall_raw_loaduse_count;
    }
    if (dut->perf_idu_stall_raw_alu)
    {
        ++idu_stall_raw_alu_count;
    }
    if (dut->perf_exu_idle_noinput)
    {
        ++exu_idle_noinput_count;
    }
    if (dut->perf_trap)
    {
        ++trap_count;
    }
#endif
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
        // 限流: 连续fault时只打前几条, 防止日志撑爆磁盘(/tmp只有12G)
        static std::size_t fault_count = 0;
        if (fault_count++ < 20)
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
std::size_t DUT::GetIDUStallRAWCount() const
{
    return idu_stall_raw_count;
}
std::size_t DUT::GetIDUStallRAWLoadUseCount() const
{
    return idu_stall_raw_loaduse_count;
}
std::size_t DUT::GetIDUStallRAWALUCount() const
{
    return idu_stall_raw_alu_count;
}
std::size_t DUT::GetEXUIdleNoInputCount() const
{
    return exu_idle_noinput_count;
}
std::size_t DUT::GetTrapCount() const
{
    return trap_count;
}

void DUT::EnableVGACheck()
{
    vga_check = true;
    vga_frame.assign(640 * 480 * 3, 0);
}
void DUT::VGACheckReport()
{
    if (!vga_check) return;
    std::println("VGA监视器结果:");
    std::println("  完整帧数: {}", vga_frames);
    if (vga_line_period_count > 1)
    {
        std::println("  行周期(期望800拍): 平均 {:.1f}, 异常行数 {}",
            static_cast<double>(vga_line_period_sum) / static_cast<double>(vga_line_period_count - 1), vga_line_period_bad);
    }
    std::println("  帧周期异常次数(期望420000拍): {}", vga_frame_period_bad);
    std::println("  最近一帧有效像素(期望307200): {}", vga_last_frame_valid_pixels);
    std::println("  像素位置错误: {}", vga_pos_errors);
    {
        std::ofstream f{"vga_frame.ppm", std::ios::binary};
        f << "P6\n640 480\n255\n";
        f.write(reinterpret_cast<const char *>(vga_frame.data()), static_cast<std::streamsize>(vga_frame.size()));
    }
    std::println("  帧已导出: vga_frame.ppm");
    const bool ok{vga_frames > 0 && vga_line_period_bad == 0 && vga_frame_period_bad == 0 &&
        vga_last_frame_valid_pixels == 307200 && vga_pos_errors == 0};
    std::println("  VGA时序检查: {}", ok ? "PASS" : "FAIL");
}
