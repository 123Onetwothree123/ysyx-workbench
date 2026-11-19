module;
#include <dlfcn.h>
export module npc.difftest.difftest:detail;
import std;
import npc.config;
import npc.difftest.DifftestCPUState;
import npc.difftest.report;
import npc.DUT;
export namespace npc::difftest::detail
{
using REFDifftestMemcpy = void (*)(npc::XWord addr, void *buf, std::size_t n, bool direction);
using REFDifftestRegcpy = void (*)(void *dut, bool direction);
using REFDifftestExec = void (*)(std::uint64_t n);
using REFDifftestRaiseIntr = void (*)(std::uint64_t no);
using REFDifftestTrap = void (*)(std::uint64_t no, std::uint64_t epc, std::uint64_t tval);
using REFDifftestInit = void (*)(int port);
using REFDifftestStateSize = std::size_t (*)();
using REFDifftestCSRStateSize = std::size_t (*)();
using REFDifftestCSRcpy = void (*)(void *dut, bool direction);
using REFDifftestFPAbiVersion = std::uint32_t (*)();
using REFDifftestFPStateSize = std::size_t (*)();
using REFDifftestFPRegcpy = void (*)(void *dut, bool direction);
using REFDifftestSetPlatform = void (*)(int ysyxsoc);
using REFDifftestLastInstruction = void (*)(std::uint64_t *pc, std::uint64_t *inst);
using REFDifftestGetCSR = bool (*)(std::uint32_t csr, std::uint64_t *value);

struct DifftestCSRState
{
    npc::XWord mstatus{};
    npc::XWord mtvec{};
    npc::XWord mepc{};
    npc::XWord mcause{};
};
static_assert(sizeof(DifftestCSRState) == 4 * sizeof(npc::XWord));

struct REFTable
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
};

struct DiffTestState
{
    REFTable ref;
    bool Enabled{false};
    bool Failed{false};
    bool FPCompareEnabled{false};
    std::size_t SkipCount{0};
    DifftestCSRState LastDUTCSR{};
};

DiffTestState &State();

template <typename Fn>
std::expected<Fn, std::string> LoadSymbol(const char *Name)
{
    dlerror();
    auto *Symbol{dlsym(State().ref.REFHandle, Name)};
    if (const char *Error{dlerror()}; Error != nullptr)
    {
        return std::unexpected{std::format("加载 DiffTest 符号 {0} 失败: {1}", Name, Error)};
    }
    return reinterpret_cast<Fn>(Symbol);
}
template <typename Fn>
Fn TryLoadSymbol(const char *Name)
{
    dlerror();
    auto *Symbol{dlsym(State().ref.REFHandle, Name)};
    if (dlerror() != nullptr)
        return nullptr;
    return reinterpret_cast<Fn>(Symbol);
}

npc::XWord ReadRetirePC(DUT &dut);
npc::XWord ReadNextPC(DUT &dut);
npc::XWord ReadMtracePC(DUT &dut);
npc::XWord ReadMtraceAddr(DUT &dut);
npc::XWord ReadTrapPC(DUT &dut);
npc::XWord ReadTrapTarget(DUT &dut);
npc::XWord ReadTrapTval(DUT &dut);
DifftestCPUState ReadReferenceState();
DifftestCPUState ReadDUTStateAtPC(DUT &dut, npc::XWord pc);
DifftestFPState ReadReferenceFPState();
DifftestCSRState ReadDUTRetireCSR(DUT &dut);
DifftestCSRState ReadDUTTrapCSR(DUT &dut);
DifftestCSRState ReadReferenceCSR();
bool CheckCSR(const DifftestCSRState &reference, const DifftestCSRState &dut);
bool ReferenceModelsDataAccess(DUT &dut);
bool IsNondeterministicCSR(DUT &dut);
std::string DisassembleWord(std::uint64_t pc, std::uint32_t instruction);
npc::difftest::ReportGPRState MakeReportGPRState(const DifftestCPUState &state);
npc::difftest::ReportCSRState MakeReportCSRState(const DifftestCSRState &state);
npc::difftest::ReportFPState MakeReportFPState(const DifftestFPState &state);
std::string PostEvent(bool regsOk, bool csrOk, bool fpOk);
void FillDUTInstruction(npc::difftest::DifftestReport &report, DUT &dut, bool trapEvent);
void FillRefInstruction(npc::difftest::DifftestReport &report, bool current, std::string_view note);
void FillTrapReport(npc::difftest::DifftestReport &report, DUT &dut);
void FillTrapTvalCSR(npc::difftest::DifftestReport &report,
                     npc::difftest::ReportCSRState &refCSR,
                     npc::difftest::ReportCSRState &dutCSR);
void AppendMemoryDump(npc::difftest::DifftestReport &report, DUT &dut,
                      std::string_view label, npc::XWord address);
npc::difftest::DifftestReport MakeBaseReport(DUT &dut, std::string_view event,
                                             bool trapEvent);
void ReportMismatch(DUT &dut, std::string_view event, npc::XWord dutPC,
                    npc::difftest::DifftestReport report);
}
