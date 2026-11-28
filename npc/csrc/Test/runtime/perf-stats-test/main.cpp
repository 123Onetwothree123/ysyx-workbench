import std;
import npc.DicArchCounterStats;
import npc.config;
import npc.NPCSimResult;
import npc.PerfCounterDescriptors;

namespace
{
bool close(double lhs, double rhs)
{
    return std::abs(lhs - rhs) < 1.0e-12;
}

int failures{0};

void check(bool condition, std::string_view message)
{
    if (condition)
        return;
    ++failures;
    std::println(std::cerr, "[FAIL] {}", message);
}

std::vector<std::string> parse_csv(std::string_view line)
{
    std::vector<std::string> fields;
    std::string field;
    bool quoted{false};
    for (std::size_t index{0}; index < line.size(); ++index)
    {
        const auto character{line[index]};
        if (quoted)
        {
            if (character == '"' && index + 1 < line.size() &&
                line[index + 1] == '"')
            {
                field.push_back('"');
                ++index;
            }
            else if (character == '"')
            {
                quoted = false;
            }
            else
            {
                field.push_back(character);
            }
        }
        else if (character == '"')
        {
            quoted = true;
        }
        else if (character == ',')
        {
            fields.push_back(std::move(field));
            field.clear();
        }
        else
        {
            field.push_back(character);
        }
    }
    fields.push_back(std::move(field));
    return fields;
}

std::string_view value_for(
    const std::vector<std::string> &header,
    const std::vector<std::string> &row,
    std::string_view name)
{
    const auto found{std::ranges::find(header, name)};
    if (found == header.end())
        return {};
    const auto index{static_cast<std::size_t>(found - header.begin())};
    return index < row.size() ? std::string_view{row[index]} : std::string_view{};
}
}

int main(int argc, char **argv)
{
    if (argc != 4 || (std::string_view{argv[1]} != "valid" &&
                      std::string_view{argv[1]} != "stale") ||
        (std::string_view{argv[3]} != "soc" &&
         std::string_view{argv[3]} != "npc"))
    {
        std::println(
            std::cerr, "usage: {} valid|stale RESULT_DIR soc|npc", argv[0]);
        return 2;
    }
    const bool expect_valid_synth{std::string_view{argv[1]} == "valid"};
    const std::string_view expected_target{argv[3]};
    DicArchCounterStats event_stats{};
    RecordExecutionEvent(event_stats, 4);
    RecordExecutionEvent(event_stats, 5);
    RecordExecutionEvent(event_stats, 6);
    check(event_stats.branch_operation == 3,
          "branch total must include conditional branch, JAL, and JALR");
    check(event_stats.jal_operation == 1 && event_stats.jalr_operation == 1,
          "JAL and JALR subset counters");
    check(ConditionalBranchCount(event_stats) == 1,
          "conditional branch subset after event classification");

    DicArchCounterStats stats{};
    stats.arithmetic_operation = 2;
    stats.mdu_complete = 3;
    stats.memory_access_operation = 5;
    stats.control_status_register_operation = 7;
    stats.branch_operation = 13;
    stats.jal_operation = 2;
    stats.jalr_operation = 3;

    check(ConditionalBranchCount(stats) == 8,
          "conditional branch count must exclude JAL and JALR exactly once");
    check(ClassifiedInstructionCount(stats) == 30,
          "classified instruction count must include the branch/jump total");

    // Malformed/legacy input must not wrap size_t and print a huge count.
    stats.branch_operation = 1;
    stats.jal_operation = 2;
    stats.jalr_operation = 3;
    check(ConditionalBranchCount(stats) == 0,
          "conditional branch count must saturate instead of underflowing");

    const auto no_access{EstimateCachePerformance(0, 0, 99)};
    check(no_access.access_count == 0 && close(no_access.amat, 0.0),
          "AMAT must be undefined/zero when there are no accesses");

    const auto all_hit{EstimateCachePerformance(16, 0, 0)};
    check(all_hit.access_count == 16, "all-hit access count");
    check(close(all_hit.hit_rate, 1.0), "all-hit rate");
    check(close(all_hit.miss_service_cycle, 0.0), "all-hit miss service time");
    check(close(all_hit.amat, 1.0),
          "zero-miss AMAT must retain the one-cycle hit baseline");

    const auto mixed{EstimateCachePerformance(9, 1, 20)};
    check(close(mixed.hit_rate, 0.9), "mixed hit rate");
    check(close(mixed.miss_service_cycle, 20.0), "miss service average");
    check(close(mixed.amat, 3.0),
          "AMAT must be hit latency plus miss rate times miss service time");

    // Build this test without optional cache/MDU macros as well.  This catches
    // conditional CSV columns whose header and row used to differ by one field.
    const auto result_dir{std::filesystem::path{argv[2]}};
    std::filesystem::remove_all(result_dir);
    NPCSimResult::Save(result_dir, event_stats, 100, 25);
    const auto csv_path{result_dir /
        (expected_target == "npc" ? "result_npc_testpdk.csv"
                                  : "result_ysyxsoc_testpdk.csv")};
    std::ifstream csv{csv_path, std::ios::binary};
    std::string header;
    std::string row;
    std::getline(csv, header);
    std::getline(csv, row);
    check(csv.good() || csv.eof(), "CSV result must be readable");
    if (header.starts_with("\xEF\xBB\xBF"))
        header.erase(0, 3);
    const auto header_fields{parse_csv(header)};
    const auto row_fields{parse_csv(row)};
    check(header.contains("schema_version") && header.contains("rtl_sha256") &&
              header.contains("config_sha256") &&
              header.contains("综合目标频率(MHz)"),
          "CSV schema must identify its version and target-frequency semantics");
    check(header_fields.size() == row_fields.size(),
          "CSV header and data row must contain the same number of fields");
    check(header_fields.size() == 143,
          "CSV schema must keep a fixed 143 fields with optional RTL features off or on");
    check(value_for(header_fields, row_fields, "schema_version") == "9",
          "CSV row must use schema version 9");
    check(value_for(header_fields, row_fields, "commit") == "abc123def456" &&
              value_for(header_fields, row_fields, "git_dirty") == "1",
          "CSV must preserve git commit and dirty status");
    check(value_for(header_fields, row_fields, "config_sha256") ==
                  "config-current" &&
              value_for(header_fields, row_fields, "config_summary") ==
                  "ICACHE=n;DCACHE=n;MUL_OFFSETS=0,1" &&
              value_for(header_fields, row_fields, "rtl_target") ==
                  expected_target &&
              value_for(header_fields, row_fields, "rtl_sha256") ==
                  (expected_target == "npc" ? "npc-rtl-current"
                                            : "rtl-current"),
          "CSV must preserve effective config and current target RTL identity");
    check(value_for(header_fields, row_fields, "说明") ==
                  "custom,metadata \"message\"",
          "CSV quoting must preserve commas and quotes in metadata");
    check(value_for(header_fields, row_fields, "synth_metadata_valid") ==
                  (expect_valid_synth ? "1" : "0"),
          "synthesis metadata validity must be explicit");
    check(value_for(header_fields, row_fields, "综合目标频率(MHz)") ==
                  (expect_valid_synth ? "777" : "0") &&
              value_for(header_fields, row_fields, "综合面积(um^2)") ==
                  (expect_valid_synth ? "123.5" : "0"),
          "stale synthesis metadata must not be attributed to the current RTL");


    // ---- 单一 descriptor 表的一致性检查 -------------------------------
    // 新增计数器必须同时声明文本/CSV 路由；漏掉任一路由会让下面的
    // coverage 检查失败，而不是静默地只采样不输出。
    check(npc::CounterDescriptorCount == 127,
          "counter descriptor table must cover every statistics field");
    {
        std::set<std::string_view> names;
        std::map<std::string_view, int> expected_csv_labels;
        for (std::size_t index{0}; index < npc::CounterDescriptorCount; ++index)
        {
            const auto &descriptor{npc::CounterDescriptors[index]};
            check(!descriptor.name.empty(), "descriptor name must not be empty");
            check(names.insert(descriptor.name).second,
                  "descriptor names must be unique");
            check(descriptor.field != nullptr,
                  "descriptor field pointer must not be null");
            for (std::size_t other{0}; other < index; ++other)
            {
                check(npc::CounterDescriptors[other].field != descriptor.field,
                      "descriptor fields must be unique");
            }
            switch (descriptor.reporting)
            {
            case npc::CounterReporting::TextAndCsv:
                check(descriptor.print_group != npc::PrintGroup::None &&
                          !descriptor.print_label.empty() &&
                          !descriptor.csv_label.empty(),
                      "TextAndCsv descriptor must declare both routes");
                break;
            case npc::CounterReporting::TextOnly:
                check(descriptor.print_group != npc::PrintGroup::None &&
                          !descriptor.print_label.empty() &&
                          descriptor.csv_label.empty(),
                      "TextOnly descriptor must declare only the text route");
                break;
            case npc::CounterReporting::InternalOnly:
                check(descriptor.print_group == npc::PrintGroup::None &&
                          descriptor.print_label.empty() &&
                          descriptor.csv_label.empty(),
                      "InternalOnly descriptor must not declare an output route");
                break;
            }
            if (!descriptor.csv_label.empty())
                ++expected_csv_labels[descriptor.csv_label];
        }

        // 读满所有计数器，触发派生行、条件行和 cache 行。
        DicArchCounterStats full{};
        for (std::size_t index{0}; index < npc::CounterDescriptorCount; ++index)
        {
            full.*npc::CounterDescriptors[index].field = index + 7;
        }
        const auto report{
            npc::FormatDicArchCounterStatistics(full, 1000, 500)};
        std::string text;
        for (const auto &line : report.lines)
        {
            text += line;
            text += '\n';
        }
        check(text.starts_with(
                  "数字集成电路计算机体系结构性能计数器\n退休指令: 500\n"),
              "text report must keep its title and retired-instruction line");
        check(text.contains(std::format(
                  "  BTB命中/缺失: {} / {}\n", full.btb_hit, full.btb_miss)),
              "paired text lines must keep the 'a / b' format");
        check(text.contains(std::format(
                  "  AR/AW/R/W/B: {} / {} / {} / {} / {}\n", full.axi_ar,
                  full.axi_aw, full.axi_r, full.axi_w, full.axi_b)),
              "five-field text lines must keep order and separators");
        check(text.contains("分支预测:\n") && text.contains("MMU/TLB:\n") &&
                  text.contains("指令细分:\n"),
              "text group headers must stay in the report");
        if constexpr (npc::HasICache)
        {
            check(text.contains("ICache 性能:\n") &&
                      text.contains(std::format(
                          "  命中: {} 次\n", full.icache_hit)),
                  "ICache text section must be generated from descriptors");
        }
        if constexpr (npc::HasDCache)
        {
            check(text.contains("DCache 性能:\n"),
                  "DCache text section must be generated from descriptors");
        }
        for (std::size_t index{0}; index < npc::CounterDescriptorCount; ++index)
        {
            const auto &descriptor{npc::CounterDescriptors[index]};
            if (descriptor.reporting == npc::CounterReporting::InternalOnly)
                continue;
            const bool feature_off{
                (!npc::HasICache &&
                 descriptor.print_group == npc::PrintGroup::ICache) ||
                (!npc::HasDCache &&
                 descriptor.print_group == npc::PrintGroup::DCache) ||
                (!npc::HasM &&
                 descriptor.name.starts_with("mdu_"))};
            if (!feature_off)
            {
                check(report.printed[index],
                      std::format(
                          "descriptor '{}' is sampled but never printed",
                          descriptor.name));
            }
        }

        // CSV 表头必须为每个非空 csv_label 出现预期次数（允许基线里
        // 两个计数器共用同一个列名）。
        std::map<std::string_view, int> actual_csv_labels;
        for (const auto &field : header_fields)
            ++actual_csv_labels[field];
        for (const auto &[label, count] : expected_csv_labels)
        {
            check(actual_csv_labels[label] == count,
                  std::format(
                      "CSV column '{}' must appear {} time(s)", label, count));
        }
    }

    if (failures != 0)
        return 1;
    std::println("[PASS] performance statistic derivations");
    return 0;
}
