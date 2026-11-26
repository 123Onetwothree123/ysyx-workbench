#include <verilated.h>
#include <cstdio>
#if __has_include(<nvboard.h>)
#include <nvboard.h>
#else
void nvboard_init(int vga_clk_cycle = 1);
void nvboard_quit();
#endif
import ysyx_26030103_TargetTop;
extern void nvboard_bind_all_pins(TargetTop* top);
import std;
import npc.Output;
import npc;

int main(int argc, char const *argv[])
{
    setvbuf(stdout, NULL, _IONBF, 0);
    SimulationContext simulation;
    SimulationContext::SetActive(simulation);
    auto &out{simulation.Out()};
    if constexpr (npc::LogLevel > 0)
    {
        if (auto logResult{log_init(out)}; !logResult)
        {
            out.Error("日志初始化失败：{}", logResult.error());
            log_close(out);
            return 1;
        }
    }
    if constexpr (npc::LogLevel > 0)
    {
        out.Warn("[NPC config] hash={}", npc::ConfigHash);
    }

    const auto failBeforeDUT = [&out](std::string_view error) {
        out.Error("{}", error);
        if constexpr (npc::LogLevel > 0)
        {
            log_close(out);
        }
        return 1;
    };

    // CLI、镜像和 ELF 须在 DUT 构造前完成。
    auto options{CLIOptions::Parse(argc, argv)};
    if (!options)
    {
        return failBeforeDUT(options.error());
    }
    if (options->GetHelpRequested())
    {
        const auto usage{CLIOptions::Usage(argv[0])};
        std::string_view remaining{usage};
        while (!remaining.empty())
        {
            const auto LineEnd{remaining.find('\n')};
            out.Info("{}", remaining.substr(0, LineEnd));
            if (LineEnd == std::string_view::npos)
            {
                break;
            }
            remaining.remove_prefix(LineEnd + 1);
        }
        if constexpr (npc::LogLevel > 0)
        {
            log_close(out);
        }
        return 0;
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
    auto load{ImageLoader::LoadFromCLI(*options, simulation)};
    if (!load)
    {
        if constexpr (npc::HasSDB)
        {
            // 未传镜像可进入空 SDB；显式坏路径必须失败。
            if (options->GetImageFile())
            {
                return failBeforeDUT(load.error());
            }
            simulation.Flash().clear();
            simulation.Mrom().clear();
            out.Info("未指定镜像文件，进入空 SDB");
        }
        else
        {
            return failBeforeDUT(load.error());
        }
    }

    if (const auto &elfFile{options->GetELFFile()}; elfFile)
    {
        auto result{InitializeFtrace(simulation.Trace(), *elfFile, npc::HasFTrace)};
        if (!result)
        {
            return failBeforeDUT(std::format("ELF 初始化失败：{}", result.error()));
        }
    }
    else if constexpr (npc::HasFTrace)
    {
        // FTRACE 开启后无需在 SDB 手工执行 `ftrace on`。
        simulation.Trace().Enable();
    }

    Verilated::commandArgs(argc, argv);
    DUT dut{simulation};
    // DUT/NVBoard 创建后的退出路径都必须经过这里。
    bool nvboardReady{false};
    const auto finalize = [&dut, &nvboardReady, &out]() {
        if constexpr (npc::LogLevel > 0)
        {
            log_close(out);
        }
        dut.final();
        if constexpr (npc::HasNvBoard)
        {
            if (nvboardReady)
            {
                nvboard_quit();
            }
        }
    };
    dut.SetShutdownHook(finalize);
    bool restored{false};
    if (const auto &restoreFile{options->GetRestoreFile()}; restoreFile)
    {
        if constexpr (npc::HasVerilatorSavable)
        {
            if (auto result{dut.RestoreCheckpoint(*restoreFile)}; !result)
            {
                out.Error("{}", result.error());
                finalize();
                return 1;
            }
            out.Info("已恢复 Verilator 状态：{}", restoreFile->string());
            restored = true;
        }
        else
        {
            out.Error("--restore 需要在构建时启用 Verilator --savable "
                      "（make VERILATOR_SAVABLE=y）");
            finalize();
            return 1;
        }
    }
    if (!restored)
    {
        dut.InitializeMemory(simulation.Flash());
    }
    if constexpr (npc::HasNvBoard)
    {
        nvboard_bind_all_pins(&*dut);
        nvboard_init();
        nvboardReady = true;
    }
    // 先复位 DUT，再同步 REF；非 x0 无 ISA 复位值。
    if (!restored)
    {
        dut.reset();
    }
    // CONFIG_DIFFTEST=n 时也显式拒绝 --diff。
    if (options->GetDiffFile())
    {
        if (!load)
        {
            out.Error("DiffTest 需要同时指定程序镜像");
            finalize();
            return 1;
        }
        auto diffResult{DifftestInitialize(dut, options->GetDiffFile(), *load)};
        if (!diffResult)
        {
            out.Error("DiffTest 初始化失败：{}", diffResult.error());
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
        dut.EnableUARTStdin(true);
        while (!NPCTrap::HasHalted())
        {
            if (dut.StepWithHaltPriority())
                break;
        }
    }
    // 逐退休已推进 REF 到当前边界，final 前再校验 trap。
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
            out.Info("SDB用户主动退出（程序未结束）");
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
    if constexpr (npc::HasDicArchCounter)
    {
        NPCTrap::PrintDicArchCounterStatistics(
            dut.GetDicArchCounterStats(), dut.GetCycle(), dut.GetInstructions());
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
                result_dir, dut.GetDicArchCounterStats(), dut.GetCycle(), dut.GetInstructions());
        }
        catch (const std::exception &error)
        {
            out.Error("性能结果保存失败：{}", error.what());
            if (default_result_dir)
            {
                std::error_code ignored;
                std::filesystem::remove(*default_result_dir, ignored);
            }
            result = 1;
        }
    }
    // 收尾只执行一次，且须在所有 DUT 读取之后。
    finalize();
    return result;
}
