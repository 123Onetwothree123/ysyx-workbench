// doxygen是deepseek ai写的
#include "difftest.hpp"
#include "DifftestCPUState.hpp"
#include <VRV32E32Reg.h>
#include <cstdint>
#include <expected>
#include <filesystem>
#include <format>
#include <optional>
#include <print>
#include <string>
#include <cstdio>
#include "memory.hpp"
#ifdef CONFIG_DIFFTEST
#include <dlfcn.h>
extern "C" void npc_ebreak(int pc, int code);
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
            return std::unexpected{std::format("加载 DiffTest 符号 {} 失败: {}", Name, Error)};
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
    REFHandle = dlopen(REFSoFile->c_str(), RTLD_LAZY); // 打开REF的动态库
    if (REFHandle == nullptr)
    {
        return std::unexpected{std::format("打开 DiffTest REF 失败: {}", dlerror())};
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
    REFMemcpy(RESET_VECTOR, pmem.data(), ImageSize, DifftestCPUState::GetDirectionToRef());
    DifftestCPUState DUTState;
    DUTState.SetPC(RESET_VECTOR); // 设置DUT的PC为复位向量
    REFRegcpy(&DUTState, DifftestCPUState::GetDirectionToRef());
    Enabled = true;
    std::println("DiffTest: ON, REF = {}", REFSoFile->string());
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
void DifftestStep(VRV32E32Reg &Top)
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
    const auto DUTState{DifftestCPUState::ReadDUTState(Top)};
    if (!REFState.CheckRegs(DUTState))
    {
        std::println("DUT和REF的寄存器数据对比不一样");
        npc_ebreak(static_cast<int>(DUTState.GetPC()), 1);
    }
#else
    static_cast<void>(Top);
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
