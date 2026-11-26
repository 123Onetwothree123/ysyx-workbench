module npc.NPCSimResult;
import std;
import npc.config;

namespace
{
using Metadata = std::unordered_map<std::string, std::string>;

Metadata LoadMetadata(const std::filesystem::path &path)
{
    Metadata metadata;
    auto input{std::ifstream{path}};
    for (std::string line; std::getline(input, line);)
    {
        const auto separator{line.find('=')};
        if (separator == std::string::npos || separator == 0)
            continue;
        metadata.insert_or_assign(
            line.substr(0, separator), line.substr(separator + 1));
    }
    return metadata;
}

std::string MetadataValue(
    const Metadata &metadata,
    std::string_view key,
    std::string_view fallback = "unknown")
{
    if (const auto found{metadata.find(std::string{key})}; found != metadata.end())
        return found->second;
    return std::string{fallback};
}

bool IsPositiveFiniteNumber(std::string_view text)
{
    if (text.empty())
        return false;
    double value{};
    const auto [end, error]{std::from_chars(
        text.data(), text.data() + text.size(), value, std::chars_format::general)};
    return error == std::errc{} && end == text.data() + text.size() &&
           std::isfinite(value) && value > 0.0;
}

std::string EscapeCsv(std::string_view field)
{
    if (field.find_first_of(",\"\r\n") == std::string_view::npos)
        return std::string{field};
    std::string escaped;
    escaped.reserve(field.size() + 2);
    escaped.push_back('"');
    for (const auto character : field)
    {
        if (character == '"')
            escaped.push_back('"');
        escaped.push_back(character);
    }
    escaped.push_back('"');
    return escaped;
}

template <typename Range>
void WriteCsvLine(std::ostream &output, const Range &fields)
{
    bool first{true};
    for (const auto &field : fields)
    {
        if (!first)
            output.put(',');
        first = false;
        output << EscapeCsv(field);
    }
    output.put('\n');
}

std::filesystem::path CreateUniqueDirectory(
    const std::filesystem::path &root,
    std::string_view name,
    const char *message)
{
    for (std::size_t suffix{0};; ++suffix)
    {
        const auto directory_name{suffix == 0
            ? std::string{name}
            : std::format("{}-{}", name, suffix)};
        const auto candidate{root / directory_name};
        std::error_code error;
        if (std::filesystem::create_directory(candidate, error))
            return candidate;
        // 已存在（含未置错误码的情形）时换下一个后缀。
        if (error == std::errc::file_exists || !error)
            continue;
        throw std::filesystem::filesystem_error{
            std::string{message}, candidate, error};
    }
}
}

std::filesystem::path NPCSimResult::CreateDefaultResultDirectory()
{
    const auto now{std::chrono::system_clock::now()};
    const auto now_time{std::chrono::system_clock::to_time_t(now)};
    const auto *local_time{std::localtime(&now_time)};
    if (local_time == nullptr)
    {
        throw std::runtime_error{"无法生成性能结果时间戳"};
    }

    std::ostringstream timestamp_stream;
    timestamp_stream << std::put_time(local_time, "%Y%m%d-%H%M%S");
    if (!timestamp_stream)
    {
        throw std::runtime_error{"无法格式化性能结果时间戳"};
    }
    const auto timestamp{timestamp_stream.str()};

    const std::filesystem::path result_root{"NPCSimResult"};
    std::filesystem::create_directories(result_root);
    return CreateUniqueDirectory(
        result_root, timestamp, "无法创建默认性能结果目录");
}

void NPCSimResult::Save(
    std::filesystem::path result_dir,
    const DicArchCounterStats &stats,
    std::size_t total_cycles,
    std::size_t total_instructions)
{
    constexpr unsigned schema_version{9};
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
    const auto icache{EstimateCachePerformance(
        stats.icache_hit,
        stats.icache_miss,
        stats.instruction_fetch_stall_ar + stats.instruction_fetch_stall_r)};
    const auto dcache{EstimateCachePerformance(
        stats.dcache_hit,
        stats.dcache_miss,
        stats.dcache_refill_req_cycle + stats.dcache_refill_resp_cycle)};
    std::filesystem::create_directories(result_dir);
    Metadata build_metadata;
    if (!npc::PerfBuildMetadataFile.empty())
    {
        build_metadata = LoadMetadata(
            std::filesystem::path{npc::PerfBuildMetadataFile});
    }
#ifdef VRISCV32E_NPC
    constexpr std::string_view rtl_target{"npc"};
    constexpr std::string_view rtl_hash_key{"rtl_npc_sha256"};
#else
    constexpr std::string_view rtl_target{"soc"};
    constexpr std::string_view rtl_hash_key{"rtl_soc_sha256"};
#endif
    const auto commit{MetadataValue(build_metadata, "commit")};
    const auto git_dirty{MetadataValue(build_metadata, "git_dirty")};
    const auto message{MetadataValue(build_metadata, "message", "")};
    const auto config_sha256{MetadataValue(build_metadata, "config_sha256")};
    const auto config_summary{MetadataValue(build_metadata, "config_summary")};
    const auto rtl_sha256{MetadataValue(build_metadata, rtl_hash_key)};
    const auto pdk{MetadataValue(build_metadata, "pdk", npc::PDK)};

    std::string freq{"0"};
    std::string area{"0"};
    bool synth_metadata_valid{false};
    if (!npc::PerfSynthFile.empty())
    {
        const auto synth_metadata{
            LoadMetadata(std::filesystem::path{npc::PerfSynthFile})};
        const auto synth_frequency{
            MetadataValue(synth_metadata, "frequency_mhz", "")};
        const auto synth_area{
            MetadataValue(synth_metadata, "area_um2", "")};
        synth_metadata_valid =
            MetadataValue(build_metadata, "format", "") == "1" &&
            MetadataValue(synth_metadata, "format", "") == "2" &&
            MetadataValue(synth_metadata, "target", "") == rtl_target &&
            MetadataValue(synth_metadata, "pdk", "") == pdk &&
            MetadataValue(synth_metadata, "config_sha256", "") == config_sha256 &&
            MetadataValue(synth_metadata, "rtl_sha256", "") == rtl_sha256 &&
            MetadataValue(synth_metadata, "measurement_valid", "") == "1" &&
            config_sha256 != "unknown" && rtl_sha256 != "unknown" &&
            IsPositiveFiniteNumber(synth_frequency) &&
            IsPositiveFiniteNumber(synth_area);
        if (synth_metadata_valid)
        {
            freq = synth_frequency;
            area = synth_area;
        }
    }

    struct CsvField
    {
        std::string_view name;
        std::function<std::string()> value;
    };
    const std::vector<CsvField> csv_fields{
        {"schema_version", [&] { return std::to_string(schema_version); }},
        {"commit", [&] { return commit; }},
        {"git_dirty", [&] { return git_dirty; }},
        {"config_sha256", [&] { return config_sha256; }},
        {"config_summary", [&] { return config_summary; }},
        {"rtl_target", [&] { return std::string{rtl_target}; }},
        {"rtl_sha256", [&] { return rtl_sha256; }},
        {"pdk", [&] { return pdk; }},
        {"synth_metadata_valid", [&] { return synth_metadata_valid ? "1" : "0"; }},
        {"说明", [&] { return message; }},
        {"仿真周期数", [&] { return std::to_string(total_cycles); }},
        {"退休指令数", [&] { return std::to_string(total_instructions); }},
        {"IPC", [&] { return std::format("{:.4f}", ipc); }},
        {"综合目标频率(MHz)", [&] { return freq; }},
        {"综合面积(um^2)", [&] { return area; }},
        {"IFU交付响应", [&] { return std::to_string(stats.instruction_fetch); }},
        {"EXU输出握手", [&] { return std::to_string(stats.execution_complete); }},
        {"LSU读完成", [&] { return std::to_string(stats.load_data); }},
        {"LSU写完成", [&] { return std::to_string(stats.store_data); }},
        {"ALU指令", [&] { return std::to_string(stats.arithmetic_operation); }},
        {"访存指令", [&] { return std::to_string(stats.memory_access_operation); }},
        {"CSR指令", [&] { return std::to_string(stats.control_status_register_operation); }},
        {"分支跳转总数", [&] { return std::to_string(stats.branch_operation); }},
        {"条件分支", [&] { return std::to_string(ConditionalBranchCount(stats)); }},
        {"JAL", [&] { return std::to_string(stats.jal_operation); }},
        {"JALR", [&] { return std::to_string(stats.jalr_operation); }},
        {"已分类EXU操作", [&] { return std::to_string(ClassifiedInstructionCount(stats)); }},
        {"访存类EXU驻留摊销", [&] { return std::format("{:.2f}", mem_exu_residency_avg); }},
        {"IFU响应反压", [&] { return std::to_string(stats.instruction_fetch_stall_pipeline); }},
        {"ICache_refill_AR_R状态合计", [&] { return std::to_string(stats.instruction_fetch_stall_axi); }},
        {"ICache_refill_AR状态", [&] { return std::to_string(stats.instruction_fetch_stall_ar); }},
        {"ICache_refill_R状态", [&] { return std::to_string(stats.instruction_fetch_stall_r); }},
        {"控制流重定向事件", [&] { return std::to_string(stats.instruction_fetch_stall_redirect); }},
        {"IFU空闲", [&] { return std::to_string(stats.instruction_fetch_stall_idle); }},
        {"EXU下游或顺序等待(已排除MDU计算)", [&] { return std::to_string(stats.exu_stall_lsu); }},
        {"LSU读FSM占用摊销", [&] { return std::format("{:.2f}", load_avg); }},
        {"LSU写FSM占用摊销", [&] { return std::format("{:.2f}", store_avg); }},
        {"LSU读请求状态", [&] { return std::to_string(stats.lsu_stall_read_ar); }},
        {"LSU读响应状态", [&] { return std::to_string(stats.lsu_stall_read_r); }},
        {"写缓冲无空位", [&] { return std::to_string(stats.lsu_stall_write_req); }},
        {"写响应在途", [&] { return std::to_string(stats.lsu_stall_write_b); }},
        {"ICache命中", [&] { return std::to_string(stats.icache_hit); }},
        {"ICache缺失", [&] { return std::to_string(stats.icache_miss); }},
        {"ICache_refill平均服务周期", [&] { return std::format("{:.2f}", icache.miss_service_cycle); }},
        {"ICache_AMAT估算", [&] { return std::format("{:.2f}", icache.amat); }},
        {"DCache_load命中", [&] { return std::to_string(stats.dcache_hit); }},
        {"DCache_可缓存load缺失", [&] { return std::to_string(stats.dcache_miss); }},
        {"DCache_refill_AR周期", [&] { return std::to_string(stats.dcache_refill_req_cycle); }},
        {"DCache_refill_R周期", [&] { return std::to_string(stats.dcache_refill_resp_cycle); }},
        {"DCache_refill平均服务周期", [&] { return std::format("{:.2f}", dcache.miss_service_cycle); }},
        {"DCache_AMAT估算", [&] { return std::format("{:.2f}", dcache.amat); }},
        {"IDU_RAW阻塞", [&] { return std::to_string(stats.idu_stall_raw); }},
        {"IDU_RAW_loaduse", [&] { return std::to_string(stats.idu_stall_raw_loaduse); }},
        {"IDU_RAW_非load未就绪", [&] { return std::to_string(stats.idu_stall_raw_alu); }},
        {"EXU空转等输入", [&] { return std::to_string(stats.exu_idle_noinput); }},
        {"异常提交", [&] { return std::to_string(stats.trap_count); }},
        {"EX/MEM等待槽占用", [&] { return std::to_string(stats.mem_waitslot); }},
        {"MDU请求", [&] { return std::to_string(stats.mdu_request); }},
        {"MDU完成", [&] { return std::to_string(stats.mdu_complete); }},
        {"MUL", [&] { return std::to_string(stats.mdu_mul); }},
        {"MULH", [&] { return std::to_string(stats.mdu_mulh); }},
        {"MULHSU", [&] { return std::to_string(stats.mdu_mulhsu); }},
        {"MULHU", [&] { return std::to_string(stats.mdu_mulhu); }},
        {"DIV", [&] { return std::to_string(stats.mdu_div); }},
        {"DIVU", [&] { return std::to_string(stats.mdu_divu); }},
        {"REM", [&] { return std::to_string(stats.mdu_rem); }},
        {"REMU", [&] { return std::to_string(stats.mdu_remu); }},
        {"MDU发射或在途活动周期", [&] { return std::to_string(stats.mdu_active_cycle); }},
        {"MDU等待计算结果周期", [&] { return std::to_string(stats.mdu_wait_cycle); }},
        {"BTB命中", [&] { return std::to_string(stats.btb_hit); }},
        {"BTB缺失", [&] { return std::to_string(stats.btb_miss); }},
        {"JAL_BTB命中", [&] { return std::to_string(stats.jal_btb_hit); }},
        {"RAS命中", [&] { return std::to_string(stats.ras_hit); }},
        {"分支误预测", [&] { return std::to_string(stats.branch_mispredict); }},
        {"ITLB命中", [&] { return std::to_string(stats.itlb_hit); }},
        {"ITLB缺失", [&] { return std::to_string(stats.itlb_miss); }},
        {"DTLB命中", [&] { return std::to_string(stats.dtlb_hit); }},
        {"DTLB缺失", [&] { return std::to_string(stats.dtlb_miss); }},
        {"TLB回填", [&] { return std::to_string(stats.tlb_refill); }},
        {"FPU请求", [&] { return std::to_string(stats.fpu_req); }},
        {"FPU完成", [&] { return std::to_string(stats.fpu_done); }},
        {"AXI_AR", [&] { return std::to_string(stats.axi_ar); }},
        {"AXI_AW", [&] { return std::to_string(stats.axi_aw); }},
        {"AXI_R", [&] { return std::to_string(stats.axi_r); }},
        {"AXI_W", [&] { return std::to_string(stats.axi_w); }},
        {"AXI_B", [&] { return std::to_string(stats.axi_b); }},
        {"load指令", [&] { return std::to_string(stats.load_op); }},
        {"store指令", [&] { return std::to_string(stats.store_op); }},
        {"AMO指令", [&] { return std::to_string(stats.amo_op); }},
        {"分支taken", [&] { return std::to_string(stats.branch_taken); }},
        {"分支not-taken", [&] { return std::to_string(stats.branch_not_taken); }},
        {"TLB冲刷", [&] { return std::to_string(stats.tlb_flush); }},
        {"FPU忙碌周期", [&] { return std::to_string(stats.fpu_active_cycle); }},
        {"fence指令", [&] { return std::to_string(stats.fence_op); }},
        {"RVC指令", [&] { return std::to_string(stats.rvc_inst); }},
        {"system指令", [&] { return std::to_string(stats.system_op); }},
        {"ICache回填", [&] { return std::to_string(stats.icache_refill); }},
        {"DCache回填", [&] { return std::to_string(stats.dcache_refill); }},
        {"定时器中断", [&] { return std::to_string(stats.timer_irq); }},
        {"外部中断", [&] { return std::to_string(stats.external_irq); }},
        {"进入中断", [&] { return std::to_string(stats.interrupt_taken); }},
        {"写缓冲无空位", [&] { return std::to_string(stats.wbuf_full); }},
        {"写缓冲排空", [&] { return std::to_string(stats.wbuf_drain); }},
        {"CSR读", [&] { return std::to_string(stats.csr_read); }},
        {"CSR写", [&] { return std::to_string(stats.csr_write); }},
        {"乘法活跃周期", [&] { return std::to_string(stats.mul_active_cycle); }},
        {"除法活跃周期", [&] { return std::to_string(stats.div_active_cycle); }},
        {"PTW walk", [&] { return std::to_string(stats.ptw_walk); }},
        {"PTW活跃周期", [&] { return std::to_string(stats.ptw_walk_cycle); }},
        {"page fault", [&] { return std::to_string(stats.page_fault); }},
        {"RAS缺失", [&] { return std::to_string(stats.ras_miss); }},
        {"重定向branch", [&] { return std::to_string(stats.redirect_branch); }},
        {"重定向fence.i", [&] { return std::to_string(stats.redirect_fence_i); }},
        {"重定向trap", [&] { return std::to_string(stats.redirect_trap); }},
        {"重定向mret", [&] { return std::to_string(stats.redirect_mret); }},
        {"FPU等待周期", [&] { return std::to_string(stats.fpu_stall_cycle); }},
        {"非法指令", [&] { return std::to_string(stats.illegal_inst); }},
        {"AXI_stall", [&] { return std::to_string(stats.axi_stall_cycle); }},
        {"AXI_error", [&] { return std::to_string(stats.axi_error); }},
        {"FPU_DIVSQRT", [&] { return std::to_string(stats.fpu_divsqrt); }},
        {"FPU_FMA", [&] { return std::to_string(stats.fpu_fma); }},
        {"FPU异常", [&] { return std::to_string(stats.fpu_exception); }},
        {"CSR非法", [&] { return std::to_string(stats.csr_illegal); }},
        {"PLIC_claim", [&] { return std::to_string(stats.plic_claim); }},
        {"PLIC_complete", [&] { return std::to_string(stats.plic_complete); }},
        {"冲刷周期", [&] { return std::to_string(stats.flush_cycle); }},
        {"非缓存访问", [&] { return std::to_string(stats.uncached_access); }},
        {"写缓冲占用周期和", [&] { return std::to_string(stats.wbuf_occupancy_cycle); }},
        {"TLB超级页命中", [&] { return std::to_string(stats.tlb_superpage_hit); }},
        {"TLB_ASID命中", [&] { return std::to_string(stats.tlb_asid_hit); }},
        {"ICache行填充", [&] { return std::to_string(stats.icache_line_fill); }},
        {"DCache行填充", [&] { return std::to_string(stats.dcache_line_fill); }},
        {"DCache写直达更新", [&] { return std::to_string(stats.dcache_writeback); }},
        {"中断pending周期", [&] { return std::to_string(stats.interrupt_pending_cycle); }},
        {"中断延迟周期和", [&] { return std::to_string(stats.interrupt_latency_cycle); }},
        {"中断最大延迟", [&] { return std::to_string(stats.interrupt_latency_max); }},
        {"中断延迟样本数", [&] { return std::to_string(stats.interrupt_latency_count); }},
        {"WFI指令", [&] { return std::to_string(stats.wfi_inst); }},
        {"发射槽占用周期", [&] { return std::to_string(stats.issue_occupancy_cycle); }},
        {"退休槽占用周期", [&] { return std::to_string(stats.retire_occupancy_cycle); }},
        {"EXU气泡周期", [&] { return std::to_string(stats.bubble_cycle); }},
        {"除法实际迭代位数", [&] { return std::to_string(stats.div_iterations); }},
        {"除法提前结束", [&] { return std::to_string(stats.div_early_out); }},
    };

    // 表头与数据行共用同一张 descriptor，避免两处列顺序漂移。
    std::vector<std::string_view> csv_header;
    std::vector<std::string> csv_row;
    csv_header.reserve(csv_fields.size());
    csv_row.reserve(csv_fields.size());
    for (const auto &field : csv_fields)
    {
        csv_header.push_back(field.name);
        csv_row.push_back(field.value());
    }

    auto csv_file{
#ifdef VRISCV32E_NPC
        result_dir / (std::string{"result_npc_"} + std::string{npc::PDK} + ".csv")
#else
        result_dir / (std::string{"result_ysyxsoc_"} + std::string{npc::PDK} + ".csv")
#endif
    };

    // 先写临时目录再 rename，避免留下半截 CSV。
    const auto staging_dir{CreateUniqueDirectory(
        result_dir, ".npc-result-tmp", "无法创建性能结果临时目录")};

    const auto staging_file{staging_dir / csv_file.filename()};
    try
    {
        {
            auto out{std::ofstream{staging_file.string(), std::ios::binary}};
            if (!out.is_open())
            {
                throw std::runtime_error{
                    std::format("无法打开性能结果文件 '{}'", csv_file.string())};
            }
            out << "\xEF\xBB\xBF";
            WriteCsvLine(out, csv_header);
            WriteCsvLine(out, csv_row);
            out.close();
            if (!out)
            {
                throw std::runtime_error{
                    std::format("无法写入性能结果文件 '{}'", csv_file.string())};
            }
        }
        std::filesystem::rename(staging_file, csv_file);
    }
    catch (...)
    {
        std::error_code ignored;
        std::filesystem::remove_all(staging_dir, ignored);
        throw;
    }
    {
        std::error_code ignored;
        std::filesystem::remove(staging_dir, ignored);
        if (std::filesystem::exists(staging_dir, ignored))
        {
            std::println(std::cerr, "警告：无法清理性能结果临时目录 '{}'",
                         staging_dir.string());
        }
    }

    std::println("");
    std::println("单次记录: {}", csv_file.string());
}
