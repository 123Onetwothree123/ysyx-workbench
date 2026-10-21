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
#ifdef CONFIG_NVBOARD
#include <nvboard.h>
#endif
module npc.DUT;
import npc.trace.itrace;
import npc.trace.disasm;
import npc.trace.mtrace;
import npc.trace.ftrace;
import npc.difftest.difftest;
import npc.ysyxSoC;
import npc.NPCTrap;
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
#ifdef CONFIG_NVBOARD
#ifndef CONFIG_NVBOARD_UPDATE_INTERVAL
#define CONFIG_NVBOARD_UPDATE_INTERVAL 100
#endif
static_assert(CONFIG_NVBOARD_UPDATE_INTERVAL > 0,
              "NVBoard刷新周期必须是正整数");
#endif
DUT::DUT()
{
#if defined(CONFIG_TRACE_VCD) || defined(CONFIG_TRACE_FST)
    Verilated::traceEverOn(true);
#endif
    dut = std::make_unique<TOP_MODULE>();
    dut->debug_gpr_raddr = 0;
#ifdef VRISCV32E_NPC
    dut->ram_init_valid = 0;
    dut->ram_init_index = 0;
    dut->ram_init_data = 0;
#endif
#if defined(CONFIG_ITRACE) || defined(CONFIG_IRINGBUF)
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
void DUT::InitializeMemory(std::span<const std::uint8_t> image)
{
#ifdef VRISCV32E_NPC
    constexpr std::size_t RamBytes{64U * 1024U * 1024U};
    constexpr std::size_t WordBytes{sizeof(std::uint32_t)};
    if (image.size() > RamBytes)
    {
        throw std::length_error{
            std::format("direct-NPC 镜像 {} 字节超出 RAM {} 字节", image.size(), RamBytes)};
    }

    // 写满整个 RAM，而不是只写镜像覆盖的部分。这保证空镜像
    // 或较短镜像之后的内存为零，不会保留上次运行的内容。
    dut->reset = 1;
    dut->ram_init_valid = 1;
    for (std::size_t wordIndex{0}; wordIndex < RamBytes / WordBytes; ++wordIndex)
    {
        std::uint32_t word{0};
        const auto byteBase{wordIndex * WordBytes};
        for (std::size_t byteIndex{0}; byteIndex < WordBytes; ++byteIndex)
        {
            const auto imageIndex{byteBase + byteIndex};
            if (imageIndex < image.size())
            {
                word |= static_cast<std::uint32_t>(image[imageIndex]) << (byteIndex * 8U);
            }
        }
        dut->ram_init_index = static_cast<std::uint32_t>(wordIndex);
        dut->ram_init_data = word;
        dut->clock = 0;
        dut->eval();
        dut->clock = 1;
        dut->eval();
    }
    dut->clock = 0;
    dut->ram_init_valid = 0;
    dut->eval();
#else
    static_cast<void>(image);
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
#ifdef CONFIG_SDB
    debug_memory_writes.clear();
#endif
#ifdef CONFIG_PERF_STATS
    instructions = 0;
    perf = {};
#endif
}
void DUT::step()
{
    dut->clock = 0;
    dut->eval();
#ifdef CONFIG_DIFFTEST
    // Sample the exact writeback that will occur at the upcoming edge.  The
    // RTL exports these combinationally to avoid adding debug-only registers
    // to every synthesized core.
    // A binary compiled with DiffTest support may run without --diff.  Keep
    // that common case at zero extra evals just like a non-DiffTest build.
    const bool debug_gpr_write{
        DifftestIsEnabled() && static_cast<bool>(dut->debug_gpr_wen)};
    const auto debug_gpr_write_address{
        static_cast<std::size_t>(dut->debug_gpr_waddr)};
#endif
#if defined(CONFIG_TRACE_VCD) || defined(CONFIG_TRACE_FST)
    tfp.dump(cycle * 2);
#endif
#ifdef CONFIG_PERF_STATS
    // These are combinational/pre-edge events.  Sample them before the rising
    // edge; several are intentionally cleared or replaced by that edge.
    // 只采样本构建实际启用的单计数器，关闭的计数器不产生逐拍读取开销。
#ifdef CONFIG_PERF_COUNTER_IFU_FETCH
    const bool perf_event_ifu_fetch = dut->perf_ifu_fetch;
#endif
#ifdef CONFIG_PERF_COUNTER_EXU_DONE
    const bool perf_event_exu_done = dut->perf_exu_done;
#endif
#ifdef CONFIG_PERF_COUNTER_LSU_LOAD
    const bool perf_event_lsu_load = dut->perf_lsu_load;
#endif
#ifdef CONFIG_PERF_COUNTER_LSU_STORE
    const bool perf_event_lsu_store = dut->perf_lsu_store;
#endif
#if defined(CONFIG_RV32_M) && defined(CONFIG_PERF_COUNTER_MDU_REQ)
    const bool perf_event_mdu_req = dut->perf_mdu_req;
#endif
#if defined(CONFIG_RV32_M) && defined(CONFIG_PERF_COUNTER_MDU_DONE)
    const bool perf_event_mdu_done = dut->perf_mdu_done;
#if defined(CONFIG_PERF_COUNTER_MDU_OP)
    const auto perf_event_mdu_op = dut->perf_mdu_op;
#endif
#endif
#if defined(CONFIG_PERF_COUNTER_ALU_OP) || defined(CONFIG_PERF_COUNTER_MEM_OP) || \
    defined(CONFIG_PERF_COUNTER_CSR_OP) || defined(CONFIG_PERF_COUNTER_BRANCH_OP) || \
    defined(CONFIG_PERF_COUNTER_JAL_OP) || defined(CONFIG_PERF_COUNTER_JALR_OP)
    const auto perf_event_kind = dut->perf_exu_event_kind;
#endif
#ifdef CONFIG_PERF_COUNTER_IFU_STALL_REDIRECT
    const bool perf_event_redirect = dut->perf_ifu_stall_redirect;
#endif
#endif
    dut->clock = 1;
    dut->eval();
#if defined(CONFIG_TRACE_VCD) || defined(CONFIG_TRACE_FST)
    tfp.dump(cycle * 2 + 1);
#endif
    ++cycle;
#ifdef CONFIG_DIFFTEST
    // Apply the pre-edge sample only after that edge has taken effect.
    if (debug_gpr_write)
    {
        if (debug_gpr_write_address > 0 &&
            debug_gpr_write_address < architectural_gpr.size())
        {
            // Read the physical register file after the edge.  Caching the
            // advertised WBU data would hide a broken/misdirected GPR write
            // from DiffTest; this costs at most one extra eval on instructions
            // that actually write a GPR, rather than 32 evals every retire.
            dut->debug_gpr_raddr =
                static_cast<CData>(debug_gpr_write_address);
            dut->eval();
            architectural_gpr[debug_gpr_write_address] =
                static_cast<std::uint32_t>(dut->debug_gpr_rdata);
        }
    }
    architectural_gpr[0] = 0;
    architectural_pc = static_cast<std::uint32_t>(dut->debug_arch_pc);
#endif
#ifndef VRISCV32E_NPC
    if (vga_check)
    {
        constexpr int HSync{96};
        constexpr int HBackPorch{48};
        constexpr int HVisible{640};
        constexpr int HFrontPorch{16};
        constexpr int HTotal{HSync + HBackPorch + HVisible + HFrontPorch};
        constexpr int VSync{2};
        constexpr int VBackPorch{33};
        constexpr int VVisible{480};
        constexpr int VFrontPorch{10};
        constexpr int VTotal{VSync + VBackPorch + VVisible + VFrontPorch};
        constexpr std::size_t VisiblePixels{
            static_cast<std::size_t>(HVisible) * VVisible};

        bool hs{dut->externalPins_vga_hsync != 0};
        const bool vs{dut->externalPins_vga_vsync != 0};
        bool vv{dut->externalPins_vga_valid != 0};
#ifdef NPC_VGA_CHECK_TEST_INJECT_CYCLE
        // Test-only observation fault: compile a dedicated regression binary
        // with one selected sample inverted.  Production builds never define
        // this macro, so neither the DUT signals nor the checker are changed.
        static_assert(NPC_VGA_CHECK_TEST_INJECT_CYCLE > 0);
        if (cycle ==
            static_cast<std::size_t>(NPC_VGA_CHECK_TEST_INJECT_CYCLE))
        {
            hs = !hs;
            vv = !vv;
        }
#endif
        const bool hsync_rise{hs && !vga_prev_hsync};
        const bool vsync_rise{vs && !vga_prev_vsync};

        // Both synchronization signals are active-low.  Checking the run
        // length on every transition catches a correct total period with a
        // wrong pulse width, which the old rising-edge-only check missed.
        if (hs == vga_prev_hsync)
        {
            ++vga_hsync_run_length;
        }
        else
        {
            const std::size_t expected_width{
                vga_prev_hsync ? static_cast<std::size_t>(HTotal - HSync)
                                : static_cast<std::size_t>(HSync)};
            if (vga_hsync_run_length != expected_width)
                ++vga_hsync_width_bad;
            vga_hsync_run_length = 1;
        }
        if (vs == vga_prev_vsync)
        {
            ++vga_vsync_run_length;
        }
        else
        {
            const std::size_t expected_width{
                vga_prev_vsync
                    ? static_cast<std::size_t>(VTotal - VSync) * HTotal
                    : static_cast<std::size_t>(VSync) * HTotal};
            if (vga_vsync_run_length != expected_width)
                ++vga_vsync_width_bad;
            vga_vsync_run_length = 1;
        }

        // The first real rising HSYNC edge gives an unambiguous X=97 anchor.
        // Thereafter the expected raster position free-runs; malformed sync
        // pulses are observed as errors instead of silently re-aligning and
        // hiding the fault.
        bool line_start{false};
        if (!vga_horizontal_aligned)
        {
            if (hsync_rise)
            {
                vga_horizontal_aligned = true;
                vga_x = HSync + 1;
            }
        }
        else
        {
            vga_x = vga_x == HTotal ? 1 : vga_x + 1;
            line_start = vga_x == 1;
        }

        if (hsync_rise)
        {
            const auto period{cycle - vga_last_hsync_cycle};
            if (vga_line_period_count > 0)
            {
                vga_line_period_sum += period;
                if (period != HTotal) ++vga_line_period_bad;
            }
            ++vga_line_period_count;
            vga_last_hsync_cycle = cycle;
        }

        if (vga_vertical_aligned && line_start)
        {
            vga_y = vga_y == VTotal ? 1 : vga_y + 1;
        }

        if (vsync_rise)
        {
            const auto period{cycle - vga_last_vsync_cycle};
            if (vga_vertical_aligned)
            {
                if (period != static_cast<std::size_t>(HTotal) * VTotal)
                    ++vga_frame_period_bad;

                // This boundary closes a full VSYNC-to-VSYNC frame.  Count
                // every coordinate separately: a duplicate and a missing
                // pixel can no longer cancel out to the expected total.
                std::size_t missing{0};
                std::size_t duplicate{0};
                for (const auto coverage : vga_pixel_coverage)
                {
                    if (coverage == 0)
                        ++missing;
                    else if (coverage > 1)
                        duplicate += static_cast<std::size_t>(coverage - 1);
                }
                ++vga_frames;
                vga_last_frame_valid_pixels = vga_valid_pixels;
                vga_last_frame_missing_pixels = missing;
                vga_last_frame_duplicate_pixels = duplicate;
                vga_missing_pixels += missing;
                vga_duplicate_pixels += duplicate;
                if (vga_valid_pixels != VisiblePixels || missing != 0 ||
                    duplicate != 0)
                    ++vga_frame_pixel_bad;

                // Export only an immutable snapshot taken at a completed
                // frame boundary.  The next partial frame writes a separate
                // working buffer and therefore cannot contaminate the PPM.
                vga_frame = vga_working_frame;
                vga_completed_frame_available = true;
            }

            vga_last_vsync_cycle = cycle;
            vga_vertical_aligned = true;
            vga_y = VSync + 1;
            vga_valid_pixels = 0;
            std::ranges::fill(vga_working_frame, std::uint8_t{0});
            std::ranges::fill(vga_pixel_coverage, std::uint8_t{0});
            if (vga_horizontal_aligned && vga_x != 1)
                ++vga_pos_errors;
        }

        if (vga_horizontal_aligned)
        {
            const bool expected_hsync{vga_x > HSync};
            if (hs != expected_hsync) ++vga_hsync_level_errors;
        }
        if (vga_vertical_aligned)
        {
            const bool expected_vsync{vga_y > VSync};
            if (vs != expected_vsync) ++vga_vsync_level_errors;
        }

        const bool raster_aligned{
            vga_horizontal_aligned && vga_vertical_aligned};
        const bool expected_valid{raster_aligned &&
            vga_x > HSync + HBackPorch &&
            vga_x <= HSync + HBackPorch + HVisible &&
            vga_y > VSync + VBackPorch &&
            vga_y <= VSync + VBackPorch + VVisible};
        if (raster_aligned && vv != expected_valid)
            ++vga_valid_timing_errors;

        if (vv)
        {
            ++vga_valid_pixels;
            const int px{vga_x - (HSync + HBackPorch + 1)};
            const int py{vga_y - (VSync + VBackPorch + 1)};
            if (!raster_aligned || px < 0 || px >= HVisible || py < 0 ||
                py >= VVisible)
            {
                ++vga_pos_errors;
            }
            else
            {
                const auto pixel_index{static_cast<std::size_t>(py) * HVisible +
                    static_cast<std::size_t>(px)};
                auto &coverage{vga_pixel_coverage[pixel_index]};
                if (coverage != std::numeric_limits<std::uint8_t>::max())
                    ++coverage;
                const auto byte_index{pixel_index * 3};
                vga_working_frame[byte_index + 0] =
                    dut->externalPins_vga_r;
                vga_working_frame[byte_index + 1] =
                    dut->externalPins_vga_g;
                vga_working_frame[byte_index + 2] =
                    dut->externalPins_vga_b;
            }
        }
        vga_prev_hsync = hs;
        vga_prev_vsync = vs;
    }
    if (vga_check && !vga_timed_out && cycle >= 50000000)
    {
        vga_timed_out = true;
        std::println(std::cerr,
                     "VGA时序检查超过50000000周期，强制终止");
        NPCTrap::Stop(static_cast<std::uint32_t>(dut->debug_arch_pc));
    }
#endif
#ifdef CONFIG_PERF_STATS
    if (dut->debug_commit)
    {
        ++instructions;
    }
#ifdef CONFIG_PERF_COUNTER_IFU_FETCH
    if (perf_event_ifu_fetch)
    {
        ++perf.instruction_fetch;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_EXU_DONE
    if (perf_event_exu_done)
    {
        ++perf.execution_complete;
    }
#endif
#if defined(CONFIG_RV32_M) && defined(CONFIG_PERF_COUNTER_MDU_REQ)
    // M扩展关闭时译码不出M指令, 这些信号恒为0, 不再逐拍采集
    if (perf_event_mdu_req)
    {
        ++perf.mdu_request;
    }
#endif
#if defined(CONFIG_RV32_M) && defined(CONFIG_PERF_COUNTER_MDU_DONE)
    if (perf_event_mdu_done)
    {
        ++perf.mdu_complete;
#if defined(CONFIG_PERF_COUNTER_MDU_OP)
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
#endif
    }
#endif
#ifdef CONFIG_PERF_COUNTER_MDU_ACTIVE
    if (dut->perf_mdu_active)
    {
        ++perf.mdu_active_cycle;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_MDU_WAIT
    if (dut->perf_mdu_wait)
    {
        ++perf.mdu_wait_cycle;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_LSU_LOAD
    if (perf_event_lsu_load)
    {
        ++perf.load_data;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_LSU_STORE
    if (perf_event_lsu_store)
    {
        ++perf.store_data;
    }
#endif
#if defined(CONFIG_PERF_COUNTER_ALU_OP) || defined(CONFIG_PERF_COUNTER_MEM_OP) || \
    defined(CONFIG_PERF_COUNTER_CSR_OP) || defined(CONFIG_PERF_COUNTER_BRANCH_OP) || \
    defined(CONFIG_PERF_COUNTER_JAL_OP) || defined(CONFIG_PERF_COUNTER_JALR_OP)
    // 事件分类按单计数器开关合取，只统计本构建实际启用的类型。
    unsigned perf_event_mask = 0;
#ifdef CONFIG_PERF_COUNTER_ALU_OP
    perf_event_mask |= PerfEventALU;
#endif
#ifdef CONFIG_PERF_COUNTER_MEM_OP
    perf_event_mask |= PerfEventMEM;
#endif
#ifdef CONFIG_PERF_COUNTER_CSR_OP
    perf_event_mask |= PerfEventCSR;
#endif
#ifdef CONFIG_PERF_COUNTER_BRANCH_OP
    perf_event_mask |= PerfEventBRANCH;
#endif
#ifdef CONFIG_PERF_COUNTER_JAL_OP
    perf_event_mask |= PerfEventJAL;
#endif
#ifdef CONFIG_PERF_COUNTER_JALR_OP
    perf_event_mask |= PerfEventJALR;
#endif
    RecordExecutionEvent(
        perf, static_cast<unsigned>(perf_event_kind), perf_event_mask);
#endif
#ifdef CONFIG_PERF_COUNTER_IFU_STALL_PIPELINE
    if (dut->perf_ifu_stall_pipeline)
    {
        ++perf.instruction_fetch_stall_pipeline;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_IFU_STALL_AXI
    if (dut->perf_ifu_stall_axi)
    {
        ++perf.instruction_fetch_stall_axi;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_IFU_STALL_AR
    if (dut->perf_ifu_stall_ar)
    {
        ++perf.instruction_fetch_stall_ar;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_IFU_STALL_R
    if (dut->perf_ifu_stall_r)
    {
        ++perf.instruction_fetch_stall_r;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_IFU_STALL_REDIRECT
    if (perf_event_redirect)
    {
        ++perf.instruction_fetch_stall_redirect;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_IFU_STALL_IDLE
    if (dut->perf_ifu_stall_idle)
    {
        ++perf.instruction_fetch_stall_idle;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_EXECUTION_ACTIVE
    if (dut->perf_execution_active)
    {
        ++perf.execution_active_cycle;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_EXU_STALL_LSU
    if (dut->perf_exu_stall_lsu)
    {
        ++perf.exu_stall_lsu;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_MEM_WAITSLOT
    if (dut->perf_mem_waitslot)
    {
        ++perf.mem_waitslot;
    }
#endif
#if defined(CONFIG_PERF_COUNTER_ALU_OP) || defined(CONFIG_PERF_COUNTER_MEM_OP) || \
    defined(CONFIG_PERF_COUNTER_CSR_OP) || defined(CONFIG_PERF_COUNTER_BRANCH_OP)
    // 类型活跃周期：Scala 侧已用 ActiveInst 生成类型电平。这里再排除 MDU
    // 在途周期作为防御；MDU相关计数器关闭时该端口为常量0，防御自动退化。
#if defined(CONFIG_PERF_COUNTER_MDU_ACTIVE)
    const bool classify_exu_level = dut->perf_execution_active && !dut->perf_mdu_active;
#else
    const bool classify_exu_level = static_cast<bool>(dut->perf_execution_active);
#endif
#ifdef CONFIG_PERF_COUNTER_ALU_OP
    if (classify_exu_level && dut->perf_alu_op)
    {
        ++perf.arithmetic_operation_active_cycle;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_MEM_OP
    if (classify_exu_level && dut->perf_mem_op)
    {
        ++perf.memory_access_operation_active_cycle;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_CSR_OP
    if (classify_exu_level && dut->perf_csr_op)
    {
        ++perf.control_status_register_operation_active_cycle;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_BRANCH_OP
    if (classify_exu_level && dut->perf_branch_op)
    {
        ++perf.branch_operation_active_cycle;
    }
#endif
#endif
#ifdef CONFIG_PERF_COUNTER_LSU_ACTIVE
    if (dut->perf_lsu_active)
    {
        ++perf.load_store_unit_active_cycle;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_LSU_LOAD_ACTIVE
    if (dut->perf_lsu_load_active)
    {
        ++perf.load_store_unit_load_active_cycle;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_LSU_STORE_ACTIVE
    if (dut->perf_lsu_store_active)
    {
        ++perf.load_store_unit_store_active_cycle;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_LSU_STALL_READ_AR
    if (dut->perf_lsu_stall_read_ar)
    {
        ++perf.lsu_stall_read_ar;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_LSU_STALL_READ_R
    if (dut->perf_lsu_stall_read_r)
    {
        ++perf.lsu_stall_read_r;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_LSU_STALL_WRITE_REQ
    if (dut->perf_lsu_stall_write_req)
    {
        ++perf.lsu_stall_write_req;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_LSU_STALL_WRITE_B
    if (dut->perf_lsu_stall_write_b)
    {
        ++perf.lsu_stall_write_b;
    }
#endif
#if defined(CONFIG_ICACHE) && defined(CONFIG_PERF_COUNTER_ICACHE_HIT)
    // ICache关闭时取指直连AXI, 这些信号恒为0, 不再逐拍采集
    if (dut->perf_icache_hit)
    {
        ++perf.icache_hit;
    }
#endif
#if defined(CONFIG_ICACHE) && defined(CONFIG_PERF_COUNTER_ICACHE_MISS)
    if (dut->perf_icache_miss)
    {
        ++perf.icache_miss;
    }
#endif
#if defined(CONFIG_DCACHE) && defined(CONFIG_PERF_COUNTER_DCACHE_HIT)
    // DCache关闭时这些信号恒为0, 不再逐拍采集
    if (dut->perf_dcache_hit)
    {
        ++perf.dcache_hit;
    }
#endif
#if defined(CONFIG_DCACHE) && defined(CONFIG_PERF_COUNTER_DCACHE_MISS)
    if (dut->perf_dcache_miss)
    {
        ++perf.dcache_miss;
    }
#endif
#if defined(CONFIG_DCACHE) && defined(CONFIG_PERF_COUNTER_DCACHE_REFILL_REQ)
    if (dut->perf_dcache_refill_req)
    {
        ++perf.dcache_refill_req_cycle;
    }
#endif
#if defined(CONFIG_DCACHE) && defined(CONFIG_PERF_COUNTER_DCACHE_REFILL_RESP)
    if (dut->perf_dcache_refill_resp)
    {
        ++perf.dcache_refill_resp_cycle;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_IDU_STALL_RAW
    if (dut->perf_idu_stall_raw)
    {
        ++perf.idu_stall_raw;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_IDU_STALL_RAW_LOADUSE
    if (dut->perf_idu_stall_raw_loaduse)
    {
        ++perf.idu_stall_raw_loaduse;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_IDU_STALL_RAW_ALU
    if (dut->perf_idu_stall_raw_alu)
    {
        ++perf.idu_stall_raw_alu;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_EXU_IDLE_NOINPUT
    if (dut->perf_exu_idle_noinput)
    {
        ++perf.exu_idle_noinput;
    }
#endif
#ifdef CONFIG_PERF_COUNTER_TRAP_COUNT
    // TrapCommit is a pre-edge combinational pulse and can disappear when the
    // pipeline flushes on that same edge.  debug_trap_valid is its registered,
    // architecturally precise counterpart and is safe to sample here.
    if (dut->debug_trap_valid)
    {
        ++perf.trap_count;
    }
#endif
#endif
#if defined(CONFIG_ITRACE) && !defined(CONFIG_ITRACE_WRITE_FILE)
    if (dut->debug_commit)
    {
        const auto pc{static_cast<std::uint32_t>(dut->debug_pc)};
        const auto instruction{static_cast<std::uint32_t>(dut->debug_instructions)};
        std::array<std::uint8_t, 4> bytes{
            static_cast<std::uint8_t>(instruction),
            static_cast<std::uint8_t>(instruction >> 8U),
            static_cast<std::uint8_t>(instruction >> 16U),
            static_cast<std::uint8_t>(instruction >> 24U),
        };
        std::array<char, 128> assembly{};
        disassemble(
            assembly.data(),
            static_cast<int>(assembly.size()),
            pc,
            bytes.data(),
            static_cast<int>(bytes.size()));
        const auto assemblyText{assembly.front() == '\0'
                                    ? std::string_view{"<unknown>"}
                                    : std::string_view{assembly.data()}};
        std::println("[ITRACE] 0x{:08x}: {:08x}  {}", pc, instruction, assemblyText);
    }
#endif
#ifdef CONFIG_ITRACE_WRITE_FILE
    // 文件 itrace 只依赖退休信号，不应被 PERF_STATS
    // 意外控制。
    if (dut->debug_commit)
    {
        static auto fp = std::ofstream("itrace.txt", std::ios::app);
        fp << std::hex << "0x" << static_cast<std::uint32_t>(dut->debug_pc) << '\n'
           << std::dec;
    }
#endif
#ifdef CONFIG_IRINGBUF
    if (dut->debug_commit)
    {
        Iringbuf.push(dut->debug_pc, dut->debug_instructions, 4);
    }
#endif
#ifdef CONFIG_FTRACE
    if (dut->debug_commit)
    {
        // debug_next_pc 与 debug_pc/debug_instructions 属于同一条
        // 退休记录。当拍处理可避免把 trap handler PC 误当作
        // call target，也不会在程序最后一条 call/ret 时丢事件。
        GlobalFtrace.OnInstruction(
            static_cast<std::uint32_t>(dut->debug_pc),
            static_cast<std::uint32_t>(dut->debug_instructions),
            static_cast<std::uint32_t>(dut->debug_next_pc));
    }
#endif
#ifdef CONFIG_SDB
    // 维护调试器的安全 live-memory shadow。只有成功退休的 store
    // 才会产生 mtrace；总线错误和非对齐访问不会污染它。非 SDB
    // 构建连容器成员和这段逐字节维护代码都不编译，避免影响性能测量。
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
#endif
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
#ifdef CONFIG_NVBOARD
    // DUT::step 是非SDB主循环、SDB c 和 SDB si 共用的唯一时钟步进
    // 入口。把GUI服务放在这里，确保所有运行模式遵循同一个刷新周期。
    if (cycle % static_cast<std::size_t>(CONFIG_NVBOARD_UPDATE_INTERVAL) == 0)
    {
        nvboard_update();
    }
#endif
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
std::expected<std::uint32_t, std::string>
DUT::ReadCachedGPR(std::uint32_t index) const
{
    if (index >= architectural_gpr.size())
    {
        return std::unexpected{std::format("GPR编号都超31号了: {0}", index)};
    }
    return architectural_gpr[index];
}
std::uint32_t DUT::ReadCachedPC() const noexcept
{
    return architectural_pc;
}
void DUT::RefreshCachedArchitecturalState()
{
    for (std::size_t index{0}; index < architectural_gpr.size(); ++index)
    {
        dut->debug_gpr_raddr = static_cast<CData>(index);
        dut->eval();
        architectural_gpr[index] =
            static_cast<std::uint32_t>(dut->debug_gpr_rdata);
    }
    architectural_gpr[0] = 0;
    dut->debug_gpr_raddr = 0;
    dut->eval();
    architectural_pc = static_cast<std::uint32_t>(dut->debug_arch_pc);
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
    constexpr std::uint32_t IMAGE_WINDOW_SIZE{CONFIG_MSIZE};
    std::uint32_t value{0};
    for (std::size_t index{0}; index < size; ++index)
    {
        const auto current{addr + static_cast<std::uint32_t>(index)};
        std::optional<std::uint8_t> byte;
#ifdef CONFIG_SDB
        if (const auto written{debug_memory_writes.find(current)};
            written != debug_memory_writes.end())
        {
            byte = written->second;
        }
#endif
        if (!byte && current >= IMAGE_BASE)
        {
            const auto offset{static_cast<std::size_t>(current - IMAGE_BASE)};
            // direct-NPC 的 InitializeMemory 会把整个 RAM 尾部清零；SoC
            // MROM 的 DPI LoadU32 也对镜像之后的窗口返回零。调试视图
            // 必须与硬件的完整 CONFIG_MSIZE 窗口一致，但不能把窗口
            // 之外的 SoC RAM/MMIO 猜成已知零。
            if (offset < static_cast<std::size_t>(IMAGE_WINDOW_SIZE))
                byte = offset < FlashMemory.size() ? FlashMemory[offset] : std::uint8_t{0};
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
#ifdef VRISCV32E_NPC
    throw std::logic_error{"direct-NPC 顶层没有 VGA 引脚"};
#else
    vga_check = true;
    // reset() leaves the VGA generator at X=1/Y=1.  Treat the currently
    // driven levels as the first sampled cycle so the first low sync widths
    // can be checked without manufacturing a fake edge.
    vga_prev_hsync = dut->externalPins_vga_hsync != 0;
    vga_prev_vsync = dut->externalPins_vga_vsync != 0;
    vga_hsync_run_length = 1;
    vga_vsync_run_length = 1;
    vga_last_hsync_cycle = 0;
    vga_last_vsync_cycle = 0;
    vga_line_period_sum = 0;
    vga_line_period_count = 0;
    vga_line_period_bad = 0;
    vga_frame_period_bad = 0;
    vga_hsync_width_bad = 0;
    vga_vsync_width_bad = 0;
    vga_hsync_level_errors = 0;
    vga_vsync_level_errors = 0;
    vga_valid_timing_errors = 0;
    vga_frame_pixel_bad = 0;
    vga_x = -1;
    vga_y = -1;
    vga_horizontal_aligned = false;
    vga_vertical_aligned = false;
    vga_valid_pixels = 0;
    vga_last_frame_valid_pixels = 0;
    vga_last_frame_missing_pixels = 0;
    vga_last_frame_duplicate_pixels = 0;
    vga_missing_pixels = 0;
    vga_duplicate_pixels = 0;
    vga_pos_errors = 0;
    vga_frames = 0;
    vga_completed_frame_available = false;
    vga_timed_out = false;
    vga_working_frame.assign(640 * 480 * 3, 0);
    vga_frame.assign(640 * 480 * 3, 0);
    vga_pixel_coverage.assign(640 * 480, 0);
#endif
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
    std::println("  HSYNC脉宽异常(期望低96/高704拍): {}", vga_hsync_width_bad);
    std::println("  VSYNC脉宽异常(期望低1600/高418400拍): {}", vga_vsync_width_bad);
    std::println("  HSYNC光栅电平错误拍数: {}", vga_hsync_level_errors);
    std::println("  VSYNC光栅电平错误拍数: {}", vga_vsync_level_errors);
    std::println("  有效区/porch时序错误拍数: {}", vga_valid_timing_errors);
    std::println("  最近一帧有效像素(期望307200): {}", vga_last_frame_valid_pixels);
    std::println("  最近一帧缺失/重复像素: {}/{}",
        vga_last_frame_missing_pixels, vga_last_frame_duplicate_pixels);
    std::println("  累计缺失/重复像素: {}/{}",
        vga_missing_pixels, vga_duplicate_pixels);
    std::println("  有效像素异常帧数: {}", vga_frame_pixel_bad);
    std::println("  像素位置错误: {}", vga_pos_errors);
    bool ppm_ok{vga_completed_frame_available};
    if (!vga_completed_frame_available)
    {
        std::println(std::cerr, "  VGA帧导出失败: 尚未采集到完整帧");
    }
    else
    {
        std::ofstream f{"vga_frame.ppm", std::ios::binary | std::ios::trunc};
        if (!f.is_open())
        {
            std::println(std::cerr, "  VGA帧导出失败: 无法打开 vga_frame.ppm");
            ppm_ok = false;
        }
        else
        {
            f << "P6\n640 480\n255\n";
            f.write(reinterpret_cast<const char *>(vga_frame.data()),
                    static_cast<std::streamsize>(vga_frame.size()));
            if (!f)
            {
                std::println(std::cerr, "  VGA帧导出失败: 写入 vga_frame.ppm 失败");
                ppm_ok = false;
            }
            f.close();
            if (f.fail())
            {
                std::println(std::cerr, "  VGA帧导出失败: 关闭 vga_frame.ppm 失败");
                ppm_ok = false;
            }
        }
    }
    if (ppm_ok)
        std::println("  已完成帧的稳定快照已导出: vga_frame.ppm");
    const bool ok{vga_frames > 0 && vga_line_period_bad == 0 && vga_frame_period_bad == 0 &&
        vga_hsync_width_bad == 0 && vga_vsync_width_bad == 0 &&
        vga_hsync_level_errors == 0 && vga_vsync_level_errors == 0 &&
        vga_valid_timing_errors == 0 &&
        vga_frame_pixel_bad == 0 && vga_last_frame_valid_pixels == 307200 &&
        vga_last_frame_missing_pixels == 0 &&
        vga_last_frame_duplicate_pixels == 0 && vga_pos_errors == 0 &&
        !vga_timed_out && ppm_ok};
    std::println("  VGA时序检查: {}", ok ? "PASS" : "FAIL");
    return ok;
}
