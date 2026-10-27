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
    for (std::size_t suffix{0};; ++suffix)
    {
        const auto directory_name{
            suffix == 0 ? timestamp : std::format("{}-{}", timestamp, suffix)};
        const auto result_dir{result_root / directory_name};
        std::error_code error;
        if (std::filesystem::create_directory(result_dir, error))
        {
            return result_dir;
        }
        if (error == std::errc::file_exists)
        {
            continue;
        }
        if (error)
        {
            throw std::filesystem::filesystem_error{
                "无法创建默认性能结果目录", result_dir, error};
        }
    }
}

void NPCSimResult::Save(
    std::filesystem::path result_dir,
    const PerfStats &stats,
    std::size_t total_cycles,
    std::size_t total_instructions)
{
    constexpr unsigned schema_version{3};
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

    const std::vector<std::string_view> csv_header{
        "schema_version", "commit", "git_dirty", "config_sha256",
        "config_summary", "rtl_target", "rtl_sha256", "pdk",
        "synth_metadata_valid", "说明", "仿真周期数", "退休指令数", "IPC",
        "综合目标频率(MHz)", "综合面积(um^2)",
        "IFU交付响应", "EXU输出握手", "LSU读完成", "LSU写完成",
        "ALU指令", "访存指令", "CSR指令", "分支跳转总数", "条件分支",
        "JAL", "JALR", "已分类EXU操作", "访存类EXU驻留摊销",
        "IFU响应反压", "ICache_refill_AR_R状态合计", "ICache_refill_AR状态",
        "ICache_refill_R状态", "控制流重定向事件", "IFU空闲",
        "EXU下游或顺序等待(已排除MDU计算)", "LSU读FSM占用摊销",
        "LSU写FSM占用摊销", "LSU读请求状态", "LSU读响应状态",
        "写缓冲无空位", "写响应在途", "ICache命中", "ICache缺失",
        "ICache_refill平均服务周期", "ICache_AMAT估算", "DCache_load命中",
        "DCache_可缓存load缺失", "DCache_refill_AR周期", "DCache_refill_R周期",
        "DCache_refill平均服务周期", "DCache_AMAT估算", "IDU_RAW阻塞",
        "IDU_RAW_loaduse", "IDU_RAW_非load未就绪", "EXU空转等输入",
        "异常提交", "EX/MEM等待槽占用", "MDU请求", "MDU完成", "MUL",
        "MULH", "MULHSU", "MULHU", "DIV", "DIVU", "REM", "REMU",
        "MDU发射或在途活动周期", "MDU等待计算结果周期"};
    const std::vector<std::string> csv_row{
        std::to_string(schema_version), commit, git_dirty, config_sha256,
        config_summary, std::string{rtl_target}, rtl_sha256, pdk,
        synth_metadata_valid ? "1" : "0", message,
        std::to_string(total_cycles), std::to_string(total_instructions),
        std::format("{:.4f}", ipc), freq, area,
        std::to_string(stats.instruction_fetch),
        std::to_string(stats.execution_complete),
        std::to_string(stats.load_data), std::to_string(stats.store_data),
        std::to_string(stats.arithmetic_operation),
        std::to_string(stats.memory_access_operation),
        std::to_string(stats.control_status_register_operation),
        std::to_string(stats.branch_operation),
        std::to_string(ConditionalBranchCount(stats)),
        std::to_string(stats.jal_operation), std::to_string(stats.jalr_operation),
        std::to_string(ClassifiedInstructionCount(stats)),
        std::format("{:.2f}", mem_exu_residency_avg),
        std::to_string(stats.instruction_fetch_stall_pipeline),
        std::to_string(stats.instruction_fetch_stall_axi),
        std::to_string(stats.instruction_fetch_stall_ar),
        std::to_string(stats.instruction_fetch_stall_r),
        std::to_string(stats.instruction_fetch_stall_redirect),
        std::to_string(stats.instruction_fetch_stall_idle),
        std::to_string(stats.exu_stall_lsu), std::format("{:.2f}", load_avg),
        std::format("{:.2f}", store_avg), std::to_string(stats.lsu_stall_read_ar),
        std::to_string(stats.lsu_stall_read_r),
        std::to_string(stats.lsu_stall_write_req),
        std::to_string(stats.lsu_stall_write_b), std::to_string(stats.icache_hit),
        std::to_string(stats.icache_miss),
        std::format("{:.2f}", icache.miss_service_cycle),
        std::format("{:.2f}", icache.amat), std::to_string(stats.dcache_hit),
        std::to_string(stats.dcache_miss),
        std::to_string(stats.dcache_refill_req_cycle),
        std::to_string(stats.dcache_refill_resp_cycle),
        std::format("{:.2f}", dcache.miss_service_cycle),
        std::format("{:.2f}", dcache.amat), std::to_string(stats.idu_stall_raw),
        std::to_string(stats.idu_stall_raw_loaduse),
        std::to_string(stats.idu_stall_raw_alu),
        std::to_string(stats.exu_idle_noinput), std::to_string(stats.trap_count),
        std::to_string(stats.mem_waitslot), std::to_string(stats.mdu_request),
        std::to_string(stats.mdu_complete), std::to_string(stats.mdu_mul),
        std::to_string(stats.mdu_mulh), std::to_string(stats.mdu_mulhsu),
        std::to_string(stats.mdu_mulhu), std::to_string(stats.mdu_div),
        std::to_string(stats.mdu_divu), std::to_string(stats.mdu_rem),
        std::to_string(stats.mdu_remu), std::to_string(stats.mdu_active_cycle),
        std::to_string(stats.mdu_wait_cycle)};
    if (csv_header.size() != csv_row.size())
        throw std::logic_error{"性能 CSV 表头与数据列数不一致"};

    auto csv_file{
#ifdef VRISCV32E_NPC
        result_dir / (std::string{"result_npc_"} + std::string{npc::PDK} + ".csv")
#else
        result_dir / (std::string{"result_ysyxsoc_"} + std::string{npc::PDK} + ".csv")
#endif
    };

    // 先在同一文件系统的独占临时目录中完整写入，然后再 rename。
    // 这样 ENOSPC/关闭失败不会把半截 CSV 留成正式结果。
    std::filesystem::path staging_dir;
    for (std::size_t suffix{0};; ++suffix)
    {
        const auto directory_name{suffix == 0
            ? std::string{".npc-result-tmp"}
            : std::format(".npc-result-tmp-{}", suffix)};
        const auto candidate{result_dir / directory_name};
        std::error_code error;
        if (std::filesystem::create_directory(candidate, error))
        {
            staging_dir = candidate;
            break;
        }
        if (error == std::errc::file_exists || !error)
        {
            continue;
        }
        throw std::filesystem::filesystem_error{
            "无法创建性能结果临时目录", candidate, error};
    }

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
            std::println(
                std::cerr,
                "警告：无法清理性能结果临时目录 '{}'",
                staging_dir.string());
        }
    }

    std::println("");
    std::println("单次记录: {}", csv_file.string());
}
