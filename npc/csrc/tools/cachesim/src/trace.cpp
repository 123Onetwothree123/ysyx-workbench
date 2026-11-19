module;
#include "common/TraceLineReader.hpp"
module trace;
import std;
import types;

struct TraceReader::Impl
{
    common::LineReader reader;
    explicit Impl(const std::string& path) : reader{path} {}
};

TraceReader::TraceReader(const std::string& path)
    : impl_{std::make_unique<Impl>(path)}
{
}

TraceReader::~TraceReader() = default;

namespace
{
    std::optional<Addr> ParseMtraceAddress(std::string_view line, std::string_view marker)
    {
        const auto markerPosition{line.find(marker)};
        if (markerPosition == std::string_view::npos)
        {
            return std::nullopt;
        }
        auto addressText{line.substr(markerPosition + marker.size())};
        const auto digitEnd{addressText.find_first_not_of("0123456789abcdefABCDEF")};
        if (digitEnd == 0)
        {
            return std::nullopt;
        }
        if (digitEnd != std::string_view::npos)
        {
            const auto delimiter{static_cast<unsigned char>(addressText[digitEnd])};
            if (addressText[digitEnd] != ',' && !std::isspace(delimiter))
            {
                return std::nullopt;
            }
            addressText = addressText.substr(0, digitEnd);
        }
        return common::ParseFull<Addr>(addressText, 16);
    }
}

auto TraceReader::try_parse(const std::string& line) -> std::optional<Addr>
{
    if (common::IsCommentLine(line))
        return std::nullopt;

    // mtrace 格式: "Memory Read: PC=0x..., Addr=0x..., Data=..."
    if (line.starts_with("Memory Read") || line.starts_with("Memory Write")) {
        if (const auto address{ParseMtraceAddress(line, "Addr=0x")})
            return address;
        return ParseMtraceAddress(line, "Addr=0X");
    }

    if (line.starts_with("0x") || line.starts_with("0X"))
        return common::ParseFull<Addr>(std::string_view{line}.substr(2), 16);
    // 统一按十进制解析，避免 stoul(base 0) 把 "0123" 当八进制
    return common::ParseFull<Addr>(line, 10);
}

auto TraceReader::next() -> std::optional<Addr>
{
    if (!impl_)
        return std::nullopt;

    std::string line{};
    while (impl_->reader.Next(line)) {
        if (auto result{try_parse(line)}) {
            ++count_;
            return result;
        }
    }
    return std::nullopt;
}
