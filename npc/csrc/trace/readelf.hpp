#ifndef READELF_HPP
#define READELF_HPP
/**
 * @file readelf.hpp
 * @brief 供NPC追踪与调试工具使用的现代C++23 ELF元数据读取器。
 * @details
 * 本模块拥有从ELF文件中读取的全部字节，并对外暴露轻量的、无所有权的
 * 名称与表项视图。公共API有意设计为面向对象的风格：调用者从路径加载一个
 * `Readelf`对象，然后从这个不可变缓存中查询或打印信息。加载错误通过
 * `std::expected`报告，而非进程级状态或errno式副作用通道。
 * 所选的ELF布局遵循NPC构建目标。`NPC_ISA64 == 0`表示使用ELF32类型，
 * `NPC_ISA64 != 0`表示使用ELF64类型。
 */
#include "ReadelfFunction.hpp"
#include <cstddef>
#include <elf.h>
#include <expected>
#include <filesystem>
#include <iosfwd>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <type_traits>
#include <vector>
#ifndef NPC_ISA64
#define NPC_ISA64 0
#endif
/**
 * @brief 供NPC使用的RAII ELF读取器与符号缓存。
 * @details
 * `Readelf`读取ELF文件头、节头表、可选的节名字符串表、常规符号表、
 * 符号字符串表，以及派生的函数符号列表。它不依赖NEMU辅助函数或全局状态。
 * 该对象为仅移动（move-only），因为许多返回值是指向内部自有缓冲区的视图。
 * 查询结果是轻量的值对象，但它们的字符串成员仍引用本实例的后备字符串表。
 */
class Readelf final
{
public:
    // 当本构建期望ELF64文件时为`true`，ELF32文件时为`false`。
    static constexpr bool is_elf64{NPC_ISA64 != 0};
    // 由`NPC_ISA64`选择的原生ELF文件头类型。
    using header_type = std::conditional_t<is_elf64, Elf64_Ehdr, Elf32_Ehdr>;
    // 由`NPC_ISA64`选择的原生ELF节头类型。
    using section_header_type = std::conditional_t<is_elf64, Elf64_Shdr, Elf32_Shdr>;
    // 由`NPC_ISA64`选择的原生ELF符号表项类型。
    using symbol_type = std::conditional_t<is_elf64, Elf64_Sym, Elf32_Sym>;
    // 由`NPC_ISA64`选择的原生ELF程序头类型。
    using program_header_type = std::conditional_t<is_elf64, Elf64_Phdr, Elf32_Phdr>;
    // 由`NPC_ISA64`选择的原生ELF半字类型。
    using half_type = std::conditional_t<is_elf64, Elf64_Half, Elf32_Half>;
    // 由`NPC_ISA64`选择的原生ELF字类型。
    using word_type = std::conditional_t<is_elf64, Elf64_Word, Elf32_Word>;
    // 由`NPC_ISA64`选择的原生ELF扩展字/标志类型。
    using xword_type = std::conditional_t<is_elf64, Elf64_Xword, Elf32_Word>;
    // 构造一个空的、未加载的阅读器。
    Readelf();
    // 禁用复制，因为公开的字符串视图可能指向内部存储。
    Readelf(const Readelf &) = delete;
    // 禁用复制赋值，因为公开的字符串视图可能指向内部存储。
    Readelf &operator=(const Readelf &) = delete;
    // 移动转移缓存的ELF数据所有权。
    Readelf(Readelf &&) noexcept;
    // 移动转移缓存的ELF数据所有权。
    Readelf &operator=(Readelf &&) noexcept;
    // 释放所有缓存的ELF数据。
    ~Readelf();
    /**
     * @brief 加载并解析ELF文件。
     * @param elf_file std::filesystem::path，要读取的ELF文件路径。
     * @return std::expected<Readelf, std::string>，成功时返回已加载的`Readelf`对象，失败时返回人类可读的错误字符串。
     * @details
     * 加载器在将原始字节解释为原生ELF结构之前，先验证魔数、ELF class、
     * 端序模式、版本以及表项大小。当前仅支持小端ELF文件，且需与`NPC_ISA64`匹配。
     */
    [[nodiscard]] static std::expected<Readelf, std::string> load(std::filesystem::path elf_file);
    // 返回本实例是否包含已成功解析的ELF文件。
    [[nodiscard]] bool loaded() const noexcept;
    // 返回用于加载此ELF文件的路径。
    [[nodiscard]] const std::filesystem::path &path() const noexcept;
    // 返回缓存的ELF文件头。
    [[nodiscard]] const header_type &header() const;
    // 返回所有缓存的节头。
    [[nodiscard]] std::span<const section_header_type> section_headers() const noexcept;
    // 返回`SHT_SYMTAB`中所有缓存的原始符号（若存在）。
    [[nodiscard]] std::span<const symbol_type> symbols() const noexcept;
    // 返回从原始符号表派生的函数符号。
    [[nodiscard]] std::span<const ReadelfFunction> functions() const noexcept;
    /**
     * @brief 查找包含指定地址的函数。
     * @param address std::size_t，要解析的虚拟地址。
     * @return std::optional<ReadelfFunction>，匹配的函数记录，若没有函数范围包含该地址则返回`std::nullopt`。
     */
    [[nodiscard]] std::optional<ReadelfFunction> find_function(std::size_t address) const noexcept;
    /**
     * @brief 仅查找包含指定地址的函数名称。
     * @param address std::size_t，要解析的虚拟地址。
     * @return std::optional<std::string_view>，函数名称的视图，未命中时返回`std::nullopt`。
     */
    [[nodiscard]] std::optional<std::string_view> find_function_name(std::size_t address) const noexcept;
    // 向`os`打印类似readelf的文件头摘要。
    void print_file_header(std::ostream &os) const;
    // 向`os`打印类似readelf的节头表。
    void print_section_headers(std::ostream &os) const;
    // 向`os`打印类似readelf的原始符号表。
    void print_symbols(std::ostream &os) const;
private:
    /**
     * @brief `load()`的实现，在`path_`已赋值后调用。
     * @return std::expected<void, std::string>，空的成功值，或描述性的解析/读取错误。
     */
    std::expected<void, std::string> load_from_file();
    // 将节名字符串表偏移解析为可显示的视图。
    [[nodiscard]] std::string_view section_name(word_type name_offset) const noexcept;
    // 将符号字符串表偏移解析为可显示的视图。
    [[nodiscard]] std::string_view symbol_name(word_type name_offset) const noexcept;
    // 返回第一个`SHT_SYMTAB`节的索引，若不存在则返回`-1`。
    [[nodiscard]] int symbol_table_section_index() const noexcept;
    // 在`load_from_file()`填充了完整缓存后为`true`。
    bool loaded_{false};
    // 用于诊断与自省的文件路径。
    std::filesystem::path path_{};
    // 缓存的ELF文件头。
    header_type header_{};
    // 缓存的节头表。
    std::vector<section_header_type> section_headers_{};
    // 缓存的`SHT_SYMTAB`原始符号表项。
    std::vector<symbol_type> symbols_{};
    // 缓存的符号字符串表。加载器会额外追加一个尾部NUL字节。
    std::vector<char> string_table_{};
    // 缓存的节名字符串表。加载器会额外追加一个尾部NUL字节。
    std::vector<char> section_name_table_{};
    // 从`symbols_`和`string_table_`派生并按地址排序的函数记录。
    std::vector<ReadelfFunction> functions_{};
};
#endif
