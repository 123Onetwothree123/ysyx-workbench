module stats;
import std;
import types;

void print_stats(const SimStats& stats, const CacheConfig& config)
{
    using namespace std;

    const char* repl_names[] = {"FIFO", "LRU", "Random"};

    println("cache 配置:");
    println("块大小：{} 字节", config.block_size);
    println("块总数：{}", config.num_blocks);
    println("相联度：{} ({})", config.ways, config.ways == 1 ? "直接映射" : config.ways == config.num_blocks ? "全相联" : "组相联");
    println("组数：{}", config.num_blocks / config.ways);
    println("替换策略：{}", repl_names[config.repl_policy < 3 ? config.repl_policy : 0]);

    unsigned misses = stats.misses();

    println();
    println("统计结果:");
    println("总访问次数：{}", stats.total);
    println("命中次数：{}", stats.hits);
    println("缺失次数：{} ({:.2f}%)", misses, stats.total > 0 ? 100.0 * misses / stats.total : 0.0);
    println("强制缺失：{} ({:.2f}%)", stats.miss_compulsory, misses > 0 ? 100.0 * stats.miss_compulsory / misses : 0.0);
    println("容量缺失：{} ({:.2f}%)", stats.miss_capacity, misses > 0 ? 100.0 * stats.miss_capacity / misses : 0.0);
    println("冲突缺失：{} ({:.2f}%)", stats.miss_conflict, misses > 0 ? 100.0 * stats.miss_conflict / misses : 0.0);
    println("命中率：{:.2f}%", stats.total > 0 ? 100.0 * stats.hits / stats.total : 0.0);
}
