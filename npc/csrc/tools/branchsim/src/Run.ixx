export module Run;
import std;
import BPAlgorithmBase;
import BPAlgorithmsType;
import BranchRecord;
import stats;

// 单算法跑整条 trace。
export auto RunOne(BPAlgorithmBase& algo,
                   std::span<const BranchRecord> trace) -> stats;

export auto Run(const BPAlgorithmsType& algos,
                std::span<const BranchRecord> trace)
    -> std::vector<std::pair<std::string_view, stats>>;
