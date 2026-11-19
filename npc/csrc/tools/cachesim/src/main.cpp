module;
#include "common/CliOptions.hpp"
#include "common/CsvResult.hpp"
#include <cstdio>

import std;
import types;
import cache;
import trace;
import stats;
import dse;

namespace {
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
    constexpr std::string_view Header{
        "块大小,块总数,容量(B),相联度,替换策略,总访问,命中,缺失,强制缺失,容量缺失,冲突缺失,命中率,缺失代价(事务数)"};
    const auto result_path{common::WriteDseCsv(
        std::span<const DseEntry>{results},
        std::filesystem::path{CACHESIM_DIR} / "TestResult", "", Header,
        [&](const DseEntry& e)
        {
            const auto& s{e.stats};
            const auto misses{s.misses()};
            const auto rate{s.total > 0 ? 100.0 * s.hits / s.total : 0.0};
            const auto transactions_per_miss{(e.config.block_size + bus_width - 1) / bus_width};
            const auto total_transactions{misses * transactions_per_miss};
            return std::vector<std::string>{
                std::format("{}", e.config.block_size),
                std::format("{}", e.config.num_blocks),
                std::format("{}", e.config.block_size * e.config.num_blocks),
                std::format("{}", e.config.ways),
                std::format("{}", repl_names[e.config.repl_policy]),
                std::format("{}", s.total),
                std::format("{}", s.hits),
                std::format("{}", misses),
                std::format("{}", s.miss_compulsory),
                std::format("{}", s.miss_capacity),
                std::format("{}", s.miss_conflict),
                std::format("{:.2f}%", rate),
                std::format("{}", total_transactions)};
        })};
    std::println("DSE 完成，{} 组参数组合，结果保存在 {}", results.size(), result_path.string());
}
} // anonymous namespace

auto main(int argc, char* argv[]) -> int
{
    common::CliOptions cli{argv[0], "[选项] <trace文件>"};
    cli.Add(common::OptionSpec{std::format("-b, --block-size <N>     块大小 (字节, 2的幂, 默认 {})", CACHE_BLOCK_SIZE), "-b", "--block-size", true});
    cli.Add(common::OptionSpec{std::format("-n, --num-blocks <N>     cache 块总数 (默认 {})", CACHE_NUM_BLOCKS), "-n", "--num-blocks", true});
    cli.Add(common::OptionSpec{std::format("-w, --ways <N>           相联度 (默认 {})", CACHE_WAYS), "-w", "--ways", true});
    cli.Add(common::OptionSpec{"-r, --replacement <策略>  替换策略: fifo / lru / random (默认 fifo)", "-r", "--replacement", true});
    cli.Add(common::OptionSpec{"--bus-width <N>         总线位宽 (字节, 默认 4, 用于估算缺失代价)", "", "--bus-width", true});
    cli.Add(common::OptionSpec{"--dse                    设计空间探索模式", "", "--dse", false});
    cli.Add(common::OptionSpec{"-h, --help               显示帮助信息", "-h", "--help", false});

    int exit_code{};
    if (!cli.Parse(argc, argv, exit_code))
        return exit_code;

    CacheConfig config;
    unsigned bus_width = 4;

    if (const auto value{cli.Value("--bus-width")}) {
        if (!common::ParseUint(*value, bus_width)) {
            std::println(std::cerr, "错误: 无效的 --bus-width 参数: {}", *value);
            return 1;
        }
    }
    if (const auto value{cli.Value("--block-size")}) {
        if (!common::ParseUint(*value, config.block_size)) {
            std::println(std::cerr, "错误: 无效的 --block-size 参数: {}", *value);
            return 1;
        }
    }
    if (const auto value{cli.Value("--num-blocks")}) {
        if (!common::ParseUint(*value, config.num_blocks)) {
            std::println(std::cerr, "错误: 无效的 --num-blocks 参数: {}", *value);
            return 1;
        }
    }
    if (const auto value{cli.Value("--ways")}) {
        if (!common::ParseUint(*value, config.ways)) {
            std::println(std::cerr, "错误: 无效的 --ways 参数: {}", *value);
            return 1;
        }
    }
    if (const auto value{cli.Value("--replacement")})
        config.repl_policy = parse_repl(*value);

    if (cli.Positional().empty()) {
        std::println(std::cerr, "错误: 未指定 trace 文件");
        cli.PrintUsage(stderr);
        return 1;
    }
    const std::string trace_path{cli.Positional().back()};

    if (bus_width == 0) {
        std::println(std::cerr, "错误: bus_width 必须大于 0");
        return 1;
    }
    if (!std::has_single_bit(config.block_size)) {
        std::println(std::cerr, "错误: 块大小必须是大于 0 的 2 的幂, 当前值 {}", config.block_size);
        return 1;
    }
    if (!std::has_single_bit(config.num_blocks)) {
        std::println(std::cerr, "错误: num_blocks 必须是大于 0 的 2 的幂, 当前值 {}", config.num_blocks);
        return 1;
    }
    if (config.ways == 0) {
        std::println(std::cerr, "错误: ways 必须大于 0");
        return 1;
    }
    if (config.num_blocks % config.ways != 0) {
        std::println(std::cerr, "错误: num_blocks ({}) 必须能被 ways ({}) 整除", config.num_blocks, config.ways);
        return 1;
    }

    try {
#if RUN_MODE == 1
        // Kconfig 选择 DSE 模式。
        dse_run(trace_path.c_str(), bus_width);
#else
        if (cli.Has("--dse"))
            dse_run(trace_path.c_str(), bus_width);
        else
            single_run(config, trace_path.c_str());
#endif
    } catch (const std::exception& e) {
        std::println(std::cerr, "错误: {}", e.what());
        return 1;
    }

    return 0;
}
