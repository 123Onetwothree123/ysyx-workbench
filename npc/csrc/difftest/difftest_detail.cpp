module npc.difftest.difftest;
import std;
import npc.config;
import npc.difftest.DifftestCPUState;
import npc.difftest.report;
import npc.NPCTrap;
import npc.trace.disasm;
import npc.DUT;
import npc.Output;
import npc.SimulationContext;
import npc.SoCMemoryMap.SoCMemoryMap;
import :detail;
namespace npc::difftest::detail
{
    DiffTestState &State()
    {
        static DiffTestState state;
        return state;
    }

    npc::XWord ReadTrapTval(DUT &dut) { return dut.ReadTrapTval(); }

    DifftestCPUState ReadReferenceState()
    {
        DifftestCPUState state;
        State().ref.REFRegcpy(&state, DifftestCPUState::GetDirectionToDUT());
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
        State().ref.REFFPRegcpy(&state, DifftestCPUState::GetDirectionToDUT());
        state.NormalizeFromABI();
        return state;
    }

    DifftestCSRState ReadDUTRetireCSR(DUT &dut)
    {
        const auto state{dut.ReadRetireCSR()};
        return {
            .mstatus = state.mstatus,
            .mtvec = state.mtvec,
            .mepc = state.mepc,
            .mcause = state.mcause,
        };
    }

    DifftestCSRState ReadDUTTrapCSR(DUT &dut)
    {
        const auto state{dut.ReadTrapCSR()};
        return {
            .mstatus = state.mstatus,
            .mtvec = state.mtvec,
            .mepc = state.mepc,
            .mcause = state.mcause,
        };
    }

    DifftestCSRState ReadReferenceCSR()
    {
        DifftestCSRState state{};
        State().ref.REFCSRcpy(&state, DifftestCPUState::GetDirectionToDUT());
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
            dut.ReadMtracePC() != dut.ReadRetirePC())
            return true;
        const auto width{static_cast<unsigned>(dut->debug_mtrace_width)};
        if (width > 3U)
            return false;
        const auto bytes{static_cast<npc::XWord>(1U << width)};
        const auto address{dut.ReadMtraceAddress()};
#ifdef VRISCV32E_NPC
        return RangeContains(address, bytes,
                             static_cast<npc::XWord>(npc::MBase),
                             static_cast<npc::XWord>(npc::MSize));
#else
        return SoCMemoryMap::IsReferenceModeledRam(address, bytes);
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
        auto assembly{::DisassembleWord(pc, instruction)};
        if (assembly.empty())
            return "<反汇编失败>";
        return assembly;
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
        // regsOk不参与命名，GPR差异由基串"retire-post"表示
        static_cast<void>(regsOk);
        return npc::difftest::EventNameWithSuffix("retire-post", csrOk, fpOk);
    }

    void FillDUTInstruction(npc::difftest::DifftestReport &report,
                            DUT &dut,
                            bool trapEvent)
    {
        report.HasDUTInstruction = true;
        report.DUTInstructionPC = dut.ReadRetirePC();
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
        if (State().ref.REFLastInstruction == nullptr)
        {
            report.RefInstructionNote =
                "REF 缺少 difftest_get_last_instruction";
            return;
        }
        std::uint64_t pc{0};
        std::uint64_t instruction{0};
        State().ref.REFLastInstruction(&pc, &instruction);
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
        report.TrapEPC = dut.ReadTrapPC();
        report.TrapTarget = dut.ReadTrapTarget();
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
        if (State().ref.REFGetCSR != nullptr)
        {
            std::uint64_t value{0};
            if (State().ref.REFGetCSR(0x343U, &value))
            {
                report.HasRefMtval = true;
                report.RefMtval = value;
            }
            value = 0;
            if (State().ref.REFGetCSR(0x143U, &value))
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
        report.DUTRetirePC = dut.ReadRetirePC();
        report.HasDUTNextPC = true;
        FillDUTInstruction(report, dut, trapEvent);
        if (trapEvent)
        {
            FillTrapReport(report, dut);
            report.DUTNextPC = report.TrapTarget;
        }
        else
        {
            report.DUTNextPC = dut.ReadNextPC();
        }
        return report;
    }

    void ReportMismatch(DUT &dut,
                        std::string_view event,
                        npc::XWord dutPC,
                        npc::difftest::DifftestReport report)
    {
        State().Failed = true;
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
        auto &out{dut.Context().Out()};
        out.WriteRaw(OutputLevel::Error, text);
        if (!text.empty() && text.back() != '\n')
            out.WriteRaw(OutputLevel::Error, "\n");
        NPCTrap::Halt(dutPC, 1);
    }
}
