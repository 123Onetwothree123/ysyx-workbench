module;
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
    if (argc < 2)
    {
        std::println(stderr, "用法: {} <btrace文件>", argv[0]);
        return 1;
    }
    BTraceReader reader{argv[1]};
    std::vector<BranchRecord> trace{};
    while (auto record{reader.next()})
    {
        trace.push_back(*record);
    }
#if RUN_MODE == 1
    // DSE 模式: 穷举 分支BTB × jalBTB 参数空间, 线程池并行评估所有算法
    const std::size_t bits_list[]{2, 3, 4, 5, 6};
    const std::size_t ways_list[]{1, 2, 4};
    auto table{RunDse(trace, bits_list, ways_list, bits_list, ways_list)};
    WriteDseCSV(table, std::filesystem::path{BRANCHSIM_DIR} / "TestResult",
                std::filesystem::path{argv[1]}.stem().string());
#else
    // 手动模式: 用 Kconfig 配置的参数对比所有算法
    BPConfig config{};
    BPAlgorithmsType algos{config};
    auto table{Run(algos, trace)};
    PrintTable(table);
    // CSV文件名带trace名, 支持一次评估多个benchmark的trace不互相覆盖
    WriteCSV(table, "TestResult", std::filesystem::path{argv[1]}.stem().string());
#endif
    return 0;
}
