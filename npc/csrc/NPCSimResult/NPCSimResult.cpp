module npc.NPCSimResult;
import std;
void NPCSimResult::Save(
    std::filesystem::path result_dir,
    const PerfStats &stats,
    std::size_t total_cycles,
    std::size_t total_instructions)
{
    double ipc{0.0};
    if (total_cycles > 0)
    {
        ipc = static_cast<double>(total_instructions) / static_cast<double>(total_cycles);
    }
    double mem_avg{0.0};
    if (stats.memory_access_operation > 0)
    {
        mem_avg = static_cast<double>(stats.memory_access_operation_active_cycle)
                / static_cast<double>(stats.memory_access_operation);
    }
    double load_avg{0.0};
    if (stats.load_data > 0)
    {
        load_avg = static_cast<double>(stats.load_store_unit_load_active_cycle) / static_cast<double>(stats.load_data);
    }
    double store_avg{0.0};
    if (stats.store_data > 0)
    {
        store_avg = static_cast<double>(stats.load_store_unit_store_active_cycle) / static_cast<double>(stats.store_data);
    }
    double amat{0.0};
    if (stats.icache_hit + stats.icache_miss > 0 && stats.icache_miss > 0)
    {
        auto hit_rate{static_cast<double>(stats.icache_hit) / static_cast<double>(stats.icache_hit + stats.icache_miss)};
        auto miss_avg{static_cast<double>(stats.instruction_fetch_stall_ar + stats.instruction_fetch_stall_r) / static_cast<double>(stats.icache_miss)};
        amat = 1.0 + (1.0 - hit_rate) * miss_avg;
    }
    std::filesystem::create_directories(result_dir);
#define XSTR(s) #s
#define STR(s) XSTR(s)

    std::string commit{
#ifdef CONFIG_PERF_GIT_COMMIT
        STR(CONFIG_PERF_GIT_COMMIT)
#else
        "unknown"
#endif
    };
    std::string msg;
    if (auto g{std::ifstream{"build/git_msg.txt"}})
    {
        std::getline(g, msg);
        std::ranges::replace(msg, ',', ';');
    }
    std::string freq{"0"};
    std::string area{"0"};
    if (auto f{std::ifstream{"build/synth.txt"}})
    {
        std::string line;
        if (std::getline(f, line))
        {
            freq = line;
        }
        if (std::getline(f, line))
        {
            area = line;
        }
    }
    auto csv_row{std::format(
        "{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{}",
        commit,
        msg,
        total_cycles,
        total_instructions,
        std::format("{:.4f}", ipc),
        freq,
        area,
        stats.instruction_fetch,
        stats.execution_complete,
        stats.load_data,
        stats.store_data,
        stats.arithmetic_operation,
        stats.memory_access_operation,
        stats.control_status_register_operation,
        stats.branch_operation,
        std::format("{:.2f}", mem_avg),
        stats.instruction_fetch_stall_pipeline,
        stats.instruction_fetch_stall_axi,
        stats.instruction_fetch_stall_ar,
        stats.instruction_fetch_stall_r,
        stats.instruction_fetch_stall_redirect,
        stats.instruction_fetch_stall_idle,
        stats.exu_stall_lsu,
        std::format("{:.2f}", load_avg),
        std::format("{:.2f}", store_avg),
        stats.lsu_stall_read_ar,
        stats.lsu_stall_read_r,
        stats.lsu_stall_write_req,
        stats.lsu_stall_write_b,
        stats.icache_hit,
        stats.icache_miss,
        std::format("{:.1f}", amat),
        stats.idu_stall_raw,
        stats.idu_stall_raw_loaduse,
        stats.idu_stall_raw_alu,
        stats.exu_idle_noinput,
        stats.trap_count,
        stats.mem_waitslot)};

    auto csv_file{
#ifdef VRISCV32E_NPC
        result_dir / (std::string{"result_npc_"} + std::string{STR(CONFIG_PDK)} + ".csv")
#else
        result_dir / (std::string{"result_ysyxsoc_"} + std::string{STR(CONFIG_PDK)} + ".csv")
#endif
    };
     {
        auto out{std::ofstream{csv_file.string(), std::ios::binary}};
        out << "\xEF\xBB\xBF";
        out << "commit,说明,仿真周期数,指令数,IPC,综合频率(MHz),综合面积(um^2),"
               "IFU取指,EXU完成,LSU读,LSU写,"
               "ALU指令,访存指令,CSR指令,分支指令,"
               "访存平均周期,"
               "IFU流水线阻塞,IFU_AXI等待,IFU_AR等待,IFU_R等待,IFU跳转冲刷,IFU空闲,"
               "EXU等LSU,"
               "LSU读延迟,LSU写延迟,"
               "LSU_AR等待,LSU_R等待,LSU_AW/W等待,LSU_B等待,"
               "ICache命中,ICache缺失,AMAT,"
               "IDU_RAW阻塞,IDU_RAW_loaduse,IDU_RAW_可转发,EXU空转等输入,异常提交,EX/MEM等待槽占用\n";
        out << csv_row << '\n';
    }

    std::println("");
    std::println("单次记录: {}", csv_file.string());
}
