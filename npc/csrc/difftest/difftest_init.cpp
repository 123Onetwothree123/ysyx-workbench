module;
#include <dlfcn.h>
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
std::expected<void, std::string> DifftestInitialize(DUT &dut,
                                                    const std::optional<std::filesystem::path> &REFSoFile,
                                                    std::size_t ImageSize)
{
    if constexpr (!npc::HasDiffTest)
    {
        if (REFSoFile)
            return std::unexpected{"没开DiffTest"};
        return {};
    }
    if (!REFSoFile)
    {
        return {};
    }
    REFHandle = dlopen(REFSoFile->c_str(), RTLD_LAZY | RTLD_LOCAL);
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
            "（npc::XLen={}；不能用 RV32 REF 驱动 RV64 DUT）",
            stateSize,
            sizeof(DifftestCPUState),
            npc::XLen)};
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
            "（npc::XLen={}）",
            stateSize,
            sizeof(DifftestCSRState),
            npc::XLen)};
    }
    auto CSRcpySymbol{LoadSymbol<REFDifftestCSRcpy>("difftest_csrcpy")};
    if (!CSRcpySymbol)
        return std::unexpected{CSRcpySymbol.error()};
    REFCSRcpy = *CSRcpySymbol;




    auto FPAbiVersion{TryLoadSymbol<REFDifftestFPAbiVersion>(
        "difftest_fp_abi_version")};
    auto FPStateSize{TryLoadSymbol<REFDifftestFPStateSize>(
        "difftest_fp_state_size")};
    auto FPRegcpy{TryLoadSymbol<REFDifftestFPRegcpy>(
        "difftest_fp_regcpy")};
    const unsigned FPSymbolCount{
        static_cast<unsigned>(FPAbiVersion != nullptr) +
        static_cast<unsigned>(FPStateSize != nullptr) +
        static_cast<unsigned>(FPRegcpy != nullptr)};
    if (FPSymbolCount != 0 && FPSymbolCount != 3)
    {
        return std::unexpected{
            "DiffTest REF 只导出了部分浮点 ABI 符号"
            "（difftest_fp_abi_version/state_size/regcpy 必须同时存在）"};
    }
    if (FPSymbolCount == 3)
    {
        if (const auto version{FPAbiVersion()}; version != 1U)
        {
            return std::unexpected{std::format(
                "DiffTest REF 浮点 ABI 版本不匹配：REF={}，需要=1",
                version)};
        }
        if (const auto size{FPStateSize()}; size != sizeof(DifftestFPState))
        {
            return std::unexpected{std::format(
                "DiffTest REF 浮点状态大小不匹配：REF={}，DUT={}",
                size,
                sizeof(DifftestFPState))};
        }
        REFFPRegcpy = FPRegcpy;
        FPCompareEnabled = dut.HasFPDebugState();
        if (FPCompareEnabled)
        {
            std::println(
                "DiffTest: 浮点状态 ABI ON（freg[32] + fcsr，XLEN={}）",
                npc::XLen);
        }
        else
        {
            std::println(
                std::cerr,
                "DiffTest: 警告：REF 导出了浮点状态 ABI，但当前 DUT 未导出"
                "浮点调试端口/未启用 F，f0-f31/fcsr 不参与比对；"
                "仅整数与 CSR 比对继续");
        }
    }
    else
    {
        std::println(
            std::cerr,
            "DiffTest: 警告：REF 未导出可选浮点状态 ABI"
            "（difftest_fp_*），f0-f31/fcsr 未比对，仅整数与 CSR 比对");
    }
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


    if (auto symbol{TryLoadSymbol<REFDifftestLastInstruction>(
            "difftest_get_last_instruction")};
        symbol != nullptr)
    {
        REFLastInstruction = symbol;
    }
    else
    {
        std::println(
            std::cerr,
            "DiffTest: 警告：REF 缺少 difftest_get_last_instruction，"
            "失败报告将不显示 REF 实际执行的指令（逐退休比对不受影响）");
    }
    if (auto symbol{TryLoadSymbol<REFDifftestGetCSR>("difftest_get_csr")};
        symbol != nullptr)
    {
        REFGetCSR = symbol;
    }
    else
    {
        std::println(
            std::cerr,
            "DiffTest: 警告：REF 缺少 difftest_get_csr，"
            "失败报告将不显示 REF 侧 mtval/stval（逐退休比对不受影响）");
    }
    if (dut.HasTrapTvalDebugState())
    {
        std::println(
            "DiffTest: trap tval ABI ON（trap 注入携带 mtval/stval）");
    }
    else
    {
        std::println(std::cerr,
                     "DiffTest: 警告：当前 RTL 未导出 debug_trap_tval，"
                     "tval 注入降级为旧接口（仅 cause）");
    }
    auto InitSymbol{LoadSymbol<REFDifftestInit>("difftest_init")};
    if (!InitSymbol)
    {
        return std::unexpected{InitSymbol.error()};
    }


#ifdef VRISCV32E_NPC
    REFSetPlatform(0);
#else
    REFSetPlatform(1);
#endif
    (*InitSymbol)(0);

    if (ImageSize > FlashMemory.size())
    {
        return std::unexpected{"DiffTest 镜像长度大于已加载内存"};
    }
    if (FlashMemory.size() > static_cast<std::size_t>(npc::MSize))
    {
        return std::unexpected{std::format(
            "DiffTest 镜像 {} 字节超出平台镜像窗口 {} 字节",
            FlashMemory.size(),
            static_cast<std::size_t>(npc::MSize))};
    }
#ifdef VRISCV32E_NPC





    const auto ramBytes{static_cast<std::size_t>(npc::MSize)};
    const auto syncBytes{ramBytes};
    std::vector<std::uint8_t> referenceMemory(syncBytes, 0);
    std::ranges::copy(FlashMemory, referenceMemory.begin());
    REFMemcpy(npc::MBase,
              referenceMemory.data(),
              referenceMemory.size(),
              DifftestCPUState::GetDirectionToRef());
#else


    REFMemcpy(npc::MBase,
              FlashMemory.data(),
              FlashMemory.size(),
              DifftestCPUState::GetDirectionToRef());
#endif




    dut.RefreshCachedArchitecturalState();
    auto DUTState{DifftestCPUState::ReadDUTState(dut)};
    REFRegcpy(&DUTState, DifftestCPUState::GetDirectionToRef());
    LastDUTCSR = ReadDUTRetireCSR(dut);
    REFCSRcpy(&LastDUTCSR, DifftestCPUState::GetDirectionToRef());
    if (FPCompareEnabled)
    {


        auto DUTFP{DifftestFPState::ReadDUTState(dut)};
        DUTFP.PrepareForREF();
        REFFPRegcpy(&DUTFP, DifftestCPUState::GetDirectionToRef());
    }
    Enabled = true;
    Failed = false;
    SkipCount = 0;
    std::println("DiffTest: ON, REF = {0}", REFSoFile->string());
    return {};
}
