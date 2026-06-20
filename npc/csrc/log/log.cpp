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
    auto now = std::chrono::zoned_time{std::chrono::current_zone(), std::chrono::system_clock::now()};
    auto filename = std::format("log/{:%Y%m%d-%H%M%S}.log", now);
    log_file.open(filename);
#endif
}

void log_close() {
#ifdef CONFIG_LOG_TO_FILE
    if (log_file.is_open()) log_file.close();
#endif
}

void log_write(LogLevel level, std::string_view msg, std::source_location loc) {
    std::string line;
#ifdef CONFIG_LOG_TIMESTAMP
    auto now = std::chrono::zoned_time{std::chrono::current_zone(), std::chrono::system_clock::now()};
    line += std::format("[{:%H:%M:%S}] ", now);
#endif
    line += std::format("[{}] ", level_str(level));
#ifdef CONFIG_LOG_SOURCE_LOCATION
    line += std::format("{}:{} ", loc.file_name(), loc.line());
#endif
    line += msg;
    std::println(std::cerr, "{}", line);
#ifdef CONFIG_LOG_TO_FILE
    if (log_file.is_open()) {
        std::println(log_file, "{}", line);
    }
#endif
}
