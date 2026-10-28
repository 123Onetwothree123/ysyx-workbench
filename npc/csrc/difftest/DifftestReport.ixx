export module npc.difftest.report;
import std;
export namespace npc::difftest
{
inline constexpr std::array<std::string_view, 32> GPRNames{
    "zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2",
    "s0/fp", "s1", "a0", "a1", "a2", "a3", "a4", "a5",
    "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7",
    "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6"};
struct ReportGPRState
{
    std::array<std::uint64_t, 32> GPR{};
    std::uint64_t PC{};
};
struct ReportCSRState
{
    std::uint64_t Mstatus{};
    std::uint64_t Mtvec{};
    std::uint64_t Mepc{};
    std::uint64_t Mcause{};
    bool HasMtval{};
    std::uint64_t Mtval{};
    std::string MtvalLabel{"mtval"};
};
struct ReportFPState
{
    std::array<std::uint64_t, 32> FReg{};
    std::uint64_t FCSR{};
};
struct ReportMemoryDump
{
    std::string Label;
    std::uint64_t Address{};
    std::size_t WordBytes{4};
    std::size_t Count{};
    std::array<std::uint64_t, 4> Words{};
};
struct DifftestReport
{
    std::string Event;
    unsigned XLen{32};
    std::uint64_t Cycle{};
    std::uint64_t DUTRetirePC{};
    bool HasDUTNextPC{};
    std::uint64_t DUTNextPC{};
    std::uint64_t RefPC{};
    bool HasDUTInstruction{};
    std::uint64_t DUTInstructionPC{};
    std::uint32_t DUTInstruction{};
    std::string DUTAssembly;
    std::string DUTInstructionNote;
    bool HasRefInstruction{};
    bool RefInstructionIsCurrent{};
    std::uint64_t RefInstructionPC{};
    std::uint32_t RefInstruction{};
    std::string RefAssembly;
    std::string RefInstructionNote;
    bool HasTrapInstruction{};
    std::uint64_t TrapInstructionPC{};
    std::uint32_t TrapInstruction{};
    std::string TrapAssembly;
    std::optional<ReportGPRState> RefState;
    std::optional<ReportGPRState> DUTState;
    std::optional<ReportCSRState> RefCSR;
    std::optional<ReportCSRState> DUTCSR;
    std::optional<ReportFPState> RefFP;
    std::optional<ReportFPState> DUTFP;
    bool HasTrap{};
    std::uint32_t TrapCause{};
    std::uint64_t TrapEPC{};
    bool TrapTvalAvailable{};
    std::uint64_t TrapTval{};
    std::uint64_t TrapTarget{};
    bool HasRefMtval{};
    std::uint64_t RefMtval{};
    bool HasRefStval{};
    std::uint64_t RefStval{};
    std::vector<ReportMemoryDump> Memory;
};
[[nodiscard]] inline std::string HexValue(std::uint64_t Value, unsigned XLen)
{
    return std::format("0x{:0{}x}", Value, XLen == 64 ? 16 : 8);
}
[[nodiscard]] inline std::string_view TrapCauseName(std::uint32_t Cause)
{
    const bool Interrupt{(Cause & 0x80000000U) != 0};
    const std::uint32_t Code{Cause & 0x7fffffffU};
    if (Interrupt)
    {
        switch (Code)
        {
        case 1:
            return "supervisor software interrupt";
        case 3:
            return "machine software interrupt";
        case 5:
            return "supervisor timer interrupt";
        case 7:
            return "machine timer interrupt";
        case 9:
            return "supervisor external interrupt";
        case 11:
            return "machine external interrupt";
        default:
            return "interrupt";
        }
    }
    switch (Code)
    {
    case 0:
        return "instruction address misaligned";
    case 1:
        return "instruction access fault";
    case 2:
        return "illegal instruction";
    case 3:
        return "breakpoint";
    case 4:
        return "load address misaligned";
    case 5:
        return "load access fault";
    case 6:
        return "store/AMO address misaligned";
    case 7:
        return "store/AMO access fault";
    case 8:
        return "ecall from U";
    case 9:
        return "ecall from S";
    case 11:
        return "ecall from M";
    case 12:
        return "instruction page fault";
    case 13:
        return "load page fault";
    case 15:
        return "store/AMO page fault";
    default:
        return "exception";
    }
}
[[nodiscard]] inline std::string FormatDifftestReport(const DifftestReport &Report)
{
    const unsigned XLen{Report.XLen == 64 ? 64U : 32U};
    std::string Out;
    Out.reserve(2048);
    const auto Add{[](std::string &Text, std::string_view Line)
                   {
                       Text.append(Line);
                       if (!Text.empty() && Text.back() != '\n')
                           Text.push_back('\n');
                   }};
    Add(Out, std::format("DiffTest 校验失败: event={}, cycle={}",
                         Report.Event, Report.Cycle));
    Add(Out, std::format("  DUT retire PC = {}",
                         HexValue(Report.DUTRetirePC, XLen)));
    if (Report.HasDUTNextPC)
    {
        Add(Out, std::format("  DUT next PC   = {}",
                             HexValue(Report.DUTNextPC, XLen)));
    }
    Add(Out, std::format("  REF PC        = {}",
                         HexValue(Report.RefPC, XLen)));
    if (Report.HasDUTInstruction)
    {
        std::string Line{std::format("  DUT 指令      = 0x{:08x}",
                                     Report.DUTInstruction)};
        if (!Report.DUTAssembly.empty())
            Line += std::format("  {}", Report.DUTAssembly);
        if (!Report.DUTInstructionNote.empty())
            Line += std::format("  [{}]", Report.DUTInstructionNote);
        Add(Out, Line);
        Add(Out, std::format("  DUT 指令 PC   = {}",
                             HexValue(Report.DUTInstructionPC, XLen)));
    }
    if (Report.HasTrapInstruction)
    {
        std::string Line{std::format("  trap PC 指令  = 0x{:08x}",
                                     Report.TrapInstruction)};
        if (!Report.TrapAssembly.empty())
            Line += std::format("  {}", Report.TrapAssembly);
        Line += "  [trap PC 处取指内存]";
        Add(Out, Line);
        Add(Out, std::format("  trap 指令 PC  = {}",
                             HexValue(Report.TrapInstructionPC, XLen)));
    }
    if (Report.HasRefInstruction)
    {
        std::string Line{std::format("  REF 指令      = 0x{:08x}",
                                     Report.RefInstruction)};
        if (!Report.RefAssembly.empty())
            Line += std::format("  {}", Report.RefAssembly);
        Line += Report.RefInstructionIsCurrent ? "  [本步执行]" : "  [上一步执行]";
        if (!Report.RefInstructionNote.empty())
            Line += std::format("  [{}]", Report.RefInstructionNote);
        Add(Out, Line);
        Add(Out, std::format("  REF 指令 PC   = {}",
                             HexValue(Report.RefInstructionPC, XLen)));
    }
    else
    {
        std::string Line{"  REF 指令      = <不可用"};
        if (!Report.RefInstructionNote.empty())
            Line += std::format("：{}", Report.RefInstructionNote);
        Line += '>';
        Add(Out, Line);
    }
    if (Report.HasDUTInstruction && Report.HasRefInstruction &&
        Report.DUTInstruction != Report.RefInstruction)
    {
        Add(Out, "  [!] DUT 与 REF 执行的指令字不同");
    }
    if (Report.RefState && Report.DUTState)
    {
        std::vector<std::size_t> Differences;
        for (std::size_t Index{0}; Index < 32; ++Index)
        {
            if (Report.RefState->GPR[Index] != Report.DUTState->GPR[Index])
                Differences.push_back(Index);
        }
        if (Differences.empty())
        {
            Add(Out, "  差异 GPR：无");
        }
        else
        {
            Add(Out, std::format("  差异 GPR（{}/32）：", Differences.size()));
            for (const auto Index : Differences)
            {
                Add(Out, std::format("    x{:<2} {:<7} REF={}  DUT={}",
                                     Index,
                                     GPRNames[Index],
                                     HexValue(Report.RefState->GPR[Index], XLen),
                                     HexValue(Report.DUTState->GPR[Index], XLen)));
            }
        }
        Add(Out, std::format("  SP(x2): REF={}  DUT={}",
                             HexValue(Report.RefState->GPR[2], XLen),
                             HexValue(Report.DUTState->GPR[2], XLen)));
        Add(Out, std::format("  TP(x4): REF={}  DUT={}",
                             HexValue(Report.RefState->GPR[4], XLen),
                             HexValue(Report.DUTState->GPR[4], XLen)));
    }
    else if (Report.DUTState)
    {
        Add(Out, std::format("  SP(x2)={}  TP(x4)={}",
                             HexValue(Report.DUTState->GPR[2], XLen),
                             HexValue(Report.DUTState->GPR[4], XLen)));
    }
    if (Report.RefCSR && Report.DUTCSR)
    {
        struct Entry
        {
            std::string_view Name;
            std::uint64_t Ref;
            std::uint64_t Dut;
        };
        std::vector<Entry> Entries{
            {"mstatus", Report.RefCSR->Mstatus, Report.DUTCSR->Mstatus},
            {"mtvec", Report.RefCSR->Mtvec, Report.DUTCSR->Mtvec},
            {"mepc", Report.RefCSR->Mepc, Report.DUTCSR->Mepc},
            {"mcause", Report.RefCSR->Mcause, Report.DUTCSR->Mcause},
        };
        if (Report.RefCSR->HasMtval && Report.DUTCSR->HasMtval)
        {
            Entries.push_back({Report.RefCSR->MtvalLabel,
                               Report.RefCSR->Mtval,
                               Report.DUTCSR->Mtval});
        }
        bool Any{false};
        for (const auto &Entry : Entries)
        {
            if (Entry.Ref == Entry.Dut)
                continue;
            if (!Any)
            {
                Add(Out, "  CSR 差异：");
                Any = true;
            }
            Add(Out, std::format("    {:<9} REF={}  DUT={}",
                                 Entry.Name,
                                 HexValue(Entry.Ref, XLen),
                                 HexValue(Entry.Dut, XLen)));
        }
        if (!Any)
            Add(Out, "  CSR 差异：无");
        if (!(Report.RefCSR->HasMtval && Report.DUTCSR->HasMtval))
        {
            Add(Out, "  注：mtval 只在 trap 边界比较"
                     "（DUT 未导出 retire mtval 调试端口）");
        }
    }
    if (Report.RefFP && Report.DUTFP)
    {
        std::vector<std::size_t> Differences;
        for (std::size_t Index{0}; Index < 32; ++Index)
        {
            if (Report.RefFP->FReg[Index] != Report.DUTFP->FReg[Index])
                Differences.push_back(Index);
        }
        if (Differences.empty())
        {
            Add(Out, "  差异 FPR：无");
        }
        else
        {
            Add(Out, std::format("  差异 FPR（{}/32）：", Differences.size()));
            for (const auto Index : Differences)
            {
                Add(Out, std::format("    f{:<2} REF={}  DUT={}",
                                     Index,
                                     HexValue(Report.RefFP->FReg[Index], XLen),
                                     HexValue(Report.DUTFP->FReg[Index], XLen)));
            }
        }
        if (Report.RefFP->FCSR != Report.DUTFP->FCSR)
        {
            Add(Out, std::format("  fcsr 差异：REF=0x{:02x}  DUT=0x{:02x}",
                                 Report.RefFP->FCSR & 0xffU,
                                 Report.DUTFP->FCSR & 0xffU));
        }
        else
        {
            Add(Out, std::format("  fcsr: REF=0x{:02x}  DUT=0x{:02x}",
                                 Report.RefFP->FCSR & 0xffU,
                                 Report.DUTFP->FCSR & 0xffU));
        }
    }
    if (Report.HasTrap)
    {
        std::string Line{std::format("  trap: cause=0x{:x} ({}) epc={}",
                                     Report.TrapCause,
                                     TrapCauseName(Report.TrapCause),
                                     HexValue(Report.TrapEPC, XLen))};
        if (Report.TrapTvalAvailable)
            Line += std::format(" tval={}", HexValue(Report.TrapTval, XLen));
        else
            Line += " tval=<不可用>";
        Line += std::format(" target={}", HexValue(Report.TrapTarget, XLen));
        Add(Out, Line);
        if (Report.HasRefMtval || Report.HasRefStval)
        {
            std::string RefLine{"        REF trap CSR:"};
            if (Report.HasRefMtval)
                RefLine += std::format(" mtval={}",
                                       HexValue(Report.RefMtval, XLen));
            if (Report.HasRefStval)
                RefLine += std::format(" stval={}",
                                       HexValue(Report.RefStval, XLen));
            Add(Out, RefLine);
        }
    }
    for (const auto &Dump : Report.Memory)
    {
        Add(Out, std::format("  内存 @{} {}:",
                             Dump.Label,
                             HexValue(Dump.Address, XLen)));
        std::string Line{"   "};
        for (std::size_t Index{0}; Index < Dump.Count; ++Index)
        {
            Line += std::format(" {}", HexValue(Dump.Words[Index], XLen));
        }
        Add(Out, Line);
    }
    return Out;
}
}
