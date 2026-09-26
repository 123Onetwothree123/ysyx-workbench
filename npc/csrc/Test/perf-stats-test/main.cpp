import std;
import npc.PerfStats;
import npc.NPCSimResult;

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
}

int main()
{
    PerfStats event_stats{};
    RecordExecutionEvent(event_stats, 4);
    RecordExecutionEvent(event_stats, 5);
    RecordExecutionEvent(event_stats, 6);
    check(event_stats.branch_operation == 3,
          "branch total must include conditional branch, JAL, and JALR");
    check(event_stats.jal_operation == 1 && event_stats.jalr_operation == 1,
          "JAL and JALR subset counters");
    check(ConditionalBranchCount(event_stats) == 1,
          "conditional branch subset after event classification");

    PerfStats stats{};
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
    const auto result_dir{std::filesystem::path{"build/csv-result"}};
    std::filesystem::remove_all(result_dir);
    NPCSimResult::Save(result_dir, event_stats, 100, 25);
    const auto csv_path{result_dir / "result_ysyxsoc_CONFIG_PDK.csv"};
    std::ifstream csv{csv_path, std::ios::binary};
    std::string header;
    std::string row;
    std::getline(csv, header);
    std::getline(csv, row);
    check(csv.good() || csv.eof(), "CSV result must be readable");
    check(header.contains("schema_version") &&
              header.contains("综合目标频率(MHz)"),
          "CSV schema must identify its version and target-frequency semantics");
    check(std::ranges::count(header, ',') == std::ranges::count(row, ','),
          "CSV header and data row must contain the same number of fields");
    check(row.starts_with("2,"), "CSV row must use schema version 2");

    if (failures != 0)
        return 1;
    std::println("[PASS] performance statistic derivations");
    return 0;
}
