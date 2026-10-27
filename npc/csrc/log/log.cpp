module;
#include <unistd.h>
#include <cstdio>

module npc.log;
import npc.config;

static std::ofstream log_file;

std::expected<void, std::string> log_init() noexcept {
    if constexpr (npc::HasLogToFile)
    {
        try
        {
            std::filesystem::create_directories(std::filesystem::path{npc::LogDir});
            auto now{std::chrono::zoned_time{std::chrono::current_zone(), std::chrono::system_clock::now()}};
            auto filename{std::format("{}/{:%Y%m%d-%H%M%S}.log", npc::LogDir, now)};
            log_file.open(filename, std::ios::out | std::ios::app);
            if (!log_file.is_open())
            {
                return std::unexpected{
                    std::format("无法打开日志文件 {}", filename)};
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

void log_close() {
    if constexpr (npc::HasLogToFile)
    {
        if (log_file.is_open()) log_file.close();
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

void log_write(LogLevel level, std::string_view msg, std::source_location loc) {
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
    std::println(std::cerr, "{}", line);
    if constexpr (npc::HasLogToFile)
    {
        if (log_file.is_open()) log_file << line << std::endl;
    }
}
