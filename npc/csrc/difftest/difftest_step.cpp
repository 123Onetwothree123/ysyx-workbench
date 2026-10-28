module npc.difftest.difftest;
import std;
import npc.config;
import npc.difftest.DifftestCPUState;
import npc.difftest.report;
import :detail;
import npc.NPCTrap;
import npc.trace.disasm;
import npc.ysyxSoC;
import npc.DUT;
using namespace npc::difftest::detail;
void DifftestStep(DUT &dut)
{
    if constexpr (!npc::HasDiffTest)
    {
        static_cast<void>(dut);
        return;
    }
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


        auto injectedState{DUTState};
        auto injectedCSR{DUTCSR};
        REFRegcpy(&injectedState, DifftestCPUState::GetDirectionToRef());
        REFCSRcpy(&injectedCSR, DifftestCPUState::GetDirectionToRef());
        if (FPCompareEnabled)
        {


            auto injectedFP{DifftestFPState::ReadDUTState(dut)};
            injectedFP.PrepareForREF();
            REFFPRegcpy(&injectedFP, DifftestCPUState::GetDirectionToRef());
        }
        LastDUTCSR = DUTCSR;
        ++SkipCount;
        return;
    }

    REFExec(1);
    const auto REFState{ReadReferenceState()};
    const auto REFCSR{ReadReferenceCSR()};
    const bool regsOk{REFState.CheckRegs(DUTState)};
    const bool csrOk{CheckCSR(REFCSR, DUTCSR)};
    std::optional<DifftestFPState> REFFP;
    std::optional<DifftestFPState> DUTFP;
    bool fpOk{true};
    if (FPCompareEnabled)
    {
        REFFP = ReadReferenceFPState();
        DUTFP = DifftestFPState::ReadDUTState(dut);
        fpOk = REFFP->Check(*DUTFP);
    }
    if (!regsOk || !csrOk || !fpOk)
    {
        const auto event{PostEvent(regsOk, csrOk, fpOk)};
        auto report{MakeBaseReport(dut, event, false)};
        report.RefPC = REFState.GetPC();
        report.RefState = MakeReportGPRState(REFState);
        report.DUTState = MakeReportGPRState(DUTState);
        report.DUTNextPC = report.DUTState->PC;
        report.RefCSR = MakeReportCSRState(REFCSR);
        report.DUTCSR = MakeReportCSRState(DUTCSR);
        if (REFFP && DUTFP)
        {
            report.RefFP = MakeReportFPState(*REFFP);
            report.DUTFP = MakeReportFPState(*DUTFP);
        }
        FillRefInstruction(report, true, "");
        ReportMismatch(dut, event, nextPC, std::move(report));
        return;
    }
    LastDUTCSR = DUTCSR;
}

void DifftestTrapStep(DUT &dut)
{
    if constexpr (!npc::HasDiffTest)
    {
        static_cast<void>(dut);
        return;
    }
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






    const auto tval{dut.ReadTrapTval()};
    if (dut.HasTrapTvalDebugState())
    {
        if (REFTrap != nullptr)
        {


            REFTrap(cause, trapPC, tval);
        }
        else if (tval != 0)
        {


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
    }
    else
    {


        REFRaiseIntr(cause);
    }

    const auto REFState{ReadReferenceState()};
    const auto target{ReadTrapTarget(dut)};
    const auto DUTState{ReadDUTStateAtPC(dut, target)};
    const auto DUTCSR{ReadDUTTrapCSR(dut)};
    const auto REFCSR{ReadReferenceCSR()};
    const bool regsOk{REFState.CheckRegs(DUTState)};
    const bool csrOk{CheckCSR(REFCSR, DUTCSR)};
    std::optional<DifftestFPState> REFFP;
    std::optional<DifftestFPState> DUTFP;
    bool fpOk{true};
    if (FPCompareEnabled)
    {


        REFFP = ReadReferenceFPState();
        DUTFP = DifftestFPState::ReadDUTState(dut);
        fpOk = REFFP->Check(*DUTFP);
    }
    if (!regsOk || !csrOk || !fpOk)
    {
        std::string event{"trap-post"};
        if (!csrOk)
            event += "-csr";
        if (!fpOk)
            event += "-fp";
        auto report{MakeBaseReport(dut, event, true)};
        report.RefPC = REFState.GetPC();
        report.RefState = MakeReportGPRState(REFState);
        report.DUTState = MakeReportGPRState(DUTState);
        auto refCSR{MakeReportCSRState(REFCSR)};
        auto dutCSR{MakeReportCSRState(DUTCSR)};
        FillTrapTvalCSR(report, refCSR, dutCSR);
        report.RefCSR = std::move(refCSR);
        report.DUTCSR = std::move(dutCSR);
        if (REFFP && DUTFP)
        {
            report.RefFP = MakeReportFPState(*REFFP);
            report.DUTFP = MakeReportFPState(*DUTFP);
        }
        FillRefInstruction(
            report, false,
            "REF 本步仅注入 trap，未执行指令（显示上一条）");
        ReportMismatch(dut, event, target, std::move(report));
        return;
    }
    LastDUTCSR = DUTCSR;
}


bool DifftestIsEnabled()
{
    return npc::HasDiffTest && Enabled;
}


bool DifftestFinalCheck(DUT &dut)
{
    if constexpr (!npc::HasDiffTest)
    {
        static_cast<void>(dut);
        return true;
    }
    if (!Enabled)
        return true;
    if (Failed)
        return false;



    dut.RefreshCachedArchitecturalState();
    const auto REFState{ReadReferenceState()};
    const auto REFCSR{ReadReferenceCSR()};
    const auto DUTState{DifftestCPUState::ReadDUTState(dut)};
    const bool regsOk{REFState.CheckRegs(DUTState)};
    const bool csrOk{CheckCSR(REFCSR, LastDUTCSR)};
    std::optional<DifftestFPState> REFFP;
    std::optional<DifftestFPState> DUTFP;
    bool fpOk{true};
    if (FPCompareEnabled)
    {
        REFFP = ReadReferenceFPState();
        DUTFP = DifftestFPState::ReadDUTState(dut);
        fpOk = REFFP->Check(*DUTFP);
    }
    if (!regsOk || !csrOk || !fpOk)
    {
        std::string event{"final"};
        if (!csrOk)
            event += "-csr";
        if (!fpOk)
            event += "-fp";
        auto report{MakeBaseReport(dut, event, false)};
        report.RefPC = REFState.GetPC();
        report.RefState = MakeReportGPRState(REFState);
        report.DUTState = MakeReportGPRState(DUTState);
        report.DUTNextPC = report.DUTState->PC;
        report.RefCSR = MakeReportCSRState(REFCSR);
        report.DUTCSR = MakeReportCSRState(LastDUTCSR);
        if (REFFP && DUTFP)
        {
            report.RefFP = MakeReportFPState(*REFFP);
            report.DUTFP = MakeReportFPState(*DUTFP);
        }
        FillRefInstruction(
            report, false,
            "REF 最后执行的指令（最终检查未再执行）");
        ReportMismatch(dut, event, DUTState.GetPC(), std::move(report));
        return false;
    }
    if (SkipCount != 0)
        std::println("DiffTest: injected {} MMIO/nondeterministic result(s)", SkipCount);
    std::println("DiffTest: final state PASS (pc=0x{:0{}x})",
                 DUTState.GetPC(),
                 npc::XHexDigits);
    return !Failed;
}
