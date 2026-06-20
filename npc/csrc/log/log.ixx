export module npc.log;
import std;

#ifndef CONFIG_LOG_LEVEL
#define CONFIG_LOG_LEVEL 3
#endif

export enum class LogLevel { Silent = 0, Error = 1, Warn = 2, Info = 3, Debug = 4 };
export inline constexpr auto CURRENT_LOG_LEVEL = static_cast<LogLevel>(CONFIG_LOG_LEVEL);

export void log_init();
export void log_close();

export template<typename... Args>
void log_error(std::format_string<Args...> fmt, Args&&... args) {
    if constexpr (CURRENT_LOG_LEVEL >= LogLevel::Error) {
        auto msg = std::format(fmt, std::forward<Args>(args)...);
        log_write(LogLevel::Error, msg);
    }
}

export template<typename... Args>
void log_warn(std::format_string<Args...> fmt, Args&&... args) {
    if constexpr (CURRENT_LOG_LEVEL >= LogLevel::Warn) {
        auto msg = std::format(fmt, std::forward<Args>(args)...);
        log_write(LogLevel::Warn, msg);
    }
}

export template<typename... Args>
void log_info(std::format_string<Args...> fmt, Args&&... args) {
    if constexpr (CURRENT_LOG_LEVEL >= LogLevel::Info) {
        auto msg = std::format(fmt, std::forward<Args>(args)...);
        log_write(LogLevel::Info, msg);
    }
}

export template<typename... Args>
void log_debug(std::format_string<Args...> fmt, Args&&... args) {
    if constexpr (CURRENT_LOG_LEVEL >= LogLevel::Debug) {
        auto msg = std::format(fmt, std::forward<Args>(args)...);
        log_write(LogLevel::Debug, msg);
    }
}

export void log_write(LogLevel level, std::string_view msg);
