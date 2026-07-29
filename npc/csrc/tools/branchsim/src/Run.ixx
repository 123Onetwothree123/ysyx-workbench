export module Run;
import std;
import BPAlgorithmsType;
import BranchRecord;
import stats;

export auto Run(const BPAlgorithmsType& algos,
                std::span<const BranchRecord> trace)
    -> std::vector<std::pair<std::string_view, stats>>;