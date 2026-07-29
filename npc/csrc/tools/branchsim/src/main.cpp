module;
#include <cstdio>

import std;
import BPConfig;
import BPAlgorithmsType;
import BTraceReader;
import BranchRecord;
import Run;
import stats;
int main(int argc, char const *argv[])
{
    if (argc < 2)
    {
        std::println(stderr, "用法: {} <btrace文件>", argv[0]);
        return 1;
    }
    auto config = BPConfig{};
    auto algos = BPAlgorithmsType{config};
    auto reader = BTraceReader{argv[1]};
    std::vector<BranchRecord> trace;
    while (auto record = reader.next())
    {
        trace.push_back(*record);
    }
    auto table = Run(algos, trace);
    PrintTable(table);
    WriteCSV(table, "TestResult");
    return 0;
}