#include "iringbuf.hpp"
#include <print>
#include <span>
#include <iterator>
#include "disasm.hpp"
iringbuf::iringbuf() noexcept = default;
iringbuf::~iringbuf() noexcept = default;
bool iringbuf::empty() const
{
    return count == 0;
}
bool iringbuf::full() const
{
    return count == CONFIG_IRINGBUF_SIZE;
}
std::size_t iringbuf::size() const
{
    return count;
}
std::size_t iringbuf::capacity() const
{
    return CONFIG_IRINGBUF_SIZE;
}
void iringbuf::push(std::uint64_t pc, std::uint32_t instruction, int len)
{
    buffer[head].SetPC(pc);
    buffer[head].SetInstruction(instruction);
    buffer[head].SetLen(len);
    head = (head + 1) % CONFIG_IRINGBUF_SIZE;
    if (count < CONFIG_IRINGBUF_SIZE)
    {
        count++;
    }
}
void iringbuf::print(std::uint64_t ErrorPC) const
{
    if (empty())
    {
        std::println("iringbuf是空的");
        return;
    }
    const auto start{(head + CONFIG_IRINGBUF_SIZE - count) % CONFIG_IRINGBUF_SIZE};
    std::println("打印iringbuf");
    for (std::size_t i{0}; i < count; i++)
    {
        const auto index{(start + i) % CONFIG_IRINGBUF_SIZE};
        const RecordInstruction &entry{buffer[index]};
        constexpr std::string_view marker_selected{"-->"};
        constexpr std::string_view marker_normal{"   "};
        std::string_view marker{(entry.GetPC() == ErrorPC) ? marker_selected : marker_normal};
        std::array<std::uint8_t, 4> inst_bytes{};
        auto instruction{entry.GetInstruction()};
        for (std::size_t j{0}; j < static_cast<std::size_t>(entry.GetLen()); j++)
        {
            inst_bytes[j] = static_cast<std::uint8_t>(instruction >> (j * 8));
        }
        std::span<std::uint8_t> bytes_span{inst_bytes.data(), static_cast<std::size_t>(entry.GetLen())};
        std::array<char, 128> AssemblyBuffer{};
        disassemble(AssemblyBuffer.data(), static_cast<int>(AssemblyBuffer.size()), entry.GetPC(), bytes_span.data(), static_cast<int>(bytes_span.size()));
        std::string BytesString;
        BytesString.reserve(static_cast<std::size_t>(entry.GetLen()) * 4);
        for (int j{entry.GetLen() - 1}; j >= 0; --j)
        {
            std::format_to(std::back_inserter(BytesString), " {:02x}", inst_bytes[j]);
        }
        std::println("{:s} 0x{:016x}: {:<24s}{:s}",
                     marker,
                     entry.GetPC(),
                     AssemblyBuffer.data(),
                     BytesString);
    }
}
