module;
#include "common/TraceLineReader.hpp"
module BTraceReader;
import std;
import BranchRecord;

struct BTraceReader::Impl
{
    common::LineReader reader;
    explicit Impl(const std::string &path) : reader{path} {}
};

BTraceReader::BTraceReader(const std::string &path)
    : impl{std::make_unique<Impl>(path)}
{
}

BTraceReader::~BTraceReader() = default;

std::optional<BranchRecord> BTraceReader::next()
{
    if (!impl)
    {
        return std::nullopt;
    }
    std::string line{};
    while (impl->reader.Next(line))
    {
        if (auto record{TryParse(line)})
        {
            ++count;
            return record;
        }
    }
    return std::nullopt;
}

std::optional<BranchRecord> BTraceReader::TryParse(std::string_view line)
{
    if (common::IsCommentLine(line))
    {
        return std::nullopt;
    }
    // pc target taken [类型 [immediate]]，空白分隔
    const auto fields{common::SplitFields(line)};
    if (!common::FieldCountIn(fields, 3, 5))
    {
        return std::nullopt;
    }
    const auto pc{common::ParseFull<Addr>(fields[0], 16)};
    const auto target{common::ParseFull<Addr>(fields[1], 16)};
    const auto taken{common::ParseFull<int>(fields[2])};
    if (!pc || !target || !taken)
    {
        return std::nullopt;
    }
    // b 条件分支, j/c jal, r/i/o/x jalr 变体。
    auto kind{BranchKind::Branch};
    std::uint32_t jalr_immediate{};
    if (fields.size() >= 4)
    {
        if (fields[3].size() != 1)
        {
            return std::nullopt;
        }
        switch (fields[3][0])
        {
        case 'j': case 'J': kind = BranchKind::Jal; break;
        case 'c': case 'C': kind = BranchKind::Call; break;
        case 'r': case 'R': kind = BranchKind::Ret; break;
        case 'i': case 'I': kind = BranchKind::JalrCall; break;
        case 'o': case 'O': kind = BranchKind::Coroutine; break;
        case 'x': case 'X': kind = BranchKind::JalrOther; break;
        default: break;
        }
        if (fields.size() == 5)
        {
            const auto immediate{common::ParseFull<std::uint32_t>(fields[4], 16)};
            if (!immediate)
            {
                return std::nullopt;
            }
            jalr_immediate = *immediate;
        }
    }
    return BranchRecord{*pc, *target, *taken != 0, kind, jalr_immediate};
}

std::size_t BTraceReader::GetCount() const
{
    return count;
}
