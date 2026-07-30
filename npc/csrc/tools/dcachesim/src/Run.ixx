export module Run;
import std;
import DCachesType;
import DCache;
import AccessRecord;
import stats;

export auto RunOne(DCache& cache,
                   std::span<const AccessRecord> trace) -> stats;

export auto Run(const DCachesType& caches,
                std::span<const AccessRecord> trace)
    -> std::vector<std::pair<std::string_view, stats>>;
