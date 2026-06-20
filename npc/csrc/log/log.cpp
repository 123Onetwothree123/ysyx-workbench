module npc.log;

static std::ofstream log_file;

static std::string_view level_str(LogLevel level) {
    switch (level) {
    case LogLevel::Error: return "ERROR";
    case LogLevel::Warn:  return "WARN";
    case LogLevel::Info:  return "INFO";
    case LogLevel::Debug: return "DEBUG";
    default: return "???";
    }
}

void log_init() {
#ifdef CONFIG_LOG_TO_FILE
    std::filesystem::create_directories("log");
    auto now = std::chrono::system_clock::now();
    auto time = std::chrono::system_clock::to_time_t(now);
    std::tm tm{};
    localtime_r(&time, &tm);
    auto filename = std::format("log/{:04}{:02}{:02}-{:02}{:02}{:02}.log",
        tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday,
        tm.tm_hour, tm.tm_min, tm.tm_sec);
    log_file.open(filename);
#endif
}

void log_close() {
#ifdef CONFIG_LOG_TO_FILE
    if (log_file.is_open()) log_file.close();
#endif
}

void log_write(LogLevel level, std::string_view msg) {
    std::println(std::cerr, "[{}] {}", level_str(level), msg);
#ifdef CONFIG_LOG_TO_FILE
    if (log_file.is_open()) {
        std::println(log_file, "[{}] {}", level_str(level), msg);
    }
#endif
}
