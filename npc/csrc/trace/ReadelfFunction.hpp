#ifndef READELF_FUNCTION_HPP
#define READELF_FUNCTION_HPP
/**
 * @file ReadelfFunction.hpp
 * @brief 为快速地址范围查找而规范化的函数符号。
 *
 * @details
 * `ReadelfFunction` 故意设计得比原始 ELF 符号表项更小、更方便。
 * `name` 指向生成它的 `Readelf` 实例所拥有的字符串表。
 * 因此，只要该 `Readelf` 对象存活且未发生破坏其内部存储的移动，
 * 该视图就保持有效。
 */
#include <cstddef>
#include <string_view>
struct ReadelfFunction
{
    // ELF 符号字符串表中存储的函数名称。
    std::string_view name{};
    // 函数的包含性起始虚拟地址。
    std::size_t start = 0;
    // 函数的排他性结束虚拟地址。
    std::size_t end = 0;
    /**
     * @brief 返回函数的字节大小。
     *
     * @return std::size_t，`end - start`。加载器只会创建结束地址不会溢出 `std::size_t` 的记录。
     */
    [[nodiscard]] std::size_t size() const noexcept;
    /**
     * @brief 检查某个地址是否属于本函数。
     *
     * @param address std::size_t，要测试的虚拟地址。
     * @return bool，若 `address` 在半开区间 `[start, end)` 内则为 `true`。
     */
    [[nodiscard]] bool contains(std::size_t address) const noexcept;
};

#endif
