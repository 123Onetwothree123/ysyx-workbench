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
namespace
{
#ifdef CONFIG_DIFFTEST
    using REFDifftestMemcpy = void (*)(std::uint32_t addr, void *buf, std::size_t n, bool direction);
    using REFDifftestRegcpy = void (*)(void *dut, bool direction);
    using REFDifftestExec = void (*)(std::uint64_t n);
    using REFDifftestRaiseIntr = void (*)(std::uint64_t no);
    using REFDifftestInit = void (*)(int port);
    void *REFHandle{nullptr};
    REFDifftestMemcpy REFMemcpy{nullptr};
    REFDifftestRegcpy REFRegcpy{nullptr};
    REFDifftestExec REFExec{nullptr};
    REFDifftestRaiseIntr REFRaiseIntr{nullptr};
    bool Enabled{false};
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
#endif
}
/// @brief 初始化DiffTest：加载REF .so、同步内存和寄存器、启用比对
/// @param REFSoFile REF动态库路径，为空则不启用
/// @param ImageSize 程序镜像大小（字节）
/// @return 成功返回空，失败返回错误信息
std::expected<void, std::string> DifftestInitialize(const std::optional<std::filesystem::path> &REFSoFile,
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
    (*InitSymbol)(0);
    // InitSymbol might corrupt global state, skip if not needed
    // REFMemcpy and REFRegcpy should be sufficient for basic difftest
    REFMemcpy(CONFIG_MBASE, FlashMemory.data(), ImageSize, DifftestCPUState::GetDirectionToRef());
    DifftestCPUState DUTState;
    DUTState.SetPC(CONFIG_RESET_PC);
    REFRegcpy(&DUTState, DifftestCPUState::GetDirectionToRef());
    Enabled = true;
    std::println("DiffTest: ON, REF = {0}", REFSoFile->string());
    return {};
#else
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
    if (!Enabled)
    {
        static bool Warned{false};
        if (!Warned)
        {
            std::println("DIFFTEST的enabled都没启动，跑个毛啊");
            Warned = true;
        }
        return;
    }
    REFExec(1);
    DifftestCPUState REFState;
    REFRegcpy(&REFState, DifftestCPUState::GetDirectionToDUT());
    const auto DUTState{DifftestCPUState::ReadDUTState(dut)};
    if (!REFState.CheckRegs(DUTState))
    {
        std::println("DUT和REF的寄存器数据对比不一样");
        NPCTrap::Halt(DUTState.GetPC(), 1);
    }
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
void DiftestFinalCheck(DUT &dut)
{
#ifdef CONFIG_DIFFTEST
    if (!Enabled)
        return;
    // NEMU 连续执行直到 ebreak（测试程序 halt 会触发）
    REFExec(100000);
    DifftestCPUState REFState;
    REFRegcpy(&REFState, DifftestCPUState::GetDirectionToDUT());
    const auto DUTState{DifftestCPUState::ReadDUTState(dut)};
    std::println(stderr, "=== Final Check: REF vs DUT ===");
    std::println(stderr, "  PC: REF=0x{:08x} DUT=0x{:08x}", REFState.GetPC(), DUTState.GetPC());
    for (std::size_t i{0}; i < 32; i++)
    {
        if (REFState.GetGPR(i) != DUTState.GetGPR(i))
            std::println(stderr, "  x{:<2}: REF=0x{:08x} DUT=0x{:08x} ***", i, REFState.GetGPR(i), DUTState.GetGPR(i));
    }
    if (!REFState.CheckRegs(DUTState))
    {
        std::println("DUT和REF的寄存器数据对比不一样 - 这是最后的比对");
    }
#else
    static_cast<void>(dut);
#endif
}
