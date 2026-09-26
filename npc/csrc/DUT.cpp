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
    if (finalized)
        return;
    finalized = true;
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
    fault_count = 0;
    debug_memory_writes.clear();
#ifdef CONFIG_PERF_STATS
    instructions = 0;
    perf = {};
#endif
}
void DUT::step()
{
#ifdef CONFIG_MTRACE
    if (dut->debug_mtrace_valid) {
        std::println("[MTRACE] valid wen={} addr=0x{:08x} wdata=0x{:08x} rdata=0x{:08x}",
            dut->debug_mtrace_wen, dut->debug_mtrace_addr, dut->debug_mtrace_wdata, dut->debug_mtrace_rdata);
    }
#endif
    dut->clock = 0;
    dut->eval();
#if defined(CONFIG_TRACE_VCD) || defined(CONFIG_TRACE_FST)
    tfp.dump(cycle * 2);
#endif
#ifdef CONFIG_PERF_STATS
    // These are combinational/pre-edge events.  Sample them before the rising
    // edge; several are intentionally cleared or replaced by that edge.
    const bool perf_event_ifu_fetch = dut->perf_ifu_fetch;
    const bool perf_event_exu_done = dut->perf_exu_done;
    const bool perf_event_lsu_load = dut->perf_lsu_load;
    const bool perf_event_lsu_store = dut->perf_lsu_store;
    const bool perf_event_mdu_req = dut->perf_mdu_req;
    const bool perf_event_mdu_done = dut->perf_mdu_done;
    const auto perf_event_mdu_op = dut->perf_mdu_op;
    const auto perf_event_kind = dut->perf_exu_event_kind;
    const bool perf_event_redirect = dut->perf_ifu_stall_redirect;
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
        const bool hs{dut->externalPins_vga_hsync != 0};
        const bool vs{dut->externalPins_vga_vsync != 0};
        const bool vv{dut->externalPins_vga_valid != 0};
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
    if (perf_event_ifu_fetch)
    {
        ++perf.instruction_fetch;
    }
#ifdef CONFIG_ITRACE_WRITE_FILE
    if (dut->debug_commit)
    {
        static auto fp = std::ofstream("itrace.txt", std::ios::app);
        fp << std::hex << "0x" << static_cast<uint32_t>(dut->debug_pc) << "\n"
           << std::dec;
    }
#endif
    if (perf_event_exu_done)
    {
        ++perf.execution_complete;
    }
#ifdef CONFIG_RV32_M
    // M扩展关闭时译码不出M指令, 这些信号恒为0, 不再逐拍采集
    if (perf_event_mdu_req)
    {
        ++perf.mdu_request;
    }
    if (perf_event_mdu_done)
    {
        ++perf.mdu_complete;
        switch (static_cast<unsigned>(perf_event_mdu_op))
        {
        case 0: ++perf.mdu_mul; break;
        case 1: ++perf.mdu_mulh; break;
        case 2: ++perf.mdu_mulhsu; break;
        case 3: ++perf.mdu_mulhu; break;
        case 4: ++perf.mdu_div; break;
        case 5: ++perf.mdu_divu; break;
        case 6: ++perf.mdu_rem; break;
        case 7: ++perf.mdu_remu; break;
        default: break;
        }
    }
    if (dut->perf_mdu_active)
    {
        ++perf.mdu_active_cycle;
    }
    if (dut->perf_mdu_wait)
    {
        ++perf.mdu_wait_cycle;
    }
#endif
    if (perf_event_lsu_load)
    {
        ++perf.load_data;
    }
    if (perf_event_lsu_store)
    {
        ++perf.store_data;
    }
    switch (static_cast<unsigned>(perf_event_kind))
    {
    case 1: ++perf.arithmetic_operation; break;
    case 2: ++perf.memory_access_operation; break;
    case 3: ++perf.control_status_register_operation; break;
    case 4: ++perf.branch_operation; break;
    case 5: ++perf.jal_operation; break;
    case 6: ++perf.jalr_operation; break;
    default: break;
    }
    if (dut->perf_ifu_stall_pipeline)
    {
        ++perf.instruction_fetch_stall_pipeline;
    }
    if (dut->perf_ifu_stall_axi)
    {
        ++perf.instruction_fetch_stall_axi;
    }
    if (dut->perf_ifu_stall_ar)
    {
        ++perf.instruction_fetch_stall_ar;
    }
    if (dut->perf_ifu_stall_r)
    {
        ++perf.instruction_fetch_stall_r;
    }
    if (perf_event_redirect)
    {
        ++perf.instruction_fetch_stall_redirect;
    }
    if (dut->perf_ifu_stall_idle)
    {
        ++perf.instruction_fetch_stall_idle;
    }
    if (dut->perf_execution_active)
    {
        ++perf.execution_active_cycle;
    }
    if (dut->perf_exu_stall_lsu)
    {
        ++perf.exu_stall_lsu;
    }
    if (dut->perf_mem_waitslot)
    {
        ++perf.mem_waitslot;
    }
    if (dut->perf_execution_active && dut->perf_alu_op)
    {
        ++perf.arithmetic_operation_active_cycle;
    }
    if (dut->perf_execution_active && dut->perf_mem_op)
    {
        ++perf.memory_access_operation_active_cycle;
    }
    if (dut->perf_execution_active && dut->perf_csr_op)
    {
        ++perf.control_status_register_operation_active_cycle;
    }
    if (dut->perf_execution_active && dut->perf_branch_op)
    {
        ++perf.branch_operation_active_cycle;
    }
    if (dut->perf_lsu_active)
    {
        ++perf.load_store_unit_active_cycle;
    }
    if (dut->perf_lsu_load_active)
    {
        ++perf.load_store_unit_load_active_cycle;
    }
    if (dut->perf_lsu_store_active)
    {
        ++perf.load_store_unit_store_active_cycle;
    }
    if (dut->perf_lsu_stall_read_ar)
    {
        ++perf.lsu_stall_read_ar;
    }
    if (dut->perf_lsu_stall_read_r)
    {
        ++perf.lsu_stall_read_r;
    }
    if (dut->perf_lsu_stall_write_req)
    {
        ++perf.lsu_stall_write_req;
    }
    if (dut->perf_lsu_stall_write_b)
    {
        ++perf.lsu_stall_write_b;
    }
#ifdef CONFIG_ICACHE
    // ICache关闭时取指直连AXI, 这些信号恒为0, 不再逐拍采集
    if (dut->perf_icache_hit)
    {
        ++perf.icache_hit;
    }
    if (dut->perf_icache_miss)
    {
        ++perf.icache_miss;
    }
#endif
#ifdef CONFIG_DCACHE
    // DCache关闭时这些信号恒为0, 不再逐拍采集
    if (dut->perf_dcache_hit)
    {
        ++perf.dcache_hit;
    }
    if (dut->perf_dcache_miss)
    {
        ++perf.dcache_miss;
    }
#endif
    if (dut->perf_idu_stall_raw)
    {
        ++perf.idu_stall_raw;
    }
    if (dut->perf_idu_stall_raw_loaduse)
    {
        ++perf.idu_stall_raw_loaduse;
    }
    if (dut->perf_idu_stall_raw_alu)
    {
        ++perf.idu_stall_raw_alu;
    }
    if (dut->perf_exu_idle_noinput)
    {
        ++perf.exu_idle_noinput;
    }
    // TrapCommit is a pre-edge combinational pulse and can disappear when the
    // pipeline flushes on that same edge.  debug_trap_valid is its registered,
    // architecturally precise counterpart and is safe to sample here.
    if (dut->debug_trap_valid)
    {
        ++perf.trap_count;
    }
#endif
#ifdef CONFIG_ITRACE
    if (dut->debug_commit)
    {
        Iringbuf.push(dut->debug_pc, dut->debug_instructions, 4);
    }
#endif
#ifdef CONFIG_FTRACE
    if (dut->debug_commit)
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
    // 维护调试器的安全 live-memory shadow。只有成功退休的 store
    // 才会产生 mtrace；总线错误和非对齐访问不会污染它。
    if (dut->debug_mtrace_valid && dut->debug_mtrace_wen)
    {
        const auto address{static_cast<std::uint32_t>(dut->debug_mtrace_addr)};
        const auto data{static_cast<std::uint32_t>(dut->debug_mtrace_wdata)};
        const auto width{static_cast<unsigned>(dut->debug_mtrace_width)};
        const auto bytes{width <= 2 ? (1U << width) : 0U};
#ifdef VRISCV32E_NPC
        const bool trackable{
            address >= static_cast<std::uint32_t>(CONFIG_MBASE) &&
            address - static_cast<std::uint32_t>(CONFIG_MBASE) <
                static_cast<std::uint32_t>(CONFIG_MSIZE)};
#else
        const bool trackable{
            (address >= 0x0f000000U && address < 0x0f008000U) ||
            (address >= 0x80000000U && address < 0x80400000U) ||
            (address >= 0xa0000000U && address < 0xa2000000U) ||
            // With ChipLink enabled this window is external RAM.  Keeping it
            // here unconditionally is safe: without ChipLink no store to the
            // window can retire successfully, so no shadow entry is created.
            address >= 0xc0000000U};
#endif
        if (trackable && bytes != 0 &&
            address <= std::numeric_limits<std::uint32_t>::max() - (bytes - 1U))
        {
            for (unsigned index{0}; index < bytes; ++index)
            {
                debug_memory_writes[address + index] =
                    static_cast<std::uint8_t>(data >> (index * 8U));
            }
        }
    }
#ifdef CONFIG_MTRACE
    if (dut->debug_mtrace_valid)
    {
        MtraceRecord(
            dut->debug_mtrace_pc,
            dut->debug_mtrace_addr,
            dut->debug_mtrace_wdata,
            dut->debug_mtrace_rdata,
            dut->debug_mtrace_width,
            dut->debug_mtrace_wen);
    }
#endif
#ifdef CONFIG_DIFFTEST
    // 同拍可能同时有更老的 WBU 退休和年轻的 EXU trap。
    // 按程序顺序先比对退休，再同步 trap。
    if (dut->debug_commit)
    {
        DifftestStep(*this);
    }
    if (dut->debug_trap_valid)
    {
        DifftestTrapStep(*this);
    }
#endif
    if (dut->debug_access_fault)
    {
        // 限流: 连续fault时只打前几条, 防止日志撑爆磁盘(/tmp只有12G)
        if (fault_count++ < 20)
        {
        auto resp{static_cast<unsigned>(dut->debug_access_fault_resp)};
        auto pc{static_cast<std::uint32_t>(dut->debug_access_fault_pc)};
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
const PerfStats &DUT::GetPerfStats() const noexcept
{
    return perf;
}
std::expected<std::uint32_t, std::string> DUT::ReadGPR(std::uint32_t index)
{
    if (index >= 32)
    {
        return std::unexpected{std::format("GPR编号都超31号了: {0}", index)};
    }
    dut->debug_gpr_raddr = static_cast<CData>(index);
    dut->eval();
    return static_cast<std::uint32_t>(dut->debug_gpr_rdata);
}
std::expected<std::uint32_t, std::string> DUT::ReadPC()
{
    dut->eval();
    return static_cast<std::uint32_t>(dut->debug_arch_pc);
}
std::expected<std::uint32_t, std::string> DUT::ReadMemory(std::uint32_t addr, std::size_t size)
{
    if (size != 1 && size != 2 && size != 4)
    {
        return std::unexpected{std::format("不支持的内存读取长度：{}", size)};
    }
    if (addr > std::numeric_limits<std::uint32_t>::max() - (size - 1U))
    {
        return std::unexpected{std::format("内存读取地址溢出：0x{:08x}", addr)};
    }

    constexpr std::uint32_t IMAGE_BASE{CONFIG_MBASE};
    std::uint32_t value{0};
    for (std::size_t index{0}; index < size; ++index)
    {
        const auto current{addr + static_cast<std::uint32_t>(index)};
        std::optional<std::uint8_t> byte;
        if (const auto written{debug_memory_writes.find(current)};
            written != debug_memory_writes.end())
        {
            byte = written->second;
        }
        else if (current >= IMAGE_BASE)
        {
            const auto offset{static_cast<std::size_t>(current - IMAGE_BASE)};
            if (offset < FlashMemory.size())
                byte = FlashMemory[offset];
        }

        if (!byte)
        {
            return std::unexpected{std::format(
                "地址 0x{:08x} 没有可验证的实时 RAM 数据；"
                "调试器只能读取初始镜像或已退休 store 的 shadow",
                current)};
        }
        value |= static_cast<std::uint32_t>(*byte) << (index * 8U);
    }
    return value;
}

void DUT::EnableVGACheck()
{
    vga_check = true;
    vga_frame.assign(640 * 480 * 3, 0);
}
bool DUT::VGACheckReport()
{
    if (!vga_check) return true;
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
    return ok;
}
