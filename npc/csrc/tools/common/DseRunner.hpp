#pragma once
#include "CsvResult.hpp"
#include "ThreadPool.hpp"
#include <algorithm>
#include <cstddef>
#include <initializer_list>
#include <span>
#include <utility>
#include <vector>
namespace common
{
// cache 合法相联度：不超过块数且整除块数
inline auto ValidWays(std::size_t ways, std::size_t num_blocks) -> bool
{
    return ways > 0 && ways <= num_blocks && num_blocks % ways == 0;
}
// 枚举笛卡尔积；make 由索引构造组合，keep 决定是否保留
template <typename Combo, typename T, typename Make, typename Keep>
auto EnumerateCombos(std::initializer_list<std::span<const T>> axes,
                     Make make, Keep keep) -> std::vector<Combo>
{
    std::vector<std::vector<T>> values{};
    values.reserve(axes.size());
    for (auto axis : axes)
        values.emplace_back(axis.begin(), axis.end());
    std::vector<Combo> combos{};
    if (values.empty())
        return combos;
    std::vector<T> index(values.size());
    auto recurse = [&](auto&& self, std::size_t depth) -> void
    {
        if (depth == values.size())
        {
            auto combo{make(std::span<const T>{index})};
            if (keep(combo))
                combos.push_back(std::move(combo));
            return;
        }
        for (auto value : values[depth])
        {
            index[depth] = value;
            self(self, depth + 1);
        }
    };
    recurse(recurse, 0);
    return combos;
}
// 并行评估每个组合；eval(combo, rows) 填满该组合的 rows_per_combo 行
template <typename Combo, typename Row, typename Eval>
auto EvalCombos(std::span<const Combo> combos, std::size_t rows_per_combo, Eval eval)
    -> std::vector<Row>
{
    std::vector<std::vector<Row>> groups(combos.size());
    ParallelFor(combos.size(), [&](std::size_t i)
    {
        groups[i].resize(rows_per_combo);
        eval(combos[i], std::span<Row>{groups[i]});
    });
    std::vector<Row> rows{};
    rows.reserve(combos.size() * rows_per_combo);
    for (auto& group : groups)
        for (auto& row : group)
            rows.push_back(std::move(row));
    return rows;
}
template <typename Row, typename Key>
void SortByDescending(std::vector<Row>& rows, Key key)
{
    std::ranges::sort(rows, [&key](const Row& a, const Row& b) { return key(a) > key(b); });
}
}
