#include "common/CliOptions.hpp"
#include <cstdio>

import std;
import BPConfig;
import BPAlgorithmsType;
import BTraceReader;
import BranchRecord;
import Run;
import dse;
import stats;
int main(int argc, char const *argv[])
{
    common::CliOptions cli{argv[0], "<btrace文件>"};
    int exit_code{};
    if (!cli.Parse(argc, argv, exit_code))
    {
        return exit_code;
    }
    if (cli.Positional().empty())
    {
        cli.PrintUsage(stderr);
        return 1;
    }
    const std::string trace_path{cli.Positional().front()};
    try
    {
        BTraceReader reader{trace_path};
        std::vector<BranchRecord> trace{};
        while (auto record{reader.next()})
        {
            trace.push_back(*record);
        }
#if RUN_MODE == 1
        // DSE：穷举参数空间并行评估。
        const std::size_t bits_list[]{2, 3, 4, 5, 6};
        const std::size_t ways_list[]{1, 2, 4};
        const std::size_t ras_bits_list[]{2, 3, 4, 5};
        auto table{RunDse(trace, bits_list, ways_list, bits_list, ways_list, ras_bits_list)};
        WriteDseCSV(table, std::filesystem::path{BRANCHSIM_DIR} / "TestResult",
                    std::filesystem::path{trace_path}.stem().string());
#else
        // 手动：用 Kconfig 参数对比所有算法。
        BPConfig config{};
        BPAlgorithmsType algos{config};
        auto table{Run(algos, trace)};
        PrintTable(table);
        // CSV 名带 trace 名，避免多 benchmark 覆盖。
        WriteCSV(table, "TestResult", std::filesystem::path{trace_path}.stem().string());
#endif
    }
    catch (const std::exception &e)
    {
        std::println(stderr, "错误: {}", e.what());
        return 1;
    }
    return 0;
}
