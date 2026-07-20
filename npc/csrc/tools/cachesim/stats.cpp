module stats;
import std;
import types;

void print_stats(const SimStats& stats, const CacheConfig& config)
{
    using namespace std;

    const char* repl_names[] = {"FIFO", "LRU", "Random"};

    println("=== Cache Configuration ===");
    println("Block size:       {} bytes", config.block_size);
    println("Num blocks:       {}", config.num_blocks);
    println("Ways:             {} ({})", config.ways, config.ways == 1 ? "直接映射" : config.ways == config.num_blocks ? "全相联" : "组相联");
    println("Sets:             {}", config.num_blocks / config.ways);
    println("Replacement:      {}", repl_names[config.repl_policy < 3 ? config.repl_policy : 0]);

    unsigned misses = stats.misses();

    println("");
    println("=== Results ===");
    println("Total accesses:   {}", stats.total);
    println("Hits:             {}", stats.hits);
    println("Misses:           {} ({:.2f}%)", misses, stats.total > 0 ? 100.0 * misses / stats.total : 0.0);
    println("  Compulsory:     {} ({:.2f}%)", stats.miss_compulsory, misses > 0 ? 100.0 * stats.miss_compulsory / misses : 0.0);
    println("  Capacity:       {} ({:.2f}%)", stats.miss_capacity, misses > 0 ? 100.0 * stats.miss_capacity / misses : 0.0);
    println("  Conflict:       {} ({:.2f}%)", stats.miss_conflict, misses > 0 ? 100.0 * stats.miss_conflict / misses : 0.0);
    println("Hit rate:         {:.2f}%", stats.total > 0 ? 100.0 * stats.hits / stats.total : 0.0);
}
