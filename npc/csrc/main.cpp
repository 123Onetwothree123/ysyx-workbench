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
    auto options{CLIOptions::Parse(argc, argv)};
    if (!options) { std::println(std::cerr, "{}", options.error()); return 1; }
    auto load{ImageLoader::LoadFromCLI(*options)};
    if (!load) { std::println(std::cerr, "{}", load.error()); return 1; }

    std::println("reset start...");
    dut.reset();
    std::println("reset done, commit={} pc=0x{:08x}", static_cast<int>(dut->debug_commit),
        static_cast<std::uint32_t>(dut->debug_pc));

    // dump first 200 cycles
    std::println("cycle,pc,commit,ifu_pipe,ifu_axi,ifu_ar,ifu_r,ifu_idle,ifu_redir,exe_act,exu_done,exu_lsu_stall,lsu_act,lsu_load,lsu_store,lsu_r_ar,lsu_r_r,lsu_w_req,lsu_w_b,acc_fault");
    for (int c = 0; c < 200 && !Verilated::gotFinish() && !NPCTrap::HasHalted(); ++c) {
        dut.step();
        std::println("{},{:08x},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{}",
            dut.GetCycle(),
            static_cast<std::uint32_t>(dut->debug_pc),
            static_cast<int>(dut->debug_commit),
            static_cast<int>(dut->perf_ifu_stall_pipeline),
            static_cast<int>(dut->perf_ifu_stall_axi),
            static_cast<int>(dut->perf_ifu_stall_ar),
            static_cast<int>(dut->perf_ifu_stall_r),
            static_cast<int>(dut->perf_ifu_stall_idle),
            static_cast<int>(dut->perf_ifu_stall_redirect),
            static_cast<int>(dut->perf_execution_active),
            static_cast<int>(dut->perf_exu_done),
            static_cast<int>(dut->perf_exu_stall_lsu),
            static_cast<int>(dut->perf_lsu_active),
            static_cast<int>(dut->perf_lsu_load_active),
            static_cast<int>(dut->perf_lsu_store_active),
            static_cast<int>(dut->perf_lsu_stall_read_ar),
            static_cast<int>(dut->perf_lsu_stall_read_r),
            static_cast<int>(dut->perf_lsu_stall_write_req),
            static_cast<int>(dut->perf_lsu_stall_write_b),
            static_cast<int>(dut->debug_access_fault));
        if (dut->trap_valid) break;
    }
    std::println("---TRACE END---");

    while (!Verilated::gotFinish() && !NPCTrap::HasHalted()) {
        dut.step();
        if (dut->trap_valid) {
            const auto halt_code{dut.ReadGPR(10)};
            NPCTrap::Halt(static_cast<std::uint32_t>(dut->trap_pc), halt_code ? *halt_code : 1u);
        }
    }
    dut.final();
    int result = NPCTrap::PrintResult(dut.GetCycle(), dut.GetInstructions());
#if defined(CONFIG_LOG_LEVEL) && CONFIG_LOG_LEVEL > 0
    log_close();
#endif
    return result;
}
