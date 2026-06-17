#ifndef IRINGBUF_HPP
#define IRINGBUF_HPP
#include "RecordInstruction.hpp"
#include <cstddef>
#include <cstdint>
#include <array>
class iringbuf
{
public:
    static constexpr std::size_t CONFIG_IRINGBUF_SIZE{16};
private:
    std::array<RecordInstruction, CONFIG_IRINGBUF_SIZE> buffer{};
    std::size_t head{0};
    std::size_t count{0};
public:
    iringbuf() noexcept;
    ~iringbuf() noexcept;
    [[nodiscard]] bool empty() const;
    [[nodiscard]] bool full() const;
    [[nodiscard]] std::size_t size() const;
    [[nodiscard]] std::size_t capacity() const;
    void push(std::uint64_t pc, std::uint32_t instruction, int len);
    void print(std::uint64_t ErrorPC) const;
};
#endif
