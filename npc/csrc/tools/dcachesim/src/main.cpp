module;
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
    if (argc < 2)
    {
        std::println(stderr, "用法: {} <dtrace文件>", argv[0]);
        return 1;
    }
    DTraceReader reader{argv[1]};
    std::vector<AccessRecord> trace{};
    while (auto record{reader.next()})
    {
        trace.push_back(*record);
    }
#if RUN_MODE == 1
    // DSE 模式: 扫描参数空间, 每组配置多线程对比三种替换策略
    const std::size_t block_sizes[]{4, 8, 16, 32, 64, 128};
    const std::size_t num_blocks_list[]{4, 8, 16, 32, 64, 128, 256, 512};
    const std::size_t ways_list[]{1, 2, 4, 8};
    auto table{RunDse(trace, block_sizes, num_blocks_list, ways_list)};
    WriteDseCSV(table, std::filesystem::path{DCACHESIM_DIR} / "TestResult");
#else
    // 手动模式: 用 Kconfig 配置的参数对比三种替换策略
    DCacheConfig config{};
    DCachesType caches{config};
    auto table{Run(caches, trace)};
    PrintTable(table);
    WriteCSV(table, "TestResult");
#endif
    return 0;
}
