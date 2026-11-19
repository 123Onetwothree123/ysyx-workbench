export module npc.log;
import std;
import npc.config;
import npc.Output;

export enum class LogLevel { Silent = 0, Error = 1, Warn = 2, Info = 3, Debug = 4 };
export inline constexpr auto CURRENT_LOG_LEVEL = static_cast<LogLevel>(npc::LogLevel);

// Logging is initialized before the DUT exists.  Report filesystem/open
// failures to main instead of allowing filesystem_error to terminate the
// process before the normal error path can run.
export [[nodiscard]] std::expected<void, std::string> log_init(Output &out) noexcept;
export void log_close(Output &out);
export void log_write(Output &out, LogLevel level, std::string_view msg, std::source_location loc);

export template<typename... Args>
void log_error(Output &out, std::format_string<Args...> fmt, Args&&... args) {
    if constexpr (CURRENT_LOG_LEVEL >= LogLevel::Error) {
        log_write(out, LogLevel::Error, std::format(fmt, std::forward<Args>(args)...), std::source_location::current());
    }
}

export template<typename... Args>
void log_warn(Output &out, std::format_string<Args...> fmt, Args&&... args) {
    if constexpr (CURRENT_LOG_LEVEL >= LogLevel::Warn) {
        log_write(out, LogLevel::Warn, std::format(fmt, std::forward<Args>(args)...), std::source_location::current());
    }
}

export template<typename... Args>
void log_info(Output &out, std::format_string<Args...> fmt, Args&&... args) {
    if constexpr (CURRENT_LOG_LEVEL >= LogLevel::Info) {
        log_write(out, LogLevel::Info, std::format(fmt, std::forward<Args>(args)...), std::source_location::current());
    }
}

export template<typename... Args>
void log_debug(Output &out, std::format_string<Args...> fmt, Args&&... args) {
    if constexpr (CURRENT_LOG_LEVEL >= LogLevel::Debug) {
        log_write(out, LogLevel::Debug, std::format(fmt, std::forward<Args>(args)...), std::source_location::current());
    }
}
