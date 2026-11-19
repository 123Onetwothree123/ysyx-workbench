module;
module npc.log;
import npc.config;
import npc.Output;

std::expected<void, std::string> log_init(Output &out) noexcept
{
    if constexpr (npc::HasLogToFile)
    {
        try
        {
            std::filesystem::create_directories(std::filesystem::path{npc::LogDir});
            auto now{std::chrono::zoned_time{std::chrono::current_zone(), std::chrono::system_clock::now()}};
            auto filename{std::format("{}/{:%Y%m%d-%H%M%S}.log", npc::LogDir, now)};
            auto result{out.OpenFile(filename)};
            if (!result)
            {
                return result;
            }
        }
        catch (const std::exception &error)
        {
            return std::unexpected{
                std::format("无法初始化日志目录 {}：{}", npc::LogDir, error.what())};
        }
    }
    return {};
}

void log_close(Output &out)
{
    if constexpr (npc::HasLogToFile)
    {
        out.CloseFile();
    }
}

static std::string_view level_str(LogLevel level) {
    switch (level) {
    case LogLevel::Error: return "ERROR";
    case LogLevel::Warn:  return "WARN";
    case LogLevel::Info:  return "INFO";
    case LogLevel::Debug: return "DEBUG";
    default: return "???";
    }
}

void log_write(Output &out, LogLevel level, std::string_view msg, std::source_location loc) {
    std::string line;
    if constexpr (npc::HasLogTimestamp)
    {
        auto now{std::chrono::zoned_time{std::chrono::current_zone(), std::chrono::system_clock::now()}};
        line += std::format("[{:%H:%M:%S}] ", now);
    }
    line += std::format("[{}] ", level_str(level));
    if constexpr (npc::HasLogSourceLocation)
    {
        line += std::format("{}:{} ", loc.file_name(), loc.line());
    }
    line += msg;
    static_cast<void>(loc);
    const auto output_level{[&] {
        switch (level) {
        case LogLevel::Error: return OutputLevel::Error;
        case LogLevel::Warn: return OutputLevel::Warn;
        case LogLevel::Info: return OutputLevel::Info;
        default: return OutputLevel::Debug;
        }
    }()};
    out.Write(output_level, line);
}
