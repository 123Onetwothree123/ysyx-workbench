module npc.NPCSimResult;
import std;
void NPCSimResult::Save(
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
    std::size_t lsu_stall_write_b)
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
    std::filesystem::path result_dir{"NPCSimResult"};
    std::filesystem::create_directories(result_dir);
    auto now{std::chrono::zoned_time{std::chrono::current_zone(), std::chrono::system_clock::now()}};
    auto timestamp{std::format("{:%Y%m%d-%H%M%S}", now)};
#define XSTR(s) #s
#define STR(s) XSTR(s)

    std::string commit{
#ifdef CONFIG_PERF_GIT_COMMIT
        STR(CONFIG_PERF_GIT_COMMIT)
#else
        "unknown"
#endif
    };
    std::string freq{"0 MHz"};
    std::string area{"0 um^2"};
    if (auto f{std::ifstream{"build/synth.txt"}})
    {
        std::string line;
        if (std::getline(f, line))
        {
            freq = line + " MHz";
        }
        if (std::getline(f, line))
        {
            area = line + " um^2";
        }
    }
    auto tsv_row{std::format(
        "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
        commit,
        "",
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
        lsu_stall_write_b)};
    auto tsv_file{result_dir / std::format("{}.tsv", timestamp)};
    {
        auto out{std::ofstream{tsv_file}};
        out << tsv_row << '\n';
    }
    auto perf_tsv{result_dir / "perf.tsv"};
    bool needs_header{!std::filesystem::exists(perf_tsv)};
    {
        auto out{std::ofstream{perf_tsv, std::ios::app}};
        if (needs_header)
        {
            out << "commit\t说明\t仿真周期数\t指令数\tIPC\t综合频率\t综合面积\tIFU取指\tEXU完成\tLSU读\tLSU写\tALU指令\t访存指令\tCSR指令\t分支指令\t访存平均周期\t流水线阻塞\tAXI等待\tAR等待\tR等待\t跳转冲刷\tIFU空闲\tEXU等LSU\t读延迟\t写延迟\tLSU_AR等\tLSU_R等\tLSU写请求等\tLSU_B等\n";
        }
        out << tsv_row << '\n';
    }
    std::println("");
    std::println("性能数据");
    std::println("{}", tsv_row);
    std::println("");
    std::println("已追加到 {}", perf_tsv.string());
    std::println("单次记录: {}", tsv_file.string());
}
