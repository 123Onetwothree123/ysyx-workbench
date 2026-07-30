export module Run;
import std;
import BPAlgorithmBase;
import BPAlgorithmsType;
import BranchRecord;
import stats;

// 单算法跑整条trace, 返回统计(DSE和手动模式共用)
export auto RunOne(BPAlgorithmBase& algo,
                   std::span<const BranchRecord> trace) -> stats;

export auto Run(const BPAlgorithmsType& algos,
                std::span<const BranchRecord> trace)
    -> std::vector<std::pair<std::string_view, stats>>;
