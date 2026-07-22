#include <verilated.h>
#ifdef VRISCV32E_NPC
#include "Vriscv32e_npc_SimTop.h"
#define TOP_MODULE Vriscv32e_npc_SimTop
#else
#include "VysyxSoCFull.h"
#define TOP_MODULE VysyxSoCFull
#endif
#ifdef CONFIG_NVBOARD
#include <nvboard.h>
extern void nvboard_bind_all_pins(TOP_MODULE* top);
#endif
import std;
import npc;

int main(int argc, char const *argv[])
{
    std::println("DEBUG: main started");
#if defined(CONFIG_LOG_LEVEL) && CONFIG_LOG_LEVEL > 0
    log_init();
    std::println("DEBUG: log_init done");
#endif
    Verilated::commandArgs(argc, argv);
    DUT dut;
#ifdef CONFIG_NVBOARD
    nvboard_bind_all_pins(&*dut);
    nvboard_init();
#endif
    auto options{CLIOptions::Parse(argc, argv)};
    if (!options)
    {
        std::println(std::cerr, "{}", options.error());
        return 1;
    }
    auto load{ImageLoader::LoadFromCLI(*options)};
    if (!load)
    {
#ifdef CONFIG_SDB
        std::println("未加载镜像文件，进入空 SDB");
#else
        std::println(std::cerr, "{}", load.error());
        return 1;
#endif
    }
#ifdef CONFIG_DIFFTEST
    if (load)
    {
        auto diffResult{DifftestInitialize(options->GetDiffFile(), *load)};
        if (!diffResult)
        {
            std::println(std::cerr, "DiffTest 初始化失败：{}", diffResult.error());
        }
    }
#endif
    dut.reset();
#ifdef CONFIG_SDB
    SDB::MainLoop(dut);
#else
    while (!Verilated::gotFinish() && !NPCTrap::HasHalted())
    {
        dut.step();
#ifdef CONFIG_NVBOARD
        nvboard_update();
#endif
        if (dut->trap_valid)
        {
            std::println("trap了");
            const auto halt_code{dut.ReadGPR(10)}; // x10 = a0
            NPCTrap::Halt(static_cast<std::uint32_t>(dut->trap_pc), halt_code ? *halt_code : 1u);
        }
    }
#endif
    dut.final();
#ifdef CONFIG_NVBOARD
    nvboard_quit();
#endif
    int result = NPCTrap::PrintResult(dut.GetCycle(), dut.GetInstructions());
#ifdef CONFIG_PERF_STATS
    NPCTrap::PrintPerformanceStatistics(
        dut.GetInstructionFetchCount(),
        dut.GetExecutionCompleteCount(),
        dut.GetLoadDataCount(),
        dut.GetStoreDataCount(),
        dut.GetArithmeticOperationCount(),
        dut.GetMemoryAccessOperationCount(),
        dut.GetControlStatusRegisterOperationCount(),
        dut.GetBranchOperationCount(),
        dut.GetCycle(),
        dut.GetInstructionFetchStallPipelineCount(),
        dut.GetInstructionFetchStallAXICount(),
        dut.GetInstructionFetchStallRedirectCount(),
        dut.GetInstructionFetchStallARCount(),
        dut.GetInstructionFetchStallRCount(),
        dut.GetInstructionFetchStallIdleCount(),
        dut.GetArithmeticOperationActiveCycleCount(),
        dut.GetMemoryAccessOperationActiveCycleCount(),
        dut.GetControlStatusRegisterOperationActiveCycleCount(),
        dut.GetBranchOperationActiveCycleCount(),
        dut.GetEXUStallLSUCount(),
        dut.GetLoadStoreUnitActiveCycleCount(),
        dut.GetLoadStoreUnitLoadActiveCycleCount(),
        dut.GetLoadStoreUnitStoreActiveCycleCount(),
        dut.GetLSUStallReadARCount(),
        dut.GetLSUStallReadRCount(),
        dut.GetLSUStallWriteReqCount(),
        dut.GetLSUStallWriteBCount(),
        dut.GetICacheHitCount(),
        dut.GetICacheMissCount());
#endif
#ifdef CONFIG_PERF_SAVE
    auto result_dir = options->GetResultDir();
    if (result_dir.has_value()) {
        NPCSimResult::Save(
            *result_dir,
        dut.GetCycle(),
        dut.GetInstructions(),
        dut.GetInstructionFetchCount(),
        dut.GetExecutionCompleteCount(),
        dut.GetLoadDataCount(),
        dut.GetStoreDataCount(),
        dut.GetArithmeticOperationCount(),
        dut.GetMemoryAccessOperationCount(),
        dut.GetControlStatusRegisterOperationCount(),
        dut.GetBranchOperationCount(),
        dut.GetMemoryAccessOperationActiveCycleCount(),
        dut.GetInstructionFetchStallPipelineCount(),
        dut.GetInstructionFetchStallAXICount(),
        dut.GetInstructionFetchStallARCount(),
        dut.GetInstructionFetchStallRCount(),
        dut.GetInstructionFetchStallRedirectCount(),
        dut.GetInstructionFetchStallIdleCount(),
        dut.GetEXUStallLSUCount(),
        dut.GetLoadStoreUnitActiveCycleCount(),
        dut.GetLoadStoreUnitLoadActiveCycleCount(),
        dut.GetLoadStoreUnitStoreActiveCycleCount(),
        dut.GetLSUStallReadARCount(),
        dut.GetLSUStallReadRCount(),
        dut.GetLSUStallWriteReqCount(),
        dut.GetLSUStallWriteBCount(),
        dut.GetICacheHitCount(),
        dut.GetICacheMissCount());
    }
#endif
    dut.final();
#ifdef CONFIG_NVBOARD
    nvboard_quit();
#endif
#ifdef CONFIG_DIFFTEST
    if (result != 0)
    {
        DiftestFinalCheck(dut);
    }
#endif
#if defined(CONFIG_LOG_LEVEL) && CONFIG_LOG_LEVEL > 0
    log_close();
#endif
    return result;
}
