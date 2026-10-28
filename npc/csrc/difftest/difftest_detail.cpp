module npc.difftest.difftest;
import std;
import npc.config;
import npc.difftest.DifftestCPUState;
import npc.difftest.report;
import npc.NPCTrap;
import npc.trace.disasm;
import npc.DUT;
import :detail;
namespace npc::difftest::detail
{
    void *REFHandle{nullptr};
    REFDifftestMemcpy REFMemcpy{nullptr};
    REFDifftestRegcpy REFRegcpy{nullptr};
    REFDifftestExec REFExec{nullptr};
    REFDifftestRaiseIntr REFRaiseIntr{nullptr};
    REFDifftestTrap REFTrap{nullptr};
    REFDifftestStateSize REFStateSize{nullptr};
    REFDifftestCSRStateSize REFCSRStateSize{nullptr};
    REFDifftestCSRcpy REFCSRcpy{nullptr};
    REFDifftestFPRegcpy REFFPRegcpy{nullptr};
    REFDifftestSetPlatform REFSetPlatform{nullptr};
    REFDifftestLastInstruction REFLastInstruction{nullptr};
    REFDifftestGetCSR REFGetCSR{nullptr};
    bool Enabled{false};
    bool Failed{false};

    bool FPCompareEnabled{false};
    std::size_t SkipCount{0};

    DifftestCSRState LastDUTCSR{};

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

    npc::XWord ReadRetirePC(DUT &dut) { return DebugWord(*dut, dut->debug_pc, dut->debug_pc_hi); }
    npc::XWord ReadNextPC(DUT &dut) { return DebugWord(*dut, dut->debug_next_pc, dut->debug_next_pc_hi); }
    npc::XWord ReadMtracePC(DUT &dut) { return DebugWord(*dut, dut->debug_mtrace_pc, dut->debug_mtrace_pc_hi); }
    npc::XWord ReadMtraceAddr(DUT &dut) { return DebugWord(*dut, dut->debug_mtrace_addr, dut->debug_mtrace_addr_hi); }
    npc::XWord ReadTrapPC(DUT &dut) { return DebugWord(*dut, dut->debug_trap_pc, dut->debug_trap_pc_hi); }
    npc::XWord ReadTrapTarget(DUT &dut) { return DebugWord(*dut, dut->debug_trap_target, dut->debug_trap_target_hi); }
    npc::XWord ReadTrapTval(DUT &dut) { return dut.ReadTrapTval(); }

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

    DifftestFPState ReadReferenceFPState()
    {
        DifftestFPState state;
        REFFPRegcpy(&state, DifftestCPUState::GetDirectionToDUT());

        state.NormalizeFromABI();
        return state;
    }

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

    DifftestCSRState ReadReferenceCSR()
    {
        DifftestCSRState state{};
        REFCSRcpy(&state, DifftestCPUState::GetDirectionToDUT());
        return state;
    }

    bool CheckCSR(const DifftestCSRState &reference,
                  const DifftestCSRState &dut)
    {

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

        if (width > 3U)
            return false;
        const auto bytes{static_cast<npc::XWord>(1U << width)};
        const auto address{ReadMtraceAddr(dut)};
#ifdef VRISCV32E_NPC

        return RangeContains(address, bytes,
                             static_cast<npc::XWord>(npc::MBase),
                             static_cast<npc::XWord>(npc::MSize));
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

    npc::difftest::ReportFPState MakeReportFPState(
        const DifftestFPState &state)
    {
        npc::difftest::ReportFPState result{};
        for (std::size_t index{0}; index < result.FReg.size(); ++index)
            result.FReg[index] = state.GetFReg(index);
        result.FCSR = state.GetFCSR();
        return result;
    }

    std::string PostEvent(bool regsOk, bool csrOk, bool fpOk)
    {
        std::string event{"retire-post"};
        if (!csrOk)
            event += "-csr";
        if (!fpOk)
            event += "-fp";
        return event;
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
        report.TrapTvalAvailable = dut.HasTrapTvalDebugState();
        if (report.TrapTvalAvailable)
            report.TrapTval = ReadTrapTval(dut);

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
        std::cerr.write(text.data(), static_cast<std::streamsize>(text.size()));
        if (!text.empty() && text.back() != '\n')
            std::cerr.put('\n');
        std::cerr.flush();
        NPCTrap::Halt(dutPC, 1);
    }
}
