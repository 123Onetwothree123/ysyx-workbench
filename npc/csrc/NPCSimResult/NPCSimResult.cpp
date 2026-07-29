module npc.NPCSimResult;
import std;
void NPCSimResult::Save(
    std::filesystem::path result_dir,
    std::size_t total_cycles,
    std::size_t total_instructions,
    std::size_t instruction_fetch,
    std::size_t execution_complete,
    std::size_t load_data,
    std::size_t store_data,
    std::size_t arithmetic_operation,
    std::size_t memory_access_operation,
    std::size_t control_status_register_operation,
    std::size_t branch_operation,
    std::size_t memory_access_operation_active_cycles,
    std::size_t instruction_fetch_stall_pipeline,
    std::size_t instruction_fetch_stall_axi,
    std::size_t instruction_fetch_stall_ar,
    std::size_t instruction_fetch_stall_r,
    std::size_t instruction_fetch_stall_redirect,
    std::size_t instruction_fetch_stall_idle,
    std::size_t exu_stall_lsu,
    std::size_t load_store_unit_active,
    std::size_t load_store_unit_load_active,
    std::size_t load_store_unit_store_active,
    std::size_t lsu_stall_read_ar,
    std::size_t lsu_stall_read_r,
    std::size_t lsu_stall_write_req,
    std::size_t lsu_stall_write_b,
    std::size_t icache_hit,
    std::size_t icache_miss,
    std::size_t idu_stall_raw,
    std::size_t idu_stall_raw_loaduse,
    std::size_t idu_stall_raw_alu,
    std::size_t exu_idle_noinput,
    std::size_t trap_count,
    std::size_t mem_waitslot)
{
    double ipc{0.0};
    if (total_cycles > 0)
    {
        ipc = static_cast<double>(total_instructions) / static_cast<double>(total_cycles);
    }
    double mem_avg{0.0};
    if (memory_access_operation > 0)
    {
        mem_avg = static_cast<double>(memory_access_operation_active_cycles)
                / static_cast<double>(memory_access_operation);
    }
    double load_avg{0.0};
    if (load_data > 0)
    {
        load_avg = static_cast<double>(load_store_unit_load_active) / static_cast<double>(load_data);
    }
    double store_avg{0.0};
    if (store_data > 0)
    {
        store_avg = static_cast<double>(load_store_unit_store_active) / static_cast<double>(store_data);
    }
    double amat{0.0};
    if (icache_hit + icache_miss > 0 && icache_miss > 0)
    {
        auto hit_rate{static_cast<double>(icache_hit) / static_cast<double>(icache_hit + icache_miss)};
        auto miss_avg{static_cast<double>(instruction_fetch_stall_ar + instruction_fetch_stall_r) / static_cast<double>(icache_miss)};
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
        instruction_fetch,
        execution_complete,
        load_data,
        store_data,
        arithmetic_operation,
        memory_access_operation,
        control_status_register_operation,
        branch_operation,
        std::format("{:.2f}", mem_avg),
        instruction_fetch_stall_pipeline,
        instruction_fetch_stall_axi,
        instruction_fetch_stall_ar,
        instruction_fetch_stall_r,
        instruction_fetch_stall_redirect,
        instruction_fetch_stall_idle,
        exu_stall_lsu,
        std::format("{:.2f}", load_avg),
        std::format("{:.2f}", store_avg),
        lsu_stall_read_ar,
        lsu_stall_read_r,
        lsu_stall_write_req,
        lsu_stall_write_b,
        icache_hit,
        icache_miss,
        std::format("{:.1f}", amat),
        idu_stall_raw,
        idu_stall_raw_loaduse,
        idu_stall_raw_alu,
        exu_idle_noinput,
        trap_count,
        mem_waitslot)};

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
