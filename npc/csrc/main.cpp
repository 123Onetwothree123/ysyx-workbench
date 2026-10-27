#include <verilated.h>
#include <cstdio>
#ifdef VRISCV32E_NPC
#include "Vriscv32e_npc_SimTop.h"
#define TOP_MODULE Vriscv32e_npc_SimTop
#else
#include "VysyxSoCFull.h"
#define TOP_MODULE VysyxSoCFull
#endif
#if __has_include(<nvboard.h>)
#include <nvboard.h>
#else
void nvboard_init(int vga_clk_cycle = 1);
void nvboard_quit();
#endif
extern void nvboard_bind_all_pins(TOP_MODULE* top);
import std;
import npc;

int main(int argc, char const *argv[])
{
    setvbuf(stdout, NULL, _IONBF, 0);
    if constexpr (npc::LogLevel > 0)
    {
        if (auto logResult{log_init()}; !logResult)
        {
            std::println(std::cerr, "日志初始化失败：{}", logResult.error());
            log_close();
            return 1;
        }
    }
    if constexpr (npc::LogLevel > 0)
    {
        // 与 checkpoint/DTS 使用的 .config 摘要保持一致，便于确认本次仿真
        // 实际加载的是哪一份配置。
        std::println(std::cerr, "[NPC config] hash={}", npc::ConfigHash);
    }

    const auto failBeforeDUT = [](std::string_view error) {
        std::println(std::cerr, "{}", error);
        if constexpr (npc::LogLevel > 0)
        {
            log_close();
        }
        return 1;
    };

    // CLI、镜像和 ELF 都必须在 DUT 构造前完成。direct-NPC 的 RAM
    // 会在构造后立即由 FlashMemory 初始化，因此不再有
    // "$readmemh 读旧 program.hex，C++ 却读新 CLI 镜像" 的窗口。
    auto options{CLIOptions::Parse(argc, argv)};
    if (!options)
    {
        return failBeforeDUT(options.error());
    }
#ifdef VRISCV32E_NPC
    if (options->GetVGACheck())
    {
        return failBeforeDUT(
            "--vga-check 仅支持带 VGA 引脚的 SoC 仿真，direct-NPC 不支持");
    }
#endif
    if constexpr (!npc::HasPerfSave)
    {
        if (options->GetResultDir())
        {
            return failBeforeDUT(
                "--result-dir 需要在构建时启用 CONFIG_PERF_SAVE");
        }
    }
    if constexpr (!npc::HasSDB)
    {
        if (options->GetBatchMode())
        {
            return failBeforeDUT("--batch 需要在构建时启用 CONFIG_SDB");
        }
    }
    auto load{ImageLoader::LoadFromCLI(*options)};
    if (!load)
    {
        if constexpr (npc::HasSDB)
        {
            // 没传镜像才是合法的空 SDB；显式给出了坏路径或
            // 过大/损坏镜像时必须失败，不能静默运行旧内存。
            if (options->GetImageFile())
            {
                return failBeforeDUT(load.error());
            }
            FlashMemory.clear();
            mrom.clear();
            std::println("未指定镜像文件，进入空 SDB");
        }
        else
        {
            return failBeforeDUT(load.error());
        }
    }

    if (const auto &elfFile{options->GetElfFile()}; elfFile)
    {
        auto result{InitializeFtrace(*elfFile, npc::HasFTrace)};
        if (!result)
        {
            return failBeforeDUT(std::format("ELF 初始化失败：{}", result.error()));
        }
    }
    else if constexpr (npc::HasFTrace)
    {
        // FTRACE 是编译期功能开关：开启后不需要再进 SDB
        // 手工执行 `ftrace on`。没有 ELF 时仍跟踪，函数名显示 ???。
        GlobalFtrace.Enable();
    }

    Verilated::commandArgs(argc, argv);
    DUT dut;
    bool restored{false};
    if (const auto &restoreFile{options->GetRestoreFile()}; restoreFile)
    {
        if constexpr (npc::HasVerilatorSavable)
        {
            if (auto result{dut.RestoreCheckpoint(*restoreFile)}; !result)
            {
                std::println(std::cerr, "{}", result.error());
                return 1;
            }
            std::println("已恢复 Verilator 状态：{}", restoreFile->string());
            restored = true;
        }
        else
        {
            std::println(std::cerr,
                         "--restore 需要在构建时启用 Verilator --savable "
                         "（make VERILATOR_SAVABLE=y）");
            return 1;
        }
    }
    if (!restored)
    {
        dut.InitializeMemory(FlashMemory);
    }
    if constexpr (npc::HasNvBoard)
    {
        nvboard_bind_all_pins(&*dut);
        nvboard_init();
    }
    // All exits after the DUT/NVBoard have been created use the same one-shot
    // cleanup path.  This also keeps DiffTest initialization failures from
    // bypassing Verilator/NVBoard finalization.
    const auto finalize = [&dut]() {
        if constexpr (npc::LogLevel > 0)
        {
            log_close();
        }
        dut.final();
        if constexpr (npc::HasNvBoard)
        {
            nvboard_quit();
        }
    };
    // Establish the DUT's architectural reset state before synchronizing a
    // reference model.  GPRs other than x0 have no ISA-defined reset value,
    // so DiffTest must copy the values actually seen by this RTL instance.
    if (!restored)
    {
        dut.reset();
    }
    // DifftestInitialize 在 CONFIG_DIFFTEST=n 时也会显式拒绝
    // --diff。不能用预处理把用户参数和错误一起删掉。
    if (options->GetDiffFile())
    {
        if (!load)
        {
            std::println(std::cerr, "DiffTest 需要同时指定程序镜像");
            finalize();
            return 1;
        }
        auto diffResult{DifftestInitialize(dut, options->GetDiffFile(), *load)};
        if (!diffResult)
        {
            std::println(std::cerr, "DiffTest 初始化失败：{}", diffResult.error());
            finalize();
            return 1;
        }
    }
    if (options->GetVGACheck())
    {
        dut.EnableVGACheck();
    }
    SDBExitReason sdbExitReason{SDBExitReason::UserQuit};
    if constexpr (npc::HasSDB)
    {
        sdbExitReason = SDB::MainLoop(dut, options->GetBatchMode());
    }
    else
    {
        // 无 SDB 时 stdin 专供客户机串口使用。
        dut.EnableUARTStdin(true);
        while (!NPCTrap::HasHalted())
        {
            // $finish 可能与本拍已寄存的 halt 脉冲同时可见。先结算 halt，
            // 再退出，否则正常的 GOOD/BAD trap 会被误报成未触发陷阱。
            if (Verilated::gotFinish())
            {
                dut.PollSimulationHalt();
                break;
            }
            dut.step();
            // DiffTest can mark the run BAD while processing this edge.  Do
            // not let a same-cycle custom halt overwrite that failure with
            // a0 == 0.
            if (NPCTrap::HasHalted())
                break;
            if (dut.PollSimulationHalt())
                break;
        }
    }
    // 逐退休比对已经把 REF 推进到 DUT 的当前架构边界。
    // GOOD/BAD trap 都在 final() 之前校验，不再让 REF 盲跑固定条数。
    if (!DifftestFinalCheck(dut))
    {
        const auto pc{dut.ReadPC()};
        NPCTrap::Halt(pc ? *pc : 0U, 1U);
    }
    if (!dut.VGACheckReport())
    {
        const auto pc{dut.ReadPC()};
        NPCTrap::Halt(pc ? *pc : 0U, 1U);
    }
    int result;
    if constexpr (npc::HasSDB)
    {
        if (sdbExitReason == SDBExitReason::UserQuit &&
            !Verilated::gotFinish() && !NPCTrap::HasHalted())
        {
            std::println("SDB用户主动退出（程序未结束）");
            result = 0;
        }
        else
        {
            result = NPCTrap::PrintResult(dut.GetCycle(), dut.GetInstructions());
        }
    }
    else
    {
        result = NPCTrap::PrintResult(dut.GetCycle(), dut.GetInstructions());
    }
    if constexpr (npc::HasPerfStats)
    {
        NPCTrap::PrintPerformanceStatistics(
            dut.GetPerfStats(), dut.GetCycle(), dut.GetInstructions());
    }
    if constexpr (npc::HasPerfSave)
    {
        std::optional<std::filesystem::path> default_result_dir;
        try
        {
            std::filesystem::path result_dir;
            if (options->GetResultDir())
            {
                result_dir = *options->GetResultDir();
            }
            else
            {
                result_dir = NPCSimResult::CreateDefaultResultDirectory();
                default_result_dir = result_dir;
            }
            NPCSimResult::Save(
                result_dir, dut.GetPerfStats(), dut.GetCycle(), dut.GetInstructions());
        }
        catch (const std::exception &error)
        {
            std::println(std::cerr, "性能结果保存失败：{}", error.what());
            if (default_result_dir)
            {
                std::error_code ignored;
                std::filesystem::remove(*default_result_dir, ignored);
            }
            result = 1;
        }
    }
    // Verilator/NVBoard 的收尾每个只执行一次，且必须在所有
    // 读取 DUT 状态的检查之后。
    finalize();
    return result;
}
