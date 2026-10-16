export module npc.log;
import std;

#ifndef CONFIG_LOG_LEVEL
#define CONFIG_LOG_LEVEL 3
#endif

export enum class LogLevel { Silent = 0, Error = 1, Warn = 2, Info = 3, Debug = 4 };
export inline constexpr auto CURRENT_LOG_LEVEL = static_cast<LogLevel>(CONFIG_LOG_LEVEL);

// Logging is initialized before the DUT exists.  Report filesystem/open
// failures to main instead of allowing filesystem_error to terminate the
// process before the normal error path can run.
export [[nodiscard]] std::expected<void, std::string> log_init() noexcept;
export void log_close();
export void log_write(LogLevel level, std::string_view msg, std::source_location loc);

export template<typename... Args>
void log_error(std::format_string<Args...> fmt, Args&&... args) {
    if constexpr (CURRENT_LOG_LEVEL >= LogLevel::Error) {
        log_write(LogLevel::Error, std::format(fmt, std::forward<Args>(args)...), std::source_location::current());
    }
}

export template<typename... Args>
void log_warn(std::format_string<Args...> fmt, Args&&... args) {
    if constexpr (CURRENT_LOG_LEVEL >= LogLevel::Warn) {
        log_write(LogLevel::Warn, std::format(fmt, std::forward<Args>(args)...), std::source_location::current());
    }
}

export template<typename... Args>
void log_info(std::format_string<Args...> fmt, Args&&... args) {
    if constexpr (CURRENT_LOG_LEVEL >= LogLevel::Info) {
        log_write(LogLevel::Info, std::format(fmt, std::forward<Args>(args)...), std::source_location::current());
    }
}

export template<typename... Args>
void log_debug(std::format_string<Args...> fmt, Args&&... args) {
    if constexpr (CURRENT_LOG_LEVEL >= LogLevel::Debug) {
        log_write(LogLevel::Debug, std::format(fmt, std::forward<Args>(args)...), std::source_location::current());
    }
}
