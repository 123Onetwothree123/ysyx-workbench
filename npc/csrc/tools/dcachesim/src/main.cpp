#include "common/CliOptions.hpp"
#include <cstdio>

import std;
import DCacheConfig;
import DCachesType;
import DTraceReader;
import AccessRecord;
import Run;
import dse;
import stats;
int main(int argc, char const* argv[])
{
    common::CliOptions cli{argv[0], "<dtrace文件>"};
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
        DTraceReader reader{trace_path};
        std::vector<AccessRecord> trace{};
        while (auto record{reader.next()})
        {
            trace.push_back(*record);
        }
#if RUN_MODE == 1
        // DSE：扫描参数空间并对比三种策略。
        const std::size_t block_sizes[]{4, 8, 16, 32, 64, 128};
        const std::size_t num_blocks_list[]{4, 8, 16, 32, 64, 128, 256, 512};
        const std::size_t ways_list[]{1, 2, 4, 8};
        auto table{RunDse(trace, block_sizes, num_blocks_list, ways_list)};
        WriteDseCSV(table, std::filesystem::path{DCACHESIM_DIR} / "TestResult");
#else
        // 手动：用 Kconfig 参数对比三种策略。
        DCacheConfig config{};
        DCachesType caches{config};
        auto table{Run(caches, trace)};
        PrintTable(table);
        WriteCSV(table, "TestResult");
#endif
    }
    catch (const std::exception& e)
    {
        std::println(stderr, "错误: {}", e.what());
        return 1;
    }
    return 0;
}
