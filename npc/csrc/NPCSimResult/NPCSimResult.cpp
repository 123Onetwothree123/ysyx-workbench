module npc.NPCSimResult;
import std;
void NPCSimResult::Save(
    std::filesystem::path result_dir,
    const PerfStats &stats,
    std::size_t total_cycles,
    std::size_t total_instructions)
{
    constexpr unsigned schema_version{2};
    double ipc{0.0};
    if (total_cycles > 0)
    {
        ipc = static_cast<double>(total_instructions) / static_cast<double>(total_cycles);
    }
    double mem_exu_residency_avg{0.0};
    if (stats.memory_access_operation > 0)
    {
        mem_exu_residency_avg =
            static_cast<double>(stats.memory_access_operation_active_cycle) /
            static_cast<double>(stats.memory_access_operation);
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
#ifdef CONFIG_ICACHE
    const auto icache{EstimateCachePerformance(
        stats.icache_hit,
        stats.icache_miss,
        stats.instruction_fetch_stall_ar + stats.instruction_fetch_stall_r)};
#endif
#ifdef CONFIG_DCACHE
    const auto dcache{EstimateCachePerformance(
        stats.dcache_hit,
        stats.dcache_miss,
        stats.dcache_refill_req_cycle + stats.dcache_refill_resp_cycle)};
#endif
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
        "{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{}",
        schema_version,
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
        ConditionalBranchCount(stats),
        stats.jal_operation,
        stats.jalr_operation,
        ClassifiedInstructionCount(stats),
        std::format("{:.2f}", mem_exu_residency_avg),
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
        stats.lsu_stall_write_b)};
#ifdef CONFIG_ICACHE
    csv_row += std::format(
        ",{},{},{},{}",
        stats.icache_hit,
        stats.icache_miss,
        std::format("{:.2f}", icache.miss_service_cycle),
        std::format("{:.2f}", icache.amat));
#endif
#ifdef CONFIG_DCACHE
    csv_row += std::format(
        ",{},{},{},{},{},{}",
        stats.dcache_hit,
        stats.dcache_miss,
        stats.dcache_refill_req_cycle,
        stats.dcache_refill_resp_cycle,
        std::format("{:.2f}", dcache.miss_service_cycle),
        std::format("{:.2f}", dcache.amat));
#endif
    csv_row += std::format(
        ",{},{},{},{},{},{}",
        stats.idu_stall_raw,
        stats.idu_stall_raw_loaduse,
        stats.idu_stall_raw_alu,
        stats.exu_idle_noinput,
        stats.trap_count,
        stats.mem_waitslot);
#ifdef CONFIG_RV32_M
    csv_row += std::format(
        ",{},{},{},{},{},{},{},{},{},{},{},{}",
        stats.mdu_request,
        stats.mdu_complete,
        stats.mdu_mul,
        stats.mdu_mulh,
        stats.mdu_mulhsu,
        stats.mdu_mulhu,
        stats.mdu_div,
        stats.mdu_divu,
        stats.mdu_rem,
        stats.mdu_remu,
        stats.mdu_active_cycle,
        stats.mdu_wait_cycle);
#endif

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
        out << "schema_version,commit,说明,仿真周期数,退休指令数,IPC,综合目标频率(MHz),综合面积(um^2),"
               "IFU交付响应,EXU输出握手,LSU读完成,LSU写完成,"
               "ALU指令,访存指令,CSR指令,分支跳转总数,条件分支,JAL,JALR,已分类EXU操作,"
               "访存类EXU驻留摊销,"
               "IFU响应反压,ICache_refill_AR_R状态合计,ICache_refill_AR状态,ICache_refill_R状态,控制流重定向事件,IFU空闲,"
               "EXU下游或顺序等待(已排除MDU计算),"
               "LSU读FSM占用摊销,LSU写FSM占用摊销,"
               "LSU读请求状态,LSU读响应状态,写缓冲无空位,写响应在途,"
#ifdef CONFIG_ICACHE
               "ICache命中,ICache缺失,ICache_refill平均服务周期,ICache_AMAT估算,"
#endif
#ifdef CONFIG_DCACHE
               "DCache_load命中,DCache_可缓存load缺失,DCache_refill_AR周期,DCache_refill_R周期,DCache_refill平均服务周期,DCache_AMAT估算,"
#endif
               "IDU_RAW阻塞,IDU_RAW_loaduse,IDU_RAW_非load未就绪,EXU空转等输入,异常提交,EX/MEM等待槽占用"
#ifdef CONFIG_RV32_M
               ",MDU请求,MDU完成,MUL,MULH,MULHSU,MULHU,DIV,DIVU,REM,REMU,MDU发射或在途活动周期,MDU等待计算结果周期"
#endif
               "\n";
        out << csv_row << '\n';
    }

    std::println("");
    std::println("单次记录: {}", csv_file.string());
}
