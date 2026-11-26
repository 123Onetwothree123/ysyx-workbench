module;
#ifdef VRISCV32E_NPC
#include "Vriscv32e_npc_SimTop.h"
#include <algorithm>
#define TOP_MODULE Vriscv32e_npc_SimTop
#else
#include "VysyxSoCFull.h"
#define TOP_MODULE VysyxSoCFull
#endif
#include <verilated_vcd_c.h>
#include <verilated_fst_c.h>
#include <verilated_save.h>
#if __has_include(<nvboard.h>)
#include <nvboard.h>
#else
void nvboard_update(void);
#endif
module npc.DUT;
import npc.Output;
import npc.config;
import npc.trace.itrace;
import npc.trace.disasm;
import npc.trace.mtrace;
import npc.trace.ftrace;
import npc.difftest.difftest;
import npc.ysyxSoC;
import npc.SoCMemoryMap.SoCMemoryMap;
import npc.NPCTrap;
import npc.VgaChecker;
import npc.SimulationContext;
struct DUT::Tracer
{
#if defined(VM_TRACE_VCD) && VM_TRACE_VCD
    std::unique_ptr<VerilatedVcdC> vcd;
#elif defined(VM_TRACE_FST) && VM_TRACE_FST
    std::unique_ptr<VerilatedFstC> fst;
#endif
    ~Tracer() { Close(); }
    void Dump(std::uint64_t time)
    {
#if defined(VM_TRACE_VCD) && VM_TRACE_VCD
        vcd->dump(time);
#elif defined(VM_TRACE_FST) && VM_TRACE_FST
        fst->dump(time);
#else
        static_cast<void>(time);
#endif
    }
    void Close()
    {
#if defined(VM_TRACE_VCD) && VM_TRACE_VCD
        if (vcd && vcd->isOpen())
            vcd->close();
#elif defined(VM_TRACE_FST) && VM_TRACE_FST
        if (fst && fst->isOpen())
            fst->close();
#endif
    }
};
namespace
{
// RV64 调试端口拆为低/高 32 位字，RV32 不读高位。
template <typename Top>
npc::XWord DebugWord(const Top &, std::uint32_t Low, std::uint32_t High)
{
    if constexpr (npc::XLen == 64)
    {
        return static_cast<npc::XWord>(Low) |
               (static_cast<npc::XWord>(High) << 32U);
    }
    else
    {
        static_cast<void>(High);
        return static_cast<npc::XWord>(Low);
    }
}
// 旧 RTL 缺浮点/tval 端口时降级而不阻断构建。
template <typename Top>
inline constexpr bool HasFPDebugPorts = requires(const Top &top) {
    top.debug_fpr_raddr;
    top.debug_fpr_rdata;
    top.debug_fpr_rdata_hi;
    top.debug_fpr_wen;
    top.debug_fpr_waddr;
    top.debug_fcsr;
    top.debug_fp_valid;
};

template <typename Top>
inline constexpr bool HasTrapTvalDebugPorts = requires(const Top &top) {
    top.debug_trap_tval;
    top.debug_trap_tval_hi;
};

// 浮点槽位固定 64 位：RV32F 高半区为 0。
template <typename Top>
std::uint64_t DebugFPR(const Top &top)
{
    return static_cast<std::uint64_t>(top.debug_fpr_rdata) |
           (static_cast<std::uint64_t>(top.debug_fpr_rdata_hi) << 32U);
}
template <typename Top>
npc::XWord DebugPC(const Top &top) { return DebugWord(top, top.debug_pc, top.debug_pc_hi); }
template <typename Top>
npc::XWord DebugNextPC(const Top &top) { return DebugWord(top, top.debug_next_pc, top.debug_next_pc_hi); }
template <typename Top>
npc::XWord DebugArchPC(const Top &top) { return DebugWord(top, top.debug_arch_pc, top.debug_arch_pc_hi); }
template <typename Top>
npc::XWord DebugGPR(const Top &top) { return DebugWord(top, top.debug_gpr_rdata, top.debug_gpr_rdata_hi); }
template <typename Top>
npc::XWord DebugMtracePC(const Top &top) { return DebugWord(top, top.debug_mtrace_pc, top.debug_mtrace_pc_hi); }
template <typename Top>
npc::XWord DebugMtraceAddr(const Top &top) { return DebugWord(top, top.debug_mtrace_addr, top.debug_mtrace_addr_hi); }
template <typename Top>
npc::XWord DebugMtraceWdata(const Top &top) { return DebugWord(top, top.debug_mtrace_wdata, top.debug_mtrace_wdata_hi); }
template <typename Top>
npc::XWord DebugMtraceRdata(const Top &top) { return DebugWord(top, top.debug_mtrace_rdata, top.debug_mtrace_rdata_hi); }
template <typename Top>
npc::XWord DebugFaultPC(const Top &top) { return DebugWord(top, top.debug_access_fault_pc, top.debug_access_fault_pc_hi); }
template <typename Top>
npc::XWord DebugTrapPC(const Top &top) { return DebugWord(top, top.debug_trap_pc, top.debug_trap_pc_hi); }
template <typename Top>
npc::XWord DebugTrapTarget(const Top &top) { return DebugWord(top, top.debug_trap_target, top.debug_trap_target_hi); }
template <typename Top>
npc::XWord DebugTrapPCHalt(const Top &top) { return DebugWord(top, top.trap_pc, top.trap_pc_hi); }
}
static_assert(npc::NvBoardUpdateInterval > 0,
              "NVBoard刷新周期必须是正整数");
DUT::DUT(SimulationContext &Context) : context{Context}
{
    if constexpr (npc::HasTraceVCD || npc::HasTraceFST)
    {
        Verilated::traceEverOn(true);
        tracer = std::make_unique<Tracer>();
    }
    dut = std::make_unique<TOP_MODULE>();
    heartbeat = std::getenv("NPC_HEARTBEAT") != nullptr;
    dut->debug_gpr_raddr = 0;
    if constexpr (HasFPDebugPorts<TOP_MODULE>)
        dut->debug_fpr_raddr = 0;
#ifdef VRISCV32E_NPC
    dut->ram_init_valid = 0;
    dut->ram_init_index = 0;
    dut->ram_init_data = 0;
    dut->uart_rx_valid = 0;
    dut->uart_rx_data = 0;
#endif
    if constexpr (npc::HasITrace || npc::HasIRingBuf)
    {
        init_disasm();
    }
#if defined(VM_TRACE_VCD) && VM_TRACE_VCD
    tracer->vcd = std::make_unique<VerilatedVcdC>();
    dut->trace(tracer->vcd.get(),
               npc::TraceDepth == 0 ? 99 : static_cast<int>(npc::TraceDepth));
    {
        auto parent{std::filesystem::path{npc::TraceFile}.parent_path()};
        if (!parent.empty())
            std::filesystem::create_directories(parent);
    }
    tracer->vcd->open(std::string{npc::TraceFile}.c_str());
#elif defined(VM_TRACE_FST) && VM_TRACE_FST
    tracer->fst = std::make_unique<VerilatedFstC>();
    dut->trace(tracer->fst.get(),
               npc::TraceDepth == 0 ? 99 : static_cast<int>(npc::TraceDepth));
    {
        auto parent{std::filesystem::path{npc::TraceFile}.parent_path()};
        if (!parent.empty())
            std::filesystem::create_directories(parent);
    }
    tracer->fst->open(std::string{npc::TraceFile}.c_str());
#endif
    checkpoint.LoadEnvironment();
    checkpoint.SetCallbacks(
        [this](const std::filesystem::path &path) {
            return SaveCheckpoint(path);
        },
        [this](int code) {
            if (shutdown_hook) { shutdown_hook(); } else { final(); }
            std::_Exit(code);
        });
}
DUT::~DUT() = default;

void DUT::InitializeMemory(std::span<const std::uint8_t> image)
{
#ifdef VRISCV32E_NPC
    // 与 RTL AXIRAM/PMA 共用 Kconfig 的 MSize。
    constexpr std::size_t RamBytes{npc::MSize};
    // 只清零镜像加 64KiB 边距，避免初始化整个 RAM。
    constexpr std::size_t ZeroMarginBytes{64U * 1024U};
    constexpr std::size_t WordBytes{sizeof(std::uint32_t)};
    if (image.size() > RamBytes)
    {
        throw std::length_error{
            std::format("direct-NPC 镜像 {} 字节超出 RAM {} 字节", image.size(), RamBytes)};
    }

    // 镜像后的余量清零，避免残留上次运行内容。
    dut->reset = 1;
    dut->ram_init_valid = 1;
    const std::size_t initBytes{
        std::min(RamBytes, image.size() + ZeroMarginBytes)};
    for (std::size_t wordIndex{0}; wordIndex < initBytes / WordBytes; ++wordIndex)
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
template <typename Top>
std::expected<void, std::string>
SaveCheckpointImpl(Top &top, const std::filesystem::path &path)
{
    if constexpr (requires(VerilatedSave &os, Top &model) { os << model; })
    {
        try
        {
            VerilatedSave os;
            os.open(path.string());
            os << top;
            os.close();
        }
        catch (const std::exception &error)
        {
            return std::unexpected{
                std::format("保存检查点 {} 失败：{}", path.string(), error.what())};
        }
        return {};
    }
    else
    {
        return std::unexpected{
            "当前 RTL 未使用 Verilator --savable 构建，无法保存检查点"};
    }
}

template <typename Top>
std::expected<void, std::string>
RestoreCheckpointImpl(Top &top, const std::filesystem::path &path)
{
    if constexpr (requires(VerilatedRestore &is, Top &model) { is >> model; })
    {
        try
        {
            VerilatedRestore is;
            is.open(path.string());
            is >> top;
            is.close();
        }
        catch (const std::exception &error)
        {
            return std::unexpected{
                std::format("恢复检查点 {} 失败：{}", path.string(), error.what())};
        }
        return {};
    }
    else
    {
        return std::unexpected{
            "当前 RTL 未使用 Verilator --savable 构建，无法恢复检查点"};
    }
}

std::expected<void, std::string>
DUT::SaveCheckpoint(const std::filesystem::path &path)
{
    return SaveCheckpointImpl(*dut, path);
}

std::expected<void, std::string>
DUT::RestoreCheckpoint(const std::filesystem::path &path)
{
    return RestoreCheckpointImpl(*dut, path);
}
SimulationContext &DUT::Context() noexcept
{
    return context;
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
void DUT::SetShutdownHook(std::function<void()> hook)
{
    shutdown_hook = std::move(hook);
}

void DUT::final()
{
    if (finalized)
        return;
    finalized = true;
    if (tracer)
        tracer->Close();
    dut->final();
}
void DUT::EnableUARTStdin(bool enable)
{
    uart.EnableStdin(enable);
}

void DUT::reset()
{
    dut->clock = 0;
    dut->reset = 1;
    dut->debug_gpr_raddr = 0;
    if constexpr (HasFPDebugPorts<TOP_MODULE>)
        dut->debug_fpr_raddr = 0;
#ifdef VRISCV32E_NPC
    dut->uart_rx_valid = 0;
    dut->uart_rx_data = 0;
    uart.Reset();
#endif
    // 同步复位需时钟沿；cpu_reset_chain 10 级，保持须明显超过 10 拍。
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
    if constexpr (HasFPDebugPorts<TOP_MODULE>)
    {
        mirror.SetFCSR(static_cast<std::uint64_t>(dut->debug_fcsr));
    }
    if constexpr (npc::HasSDB)
    {
        debug_memory_writes.clear();
    }
    if constexpr (npc::HasDicArchCounter)
    {
        instructions = 0;
        perf.Reset();
    }
}
void DUT::step()
{
#ifdef VRISCV32E_NPC
    const auto uart_input{uart.PumpInput(static_cast<bool>(dut->uart_rx_ready))};
    dut->uart_rx_valid = uart_input.valid ? 1 : 0;
    dut->uart_rx_data = uart_input.data;
#endif
    dut->clock = 0;
    dut->eval();
    // 写回在下一沿发生；无 --diff 时不增加 eval。
    const bool debug_gpr_write{
        DifftestIsEnabled() && static_cast<bool>(dut->debug_gpr_wen)};
    const auto debug_gpr_write_address{
        static_cast<std::size_t>(dut->debug_gpr_waddr)};
    // 浮点写口与 GPR 同沿；旧 RTL 无端口时不实例化。
    constexpr bool HasFP{HasFPDebugPorts<TOP_MODULE>};
    bool debug_fpr_write{false};
    std::size_t debug_fpr_write_address{0};
    if constexpr (HasFP)
    {
        debug_fpr_write =
            DifftestIsEnabled() && static_cast<bool>(dut->debug_fpr_wen);
        debug_fpr_write_address =
            static_cast<std::size_t>(dut->debug_fpr_waddr);
    }
    if (tracer)
        tracer->Dump(cycle * 2);
    // pre-edge 事件沿前采样；关闭的计数器 RTL 绑 0。
    npc::PerfSignals perf_signals{};
    perf_signals.ifu_fetch = static_cast<bool>(dut->perf_ifu_fetch);
    perf_signals.exu_done = static_cast<bool>(dut->perf_exu_done);
    perf_signals.lsu_load = static_cast<bool>(dut->perf_lsu_load);
    perf_signals.lsu_store = static_cast<bool>(dut->perf_lsu_store);
    perf_signals.mdu_req = static_cast<bool>(dut->perf_mdu_req);
    perf_signals.mdu_done = static_cast<bool>(dut->perf_mdu_done);
    perf_signals.mdu_op = static_cast<unsigned>(dut->perf_mdu_op);
    perf_signals.exu_event_kind =
        npc::HasDicArchCounter ? static_cast<unsigned>(dut->perf_exu_event_kind)
                               : 0U;
    perf_signals.ifu_stall_redirect =
        static_cast<bool>(dut->perf_ifu_stall_redirect);
    dut->clock = 1;
    dut->eval();
    if (tracer)
        tracer->Dump(cycle * 2 + 1);
    ++cycle;
    if (heartbeat && cycle % 10000000U == 0U)
    {
        context.Out().Warn(
                     "[heartbeat] cycle={} pc=0x{:016x} arch_pc=0x{:016x}",
                     cycle, DebugPC(*dut), DebugArchPC(*dut));
    }
    if constexpr (npc::HasVerilatorSavable)
    {
        checkpoint.OnCycle(cycle);
#ifdef VRISCV32E_NPC
        // UART TX host 副本用于 marker 定位。
        if (static_cast<bool>(dut->uart_tx_valid) &&
            uart.FeedTx(static_cast<std::uint8_t>(
                            static_cast<unsigned>(dut->uart_tx_data)),
                        checkpoint.Marker()))
        {
            checkpoint.OnMarker();
        }
#endif
    }
    checkpoint.OnFork(cycle);
    if (debug_gpr_write)
    {
        if (debug_gpr_write_address > 0 &&
            debug_gpr_write_address < 32)
        {
            // 沿后回读物理 GPR，避免写数据错误被镜像掩盖。
            dut->debug_gpr_raddr =
                static_cast<CData>(debug_gpr_write_address);
            dut->eval();
            mirror.SetGPR(static_cast<std::uint32_t>(debug_gpr_write_address),
                          DebugGPR(*dut));
        }
    }
    mirror.SetGPR(0, 0);
    if constexpr (HasFP)
    {
        if (debug_fpr_write)
        {
            if (debug_fpr_write_address < 32)
            {
                // 同样回读物理 FPR；FLEN=32 时高半区为 0。
                dut->debug_fpr_raddr =
                    static_cast<CData>(debug_fpr_write_address);
                dut->eval();
                mirror.SetFPR(
                    static_cast<std::uint32_t>(debug_fpr_write_address),
                    DebugFPR(*dut));
            }
        }
        mirror.SetFCSR(static_cast<std::uint64_t>(dut->debug_fcsr));
    }
    mirror.SetPC(DebugArchPC(*dut));
#ifndef VRISCV32E_NPC
    if (vga.Sample({.hsync = dut->externalPins_vga_hsync != 0,
                    .vsync = dut->externalPins_vga_vsync != 0,
                    .valid = dut->externalPins_vga_valid != 0,
                    .rgb = (static_cast<std::uint32_t>(dut->externalPins_vga_r) << 16) |
                           (static_cast<std::uint32_t>(dut->externalPins_vga_g) << 8) |
                           static_cast<std::uint32_t>(dut->externalPins_vga_b),
                    .cycle = cycle},
                   context.Out()))
    {
        NPCTrap::Stop(DebugArchPC(*dut));
    }
#endif
if constexpr (npc::HasDicArchCounter)
{
    if (dut->debug_commit)
    {
        ++instructions;
    }
    perf_signals.mdu_active = static_cast<bool>(dut->perf_mdu_active);
    perf_signals.mdu_wait = static_cast<bool>(dut->perf_mdu_wait);
    perf_signals.ifu_stall_pipeline =
        static_cast<bool>(dut->perf_ifu_stall_pipeline);
    perf_signals.ifu_stall_axi = static_cast<bool>(dut->perf_ifu_stall_axi);
    perf_signals.ifu_stall_ar = static_cast<bool>(dut->perf_ifu_stall_ar);
    perf_signals.ifu_stall_r = static_cast<bool>(dut->perf_ifu_stall_r);
    perf_signals.ifu_stall_idle = static_cast<bool>(dut->perf_ifu_stall_idle);
    perf_signals.execution_active =
        static_cast<bool>(dut->perf_execution_active);
    perf_signals.exu_stall_lsu = static_cast<bool>(dut->perf_exu_stall_lsu);
    perf_signals.mem_waitslot = static_cast<bool>(dut->perf_mem_waitslot);
    perf_signals.alu_op = static_cast<bool>(dut->perf_alu_op);
    perf_signals.mem_op = static_cast<bool>(dut->perf_mem_op);
    perf_signals.csr_op = static_cast<bool>(dut->perf_csr_op);
    perf_signals.branch_op = static_cast<bool>(dut->perf_branch_op);
    perf_signals.lsu_active = static_cast<bool>(dut->perf_lsu_active);
    perf_signals.lsu_load_active =
        static_cast<bool>(dut->perf_lsu_load_active);
    perf_signals.lsu_store_active =
        static_cast<bool>(dut->perf_lsu_store_active);
    perf_signals.lsu_stall_read_ar =
        static_cast<bool>(dut->perf_lsu_stall_read_ar);
    perf_signals.lsu_stall_read_r =
        static_cast<bool>(dut->perf_lsu_stall_read_r);
    perf_signals.lsu_stall_write_req =
        static_cast<bool>(dut->perf_lsu_stall_write_req);
    perf_signals.lsu_stall_write_b =
        static_cast<bool>(dut->perf_lsu_stall_write_b);
    perf_signals.icache_hit = static_cast<bool>(dut->perf_icache_hit);
    perf_signals.icache_miss = static_cast<bool>(dut->perf_icache_miss);
    perf_signals.dcache_hit = static_cast<bool>(dut->perf_dcache_hit);
    perf_signals.dcache_miss = static_cast<bool>(dut->perf_dcache_miss);
    perf_signals.dcache_refill_req =
        static_cast<bool>(dut->perf_dcache_refill_req);
    perf_signals.dcache_refill_resp =
        static_cast<bool>(dut->perf_dcache_refill_resp);
    perf_signals.idu_stall_raw = static_cast<bool>(dut->perf_idu_stall_raw);
    perf_signals.idu_stall_raw_loaduse =
        static_cast<bool>(dut->perf_idu_stall_raw_loaduse);
    perf_signals.idu_stall_raw_alu =
        static_cast<bool>(dut->perf_idu_stall_raw_alu);
    perf_signals.exu_idle_noinput =
        static_cast<bool>(dut->perf_exu_idle_noinput);
    perf_signals.trap_valid = static_cast<bool>(dut->debug_trap_valid);
    perf_signals.btb_hit = static_cast<bool>(dut->perf_btb_hit);
    perf_signals.btb_miss = static_cast<bool>(dut->perf_btb_miss);
    perf_signals.jal_btb_hit = static_cast<bool>(dut->perf_jal_btb_hit);
    perf_signals.ras_hit = static_cast<bool>(dut->perf_ras_hit);
    perf_signals.branch_mispredict =
        static_cast<bool>(dut->perf_branch_mispredict);
    perf_signals.itlb_hit = static_cast<bool>(dut->perf_itlb_hit);
    perf_signals.itlb_miss = static_cast<bool>(dut->perf_itlb_miss);
    perf_signals.dtlb_hit = static_cast<bool>(dut->perf_dtlb_hit);
    perf_signals.dtlb_miss = static_cast<bool>(dut->perf_dtlb_miss);
    perf_signals.tlb_refill = static_cast<bool>(dut->perf_tlb_refill);
    perf_signals.fpu_req = static_cast<bool>(dut->perf_fpu_req);
    perf_signals.fpu_done = static_cast<bool>(dut->perf_fpu_done);
    perf_signals.axi_ar = static_cast<bool>(dut->perf_axi_ar);
    perf_signals.axi_aw = static_cast<bool>(dut->perf_axi_aw);
    perf_signals.axi_r = static_cast<bool>(dut->perf_axi_r);
    perf_signals.axi_w = static_cast<bool>(dut->perf_axi_w);
    perf_signals.axi_b = static_cast<bool>(dut->perf_axi_b);
    perf_signals.load_op = static_cast<bool>(dut->perf_load_op);
    perf_signals.store_op = static_cast<bool>(dut->perf_store_op);
    perf_signals.amo_op = static_cast<bool>(dut->perf_amo_op);
    perf_signals.branch_taken = static_cast<bool>(dut->perf_branch_taken);
    perf_signals.branch_not_taken =
        static_cast<bool>(dut->perf_branch_not_taken);
    perf_signals.tlb_flush = static_cast<bool>(dut->perf_tlb_flush);
    perf_signals.fpu_active = static_cast<bool>(dut->perf_fpu_active);
    perf_signals.fence_op = static_cast<bool>(dut->perf_fence_op);
    perf_signals.rvc_inst = static_cast<bool>(dut->perf_rvc_inst);
    perf_signals.system_op = static_cast<bool>(dut->perf_system_op);
    perf_signals.icache_refill = static_cast<bool>(dut->perf_icache_refill);
    perf_signals.dcache_refill = static_cast<bool>(dut->perf_dcache_refill);
    perf_signals.timer_irq = static_cast<bool>(dut->perf_timer_irq);
    perf_signals.external_irq = static_cast<bool>(dut->perf_external_irq);
    perf_signals.interrupt_taken =
        static_cast<bool>(dut->perf_interrupt_taken);
    perf_signals.wbuf_full = static_cast<bool>(dut->perf_wbuf_full);
    perf_signals.wbuf_drain = static_cast<bool>(dut->perf_wbuf_drain);
    perf_signals.csr_read = static_cast<bool>(dut->perf_csr_read);
    perf_signals.csr_write = static_cast<bool>(dut->perf_csr_write);
    perf_signals.mul_active = static_cast<bool>(dut->perf_mul_active);
    perf_signals.div_active = static_cast<bool>(dut->perf_div_active);
    perf_signals.ptw_walk = static_cast<bool>(dut->perf_ptw_walk);
    perf_signals.ptw_walk_cycle =
        static_cast<bool>(dut->perf_ptw_walk_cycle);
    perf_signals.page_fault = static_cast<bool>(dut->perf_page_fault);
    perf_signals.ras_miss = static_cast<bool>(dut->perf_ras_miss);
    perf_signals.redirect_branch =
        static_cast<bool>(dut->perf_redirect_branch);
    perf_signals.redirect_fence_i =
        static_cast<bool>(dut->perf_redirect_fence_i);
    perf_signals.redirect_trap = static_cast<bool>(dut->perf_redirect_trap);
    perf_signals.redirect_mret = static_cast<bool>(dut->perf_redirect_mret);
    perf_signals.fpu_stall = static_cast<bool>(dut->perf_fpu_stall);
    perf_signals.illegal_inst = static_cast<bool>(dut->perf_illegal_inst);
    perf_signals.axi_stall = static_cast<bool>(dut->perf_axi_stall);
    perf_signals.axi_error = static_cast<bool>(dut->perf_axi_error);
    perf_signals.fpu_divsqrt = static_cast<bool>(dut->perf_fpu_divsqrt);
    perf_signals.fpu_fma = static_cast<bool>(dut->perf_fpu_fma);
    perf_signals.fpu_exception = static_cast<bool>(dut->perf_fpu_exception);
    perf_signals.csr_illegal = static_cast<bool>(dut->perf_csr_illegal);
    perf_signals.plic_claim = static_cast<bool>(dut->perf_plic_claim);
    perf_signals.plic_complete = static_cast<bool>(dut->perf_plic_complete);
    perf_signals.flush_cycle = static_cast<bool>(dut->perf_flush_cycle);
    perf_signals.uncached_access =
        static_cast<bool>(dut->perf_uncached_access);
    perf_signals.wbuf_count = static_cast<unsigned>(dut->perf_wbuf_count);
    perf_signals.tlb_superpage_hit =
        static_cast<bool>(dut->perf_tlb_superpage_hit);
    perf_signals.tlb_asid_hit = static_cast<bool>(dut->perf_tlb_asid_hit);
    perf_signals.icache_line_fill =
        static_cast<bool>(dut->perf_icache_line_fill);
    perf_signals.dcache_line_fill =
        static_cast<bool>(dut->perf_dcache_line_fill);
    perf_signals.dcache_writeback =
        static_cast<bool>(dut->perf_dcache_writeback);
    perf_signals.irq_pending = static_cast<bool>(dut->perf_irq_pending);
    perf_signals.wfi_inst = static_cast<bool>(dut->perf_wfi_inst);
    perf_signals.issue_occupancy =
        static_cast<bool>(dut->perf_issue_occupancy);
    perf_signals.retire_occupancy =
        static_cast<bool>(dut->perf_retire_occupancy);
    perf_signals.bubble_cycle = static_cast<bool>(dut->perf_bubble_cycle);
    perf_signals.div_done = static_cast<bool>(dut->perf_div_done);
    perf_signals.div_iterations =
        static_cast<unsigned>(dut->perf_div_iterations);
    perf_signals.div_early_out = static_cast<bool>(dut->perf_div_early_out);
    perf.Sample(perf_signals);
}
    if constexpr (npc::HasITrace && !npc::HasITraceWriteFile)
    {
    if (dut->debug_commit)
    {
        const auto pc{DebugPC(*dut)};
        const auto instruction{static_cast<std::uint32_t>(dut->debug_instructions)};
        const auto assemblyText{DisassembleWord(pc, instruction)};
        std::println("[ITRACE] 0x{:0{}x}: {:08x}  {}",
                     pc,
                     npc::XHexDigits,
                     instruction,
                     assemblyText.empty()
                         ? std::string_view{"<unknown>"}
                         : std::string_view{assemblyText});
    }
    }
    if constexpr (npc::HasITraceWriteFile)
    {
    // itrace 只依赖退休信号，不受性能计数开关控制。
    if (dut->debug_commit)
    {
        static auto fp = std::ofstream("itrace.txt", std::ios::app);
        fp << std::hex << "0x" << DebugPC(*dut) << '\n'
           << std::dec;
    }
    }
    if constexpr (npc::HasIRingBuf)
    {
    if (dut->debug_commit)
    {
        Iringbuf.push(DebugPC(*dut), dut->debug_instructions, 4);
    }
    }
    if constexpr (npc::HasFTrace)
    {
    if (dut->debug_commit)
    {
        // next_pc 与 pc 同属一条退休记录；RV64 保留完整 64 位。
        context.Trace().OnInstruction(
            DebugPC(*dut),
            static_cast<std::uint32_t>(dut->debug_instructions),
            DebugNextPC(*dut));
    }
    }
    if constexpr (npc::HasSDB)
    {
    // 仅成功退休的 store 进入 live-memory shadow。
    if (dut->debug_mtrace_valid && dut->debug_mtrace_wen)
    {
        const auto address{DebugMtraceAddr(*dut)};
        const auto data{DebugMtraceWdata(*dut)};
        const auto width{static_cast<unsigned>(dut->debug_mtrace_width)};
        // width=3 表示 8 字节 LD/SD，RV64 下必须支持。
        const auto bytes{width <= 3 ? (1U << width) : 0U};
#ifdef VRISCV32E_NPC
        const bool trackable{
            address >= static_cast<std::uint64_t>(npc::MBase) &&
            address - static_cast<std::uint64_t>(npc::MBase) <
                static_cast<std::uint64_t>(npc::MSize)};
#else
        const bool trackable{SoCMemoryMap::IsShadowRam(address, bytes)};
#endif
        if (trackable && bytes != 0 &&
            address <= std::numeric_limits<std::uint64_t>::max() - (bytes - 1U))
        {
            for (unsigned index{0}; index < bytes; ++index)
            {
                debug_memory_writes[address + index] =
                    static_cast<std::uint8_t>(data >> (index * 8U));
            }
        }
    }
    }
    if constexpr (npc::HasMTrace)
    {
    if (dut->debug_mtrace_valid)
    {
        MtraceRecord(
            DebugMtracePC(*dut),
            DebugMtraceAddr(*dut),
            DebugMtraceWdata(*dut),
            DebugMtraceRdata(*dut),
            dut->debug_mtrace_width,
            dut->debug_mtrace_wen);
    }
    }
    if constexpr (npc::HasDiffTest)
    {
    // 同拍先比对退休，再同步 trap。
    if (dut->debug_commit)
    {
        DifftestStep(*this);
    }
    if (dut->debug_trap_valid)
    {
        DifftestTrapStep(*this);
    }
    }
    if (dut->debug_access_fault)
    {
        // 连续 fault 只打前几条，防止日志撑爆磁盘。
        if (fault_count++ < 20)
        {
        auto resp{static_cast<unsigned>(dut->debug_access_fault_resp)};
        auto pc{DebugFaultPC(*dut)};
        if (resp == 2)
        {
            context.Out().Error("Access Fault [SLVERR] at PC=0x{:0{}x}, cycle={}",
                                pc, npc::XHexDigits, cycle);
            context.Out().Error("  从设备报错了，可能是访问了不该访问的偏移或者往只读的地方写东西了");
        }
        else if (resp == 3)
        {
            context.Out().Error("Access Fault [DECERR] at PC=0x{:0{}x}, cycle={}",
                                pc, npc::XHexDigits, cycle);
            context.Out().Error("  地址译码错误，鬼知道你访问了什么地址，AXI总线根本找不到对应的从设备");
        }
        else
        {
            context.Out().Error("Access Fault [RESP={}] at PC=0x{:0{}x}, cycle={}",
                                resp, pc, npc::XHexDigits, cycle);
            context.Out().Error("  这什么AXI响应码，我也不认识");
        }
        }
    }
    if constexpr (npc::HasNvBoard)
    {
        // 所有运行模式共用 step，GUI 服务在此统一刷新。
        if (cycle % npc::NvBoardUpdateInterval == 0)
        {
            nvboard_update();
        }
    }
}
bool DUT::StepWithHaltPriority()
{
    // $finish 与 halt 同拍时先结算 halt。
    if (Verilated::gotFinish())
    {
        static_cast<void>(PollSimulationHalt());
        return true;
    }
    step();
    // DiffTest 可能在本沿标记 BAD，勿让 custom halt 覆盖。
    if (NPCTrap::HasHalted())
        return true;
    return PollSimulationHalt();
}
bool DUT::PollSimulationHalt()
{
    // 失败优先；trap_valid 是单拍脉冲，须在推进前结算。
    if (!dut->trap_valid || NPCTrap::HasHalted())
        return false;
    context.Out().Info("收到仿真 halt 请求");
    const auto halt_code{ReadGPR(10)}; // x10 = a0
    // custom halt 返回码是 32 位 ABI；地址部分保持完整 XWord。
    NPCTrap::Halt(ReadHaltPC(),
                  halt_code ? static_cast<std::uint32_t>(*halt_code) : 1u);
    return true;
}
std::size_t DUT::GetCycle() const
{
    return cycle;
}
std::size_t DUT::GetInstructions() const
{
    return instructions;
}
const DicArchCounterStats &DUT::GetDicArchCounterStats() const noexcept
{
    return perf.Stats();
}
std::expected<npc::XWord, std::string> DUT::ReadGPR(std::uint32_t index)
{
    if (index >= 32)
    {
        return std::unexpected{std::format("GPR编号都超31号了: {0}", index)};
    }
    dut->debug_gpr_raddr = static_cast<CData>(index);
    dut->eval();
    return DebugGPR(*dut);
}
std::expected<npc::XWord, std::string> DUT::ReadPC()
{
    dut->eval();
    return DebugArchPC(*dut);
}
std::expected<npc::XWord, std::string>
DUT::ReadCachedGPR(std::uint32_t index) const
{
    return mirror.ReadCachedGPR(index);
}
npc::XWord DUT::ReadCachedPC() const noexcept
{
    return mirror.ReadCachedPC();
}
bool DUT::HasFPDebugState() const
{
    if constexpr (HasFPDebugPorts<TOP_MODULE>)
    {
        return static_cast<bool>(dut->debug_fp_valid);
    }
    else
    {
        // 旧 RTL 无浮点调试口，告警后降级为整数/CSR 比对。
        static bool warned{false};
        if (!warned)
        {
            context.Out().Warn(
                "DUT: 警告：当前 RTL 未导出 debug_fpr/fcsr/fp_valid 端口，"
                "f0-f31/fcsr 不参与 DiffTest；请重新生成 ysyxSoCFull");
            warned = true;
        }
        return false;
    }
}
std::expected<std::uint64_t, std::string>
DUT::ReadCachedFPR(std::uint32_t index) const
{
    if constexpr (HasFPDebugPorts<TOP_MODULE>)
    {
        return mirror.ReadCachedFPR(index);
    }
    else
    {
        static_cast<void>(index);
        return std::unexpected{
            "当前 RTL 顶层未导出浮点调试端口（旧 RTL）"};
    }
}
std::uint64_t DUT::ReadCachedFCSR() const noexcept
{
    if constexpr (HasFPDebugPorts<TOP_MODULE>)
    {
        return mirror.ReadCachedFCSR();
    }
    else
    {
        return 0;
    }
}
npc::XWord DUT::ReadRetirePC() const
{
    return DebugPC(*dut);
}
npc::XWord DUT::ReadNextPC() const
{
    return DebugNextPC(*dut);
}
npc::XWord DUT::ReadMtracePC() const
{
    return DebugMtracePC(*dut);
}
npc::XWord DUT::ReadMtraceAddress() const
{
    return DebugMtraceAddr(*dut);
}
npc::XWord DUT::ReadHaltPC() const
{
    return DebugTrapPCHalt(*dut);
}
npc::XWord DUT::ReadTrapPC() const
{
    return DebugTrapPC(*dut);
}
npc::XWord DUT::ReadTrapTarget() const
{
    return DebugTrapTarget(*dut);
}
DUT::RetireCSRState DUT::ReadRetireCSR() const
{
    return {
        .mstatus = DebugWord(*dut, dut->debug_csr_mstatus, dut->debug_csr_mstatus_hi),
        .mtvec = DebugWord(*dut, dut->debug_csr_mtvec, dut->debug_csr_mtvec_hi),
        .mepc = DebugWord(*dut, dut->debug_csr_mepc, dut->debug_csr_mepc_hi),
        .mcause = DebugWord(*dut, dut->debug_csr_mcause, dut->debug_csr_mcause_hi),
    };
}
DUT::TrapCSRState DUT::ReadTrapCSR() const
{
    return {
        .mstatus = DebugWord(*dut, dut->debug_trap_mstatus, dut->debug_trap_mstatus_hi),
        .mtvec = DebugWord(*dut, dut->debug_trap_mtvec, dut->debug_trap_mtvec_hi),
        .mepc = DebugWord(*dut, dut->debug_trap_mepc, dut->debug_trap_mepc_hi),
        .mcause = DebugWord(*dut, dut->debug_trap_mcause, dut->debug_trap_mcause_hi),
    };
}
bool DUT::HasTrapTvalDebugState() const noexcept
{
    return HasTrapTvalDebugPorts<TOP_MODULE>;
}
npc::XWord DUT::ReadTrapTval() const
{
    if constexpr (HasTrapTvalDebugPorts<TOP_MODULE>)
    {
        return DebugWord(
            *dut, dut->debug_trap_tval, dut->debug_trap_tval_hi);
    }
    else
    {
        return 0;
    }
}
std::uint32_t DUT::ReadTrapCause() const
{
    return static_cast<std::uint32_t>(dut->debug_trap_cause);
}
void DUT::RefreshCachedArchitecturalState()
{
    mirror.RefreshGPR([this](std::uint32_t index) {
        dut->debug_gpr_raddr = static_cast<CData>(index);
        dut->eval();
        return DebugGPR(*dut);
    });
    dut->debug_gpr_raddr = 0;
    if constexpr (HasFPDebugPorts<TOP_MODULE>)
    {
        if (static_cast<bool>(dut->debug_fp_valid))
        {
            mirror.RefreshFPR([this](std::uint32_t index) {
                dut->debug_fpr_raddr = static_cast<CData>(index);
                dut->eval();
                return DebugFPR(*dut);
            });
            dut->debug_fpr_raddr = 0;
        }
    }
    dut->eval();
    if constexpr (HasFPDebugPorts<TOP_MODULE>)
    {
        mirror.SetFCSR(static_cast<std::uint64_t>(dut->debug_fcsr));
    }
    mirror.SetPC(DebugArchPC(*dut));
}
std::expected<std::uint64_t, std::string> DUT::ReadMemory(std::uint64_t addr, std::size_t size)
{
    if (size != 1 && size != 2 && size != 4 && size != 8)
    {
        return std::unexpected{std::format("不支持的内存读取长度：{}", size)};
    }
    if (addr > std::numeric_limits<std::uint64_t>::max() - (size - 1U))
    {
        return std::unexpected{std::format("内存读取地址溢出：0x{:x}", addr)};
    }

#ifdef VRISCV32E_NPC
    constexpr std::uint64_t IMAGE_BASE{npc::MBase};
    constexpr std::uint64_t IMAGE_WINDOW_SIZE{npc::MSize};
#else
    constexpr std::uint64_t MromBase{npc::SocMBase};
    constexpr std::uint64_t MromWindowSize{npc::SocMSize};
    constexpr std::uint64_t FlashBase{npc::SocFlashBase};
    constexpr std::uint64_t FlashWindowSize{npc::SocFlashSize};
#endif
    std::uint64_t value{0};
    for (std::size_t index{0}; index < size; ++index)
    {
        const auto current{addr + static_cast<std::uint64_t>(index)};
        std::optional<std::uint8_t> byte;
        if constexpr (npc::HasSDB)
        {
            if (const auto written{debug_memory_writes.find(current)};
                written != debug_memory_writes.end())
            {
                byte = written->second;
            }
        }
#ifdef VRISCV32E_NPC
        if (!byte && current >= IMAGE_BASE)
        {
            const auto offset{static_cast<std::size_t>(current - IMAGE_BASE)};
            // 调试视图覆盖完整 MSize 窗口，但不猜测 MMIO/外部 RAM。
            if (offset < static_cast<std::size_t>(IMAGE_WINDOW_SIZE))
            {
                const auto &flash{context.Flash()};
                byte = offset < flash.size() ? flash[offset] : std::uint8_t{0};
            }
        }
#else
        // SoC 调试视图分别覆盖 MROM 与 Flash 窗口；不猜测 PSRAM/SDRAM。
        if (!byte && current >= MromBase)
        {
            const auto offset{static_cast<std::size_t>(current - MromBase)};
            if (offset < static_cast<std::size_t>(MromWindowSize))
            {
                const auto &mrom{context.Mrom()};
                byte = offset < mrom.size() ? mrom[offset] : std::uint8_t{0};
            }
        }
        if (!byte && current >= FlashBase)
        {
            const auto offset{static_cast<std::size_t>(current - FlashBase)};
            if (offset < static_cast<std::size_t>(FlashWindowSize))
            {
                const auto &flash{context.Flash()};
                byte = offset < flash.size() ? flash[offset] : std::uint8_t{0};
            }
        }
#endif
        if (!byte)
        {
            return std::unexpected{std::format("地址 0x{:x} 没有可验证的实时 RAM 数据；" "调试器只能读取初始镜像或已退休 store 的 shadow", current)};
        }
        value |= static_cast<std::uint64_t>(*byte) << (index * 8U);
    }
    return value;
}

void DUT::EnableVGACheck()
{
#ifdef VRISCV32E_NPC
    throw std::logic_error{"direct-NPC 顶层没有 VGA 引脚"};
#else
    vga.Enable(dut->externalPins_vga_hsync != 0,
               dut->externalPins_vga_vsync != 0);
#endif
}
bool DUT::VGACheckReport()
{
    return vga.Report(context.Out());
}
