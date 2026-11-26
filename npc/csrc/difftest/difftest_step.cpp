module npc.difftest.difftest;
import std;
import npc.Output;
import npc.config;
import npc.difftest.DifftestCPUState;
import npc.difftest.report;
import :detail;
import npc.NPCTrap;
import npc.trace.disasm;
import npc.ysyxSoC;
import npc.DUT;
import npc.SimulationContext;
using namespace npc::difftest::detail;
namespace
{
struct Snapshot
{
    DifftestCPUState regs;
    DifftestCSRState csr;
    std::optional<DifftestFPState> fp;
};
struct SnapshotDiff
{
    bool regsOk{true};
    bool csrOk{true};
    bool fpOk{true};
    [[nodiscard]] bool Ok() const { return regsOk && csrOk && fpOk; }
};
SnapshotDiff CompareSnapshots(const Snapshot &ref, const Snapshot &dut)
{
    SnapshotDiff diff;
    diff.regsOk = ref.regs.CheckRegs(dut.regs);
    diff.csrOk = CheckCSR(ref.csr, dut.csr);
    if (ref.fp && dut.fp)
    {
        diff.fpOk = ref.fp->Check(*dut.fp);
    }
    return diff;
}
// 快照报告各调用点的差异只有 DUTNextPC 是否刷新、是否补 trap tval、
// REF 指令来源及备注，其余字段必须逐字一致。
struct SnapshotReportOptions
{
    bool SetDUTNextPC{};
    bool FillTrapTvalCSR{};
    bool RefInstructionIsCurrent{};
    std::string_view RefInstructionNote{};
};
void FillSnapshotReport(npc::difftest::DifftestReport &report,
                        const Snapshot &RefSnapshot,
                        const Snapshot &DUTSnapshot,
                        const SnapshotReportOptions &options)
{
    report.RefPC = RefSnapshot.regs.GetPC();
    report.RefState = MakeReportGPRState(RefSnapshot.regs);
    report.DUTState = MakeReportGPRState(DUTSnapshot.regs);
    if (options.SetDUTNextPC)
        report.DUTNextPC = report.DUTState->PC;
    auto RefCSR{MakeReportCSRState(RefSnapshot.csr)};
    auto DUTCSR{MakeReportCSRState(DUTSnapshot.csr)};
    if (options.FillTrapTvalCSR)
        FillTrapTvalCSR(report, RefCSR, DUTCSR);
    report.RefCSR = std::move(RefCSR);
    report.DUTCSR = std::move(DUTCSR);
    if (RefSnapshot.fp && DUTSnapshot.fp)
    {
        report.RefFP = MakeReportFPState(*RefSnapshot.fp);
        report.DUTFP = MakeReportFPState(*DUTSnapshot.fp);
    }
    FillRefInstruction(report, options.RefInstructionIsCurrent,
                       options.RefInstructionNote);
}
}
static void DifftestStepImpl(DUT &dut)
{
    if constexpr (!npc::HasDiffTest)
    {
        static_cast<void>(dut);
        return;
    }
    if (!State().Enabled || State().Failed)
        return;
    const auto retirePC{dut.ReadRetirePC()};
    const auto before{ReadReferenceState()};
    if (before.GetPC() != retirePC)
    {
        auto report{MakeBaseReport(dut, "retire-pre", false)};
        report.RefPC = before.GetPC();
        FillRefInstruction(report, false, "");
        ReportMismatch(dut, "retire-pre", retirePC, std::move(report));
        return;
    }

    const auto nextPC{dut.ReadNextPC()};
    const auto DUTState{ReadDUTStateAtPC(dut, nextPC)};
    const auto DUTCSR{ReadDUTRetireCSR(dut)};
    const bool injectResult{!ReferenceModelsDataAccess(dut) ||
                            IsNondeterministicCSR(dut)};
    if (injectResult)
    {
        auto injectedState{DUTState};
        auto injectedCSR{DUTCSR};
        State().ref.REFRegcpy(&injectedState, DifftestCPUState::GetDirectionToRef());
        State().ref.REFCSRcpy(&injectedCSR, DifftestCPUState::GetDirectionToRef());
        if (State().FPCompareEnabled)
        {
            auto injectedFP{DifftestFPState::ReadDUTState(dut)};
            injectedFP.PrepareForREF();
            State().ref.REFFPRegcpy(&injectedFP, DifftestCPUState::GetDirectionToRef());
        }
        State().LastDUTCSR = DUTCSR;
        ++State().SkipCount;
        return;
    }

    State().ref.REFExec(1);
    const auto REFState{ReadReferenceState()};
    const auto REFCSR{ReadReferenceCSR()};
    const bool regsOk{REFState.CheckRegs(DUTState)};
    const bool csrOk{CheckCSR(REFCSR, DUTCSR)};
    std::optional<DifftestFPState> REFFP;
    std::optional<DifftestFPState> DUTFP;
    bool fpOk{true};
    if (State().FPCompareEnabled)
    {
        REFFP = ReadReferenceFPState();
        DUTFP = DifftestFPState::ReadDUTState(dut);
        fpOk = REFFP->Check(*DUTFP);
    }
    if (!regsOk || !csrOk || !fpOk)
    {
        const auto event{PostEvent(regsOk, csrOk, fpOk)};
        auto report{MakeBaseReport(dut, event, false)};
        const Snapshot RefSnapshot{REFState, REFCSR, REFFP};
        const Snapshot DUTSnapshot{DUTState, DUTCSR, DUTFP};
        FillSnapshotReport(report, RefSnapshot, DUTSnapshot,
                           {.SetDUTNextPC = true,
                            .RefInstructionIsCurrent = true});
        ReportMismatch(dut, event, nextPC, std::move(report));
        return;
    }
    State().LastDUTCSR = DUTCSR;
}

void DifftestTrapStep(DUT &dut)
{
    if constexpr (!npc::HasDiffTest)
    {
        static_cast<void>(dut);
        return;
    }
    if (!State().Enabled || State().Failed)
        return;

    const auto trapPC{dut.ReadTrapPC()};
    const auto rawCause{static_cast<std::uint32_t>(dut->debug_trap_cause)};
    // 中断位需从 NPC 的 bit31 转到 NEMU RV64 的 bit63。
    std::uint64_t cause{rawCause};
    if constexpr (npc::XLen == 64)
    {
        if ((rawCause & 0x80000000U) != 0)
        {
            cause = (static_cast<std::uint64_t>(rawCause) & 0x7fffffffULL) |
                    (1ULL << 63);
        }
    }
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
        if (State().ref.REFTrap != nullptr)
        {
            State().ref.REFTrap(cause, trapPC, tval);
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
            State().ref.REFRaiseIntr(cause);
        }
    }
    else
    {
        State().ref.REFRaiseIntr(cause);
    }

    const auto target{dut.ReadTrapTarget()};
    Snapshot refSnap{ReadReferenceState(), ReadReferenceCSR(), std::nullopt};
    Snapshot dutSnap{ReadDUTStateAtPC(dut, target), ReadDUTTrapCSR(dut), std::nullopt};
    if (State().FPCompareEnabled)
    {
        refSnap.fp = ReadReferenceFPState();
        dutSnap.fp = DifftestFPState::ReadDUTState(dut);
    }
    const auto diff{CompareSnapshots(refSnap, dutSnap)};
    if (!diff.Ok())
    {
        const auto event{npc::difftest::EventNameWithSuffix(
            "trap-post", diff.csrOk, diff.fpOk)};
        auto report{MakeBaseReport(dut, event, true)};
        FillSnapshotReport(
            report, refSnap, dutSnap,
            {.FillTrapTvalCSR = true,
             .RefInstructionNote = "REF 本步仅注入 trap，未执行指令（显示上一条）"});
        ReportMismatch(dut, event, target, std::move(report));
        return;
    }
    State().LastDUTCSR = dutSnap.csr;
}

void DifftestStep(DUT &dut)
{
    try
    {
        DifftestStepImpl(dut);
    }
    catch (const std::exception &error)
    {
        State().Failed = true;
        dut.Context().Out().Error("DiffTest step 异常：{}", error.what());
        NPCTrap::Halt(0, 1);
    }
}

bool DifftestIsEnabled()
{
    return npc::HasDiffTest && State().Enabled;
}

static bool DifftestFinalCheckImpl(DUT &dut)
{
    if constexpr (!npc::HasDiffTest)
    {
        static_cast<void>(dut);
        return true;
    }
    if (!State().Enabled)
        return true;
    if (State().Failed)
        return false;

    dut.RefreshCachedArchitecturalState();
    Snapshot refSnap{ReadReferenceState(), ReadReferenceCSR(), std::nullopt};
    Snapshot dutSnap{DifftestCPUState::ReadDUTState(dut), State().LastDUTCSR, std::nullopt};
    if (State().FPCompareEnabled)
    {
        refSnap.fp = ReadReferenceFPState();
        dutSnap.fp = DifftestFPState::ReadDUTState(dut);
    }
    const auto diff{CompareSnapshots(refSnap, dutSnap)};
    if (!diff.Ok())
    {
        const auto event{npc::difftest::EventNameWithSuffix(
            "final", diff.csrOk, diff.fpOk)};
        auto report{MakeBaseReport(dut, event, false)};
        FillSnapshotReport(
            report, refSnap, dutSnap,
            {.SetDUTNextPC = true,
             .RefInstructionNote = "REF 最后执行的指令（最终检查未再执行）"});
        ReportMismatch(dut, event, dutSnap.regs.GetPC(), std::move(report));
        return false;
    }
    if (State().SkipCount != 0)
        dut.Context().Out().Info("DiffTest: injected {} MMIO/nondeterministic result(s)", State().SkipCount);
    dut.Context().Out().Info("DiffTest: final state PASS (pc=0x{:0{}x})",
                             dutSnap.regs.GetPC(),
                             npc::XHexDigits);
    return !State().Failed;
}

bool DifftestFinalCheck(DUT &dut)
{
    try
    {
        return DifftestFinalCheckImpl(dut);
    }
    catch (const std::exception &error)
    {
        State().Failed = true;
        dut.Context().Out().Error("DiffTest final check 异常：{}", error.what());
        return false;
    }
}
