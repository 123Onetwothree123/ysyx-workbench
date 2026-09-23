// doxygen是deepseek ai写的
module;
#ifdef CONFIG_DIFFTEST
#include <dlfcn.h>
#include <cstdio>
#endif
module npc.difftest.difftest;
import npc.difftest.DifftestCPUState;
import npc.NPCTrap;
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
    using REFDifftestMemcpy = void (*)(std::uint32_t addr, void *buf, std::size_t n, bool direction);
    using REFDifftestRegcpy = void (*)(void *dut, bool direction);
    using REFDifftestExec = void (*)(std::uint64_t n);
    using REFDifftestRaiseIntr = void (*)(std::uint64_t no);
    using REFDifftestInit = void (*)(int port);
    using REFDifftestStateSize = std::size_t (*)();
    using REFDifftestCSRStateSize = std::size_t (*)();
    using REFDifftestCSRcpy = void (*)(void *dut, bool direction);
    using REFDifftestSetPlatform = void (*)(int ysyxsoc);
    void *REFHandle{nullptr};
    REFDifftestMemcpy REFMemcpy{nullptr};
    REFDifftestRegcpy REFRegcpy{nullptr};
    REFDifftestExec REFExec{nullptr};
    REFDifftestRaiseIntr REFRaiseIntr{nullptr};
    REFDifftestStateSize REFStateSize{nullptr};
    REFDifftestCSRStateSize REFCSRStateSize{nullptr};
    REFDifftestCSRcpy REFCSRcpy{nullptr};
    REFDifftestSetPlatform REFSetPlatform{nullptr};
    bool Enabled{false};
    bool Failed{false};
    std::size_t SkipCount{0};

    struct DifftestCSRState
    {
        std::uint32_t mstatus{};
        std::uint32_t mtvec{};
        std::uint32_t mepc{};
        std::uint32_t mcause{};
    };
    static_assert(sizeof(DifftestCSRState) == 4 * sizeof(std::uint32_t));
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

    DifftestCPUState ReadReferenceState()
    {
        DifftestCPUState state;
        REFRegcpy(&state, DifftestCPUState::GetDirectionToDUT());
        return state;
    }

    DifftestCPUState ReadDUTStateAtPC(DUT &dut, std::uint32_t pc)
    {
        auto state{DifftestCPUState::ReadDUTState(dut)};
        state.SetPC(pc);
        return state;
    }

    DifftestCSRState ReadDUTRetireCSR(DUT &dut)
    {
        return {
            .mstatus = static_cast<std::uint32_t>(dut->debug_csr_mstatus),
            .mtvec = static_cast<std::uint32_t>(dut->debug_csr_mtvec),
            .mepc = static_cast<std::uint32_t>(dut->debug_csr_mepc),
            .mcause = static_cast<std::uint32_t>(dut->debug_csr_mcause),
        };
    }

    DifftestCSRState ReadDUTTrapCSR(DUT &dut)
    {
        return {
            .mstatus = static_cast<std::uint32_t>(dut->debug_trap_mstatus),
            .mtvec = static_cast<std::uint32_t>(dut->debug_trap_mtvec),
            .mepc = static_cast<std::uint32_t>(dut->debug_trap_mepc),
            .mcause = static_cast<std::uint32_t>(dut->debug_trap_mcause),
        };
    }

    DifftestCSRState ReadReferenceCSR()
    {
        DifftestCSRState state{};
        REFCSRcpy(&state, DifftestCPUState::GetDirectionToDUT());
        return state;
    }

    bool CheckCSR(const DifftestCSRState &reference,
                  const DifftestCSRState &dut,
                  std::string_view event,
                  std::uint32_t pc)
    {
        if (reference.mstatus == dut.mstatus && reference.mtvec == dut.mtvec &&
            reference.mepc == dut.mepc && reference.mcause == dut.mcause)
            return true;
        std::println(
            std::cerr,
            "DiffTest {} CSR 不匹配（pc=0x{:08x}）:\n"
            "  mstatus REF=0x{:08x} DUT=0x{:08x}\n"
            "  mtvec   REF=0x{:08x} DUT=0x{:08x}\n"
            "  mepc    REF=0x{:08x} DUT=0x{:08x}\n"
            "  mcause  REF=0x{:08x} DUT=0x{:08x}",
            event,
            pc,
            reference.mstatus,
            dut.mstatus,
            reference.mtvec,
            dut.mtvec,
            reference.mepc,
            dut.mepc,
            reference.mcause,
            dut.mcause);
        return false;
    }

    constexpr bool RangeContains(std::uint32_t address,
                                 std::uint32_t bytes,
                                 std::uint32_t base,
                                 std::uint32_t size)
    {
        return bytes != 0 && address >= base && address - base < size &&
               bytes <= size - (address - base);
    }

    bool ReferenceModelsDataAccess(DUT &dut)
    {
        if (!dut->debug_mtrace_valid ||
            static_cast<std::uint32_t>(dut->debug_mtrace_pc) !=
                static_cast<std::uint32_t>(dut->debug_pc))
            return true;
        const auto width{static_cast<unsigned>(dut->debug_mtrace_width)};
        if (width > 2)
            return false;
        const auto bytes{static_cast<std::uint32_t>(1U << width)};
        const auto address{static_cast<std::uint32_t>(dut->debug_mtrace_addr)};
#ifdef VRISCV32E_NPC
        return RangeContains(address, bytes, 0x80000000U, 0x00040000U);
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

    void ReportMismatch(DUT &dut, std::string_view event, std::uint32_t dutPC)
    {
        Failed = true;
        std::println(std::cerr,
                     "DiffTest {} 校验失败（retire PC=0x{:08x}, cycle={}）",
                     event,
                     static_cast<std::uint32_t>(dut->debug_pc),
                     dut.GetCycle());
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
        return std::unexpected{
            std::format("DiffTest REF 不支持固定 RV32 状态 ABI：{}",
                        StateSizeSymbol.error())};
    }
    REFStateSize = *StateSizeSymbol;
    if (const auto stateSize{REFStateSize()}; stateSize != sizeof(DifftestCPUState))
    {
        return std::unexpected{std::format(
            "DiffTest 状态 ABI 不匹配：REF={} 字节，DUT={} 字节",
            stateSize,
            sizeof(DifftestCPUState))};
    }
    auto CSRStateSizeSymbol{
        LoadSymbol<REFDifftestCSRStateSize>("difftest_csr_state_size")};
    if (!CSRStateSizeSymbol)
        return std::unexpected{CSRStateSizeSymbol.error()};
    REFCSRStateSize = *CSRStateSizeSymbol;
    if (const auto stateSize{REFCSRStateSize()}; stateSize != sizeof(DifftestCSRState))
    {
        return std::unexpected{std::format(
            "DiffTest CSR 状态 ABI 不匹配：REF={} 字节，DUT={} 字节",
            stateSize,
            sizeof(DifftestCSRState))};
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
    REFMemcpy(CONFIG_MBASE,
              FlashMemory.data(),
              FlashMemory.size(),
              DifftestCPUState::GetDirectionToRef());
    // RISC-V does not define reset values for x1..x31.  Synchronize the
    // reference from the post-reset RTL state instead of assuming zero.
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
    const auto retirePC{static_cast<std::uint32_t>(dut->debug_pc)};
    const auto before{ReadReferenceState()};
    if (before.GetPC() != retirePC)
    {
        std::println(std::cerr,
                     "DiffTest 执行前 PC 不匹配：REF=0x{:08x}, DUT retire=0x{:08x}",
                     before.GetPC(),
                     retirePC);
        ReportMismatch(dut, "retire-pre", retirePC);
        return;
    }

    const auto nextPC{static_cast<std::uint32_t>(dut->debug_next_pc)};
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
    if (!REFState.CheckRegs(DUTState))
    {
        ReportMismatch(dut, "retire-post", nextPC);
        return;
    }
    if (!CheckCSR(ReadReferenceCSR(), DUTCSR, "retire-post", nextPC))
    {
        ReportMismatch(dut, "retire-post-csr", nextPC);
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

    const auto trapPC{static_cast<std::uint32_t>(dut->debug_trap_pc)};
    const auto cause{static_cast<std::uint32_t>(dut->debug_trap_cause)};
    const auto before{ReadReferenceState()};
    if (before.GetPC() != trapPC)
    {
        std::println(std::cerr,
                     "DiffTest trap 前 PC 不匹配：REF=0x{:08x}, DUT trap=0x{:08x}",
                     before.GetPC(),
                     trapPC);
        ReportMismatch(dut, "trap-pre", trapPC);
        return;
    }

    // The DUT has already classified the precise trap.  Inject both
    // interrupts and synchronous exceptions at this architectural boundary:
    // executing the faulting instruction in REF is unsafe for PMA faults,
    // illegal instructions, and misaligned control transfers that the simple
    // reference memory/decoder may not model.
    REFRaiseIntr(cause);

    const auto REFState{ReadReferenceState()};
    const auto target{static_cast<std::uint32_t>(dut->debug_trap_target)};
    const auto DUTState{ReadDUTStateAtPC(dut, target)};
    const auto DUTCSR{ReadDUTTrapCSR(dut)};
    if (!REFState.CheckRegs(DUTState))
    {
        ReportMismatch(dut, "trap-post", target);
        return;
    }
    if (!CheckCSR(ReadReferenceCSR(), DUTCSR, "trap-post", target))
    {
        ReportMismatch(dut, "trap-post-csr", target);
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
    const auto REFState{ReadReferenceState()};
    const auto DUTState{DifftestCPUState::ReadDUTState(dut)};
    const bool matched{REFState.CheckRegs(DUTState)};
    if (!matched)
    {
        ReportMismatch(dut, "final", DUTState.GetPC());
        return false;
    }
    if (!CheckCSR(ReadReferenceCSR(), LastDUTCSR, "final", DUTState.GetPC()))
    {
        ReportMismatch(dut, "final-csr", DUTState.GetPC());
        return false;
    }
    if (SkipCount != 0)
        std::println("DiffTest: injected {} MMIO/nondeterministic result(s)", SkipCount);
    std::println("DiffTest: final state PASS (pc=0x{:08x})", DUTState.GetPC());
    return !Failed;
#else
    static_cast<void>(dut);
    return true;
#endif
}
