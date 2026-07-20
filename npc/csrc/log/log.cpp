module npc.log;

import <unistd.h>;

static std::ofstream log_file;
static int saved_stdout_fd{-1};
static int saved_stderr_fd{-1};
static int pipefd[2]{-1, -1};
static std::thread tee_thread;

static void tee_loop() {
    char buf[4096];
    while (true) {
        auto n{read(pipefd[0], buf, sizeof(buf))};
        if (n <= 0) break;
        write(saved_stdout_fd, buf, static_cast<std::size_t>(n));
        if (log_file.is_open()) {
            log_file.write(buf, n);
            log_file.flush();
        }
    }
}

#ifndef CONFIG_LOG_DIR
#define CONFIG_LOG_DIR "log"
#endif

void log_init() {
#ifdef CONFIG_LOG_TO_FILE
    std::filesystem::create_directories(CONFIG_LOG_DIR);
    auto now{std::chrono::zoned_time{std::chrono::current_zone(), std::chrono::system_clock::now()}};
    auto filename{std::format("{}/{:%Y%m%d-%H%M%S}.log", CONFIG_LOG_DIR, now)};
    log_file.open(filename);

    saved_stdout_fd = dup(STDOUT_FILENO);
    saved_stderr_fd = dup(STDERR_FILENO);

    if (pipe(pipefd) == 0) {
        tee_thread = std::thread(tee_loop);
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[1]);
    }
#endif
}

void log_close() {
#ifdef CONFIG_LOG_TO_FILE
    fflush(stdout);
    fflush(stderr);
    if (saved_stdout_fd >= 0) {
        dup2(saved_stdout_fd, STDOUT_FILENO);
        close(saved_stdout_fd);
        saved_stdout_fd = -1;
    }
    if (saved_stderr_fd >= 0) {
        dup2(saved_stderr_fd, STDERR_FILENO);
        close(saved_stderr_fd);
        saved_stderr_fd = -1;
    }
    if (tee_thread.joinable()) {
        tee_thread.join();
    }
    if (pipefd[0] >= 0) {
        close(pipefd[0]);
        pipefd[0] = -1;
    }
    if (log_file.is_open()) {
        log_file.close();
    }
#endif
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
#ifdef CONFIG_LOG_TIMESTAMP
    auto now{std::chrono::zoned_time{std::chrono::current_zone(), std::chrono::system_clock::now()}};
    line += std::format("[{:%H:%M:%S}] ", now);
#endif
    line += std::format("[{}] ", level_str(level));
#ifdef CONFIG_LOG_SOURCE_LOCATION
    line += std::format("{}:{} ", loc.file_name(), loc.line());
#endif
    line += msg;
    std::println(std::cerr, "{}", line);
}
