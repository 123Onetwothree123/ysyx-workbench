#include <verilated.h>
#include <cstdio>
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
    setvbuf(stdout, NULL, _IONBF, 0);
#if defined(CONFIG_LOG_LEVEL) && CONFIG_LOG_LEVEL > 0
    log_init();
#endif
    Verilated::commandArgs(argc, argv);
    DUT dut;
#ifdef CONFIG_NVBOARD
    nvboard_bind_all_pins(&*dut);
    nvboard_init();
#endif
    // All exits after the DUT/NVBoard have been created use the same one-shot
    // cleanup path.  This also keeps DiffTest initialization failures from
    // bypassing Verilator/NVBoard finalization.
    const auto finalize = [&dut]() {
#if defined(CONFIG_LOG_LEVEL) && CONFIG_LOG_LEVEL > 0
        log_close();
#endif
        dut.final();
#ifdef CONFIG_NVBOARD
        nvboard_quit();
#endif
    };
    auto options{CLIOptions::Parse(argc, argv)};
    if (!options)
    {
        std::println(std::cerr, "{}", options.error());
        finalize();
        return 1;
    }
    auto load{ImageLoader::LoadFromCLI(*options)};
    if (!load)
    {
#ifdef CONFIG_SDB
        std::println("未加载镜像文件，进入空 SDB");
#else
        std::println(std::cerr, "{}", load.error());
        finalize();
        return 1;
#endif
    }
    // Establish the DUT's architectural reset state before synchronizing a
    // reference model.  GPRs other than x0 have no ISA-defined reset value,
    // so DiffTest must copy the values actually seen by this RTL instance.
    dut.reset();
#ifdef CONFIG_DIFFTEST
    if (load)
    {
        auto diffResult{DifftestInitialize(dut, options->GetDiffFile(), *load)};
        if (!diffResult)
        {
            std::println(std::cerr, "DiffTest 初始化失败：{}", diffResult.error());
            finalize();
            return 1;
        }
    }
#endif
    if (options->GetVGACheck())
    {
        dut.EnableVGACheck();
    }
#ifdef CONFIG_SDB
    const bool sdbUserQuit{SDB::MainLoop(dut)};
#else
    while (!Verilated::gotFinish() && !NPCTrap::HasHalted())
    {
        dut.step();
        // DiffTest can mark the run BAD while processing this edge.  Do not
        // let a same-cycle custom halt overwrite that failure with a0 == 0.
        if (NPCTrap::HasHalted())
            break;
        if (options->GetVGACheck() && dut.GetCycle() >= 50000000)
        {
            const auto pc{dut.ReadPC()};
            std::println(std::cerr, "VGA时序检查超过50000000周期，强制终止");
            NPCTrap::Stop(pc ? *pc : 0U);
        }
#ifdef CONFIG_NVBOARD
        nvboard_update();
#endif
        if (dut->trap_valid)
        {
            std::println("收到仿真 halt 请求");
            const auto halt_code{dut.ReadGPR(10)}; // x10 = a0
            NPCTrap::Halt(static_cast<std::uint32_t>(dut->trap_pc), halt_code ? *halt_code : 1u);
        }
    }
#endif
#ifdef CONFIG_DIFFTEST
    // 逐退休比对已经把 REF 推进到 DUT 的当前架构边界。
    // GOOD/BAD trap 都在 final() 之前校验，不再让 REF 盲跑固定条数。
    if (!DifftestFinalCheck(dut))
    {
        const auto pc{dut.ReadPC()};
        NPCTrap::Halt(pc ? *pc : 0U, 1U);
    }
#endif
    if (!dut.VGACheckReport())
    {
        const auto pc{dut.ReadPC()};
        NPCTrap::Halt(pc ? *pc : 0U, 1U);
    }
#ifdef CONFIG_SDB
    int result;
    if (sdbUserQuit && !NPCTrap::HasHalted())
    {
        std::println("SDB用户主动退出（程序未结束）");
        result = 0;
    }
    else
    {
        result = NPCTrap::PrintResult(dut.GetCycle(), dut.GetInstructions());
    }
#else
    int result = NPCTrap::PrintResult(dut.GetCycle(), dut.GetInstructions());
#endif
#ifdef CONFIG_PERF_STATS
    NPCTrap::PrintPerformanceStatistics(dut.GetPerfStats(), dut.GetCycle());
#endif
#ifdef CONFIG_PERF_SAVE
    auto result_dir = options->GetResultDir();
    if (result_dir.has_value()) {
        NPCSimResult::Save(*result_dir, dut.GetPerfStats(), dut.GetCycle(), dut.GetInstructions());
    }
#endif
    // Verilator/NVBoard 的收尾每个只执行一次，且必须在所有
    // 读取 DUT 状态的检查之后。
    finalize();
    return result;
}
