module npc.NPCTrap;
import npc.trace.itrace;
namespace
{
    bool Halted{false};
    std::uint32_t HaltPC{0};   // 记录停止的时候的PC
    std::uint32_t HaltCode{0}; // 返回码，0是good，1是bad
} // namespace
void NPCTrap::Halt(std::uint32_t PC, std::uint32_t Code) noexcept
{
    Halted = true;
    HaltPC = PC;
    HaltCode = Code;
}
void NPCTrap::Stop() noexcept
{
    Halted = true;
}
bool NPCTrap::HasHalted() noexcept
{
    return Halted;
}
std::uint32_t NPCTrap::GetPC() noexcept
{
    return HaltPC;
}
std::uint32_t NPCTrap::GetCode() noexcept
{
    return HaltCode;
}
int NPCTrap::PrintResult(std::size_t Cycles, std::size_t Insts)
{
    if (!Halted)
    {
        std::println("NPC在未触发陷阱的情况下退出");
        return 0;
    }
#ifdef CONFIG_PERF_STATS
    auto ipc{Cycles > 0 ? static_cast<double>(Insts) / static_cast<double>(Cycles) : 0.0};
#endif
    if (HaltCode == 0)
    {
#ifdef CONFIG_PERF_STATS
        std::println("HIT GOOD TRAP at pc = 0x{0:08x}, cycles = {1}, insts = {2}, ipc = {3:.4f}", HaltPC, Cycles, Insts, ipc);
#else
        std::println("HIT GOOD TRAP at pc = 0x{0:08x}, cycles = {1}", HaltPC, Cycles);
#endif
        return 0;
    }
#ifdef CONFIG_PERF_STATS
    std::println(std::cerr, "HIT BAD TRAP at pc = 0x{0:08x}, code = {1}, cycles = {2}, insts = {3}, ipc = {4:.4f}", HaltPC, HaltCode, Cycles, Insts, ipc);
#else
    std::println(std::cerr, "HIT BAD TRAP at pc = 0x{0:08x}, code = {1}, cycles = {2}", HaltPC, HaltCode, Cycles);
#endif
    PrintIringbuf(HaltPC);
    return 1;
}
#ifdef CONFIG_PERF_STATS
void NPCTrap::PrintPerfStats(
    std::size_t perf_ifu_fetch,
    std::size_t perf_exu_done,
    std::size_t perf_lsu_load,
    std::size_t perf_lsu_store,
    std::size_t perf_alu_op,
    std::size_t perf_mem_op,
    std::size_t perf_csr_op,
    std::size_t perf_branch_op)
{
    std::println("========== 性能计数器 ==========");
    std::println("IFU取到指令      : {:>12}", perf_ifu_fetch);
    std::println("EXU完成计算      : {:>12}", perf_exu_done);
    std::println("LSU取到数据      : {:>12}", perf_lsu_load);
    std::println("LSU写出数据      : {:>12}", perf_lsu_store);
    std::println("ALU指令          : {:>12}", perf_alu_op);
    std::println("访存指令         : {:>12}", perf_mem_op);
    std::println("CSR指令          : {:>12}", perf_csr_op);
    std::println("分支/跳转指令    : {:>12}", perf_branch_op);
    std::println("--------------------------------");
    auto insts_sum{perf_alu_op + perf_mem_op + perf_csr_op + perf_branch_op};
    std::println("指令类别合计      : {:>12}  (应与IFU取指一致)", insts_sum);
    std::println("IFU取指           : {:>12}", perf_ifu_fetch);
    std::println("EXU完成           : {:>12}  (应与IFU取指接近)", perf_exu_done);
    auto lsu_sum{perf_lsu_load + perf_lsu_store};
    std::println("LSU合计           : {:>12}  (应与访存指令一致)", lsu_sum);
}
