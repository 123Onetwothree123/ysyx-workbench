import std;
import types;
import cache;
import trace;
import stats;
import dse;

namespace {

void print_usage(const char* prog)
{
    std::println("用法: {} [选项] <trace文件>", prog);
    std::println();
    std::println("选项:");
    std::println("  -b, --block-size <N>     块大小 (字节, 2的幂, 默认 {})", CACHE_BLOCK_SIZE);
    std::println("  -n, --num-blocks <N>     cache 块总数 (默认 {})", CACHE_NUM_BLOCKS);
    std::println("  -w, --ways <N>           相联度 (默认 {})", CACHE_WAYS);
    std::println("  -r, --replacement <策略>  替换策略: fifo / lru / random (默认 fifo)");
    std::println("  --bus-width <N>         总线位宽 (字节, 默认 4, 用于估算缺失代价)");
    std::println("  --dse                    设计空间探索模式");
    std::println("  -h, --help               显示帮助信息");
}

auto parse_repl(std::string_view s) -> unsigned
{
    if (s == "fifo" || s == "FIFO") return 0;
    if (s == "lru"  || s == "LRU")  return 1;
    if (s == "random" || s == "RANDOM") return 2;
    std::println(std::cerr, "未知替换策略: {}, 使用默认 FIFO", s);
    return 0;
}

void single_run(CacheConfig config, const char* trace_path)
{
    CacheSim sim(config);
    TraceReader reader(trace_path);
    while (auto addr = reader.next())
        sim.access(*addr);
    std::println("处理了 {} 条 PC 记录", reader.count());
    print_stats(sim.stats(), sim.config());
}

void dse_run(const char* trace_path, unsigned bus_width)
{
    const unsigned block_sizes[] = {4, 8, 16, 32, 64, 128};
    const unsigned num_blocks_list[] = {4, 8, 16, 32, 64, 128, 256, 512};
    const unsigned ways_list[] = {1, 2, 4, 8};
    const unsigned repl_list[] = {0, 1, 2}; // fifo, lru, random
    const char* repl_names[] = {"fifo", "lru", "random"};

    auto results = run_dse(trace_path, block_sizes, num_blocks_list, ways_list, repl_list);

    // 生成结果目录 cachesim/TestResult/时间戳/
    auto now = std::chrono::system_clock::now();
    auto time_t = std::chrono::system_clock::to_time_t(now);
    auto tm = *std::localtime(&time_t);
    auto dir = std::format("{}/TestResult/{:04}{:02}{:02}-{:02}{:02}{:02}",
        CACHESIM_DIR,
        tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday, tm.tm_hour, tm.tm_min, tm.tm_sec);
    std::filesystem::create_directories(dir);

    auto result_path = std::format("{}/result.csv", dir);
    auto fp = std::ofstream(result_path, std::ios::binary);
    // UTF-8 BOM
    fp << "\xEF\xBB\xBF";
    fp << "块大小,块总数,容量(B),相联度,替换策略,总访问,命中,缺失,强制缺失,容量缺失,冲突缺失,命中率,缺失代价(事务数)\n";

    for (const auto& r : results) {
        auto& s = r.stats;
        auto misses = s.misses();
        auto rate = s.total > 0 ? 100.0 * s.hits / s.total : 0.0;
        auto transactions_per_miss = (r.config.block_size + bus_width - 1) / bus_width;
        auto total_transactions = misses * transactions_per_miss;
        fp << r.config.block_size << ","
           << r.config.num_blocks << ","
           << r.config.block_size * r.config.num_blocks << ","
           << r.config.ways << ","
           << repl_names[r.config.repl_policy] << ","
           << s.total << ","
           << s.hits << ","
           << misses << ","
           << s.miss_compulsory << ","
           << s.miss_capacity << ","
           << s.miss_conflict << ","
           << std::fixed << std::setprecision(2) << rate << "%,"
           << total_transactions << "\n";
    }

    fp.close();
    std::println("DSE 完成，{} 组参数组合，结果保存在 {}", results.size(), result_path);
}

} // anonymous namespace

auto parse_uint(const char *text) -> std::optional<unsigned>
{
    std::uint32_t value{};
    const auto end{text + std::char_traits<char>::length(text)};
    const auto [ptr, ec]{std::from_chars(text, end, value, 10)};
    if (ec == std::errc{} && ptr != text)
    {
        return value;
    }
    return std::nullopt;
}

auto main(int argc, char* argv[]) -> int
{
    CacheConfig config;
    const char* trace_path = nullptr;
    bool dse_mode = false;
    unsigned bus_width = 4;

    for (int i = 1; i < argc; ++i) {
        if (argv[i][0] != '-') {
            trace_path = argv[i];
            continue;
        }

        std::string_view arg = argv[i];
        auto is_long = arg.starts_with("--");

        if (arg == "-h" || arg == "--help") {
            print_usage(argv[0]);
            return 0;
        }

        if (arg == "--dse") {
            dse_mode = true;
        } else if (is_long ? arg == "--bus-width" : false) {
            if (i + 1 < argc) {
                if (auto v{parse_uint(argv[++i])})
                    bus_width = *v;
                else {
                    std::println(std::cerr, "错误: 无效的 --bus-width 参数: {}", argv[i]);
                    return 1;
                }
            }
        } else if (is_long ? arg == "--block-size" : arg == "-b") {
            if (i + 1 < argc) {
                if (auto v{parse_uint(argv[++i])})
                    config.block_size = *v;
                else {
                    std::println(std::cerr, "错误: 无效的 --block-size 参数: {}", argv[i]);
                    return 1;
                }
            }
        } else if (is_long ? arg == "--num-blocks" : arg == "-n") {
            if (i + 1 < argc) {
                if (auto v{parse_uint(argv[++i])})
                    config.num_blocks = *v;
                else {
                    std::println(std::cerr, "错误: 无效的 --num-blocks 参数: {}", argv[i]);
                    return 1;
                }
            }
        } else if (is_long ? arg == "--ways" : arg == "-w") {
            if (i + 1 < argc) {
                if (auto v{parse_uint(argv[++i])})
                    config.ways = *v;
                else {
                    std::println(std::cerr, "错误: 无效的 --ways 参数: {}", argv[i]);
                    return 1;
                }
            }
        } else if (is_long ? arg == "--replacement" : arg == "-r") {
            if (i + 1 < argc) config.repl_policy = parse_repl(argv[++i]);
        } else {
            std::println(std::cerr, "未知选项: {}", argv[i]);
            print_usage(argv[0]);
            return 1;
        }
    }

    if (!trace_path) {
        std::println(std::cerr, "错误: 未指定 trace 文件");
        print_usage(argv[0]);
        return 1;
    }

#if RUN_MODE == 1
    // Kconfig 选择了 DSE 模式: 直接做设计空间探索 (多线程)
    dse_run(trace_path, bus_width);
    return 0;
#endif

    if (dse_mode) {
        dse_run(trace_path, bus_width);
        return 0;
    }
    if ((config.block_size & (config.block_size - 1)) != 0) {
        std::println(std::cerr, "错误: 块大小必须是 2 的幂, 当前值 {}", config.block_size);
        return 1;
    }
    if (config.num_blocks == 0 || config.ways == 0) {
        std::println(std::cerr, "错误: num_blocks 和 ways 必须大于 0");
        return 1;
    }
    if (config.num_blocks % config.ways != 0) {
        std::println(std::cerr, "错误: num_blocks ({}) 必须能被 ways ({}) 整除", config.num_blocks, config.ways);
        return 1;
    }

    try {
        single_run(config, trace_path);
    } catch (const std::exception& e) {
        std::println(std::cerr, "错误: {}", e.what());
        return 1;
    }

    return 0;
}
