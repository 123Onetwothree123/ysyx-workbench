// doxygen是deepseek ai写的
module;
#ifdef CONFIG_DIFFTEST
#include <dlfcn.h>
#include <cstdio>
#endif
#include "../Config.h"
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
                  const DifftestCSRState &dut,
                  std::string_view event,
                  npc::XWord pc)
    {
        if (reference.mstatus == dut.mstatus && reference.mtvec == dut.mtvec &&
            reference.mepc == dut.mepc && reference.mcause == dut.mcause)
            return true;
        std::println(
            std::cerr,
            "DiffTest {} CSR 不匹配（pc=0x{:0{}x}）:\n"
            "  mstatus REF=0x{:0{}x} DUT=0x{:0{}x}\n"
            "  mtvec   REF=0x{:0{}x} DUT=0x{:0{}x}\n"
            "  mepc    REF=0x{:0{}x} DUT=0x{:0{}x}\n"
            "  mcause  REF=0x{:0{}x} DUT=0x{:0{}x}",
            event,
            pc,
            npc::XHexDigits,
            reference.mstatus,
            npc::XHexDigits,
            dut.mstatus,
            npc::XHexDigits,
            reference.mtvec,
            npc::XHexDigits,
            dut.mtvec,
            npc::XHexDigits,
            reference.mepc,
            npc::XHexDigits,
            dut.mepc,
            npc::XHexDigits,
            reference.mcause,
            npc::XHexDigits,
            dut.mcause,
            npc::XHexDigits);
        return false;
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
        // RV32 最多 4 字节；RV64 的 LD/SD 是 8 字节，不能误判为不可建模。
        if (width > (npc::XLen == 64 ? 3U : 2U))
            return false;
        const auto bytes{static_cast<npc::XWord>(1U << width)};
        const auto address{ReadMtraceAddr(dut)};
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

    void ReportMismatch(DUT &dut, std::string_view event, npc::XWord dutPC)
    {
        Failed = true;
        std::println(std::cerr,
                     "DiffTest {} 校验失败（retire PC=0x{:0{}x}, cycle={}）",
                     event,
                     ReadRetirePC(dut),
                     npc::XHexDigits,
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
    // direct RTL 初始化会把镜像及其后 64 KiB 清零，而 NEMU 的堆内存没有
    // 同样的复位保证。因此至少同步历史的 256 KiB 窗口（以及镜像本身），
    // 避免程序第一次读取 RAM 尾部时产生伪差异。同步上限仍由 MSIZE 决定，
    // 不能把某个固定 RAM 大小写成静态断言，否则会拒绝合法配置。
    // RV32/RV64 direct-NPC 共用这一复位语义，地址参数保持完整 XWord。
    constexpr std::size_t DirectRamBytes{256U * 1024U};
    constexpr std::size_t ZeroMarginBytes{64U * 1024U};
    const auto ramBytes{static_cast<std::size_t>(CONFIG_MSIZE)};
    const auto syncBytes{std::min<std::size_t>(
        ramBytes,
        std::max(DirectRamBytes, FlashMemory.size() + ZeroMarginBytes))};
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
        std::println(std::cerr,
                     "DiffTest 执行前 PC 不匹配：REF=0x{:0{}x}, DUT retire=0x{:0{}x}",
                     before.GetPC(),
                     npc::XHexDigits,
                     retirePC,
                     npc::XHexDigits);
        ReportMismatch(dut, "retire-pre", retirePC);
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

    const auto trapPC{ReadTrapPC(dut)};
    const auto cause{static_cast<std::uint32_t>(dut->debug_trap_cause)};
    const auto before{ReadReferenceState()};
    if (before.GetPC() != trapPC)
    {
        std::println(std::cerr,
                     "DiffTest trap 前 PC 不匹配：REF=0x{:0{}x}, DUT trap=0x{:0{}x}",
                     before.GetPC(),
                     npc::XHexDigits,
                     trapPC,
                     npc::XHexDigits);
        ReportMismatch(dut, "trap-pre", trapPC);
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
        std::println(std::cerr,
                     "DiffTest trap tval=0x{:0{}x} 无法注入：REF 缺少 "
                     "difftest_trap（trap PC=0x{:0{}x}）",
                     tval,
                     npc::XHexDigits,
                     trapPC,
                     npc::XHexDigits);
        ReportMismatch(dut, "trap-tval-unsupported", trapPC);
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
    // The retirement mirror is exact for the GPR's sole indexed write port.
    // Rescan the physical RF once at the final boundary as an independent
    // guard against unintended writes outside that port or bookkeeping bugs.
    dut.RefreshCachedArchitecturalState();
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
    std::println("DiffTest: final state PASS (pc=0x{:0{}x})",
                 DUTState.GetPC(),
                 npc::XHexDigits);
    return !Failed;
#else
    static_cast<void>(dut);
    return true;
#endif
}
