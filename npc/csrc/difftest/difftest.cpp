// doxygen是deepseek ai写的
module;
#include <algorithm>
#include <cstdio>
#include <expected>
#include <filesystem>
#include <optional>
#include <print>
#include <string>
#include <utility>
#include <vector>
#ifdef CONFIG_DIFFTEST
#include <dlfcn.h>
#include "DifftestReport.h"
#endif
#include "../Config.h"
module npc.difftest.difftest;
import npc.difftest.DifftestCPUState;
import npc.NPCTrap;
import npc.trace.disasm;
import npc.ysyxSoC;
// 兜底值必须与 Kconfig 的 CONFIG_MBASE / CONFIG_RESET_PC 默认值一致，
// 否则脱离 Makefile 直接编译时 DiffTest 与 SoC 地址映射会不一致
#ifndef CONFIG_MBASE
#define CONFIG_MBASE 0x30000000
#endif
#ifndef CONFIG_RESET_PC
#define CONFIG_RESET_PC 0x30000000
#endif
#ifndef CONFIG_MSIZE
#define CONFIG_MSIZE 0x10000000
#endif
namespace
{
#ifdef CONFIG_DIFFTEST
    // REF ABI 的地址参数按构建 XLEN 取宽；RV32 仍是 32 位，RV64 不允许
    // 在进入 REF 前把地址截成 32 位。
    using REFDifftestMemcpy = void (*)(npc::XWord addr, void *buf, std::size_t n, bool direction);
    using REFDifftestRegcpy = void (*)(void *dut, bool direction);
    using REFDifftestExec = void (*)(std::uint64_t n);
    using REFDifftestRaiseIntr = void (*)(std::uint64_t no);
    // 带 tval 的 trap 注入扩展。三个参数固定 64 位，与寄存器/CSR 快照
    // ABI 解耦；RV32 REF 在内部截断到 32 位。
    using REFDifftestTrap = void (*)(std::uint64_t no,
                                     std::uint64_t epc,
                                     std::uint64_t tval);
    using REFDifftestInit = void (*)(int port);
    using REFDifftestStateSize = std::size_t (*)();
    using REFDifftestCSRStateSize = std::size_t (*)();
    using REFDifftestCSRcpy = void (*)(void *dut, bool direction);
    using REFDifftestSetPlatform = void (*)(int ysyxsoc);
    // 可选诊断 ABI：旧 REF 没有这两个符号时只影响失败报告，不影响比对。
    using REFDifftestLastInstruction = void (*)(std::uint64_t *pc,
                                                std::uint64_t *inst);
    using REFDifftestGetCSR = bool (*)(std::uint32_t csr,
                                       std::uint64_t *value);
    void *REFHandle{nullptr};
    REFDifftestMemcpy REFMemcpy{nullptr};
    REFDifftestRegcpy REFRegcpy{nullptr};
    REFDifftestExec REFExec{nullptr};
    REFDifftestRaiseIntr REFRaiseIntr{nullptr};
    REFDifftestTrap REFTrap{nullptr};
    REFDifftestStateSize REFStateSize{nullptr};
    REFDifftestCSRStateSize REFCSRStateSize{nullptr};
    REFDifftestCSRcpy REFCSRcpy{nullptr};
    REFDifftestSetPlatform REFSetPlatform{nullptr};
    REFDifftestLastInstruction REFLastInstruction{nullptr};
    REFDifftestGetCSR REFGetCSR{nullptr};
    bool Enabled{false};
    bool Failed{false};
    std::size_t SkipCount{0};

    struct DifftestCSRState
    {
        npc::XWord mstatus{};
        npc::XWord mtvec{};
        npc::XWord mepc{};
        npc::XWord mcause{};
    };
    // RV32 下与旧 REF ABI 的 4 x uint32_t 完全一致；RV64 下为
    // 4 x uint64_t，必须由 REF 的 difftest_csr_state_size() 动态确认。
    static_assert(sizeof(DifftestCSRState) == 4 * sizeof(npc::XWord));
    DifftestCSRState LastDUTCSR{};
    /// @brief 从REF .so中按名称加载符号并转为指定函数指针类型
    /// @tparam Fn 目标函数指针类型
    /// @param Name 符号名称
    /// @return 成功返回函数指针，失败返回错误信息
    template <typename Fn>
    std::expected<Fn, std::string> LoadSymbol(const char *Name)
    {
        dlerror();                            // 清除dlerror之前的错误状态
        auto *Symbol{dlsym(REFHandle, Name)}; // 从REF里面获取符号的地址
        if (const char *Error{dlerror()}; Error != nullptr)
        {
            return std::unexpected{std::format("加载 DiffTest 符号 {0} 失败: {1}", Name, Error)};
        }
        return reinterpret_cast<Fn>(Symbol);
    }

    /// @brief 尝试加载可选符号；缺失时返回 nullptr，不产生启动错误
    template <typename Fn>
    Fn TryLoadSymbol(const char *Name)
    {
        dlerror();
        auto *Symbol{dlsym(REFHandle, Name)};
        if (dlerror() != nullptr)
        {
            return nullptr;
        }
        return reinterpret_cast<Fn>(Symbol);
    }

    template <typename Top>
    npc::XWord DebugWord(const Top &, std::uint32_t Low, std::uint32_t High)
    {
#if CONFIG_XLEN == 64
        return static_cast<npc::XWord>(Low) |
               (static_cast<npc::XWord>(High) << 32U);
#else
        static_cast<void>(High);
        return static_cast<npc::XWord>(Low);
#endif
    }

    // RV64 的调试字拆成低/高两个 32 位端口。direct-NPC 与 full-SoC
    // 现在都导出同一组 `_hi` 端口，DiffTest 使用完全相同的拼接逻辑；
    // RV32 只读取历史低端口。
#if CONFIG_XLEN == 64
    npc::XWord ReadRetirePC(DUT &dut) { return DebugWord(*dut, dut->debug_pc, dut->debug_pc_hi); }
    npc::XWord ReadNextPC(DUT &dut) { return DebugWord(*dut, dut->debug_next_pc, dut->debug_next_pc_hi); }
    npc::XWord ReadMtracePC(DUT &dut) { return DebugWord(*dut, dut->debug_mtrace_pc, dut->debug_mtrace_pc_hi); }
    npc::XWord ReadMtraceAddr(DUT &dut) { return DebugWord(*dut, dut->debug_mtrace_addr, dut->debug_mtrace_addr_hi); }
    npc::XWord ReadTrapPC(DUT &dut) { return DebugWord(*dut, dut->debug_trap_pc, dut->debug_trap_pc_hi); }
    npc::XWord ReadTrapTarget(DUT &dut) { return DebugWord(*dut, dut->debug_trap_target, dut->debug_trap_target_hi); }
    npc::XWord ReadTrapTval(DUT &dut) { return DebugWord(*dut, dut->debug_trap_tval, dut->debug_trap_tval_hi); }
#else
    npc::XWord ReadRetirePC(DUT &dut) { return static_cast<npc::XWord>(dut->debug_pc); }
    npc::XWord ReadNextPC(DUT &dut) { return static_cast<npc::XWord>(dut->debug_next_pc); }
    npc::XWord ReadMtracePC(DUT &dut) { return static_cast<npc::XWord>(dut->debug_mtrace_pc); }
    npc::XWord ReadMtraceAddr(DUT &dut) { return static_cast<npc::XWord>(dut->debug_mtrace_addr); }
    npc::XWord ReadTrapPC(DUT &dut) { return static_cast<npc::XWord>(dut->debug_trap_pc); }
    npc::XWord ReadTrapTarget(DUT &dut) { return static_cast<npc::XWord>(dut->debug_trap_target); }
#ifdef VRISCV32E_NPC
    npc::XWord ReadTrapTval(DUT &dut) { return static_cast<npc::XWord>(dut->debug_trap_tval); }
#else
    // full-SoC 调试端口尚未导出 trap tval（由独立任务补齐）。这里明确
    // 返回不可用标记，初始化时告警并降级为旧接口，不能伪装成 tval=0。
    npc::XWord ReadTrapTval(DUT &) { return 0; }
#endif
#endif

    DifftestCPUState ReadReferenceState()
    {
        DifftestCPUState state;
        REFRegcpy(&state, DifftestCPUState::GetDirectionToDUT());
        return state;
    }

    DifftestCPUState ReadDUTStateAtPC(DUT &dut, npc::XWord pc)
    {
        auto state{DifftestCPUState::ReadDUTState(dut)};
        state.SetPC(pc);
        return state;
    }

#if CONFIG_XLEN == 64
    DifftestCSRState ReadDUTRetireCSR(DUT &dut)
    {
        return {
            .mstatus = DebugWord(*dut, dut->debug_csr_mstatus, dut->debug_csr_mstatus_hi),
            .mtvec = DebugWord(*dut, dut->debug_csr_mtvec, dut->debug_csr_mtvec_hi),
            .mepc = DebugWord(*dut, dut->debug_csr_mepc, dut->debug_csr_mepc_hi),
            .mcause = DebugWord(*dut, dut->debug_csr_mcause, dut->debug_csr_mcause_hi),
        };
    }

    DifftestCSRState ReadDUTTrapCSR(DUT &dut)
    {
        return {
            .mstatus = DebugWord(*dut, dut->debug_trap_mstatus, dut->debug_trap_mstatus_hi),
            .mtvec = DebugWord(*dut, dut->debug_trap_mtvec, dut->debug_trap_mtvec_hi),
            .mepc = DebugWord(*dut, dut->debug_trap_mepc, dut->debug_trap_mepc_hi),
            .mcause = DebugWord(*dut, dut->debug_trap_mcause, dut->debug_trap_mcause_hi),
        };
    }
#else
    // RV32 只有 32 位 CSR 调试端口；两种顶层都无需高半区。
    DifftestCSRState ReadDUTRetireCSR(DUT &dut)
    {
        return {
            .mstatus = static_cast<npc::XWord>(dut->debug_csr_mstatus),
            .mtvec = static_cast<npc::XWord>(dut->debug_csr_mtvec),
            .mepc = static_cast<npc::XWord>(dut->debug_csr_mepc),
            .mcause = static_cast<npc::XWord>(dut->debug_csr_mcause),
        };
    }

    DifftestCSRState ReadDUTTrapCSR(DUT &dut)
    {
        return {
            .mstatus = static_cast<npc::XWord>(dut->debug_trap_mstatus),
            .mtvec = static_cast<npc::XWord>(dut->debug_trap_mtvec),
            .mepc = static_cast<npc::XWord>(dut->debug_trap_mepc),
            .mcause = static_cast<npc::XWord>(dut->debug_trap_mcause),
        };
    }
#endif

    DifftestCSRState ReadReferenceCSR()
    {
        DifftestCSRState state{};
        REFCSRcpy(&state, DifftestCPUState::GetDirectionToDUT());
        return state;
    }

    bool CheckCSR(const DifftestCSRState &reference,
                  const DifftestCSRState &dut)
    {
        // 与 CheckRegs 一样只判定。差异由完整报告统一打印。
        return reference.mstatus == dut.mstatus &&
               reference.mtvec == dut.mtvec &&
               reference.mepc == dut.mepc &&
               reference.mcause == dut.mcause;
    }

    constexpr bool RangeContains(npc::XWord address,
                                 npc::XWord bytes,
                                 npc::XWord base,
                                 npc::XWord size)
    {
        return bytes != 0 && address >= base && address - base < size &&
               bytes <= size - (address - base);
    }

    bool ReferenceModelsDataAccess(DUT &dut)
    {
        if (!dut->debug_mtrace_valid ||
            ReadMtracePC(dut) != ReadRetirePC(dut))
            return true;
        const auto width{static_cast<unsigned>(dut->debug_mtrace_width)};
        // RV32D 的 FLD/FSD 与 RV64 的 LD/SD 都是 8 字节（width=3）。
        // NEMU REF 会按两个 32 位对齐字实现 RV32D 访存，因此这里不能
        // 再把 RV32 的 width=3 误判成“REF 不建模”的 MMIO 注入。
        if (width > 3U)
            return false;
        const auto bytes{static_cast<npc::XWord>(1U << width)};
        const auto address{ReadMtraceAddr(dut)};
#ifdef VRISCV32E_NPC
        // direct-NPC 的 RAM 窗口就是 CONFIG_MBASE/CONFIG_MSIZE。不能写死
        // 旧版的 256 KiB：xv6 的内核数据、页表和用户页都在这之后，
        // 否则这些 load/store 会被当成 MMIO 注入，REF 内存永远不更新。
        return RangeContains(address, bytes,
                             static_cast<npc::XWord>(CONFIG_MBASE),
                             static_cast<npc::XWord>(CONFIG_MSIZE));
#else
        return RangeContains(address, bytes, 0x0f000000U, 0x00008000U) ||
               RangeContains(address, bytes, 0x30000000U, 0x10000000U) ||
               RangeContains(address, bytes, 0x80000000U, 0x00400000U) ||
               RangeContains(address, bytes, 0xa0000000U, 0x02000000U);
#endif
    }

    bool IsNondeterministicCSR(DUT &dut)
    {
        const auto instruction{static_cast<std::uint32_t>(dut->debug_instructions)};
        if ((instruction & 0x7fU) != 0x73U || ((instruction >> 12) & 7U) == 0U)
            return false;
        const auto address{instruction >> 20};
        return address == 0xb00U || address == 0xb80U;
    }

#ifdef VRISCV32E_NPC
    // direct-NPC 导出了 debug_trap_tval/_hi；full-SoC 目前没有该端口，
    // 报告必须明确标成“不可用”，不能把 0 当成真实 tval。
    constexpr bool DUTTrapTvalAvailable{true};
#else
    constexpr bool DUTTrapTvalAvailable{false};
#endif

    std::string DisassembleWord(std::uint64_t pc, std::uint32_t instruction)
    {
        init_disasm();
        std::array<std::uint8_t, 4> bytes{
            static_cast<std::uint8_t>(instruction),
            static_cast<std::uint8_t>(instruction >> 8U),
            static_cast<std::uint8_t>(instruction >> 16U),
            static_cast<std::uint8_t>(instruction >> 24U),
        };
        std::array<char, 128> assembly{};
        disassemble(assembly.data(),
                    static_cast<int>(assembly.size()),
                    pc,
                    bytes.data(),
                    static_cast<int>(bytes.size()));
        if (assembly.front() == '\0')
            return "<反汇编失败>";
        return std::string{assembly.data()};
    }

    npc::difftest::ReportGPRState MakeReportGPRState(
        const DifftestCPUState &state)
    {
        npc::difftest::ReportGPRState result{};
        for (std::size_t index{0}; index < result.GPR.size(); ++index)
            result.GPR[index] = state.GetGPR(index);
        result.PC = state.GetPC();
        return result;
    }

    npc::difftest::ReportCSRState MakeReportCSRState(
        const DifftestCSRState &state)
    {
        return {
            .Mstatus = state.mstatus,
            .Mtvec = state.mtvec,
            .Mepc = state.mepc,
            .Mcause = state.mcause,
        };
    }

    void FillDUTInstruction(npc::difftest::DifftestReport &report,
                            DUT &dut,
                            bool trapEvent)
    {
        report.HasDUTInstruction = true;
        report.DUTInstructionPC = ReadRetirePC(dut);
        report.DUTInstruction =
            static_cast<std::uint32_t>(dut->debug_instructions);
        report.DUTAssembly =
            DisassembleWord(report.DUTInstructionPC, report.DUTInstruction);
        report.DUTInstructionNote =
            trapEvent ? "最近退休指令，不一定是触发 trap 的指令"
                      : "本步退休指令";
    }

    void FillRefInstruction(npc::difftest::DifftestReport &report,
                            bool current,
                            std::string_view note)
    {
        if (REFLastInstruction == nullptr)
        {
            report.RefInstructionNote =
                "REF 缺少 difftest_get_last_instruction";
            return;
        }

        std::uint64_t pc{0};
        std::uint64_t instruction{0};
        REFLastInstruction(&pc, &instruction);
        report.HasRefInstruction = true;
        report.RefInstructionIsCurrent = current;
        report.RefInstructionPC = pc;
        report.RefInstruction = static_cast<std::uint32_t>(instruction);
        report.RefAssembly = DisassembleWord(pc, report.RefInstruction);
        if (!note.empty())
            report.RefInstructionNote = std::string{note};
    }

    void FillTrapReport(npc::difftest::DifftestReport &report, DUT &dut)
    {
        report.HasTrap = true;
        report.TrapCause = dut.ReadTrapCause();
        report.TrapEPC = ReadTrapPC(dut);
        report.TrapTarget = ReadTrapTarget(dut);
        report.TrapTvalAvailable = DUTTrapTvalAvailable;
        if constexpr (DUTTrapTvalAvailable)
            report.TrapTval = ReadTrapTval(dut);

        // trap 指令通常不会进入 WBU 退休路径，debug_instructions 可能是
        // 更老的指令。这里再读一次 trap PC 处的取指内存作为补充。
        if (const auto instruction{dut.ReadMemory(report.TrapEPC, 4)};
            instruction)
        {
            report.HasTrapInstruction = true;
            report.TrapInstructionPC = report.TrapEPC;
            report.TrapInstruction =
                static_cast<std::uint32_t>(*instruction);
            report.TrapAssembly =
                DisassembleWord(report.TrapInstructionPC,
                                report.TrapInstruction);
        }

        // REF 的 mtval/stval 是可选 ABI；旧 REF 缺符号时保持不可用。
        if (REFGetCSR != nullptr)
        {
            std::uint64_t value{0};
            if (REFGetCSR(0x343U, &value))
            {
                report.HasRefMtval = true;
                report.RefMtval = value;
            }
            value = 0;
            if (REFGetCSR(0x143U, &value))
            {
                report.HasRefStval = true;
                report.RefStval = value;
            }
        }
    }

    // 把 CSR 快照里与 trap tval 对应的那一项补进报告：委托到 S 时
    // REF 写 stval，M 模式 trap 写 mtval。优先选择与 DUT tval 一致的
    // 那个，避免把合法的 S 模式 trap 误报成 mtval 差异。
    void FillTrapTvalCSR(npc::difftest::DifftestReport &report,
                         npc::difftest::ReportCSRState &refCSR,
                         npc::difftest::ReportCSRState &dutCSR)
    {
        if (!report.HasTrap || !report.TrapTvalAvailable)
            return;
        dutCSR.HasMtval = true;
        dutCSR.Mtval = report.TrapTval;

        if (report.HasRefStval && report.RefStval == report.TrapTval)
        {
            refCSR.HasMtval = true;
            refCSR.Mtval = report.RefStval;
            refCSR.MtvalLabel = "stval";
        }
        else if (report.HasRefMtval)
        {
            refCSR.HasMtval = true;
            refCSR.Mtval = report.RefMtval;
            refCSR.MtvalLabel = "mtval";
        }
        else if (report.HasRefStval)
        {
            refCSR.HasMtval = true;
            refCSR.Mtval = report.RefStval;
            refCSR.MtvalLabel = "stval";
        }
    }

    void AppendMemoryDump(npc::difftest::DifftestReport &report,
                          DUT &dut,
                          std::string_view label,
                          npc::XWord address)
    {
        const auto wordBytes{npc::XLen == 64 ? std::size_t{8}
                                             : std::size_t{4}};
        const auto base{static_cast<std::uint64_t>(address) &
                        ~static_cast<std::uint64_t>(wordBytes - 1U)};
        npc::difftest::ReportMemoryDump dump{};
        dump.Label = std::string{label};
        dump.Address = base;
        dump.WordBytes = wordBytes;
        for (std::size_t index{0}; index < dump.Words.size(); ++index)
        {
            const auto value{dut.ReadMemory(
                base + index * wordBytes, wordBytes)};
            if (!value)
            {
                if (dump.Count == 0)
                    return;
                break;
            }
            dump.Words[dump.Count++] = *value;
        }
        report.Memory.push_back(std::move(dump));
    }

    npc::difftest::DifftestReport MakeBaseReport(DUT &dut,
                                                 std::string_view event,
                                                 bool trapEvent)
    {
        npc::difftest::DifftestReport report{};
        report.Event = std::string{event};
        report.XLen = npc::XLen;
        report.Cycle = dut.GetCycle();
        report.DUTRetirePC = ReadRetirePC(dut);
        report.HasDUTNextPC = true;
        FillDUTInstruction(report, dut, trapEvent);
        if (trapEvent)
        {
            FillTrapReport(report, dut);
            // trap 事件的“下一 PC”是 trap 目标，不是最近一条退休指令
            // 的 next PC；否则报告里的 next PC 会误导定位。
            report.DUTNextPC = report.TrapTarget;
        }
        else
        {
            report.DUTNextPC = ReadNextPC(dut);
        }
        return report;
    }

    void ReportMismatch(DUT &dut,
                        std::string_view event,
                        npc::XWord dutPC,
                        npc::difftest::DifftestReport report)
    {
        Failed = true;
        report.Event = std::string{event};

        // 只在第一次失败时抓内存，最多 PC/SP/TP 三小段；读不到就跳过。
        // trap 现场已经单独反汇编了 trap PC，这里不再重复 dump 同一段。
        if (!report.HasTrap && report.HasDUTInstruction)
            AppendMemoryDump(report, dut, "PC", report.DUTInstructionPC);
        if (report.DUTState)
        {
            const auto sp{report.DUTState->GPR[2]};
            const auto tp{report.DUTState->GPR[4]};
            const auto pc{report.HasTrap ? report.TrapEPC
                                         : report.DUTInstructionPC};
            if (sp != pc)
                AppendMemoryDump(report, dut, "SP", sp);
            if (tp != pc && tp != sp)
                AppendMemoryDump(report, dut, "TP", tp);
        }

        const auto text{npc::difftest::FormatDifftestReport(report)};
        std::fwrite(text.data(), 1, text.size(), stderr);
        if (!text.empty() && text.back() != '\n')
            std::fputc('\n', stderr);
        std::fflush(stderr);
        NPCTrap::Halt(dutPC, 1);
    }
#endif
}
/// @brief 初始化DiffTest：加载REF .so、同步内存和寄存器、启用比对
/// @param REFSoFile REF动态库路径，为空则不启用
/// @param ImageSize 程序镜像大小（字节）
/// @return 成功返回空，失败返回错误信息
std::expected<void, std::string> DifftestInitialize(DUT &dut,
                                                    const std::optional<std::filesystem::path> &REFSoFile,
                                                    std::size_t ImageSize)
{
#ifdef CONFIG_DIFFTEST
    if (!REFSoFile) // 如果没有提供REF .so的文件路径
    {
        return {}; // 不用DiffTest，直接返回空expected，成功，但是不干事
    }
    REFHandle = dlopen(REFSoFile->c_str(), RTLD_LAZY | RTLD_LOCAL); // 打开REF的动态库
    if (REFHandle == nullptr)
    {
        return std::unexpected{std::format("打开 DiffTest REF 失败: {0}", dlerror())};
    }
    auto MemcpySymbol{LoadSymbol<REFDifftestMemcpy>("difftest_memcpy")};
    if (!MemcpySymbol)
    {
        return std::unexpected{MemcpySymbol.error()};
    }
    REFMemcpy = *MemcpySymbol;
    auto RegcpySymbol{LoadSymbol<REFDifftestRegcpy>("difftest_regcpy")};
    if (!RegcpySymbol)
    {
        return std::unexpected{RegcpySymbol.error()};
    }
    REFRegcpy = *RegcpySymbol;
    auto StateSizeSymbol{LoadSymbol<REFDifftestStateSize>("difftest_state_size")};
    if (!StateSizeSymbol)
    {
        return std::unexpected{std::format(
            "DiffTest REF 缺少状态 ABI 协商符号 difftest_state_size：{}",
            StateSizeSymbol.error())};
    }
    REFStateSize = *StateSizeSymbol;
    if (const auto stateSize{REFStateSize()}; stateSize != sizeof(DifftestCPUState))
    {
        return std::unexpected{std::format(
            "DiffTest 状态 ABI 不匹配：REF={} 字节，DUT={} 字节"
            "（CONFIG_XLEN={}；不能用 RV32 REF 驱动 RV64 DUT）",
            stateSize,
            sizeof(DifftestCPUState),
            CONFIG_XLEN)};
    }
    auto CSRStateSizeSymbol{
        LoadSymbol<REFDifftestCSRStateSize>("difftest_csr_state_size")};
    if (!CSRStateSizeSymbol)
    {
        return std::unexpected{std::format(
            "DiffTest REF 缺少 CSR 状态 ABI 协商符号 difftest_csr_state_size：{}",
            CSRStateSizeSymbol.error())};
    }
    REFCSRStateSize = *CSRStateSizeSymbol;
    if (const auto stateSize{REFCSRStateSize()}; stateSize != sizeof(DifftestCSRState))
    {
        return std::unexpected{std::format(
            "DiffTest CSR 状态 ABI 不匹配：REF={} 字节，DUT={} 字节"
            "（CONFIG_XLEN={}）",
            stateSize,
            sizeof(DifftestCSRState),
            CONFIG_XLEN)};
    }
    auto CSRcpySymbol{LoadSymbol<REFDifftestCSRcpy>("difftest_csrcpy")};
    if (!CSRcpySymbol)
        return std::unexpected{CSRcpySymbol.error()};
    REFCSRcpy = *CSRcpySymbol;
    auto SetPlatformSymbol{
        LoadSymbol<REFDifftestSetPlatform>("difftest_set_platform")};
    if (!SetPlatformSymbol)
        return std::unexpected{SetPlatformSymbol.error()};
    REFSetPlatform = *SetPlatformSymbol;
    auto ExecSymbol{LoadSymbol<REFDifftestExec>("difftest_exec")};
    if (!ExecSymbol)
    {
        return std::unexpected{ExecSymbol.error()};
    }
    REFExec = *ExecSymbol;
    auto RaiseIntrSymbol{LoadSymbol<REFDifftestRaiseIntr>("difftest_raise_intr")};
    if (!RaiseIntrSymbol)
    {
        return std::unexpected{RaiseIntrSymbol.error()};
    }
    REFRaiseIntr = *RaiseIntrSymbol;
    // tval 注入是可选 ABI 扩展：旧 REF 没有该符号时允许启动，但必须
    // 明确告警；之后遇到非零 tval 的 trap 会拒绝继续比对，避免用陈旧
    // mtval/stval 静默错跑。
    auto TrapSymbol{LoadSymbol<REFDifftestTrap>("difftest_trap")};
    if (TrapSymbol)
    {
        REFTrap = *TrapSymbol;
    }
    else
    {
        std::println(
            std::cerr,
            "DiffTest: 警告：REF 缺少 difftest_trap，tval 注入不可用；"
            "含非零 mtval/stval 的 trap 将明确失败，请更新 REF");
    }
    // 失败诊断是可选扩展：缺失时打印一条明确降级提示即可，绝不能
    // 让原本可以工作的旧 REF 启动失败。
    if (auto symbol{TryLoadSymbol<REFDifftestLastInstruction>(
            "difftest_get_last_instruction")};
        symbol != nullptr)
    {
        REFLastInstruction = symbol;
    }
    else
    {
        std::println(
            std::cerr,
            "DiffTest: 警告：REF 缺少 difftest_get_last_instruction，"
            "失败报告将不显示 REF 实际执行的指令（逐退休比对不受影响）");
    }
    if (auto symbol{TryLoadSymbol<REFDifftestGetCSR>("difftest_get_csr")};
        symbol != nullptr)
    {
        REFGetCSR = symbol;
    }
    else
    {
        std::println(
            std::cerr,
            "DiffTest: 警告：REF 缺少 difftest_get_csr，"
            "失败报告将不显示 REF 侧 mtval/stval（逐退休比对不受影响）");
    }
#ifndef VRISCV32E_NPC
    std::println(std::cerr,
                 "DiffTest: 警告：full-SoC 调试端口未导出 trap tval，"
                 "本平台 tval 注入降级为旧接口（仅 cause）");
#endif
    auto InitSymbol{LoadSymbol<REFDifftestInit>("difftest_init")};
    if (!InitSymbol)
    {
        return std::unexpected{InitSymbol.error()};
    }
    // 必须在 REF 初始化/加载镜像前选择地址图：普通 NEMU 的
    // 0xa0000000 是 MMIO，只有 full-SoC 模式才是 SDRAM。
#ifdef VRISCV32E_NPC
    REFSetPlatform(0);
#else
    REFSetPlatform(1);
#endif
    (*InitSymbol)(0);
    // 包含 direct-NPC cache padding 在内的整个实际镜像都要同步。
    if (ImageSize > FlashMemory.size())
    {
        return std::unexpected{"DiffTest 镜像长度大于已加载内存"};
    }
    if (FlashMemory.size() > static_cast<std::size_t>(CONFIG_MSIZE))
    {
        return std::unexpected{std::format(
            "DiffTest 镜像 {} 字节超出平台镜像窗口 {} 字节",
            FlashMemory.size(),
            static_cast<std::size_t>(CONFIG_MSIZE))};
    }
#ifdef VRISCV32E_NPC
    // direct RTL 初始化会把整个 CONFIG_MSIZE RAM 清零，而 NEMU REF 的
    // 堆内存没有同样的复位保证（MEM_RANDOM 默认为 y）。xv6 的页分配器
    // 会遍历到 64 MiB 尾部，只同步旧的 256 KiB 会让 REF 读到随机值。
    // 因此按完整 RAM 窗口同步：RTL 与 REF 都从“镜像 + 零”开始。
    // RV32/RV64 direct-NPC 共用这一复位语义，地址参数保持完整 XWord。
    const auto ramBytes{static_cast<std::size_t>(CONFIG_MSIZE)};
    const auto syncBytes{ramBytes};
    std::vector<std::uint8_t> referenceMemory(syncBytes, 0);
    std::ranges::copy(FlashMemory, referenceMemory.begin());
    REFMemcpy(CONFIG_MBASE,
              referenceMemory.data(),
              referenceMemory.size(),
              DifftestCPUState::GetDirectionToRef());
#else
    // Full SoC MROM is a 256 MiB address window, but only the loaded image is
    // backed by host storage.  Do not manufacture/copy a 256 MiB zero buffer.
    REFMemcpy(CONFIG_MBASE,
              FlashMemory.data(),
              FlashMemory.size(),
              DifftestCPUState::GetDirectionToRef());
#endif
    // RISC-V does not define reset values for x1..x31.  Seed the mirror only
    // when a REF was actually requested (a DiffTest-capable binary without
    // --diff must retain zero per-instruction debug overhead), then synchronize
    // the reference from the post-reset physical RTL state.
    dut.RefreshCachedArchitecturalState();
    auto DUTState{DifftestCPUState::ReadDUTState(dut)};
    REFRegcpy(&DUTState, DifftestCPUState::GetDirectionToRef());
    LastDUTCSR = ReadDUTRetireCSR(dut);
    REFCSRcpy(&LastDUTCSR, DifftestCPUState::GetDirectionToRef());
    Enabled = true;
    Failed = false;
    SkipCount = 0;
    std::println("DiffTest: ON, REF = {0}", REFSoFile->string());
    return {};
#else
    static_cast<void>(dut);
    if (REFSoFile)
    {
        return std::unexpected{"没开DiffTest"};
    }
    static_cast<void>(ImageSize);
    return {};
#endif
}
/// @brief 执行一步DiffTest比对：REF跑 1 条指令后与DUT寄存器对比
/// @param Top Verilator顶层模块引用，用于读取DUT状态
/// @note 比对不通过会调用npc_ebreak终止仿真
void DifftestStep(DUT &dut)
{
#ifdef CONFIG_DIFFTEST
    if (!Enabled || Failed)
        return;
    const auto retirePC{ReadRetirePC(dut)};
    const auto before{ReadReferenceState()};
    if (before.GetPC() != retirePC)
    {
        auto report{MakeBaseReport(dut, "retire-pre", false)};
        report.RefPC = before.GetPC();
        FillRefInstruction(report, false, "");
        ReportMismatch(dut, "retire-pre", retirePC, std::move(report));
        return;
    }

    const auto nextPC{ReadNextPC(dut)};
    const auto DUTState{ReadDUTStateAtPC(dut, nextPC)};
    const auto DUTCSR{ReadDUTRetireCSR(dut)};
    const bool injectResult{!ReferenceModelsDataAccess(dut) ||
                            IsNondeterministicCSR(dut)};
    if (injectResult)
    {
        // MMIO 和 mcycle 没有可重现的 REF 结果。不执行 REF 这条
        // 指令，而是在精确退休边界注入 DUT 的 GPR/PC/CSR 结果。
        auto injectedState{DUTState};
        auto injectedCSR{DUTCSR};
        REFRegcpy(&injectedState, DifftestCPUState::GetDirectionToRef());
        REFCSRcpy(&injectedCSR, DifftestCPUState::GetDirectionToRef());
        LastDUTCSR = DUTCSR;
        ++SkipCount;
        return;
    }

    REFExec(1);
    const auto REFState{ReadReferenceState()};
    const auto REFCSR{ReadReferenceCSR()};
    const bool regsOk{REFState.CheckRegs(DUTState)};
    const bool csrOk{CheckCSR(REFCSR, DUTCSR)};
    if (!regsOk || !csrOk)
    {
        auto report{MakeBaseReport(
            dut, regsOk ? "retire-post-csr" : "retire-post", false)};
        report.RefPC = REFState.GetPC();
        report.RefState = MakeReportGPRState(REFState);
        report.DUTState = MakeReportGPRState(DUTState);
        report.DUTNextPC = report.DUTState->PC;
        report.RefCSR = MakeReportCSRState(REFCSR);
        report.DUTCSR = MakeReportCSRState(DUTCSR);
        FillRefInstruction(report, true, "");
        ReportMismatch(dut,
                       regsOk ? "retire-post-csr" : "retire-post",
                       nextPC,
                       std::move(report));
        return;
    }
    LastDUTCSR = DUTCSR;
#else
    static_cast<void>(dut);
#endif
}

void DifftestTrapStep(DUT &dut)
{
#ifdef CONFIG_DIFFTEST
    if (!Enabled || Failed)
        return;

    const auto trapPC{ReadTrapPC(dut)};
    const auto cause{static_cast<std::uint32_t>(dut->debug_trap_cause)};
    const auto before{ReadReferenceState()};
    if (before.GetPC() != trapPC)
    {
        auto report{MakeBaseReport(dut, "trap-pre", true)};
        report.RefPC = before.GetPC();
        FillRefInstruction(report, false, "");
        ReportMismatch(dut, "trap-pre", trapPC, std::move(report));
        return;
    }

    // The DUT has already classified the precise trap.  Inject both
    // interrupts and synchronous exceptions at this architectural boundary:
    // executing the faulting instruction in REF is unsafe for PMA faults,
    // illegal instructions, and misaligned control transfers that the simple
    // reference memory/decoder may not model.
    const auto tval{ReadTrapTval(dut)};
#if defined(VRISCV32E_NPC)
    if (REFTrap != nullptr)
    {
        // 新版 REF：cause/epc/tval 一次注入，REF 在同一个架构边界写入
        // mcause/mepc/mtval（或委托到 S 时的 scause/sepc/stval）。
        REFTrap(cause, trapPC, tval);
    }
    else if (tval != 0)
    {
        // 旧 REF 只能传 cause，无法表达非零 tval；明确失败而不是让
        // 后续 csrr mtval/stval 产生难以定位的寄存器差异。
        auto report{MakeBaseReport(dut, "trap-tval-unsupported", true)};
        report.RefPC = before.GetPC();
        FillRefInstruction(report, false,
                           "trap 注入被拒绝，这是上一条 REF 指令");
        ReportMismatch(dut, "trap-tval-unsupported", trapPC,
                       std::move(report));
        return;
    }
    else
    {
        REFRaiseIntr(cause);
    }
#else
    // full-SoC 调试端口暂未导出 tval；初始化时已明确告警，这里保持旧
    // 行为。等待 full-SoC tval 端口补齐后再切换到 REFTrap。
    REFRaiseIntr(cause);
#endif

    const auto REFState{ReadReferenceState()};
    const auto target{ReadTrapTarget(dut)};
    const auto DUTState{ReadDUTStateAtPC(dut, target)};
    const auto DUTCSR{ReadDUTTrapCSR(dut)};
    const auto REFCSR{ReadReferenceCSR()};
    const bool regsOk{REFState.CheckRegs(DUTState)};
    const bool csrOk{CheckCSR(REFCSR, DUTCSR)};
    if (!regsOk || !csrOk)
    {
        auto report{MakeBaseReport(
            dut, regsOk ? "trap-post-csr" : "trap-post", true)};
        report.RefPC = REFState.GetPC();
        report.RefState = MakeReportGPRState(REFState);
        report.DUTState = MakeReportGPRState(DUTState);
        auto refCSR{MakeReportCSRState(REFCSR)};
        auto dutCSR{MakeReportCSRState(DUTCSR)};
        FillTrapTvalCSR(report, refCSR, dutCSR);
        report.RefCSR = std::move(refCSR);
        report.DUTCSR = std::move(dutCSR);
        FillRefInstruction(
            report, false,
            "REF 本步仅注入 trap，未执行指令（显示上一条）");
        ReportMismatch(dut,
                       regsOk ? "trap-post-csr" : "trap-post",
                       target,
                       std::move(report));
        return;
    }
    LastDUTCSR = DUTCSR;
#else
    static_cast<void>(dut);
#endif
}
/// @brief 查询DiffTest是否已启用
/// @return 已启用返回true，否则返回false
bool DifftestIsEnabled()
{
#ifdef CONFIG_DIFFTEST
    return Enabled;
#else
    return false;
#endif
}
/// @brief 跑完整体比对：NEMU连续执行直至trap，与DUT最终状态逐寄存器对比
/// @note 需在 DUT 已触发 trap 后调用
bool DifftestFinalCheck(DUT &dut)
{
#ifdef CONFIG_DIFFTEST
    if (!Enabled)
        return true;
    if (Failed)
        return false;
    // The retirement mirror is exact for the GPR's sole indexed write port.
    // Rescan the physical RF once at the final boundary as an independent
    // guard against unintended writes outside that port or bookkeeping bugs.
    dut.RefreshCachedArchitecturalState();
    const auto REFState{ReadReferenceState()};
    const auto REFCSR{ReadReferenceCSR()};
    const auto DUTState{DifftestCPUState::ReadDUTState(dut)};
    const bool regsOk{REFState.CheckRegs(DUTState)};
    const bool csrOk{CheckCSR(REFCSR, LastDUTCSR)};
    if (!regsOk || !csrOk)
    {
        auto report{MakeBaseReport(
            dut, regsOk ? "final-csr" : "final", false)};
        report.RefPC = REFState.GetPC();
        report.RefState = MakeReportGPRState(REFState);
        report.DUTState = MakeReportGPRState(DUTState);
        report.DUTNextPC = report.DUTState->PC;
        report.RefCSR = MakeReportCSRState(REFCSR);
        report.DUTCSR = MakeReportCSRState(LastDUTCSR);
        FillRefInstruction(
            report, false,
            "REF 最后执行的指令（最终检查未再执行）");
        ReportMismatch(dut,
                       regsOk ? "final-csr" : "final",
                       DUTState.GetPC(),
                       std::move(report));
        return false;
    }
    if (SkipCount != 0)
        std::println("DiffTest: injected {} MMIO/nondeterministic result(s)", SkipCount);
    std::println("DiffTest: final state PASS (pc=0x{:0{}x})",
                 DUTState.GetPC(),
                 npc::XHexDigits);
    return !Failed;
#else
    static_cast<void>(dut);
    return true;
#endif
}
