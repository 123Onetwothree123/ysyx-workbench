module;
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
module npc.Output;
import std;
namespace
{
bool Terminal(FILE *Stream)
{
    return ::isatty(::fileno(Stream)) != 0;
}
}
Output::Output()
    : stdout_color{Terminal(stdout) && std::getenv("NO_COLOR") == nullptr},
      stderr_color{Terminal(stderr) && std::getenv("NO_COLOR") == nullptr}
{
}
Output::~Output()
{
    CloseFile();
}
void Output::SetMinLevel(OutputLevel Level) noexcept
{
    min_level = Level;
}
std::expected<void, std::string> Output::OpenFile(const std::filesystem::path &Path)
{
    CloseFile();
    file.open(Path, std::ios::out | std::ios::app);
    if (!file.is_open())
    {
        return std::unexpected{std::format("无法打开日志文件 {}", Path.string())};
    }
    return {};
}
void Output::CloseFile()
{
    if (file.is_open())
    {
        file.close();
    }
}
std::ostream &Output::StreamFor(OutputLevel Level) noexcept
{
    return Level <= OutputLevel::Warn ? std::cerr : std::cout;
}
bool Output::UseColor(OutputLevel Level) const noexcept
{
    return Level <= OutputLevel::Warn ? stderr_color : stdout_color;
}
std::string_view Output::ColorFor(OutputLevel Level) const noexcept
{
    switch (Level)
    {
    case OutputLevel::Error: return "\033[31m";
    case OutputLevel::Warn: return "\033[33m";
    case OutputLevel::Info: return "";
    case OutputLevel::Debug: return "\033[90m";
    }
    return "";
}
void Output::Write(OutputLevel Level, std::string_view Message)
{
    auto &stream{StreamFor(Level)};
    const auto color{UseColor(Level) ? ColorFor(Level) : std::string_view{}};
    if (!color.empty())
    {
        stream << color;
    }
    stream.write(Message.data(), static_cast<std::streamsize>(Message.size()));
    stream.put('\n');
    if (!color.empty())
    {
        stream << "\033[0m";
    }
    stream.flush();
    if (file.is_open())
    {
        file.write(Message.data(), static_cast<std::streamsize>(Message.size()));
        file.put('\n');
        file.flush();
    }
}
void Output::WriteRaw(OutputLevel Level, std::string_view Text)
{
    auto &stream{StreamFor(Level)};
    const auto color{UseColor(Level) ? ColorFor(Level) : std::string_view{}};
    if (!color.empty())
    {
        stream << color;
    }
    stream.write(Text.data(), static_cast<std::streamsize>(Text.size()));
    if (!color.empty())
    {
        stream << "\033[0m";
    }
    stream.flush();
    if (file.is_open())
    {
        file.write(Text.data(), static_cast<std::streamsize>(Text.size()));
        file.flush();
    }
}
