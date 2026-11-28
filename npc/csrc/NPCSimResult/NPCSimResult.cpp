module npc.NPCSimResult;
import std;
import npc.config;
import npc.PerfCounterDescriptors;

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
    // 计数器列的名字、顺序和值都由 npc.PerfCounterDescriptors 的同一张
    // descriptor 表提供，这里只保留元数据/派生列和列序。
    const auto counter = [&stats](std::size_t DicArchCounterStats::*field) {
        const auto &descriptor{npc::CounterDescriptorFor(field)};
        return CsvField{
            descriptor.csv_label,
            [&stats, field, format = descriptor.csv_format]() -> std::string {
                if (format == npc::CounterCsvFormat::Fixed2)
                {
                    return std::format(
                        "{:.2f}", static_cast<double>(stats.*field));
                }
                return std::to_string(stats.*field);
            }};
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
        counter(&DicArchCounterStats::instruction_fetch),
        counter(&DicArchCounterStats::execution_complete),
        counter(&DicArchCounterStats::load_data),
        counter(&DicArchCounterStats::store_data),
        counter(&DicArchCounterStats::arithmetic_operation),
        counter(&DicArchCounterStats::memory_access_operation),
        counter(&DicArchCounterStats::control_status_register_operation),
        counter(&DicArchCounterStats::branch_operation),
        {"条件分支", [&] { return std::to_string(ConditionalBranchCount(stats)); }},
        counter(&DicArchCounterStats::jal_operation),
        counter(&DicArchCounterStats::jalr_operation),
        {"已分类EXU操作", [&] { return std::to_string(ClassifiedInstructionCount(stats)); }},
        {"访存类EXU驻留摊销", [&] { return std::format("{:.2f}", mem_exu_residency_avg); }},
        counter(&DicArchCounterStats::instruction_fetch_stall_pipeline),
        counter(&DicArchCounterStats::instruction_fetch_stall_axi),
        counter(&DicArchCounterStats::instruction_fetch_stall_ar),
        counter(&DicArchCounterStats::instruction_fetch_stall_r),
        counter(&DicArchCounterStats::instruction_fetch_stall_redirect),
        counter(&DicArchCounterStats::instruction_fetch_stall_idle),
        counter(&DicArchCounterStats::exu_stall_lsu),
        {"LSU读FSM占用摊销", [&] { return std::format("{:.2f}", load_avg); }},
        {"LSU写FSM占用摊销", [&] { return std::format("{:.2f}", store_avg); }},
        counter(&DicArchCounterStats::lsu_stall_read_ar),
        counter(&DicArchCounterStats::lsu_stall_read_r),
        counter(&DicArchCounterStats::lsu_stall_write_req),
        counter(&DicArchCounterStats::lsu_stall_write_b),
        counter(&DicArchCounterStats::icache_hit),
        counter(&DicArchCounterStats::icache_miss),
        {"ICache_refill平均服务周期", [&] { return std::format("{:.2f}", icache.miss_service_cycle); }},
        {"ICache_AMAT估算", [&] { return std::format("{:.2f}", icache.amat); }},
        counter(&DicArchCounterStats::dcache_hit),
        counter(&DicArchCounterStats::dcache_miss),
        counter(&DicArchCounterStats::dcache_refill_req_cycle),
        counter(&DicArchCounterStats::dcache_refill_resp_cycle),
        {"DCache_refill平均服务周期", [&] { return std::format("{:.2f}", dcache.miss_service_cycle); }},
        {"DCache_AMAT估算", [&] { return std::format("{:.2f}", dcache.amat); }},
        counter(&DicArchCounterStats::idu_stall_raw),
        counter(&DicArchCounterStats::idu_stall_raw_loaduse),
        counter(&DicArchCounterStats::idu_stall_raw_alu),
        counter(&DicArchCounterStats::exu_idle_noinput),
        counter(&DicArchCounterStats::trap_count),
        counter(&DicArchCounterStats::mem_waitslot),
        counter(&DicArchCounterStats::mdu_request),
        counter(&DicArchCounterStats::mdu_complete),
        counter(&DicArchCounterStats::mdu_mul),
        counter(&DicArchCounterStats::mdu_mulh),
        counter(&DicArchCounterStats::mdu_mulhsu),
        counter(&DicArchCounterStats::mdu_mulhu),
        counter(&DicArchCounterStats::mdu_div),
        counter(&DicArchCounterStats::mdu_divu),
        counter(&DicArchCounterStats::mdu_rem),
        counter(&DicArchCounterStats::mdu_remu),
        counter(&DicArchCounterStats::mdu_active_cycle),
        counter(&DicArchCounterStats::mdu_wait_cycle),
        counter(&DicArchCounterStats::btb_hit),
        counter(&DicArchCounterStats::btb_miss),
        counter(&DicArchCounterStats::jal_btb_hit),
        counter(&DicArchCounterStats::ras_hit),
        counter(&DicArchCounterStats::branch_mispredict),
        counter(&DicArchCounterStats::itlb_hit),
        counter(&DicArchCounterStats::itlb_miss),
        counter(&DicArchCounterStats::dtlb_hit),
        counter(&DicArchCounterStats::dtlb_miss),
        counter(&DicArchCounterStats::tlb_refill),
        counter(&DicArchCounterStats::fpu_req),
        counter(&DicArchCounterStats::fpu_done),
        counter(&DicArchCounterStats::axi_ar),
        counter(&DicArchCounterStats::axi_aw),
        counter(&DicArchCounterStats::axi_r),
        counter(&DicArchCounterStats::axi_w),
        counter(&DicArchCounterStats::axi_b),
        counter(&DicArchCounterStats::load_op),
        counter(&DicArchCounterStats::store_op),
        counter(&DicArchCounterStats::amo_op),
        counter(&DicArchCounterStats::branch_taken),
        counter(&DicArchCounterStats::branch_not_taken),
        counter(&DicArchCounterStats::tlb_flush),
        counter(&DicArchCounterStats::fpu_active_cycle),
        counter(&DicArchCounterStats::fence_op),
        counter(&DicArchCounterStats::rvc_inst),
        counter(&DicArchCounterStats::system_op),
        counter(&DicArchCounterStats::icache_refill),
        counter(&DicArchCounterStats::dcache_refill),
        counter(&DicArchCounterStats::timer_irq),
        counter(&DicArchCounterStats::external_irq),
        counter(&DicArchCounterStats::interrupt_taken),
        counter(&DicArchCounterStats::wbuf_full),
        counter(&DicArchCounterStats::wbuf_drain),
        counter(&DicArchCounterStats::csr_read),
        counter(&DicArchCounterStats::csr_write),
        counter(&DicArchCounterStats::mul_active_cycle),
        counter(&DicArchCounterStats::div_active_cycle),
        counter(&DicArchCounterStats::ptw_walk),
        counter(&DicArchCounterStats::ptw_walk_cycle),
        counter(&DicArchCounterStats::page_fault),
        counter(&DicArchCounterStats::ras_miss),
        counter(&DicArchCounterStats::redirect_branch),
        counter(&DicArchCounterStats::redirect_fence_i),
        counter(&DicArchCounterStats::redirect_trap),
        counter(&DicArchCounterStats::redirect_mret),
        counter(&DicArchCounterStats::fpu_stall_cycle),
        counter(&DicArchCounterStats::illegal_inst),
        counter(&DicArchCounterStats::axi_stall_cycle),
        counter(&DicArchCounterStats::axi_error),
        counter(&DicArchCounterStats::fpu_divsqrt),
        counter(&DicArchCounterStats::fpu_fma),
        counter(&DicArchCounterStats::fpu_exception),
        counter(&DicArchCounterStats::csr_illegal),
        counter(&DicArchCounterStats::plic_claim),
        counter(&DicArchCounterStats::plic_complete),
        counter(&DicArchCounterStats::flush_cycle),
        counter(&DicArchCounterStats::uncached_access),
        counter(&DicArchCounterStats::wbuf_occupancy_cycle),
        counter(&DicArchCounterStats::tlb_superpage_hit),
        counter(&DicArchCounterStats::tlb_asid_hit),
        counter(&DicArchCounterStats::icache_line_fill),
        counter(&DicArchCounterStats::dcache_line_fill),
        counter(&DicArchCounterStats::dcache_writeback),
        counter(&DicArchCounterStats::interrupt_pending_cycle),
        counter(&DicArchCounterStats::interrupt_latency_cycle),
        counter(&DicArchCounterStats::interrupt_latency_max),
        counter(&DicArchCounterStats::interrupt_latency_count),
        counter(&DicArchCounterStats::wfi_inst),
        counter(&DicArchCounterStats::issue_occupancy_cycle),
        counter(&DicArchCounterStats::retire_occupancy_cycle),
        counter(&DicArchCounterStats::bubble_cycle),
        counter(&DicArchCounterStats::div_iterations),
        counter(&DicArchCounterStats::div_early_out),
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
