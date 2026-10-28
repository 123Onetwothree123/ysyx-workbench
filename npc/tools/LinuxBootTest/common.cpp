module;
#include <errno.h>
#include <poll.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
module npc.linux_boot_test;
import std;
namespace npc::linux_boot_test {
Context g_ctx;
[[noreturn]] void fail(const std::string &message) {
    throw Failure(message);
}
void require(bool condition, const std::string &message) {
    if (!condition) {
        fail(message);
    }
}
std::string trim(std::string value) {
    const auto is_space = [](unsigned char c) { return std::isspace(c) != 0; };
    while (!value.empty() && is_space(static_cast<unsigned char>(value.front()))) {
        value.erase(value.begin());
    }
    while (!value.empty() && is_space(static_cast<unsigned char>(value.back()))) {
        value.pop_back();
    }
    return value;
}
std::string hex_u64(uint64_t value) {
    std::ostringstream out;
    out << "0x" << std::hex << value;
    return out.str();
}
std::string decimal_u64(uint64_t value) { return std::to_string(value); }
fs::path find_tool(const std::string &name) {
    if (name.find('/') != std::string::npos) {
        require(::access(name.c_str(), X_OK) == 0,
                "required executable is not available: " + name);
        return fs::path(name);
    }
    const char *path_env = std::getenv("PATH");
    require(path_env != nullptr && *path_env != '\0',
            "PATH is empty; cannot locate " + name);
    std::string paths(path_env);
    std::size_t begin = 0;
    while (begin <= paths.size()) {
        const std::size_t end = paths.find(':', begin);
        const std::string dir =
            paths.substr(begin, end == std::string::npos ? std::string::npos
                                                         : end - begin);
        if (!dir.empty()) {
            const fs::path candidate = fs::path(dir) / name;
            struct stat st {};
            if (::stat(candidate.c_str(), &st) == 0 && S_ISREG(st.st_mode) &&
                ::access(candidate.c_str(), X_OK) == 0) {
                return candidate;
            }
        }
        if (end == std::string::npos) {
            break;
        }
        begin = end + 1;
    }
    fail("required executable not found in PATH: " + name);
}
std::string command_display(const std::vector<std::string> &argv) {
    std::ostringstream out;
    for (std::size_t i = 0; i < argv.size(); ++i) {
        if (i != 0) {
            out << ' ';
        }
        out << argv[i];
    }
    return out.str();
}
CommandResult run_command(const std::vector<std::string> &argv,
                          const Env &overrides,
                          const std::optional<fs::path> &cwd) {
    require(!argv.empty(), "internal error: empty command line");
    require(!argv[0].empty(), "internal error: empty executable path");
    int out_pipe[2] = {-1, -1};
    int err_pipe[2] = {-1, -1};
    if (::pipe(out_pipe) != 0) {
        fail("pipe() failed: " + std::string(std::strerror(errno)));
    }
    if (::pipe(err_pipe) != 0) {
        const std::string error = std::strerror(errno);
        ::close(out_pipe[0]);
        ::close(out_pipe[1]);
        fail("pipe() failed: " + error);
    }
    std::vector<std::string> environment;
    for (char **entry = ::environ; entry != nullptr && *entry != nullptr;
         ++entry) {
        const std::string value(*entry);
        const std::size_t equals = value.find('=');
        if (equals == std::string::npos) {
            continue;
        }
        const std::string key = value.substr(0, equals);
        if (overrides.find(key) != overrides.end()) {
            continue;
        }
        if (key.starts_with("SBI_") || key.starts_with("LINUX_") ||
            key.starts_with("NPC_CONFIG_") || key.starts_with("NPC_RAM_")) {
            continue;
        }
        environment.push_back(value);
    }
    for (const auto &[key, value] : overrides) {
        environment.push_back(key + "=" + value);
    }
    std::vector<char *> argv_raw;
    argv_raw.reserve(argv.size() + 1);
    for (const auto &argument : argv) {
        argv_raw.push_back(const_cast<char *>(argument.c_str()));
    }
    argv_raw.push_back(nullptr);
    std::vector<char *> envp_raw;
    envp_raw.reserve(environment.size() + 1);
    for (auto &entry : environment) {
        envp_raw.push_back(entry.data());
    }
    envp_raw.push_back(nullptr);
    const pid_t pid = ::fork();
    if (pid < 0) {
        const std::string error = std::strerror(errno);
        ::close(out_pipe[0]);
        ::close(out_pipe[1]);
        ::close(err_pipe[0]);
        ::close(err_pipe[1]);
        fail("fork() failed: " + error);
    }
    if (pid == 0) {
        ::close(out_pipe[0]);
        ::close(err_pipe[0]);
        if (::dup2(out_pipe[1], STDOUT_FILENO) < 0 ||
            ::dup2(err_pipe[1], STDERR_FILENO) < 0) {
            ::_exit(126);
        }
        ::close(out_pipe[1]);
        ::close(err_pipe[1]);
        if (cwd.has_value() && ::chdir(cwd->c_str()) != 0) {
            ::_exit(126);
        }
        ::execve(argv_raw[0], argv_raw.data(), envp_raw.data());
        char message[512] = {};
        const int length = std::snprintf(
            message, sizeof(message), "failed to execute %s: %s\n",
            argv_raw[0], std::strerror(errno));
        if (length > 0) {
            ::write(STDERR_FILENO, message,
                    static_cast<std::size_t>(length));
        }
        ::_exit(127);
    }
    ::close(out_pipe[1]);
    ::close(err_pipe[1]);
    CommandResult result;
    result.command = command_display(argv);
    std::array<pollfd, 2> poll_fds{{
        {out_pipe[0], POLLIN, 0},
        {err_pipe[0], POLLIN, 0},
    }};
    std::array<std::string *, 2> destinations{{&result.out, &result.err}};
    int open_readers = 2;
    while (open_readers > 0) {
        const int ready = ::poll(poll_fds.data(), poll_fds.size(), -1);
        if (ready < 0) {
            if (errno == EINTR) {
                continue;
            }
            break;
        }
        for (std::size_t i = 0; i < poll_fds.size(); ++i) {
            if (poll_fds[i].fd < 0 || poll_fds[i].revents == 0) {
                continue;
            }
            char buffer[4096];
            const ssize_t got =
                ::read(poll_fds[i].fd, buffer, sizeof(buffer));
            if (got > 0) {
                destinations[i]->append(buffer, static_cast<std::size_t>(got));
            } else if (got == 0 || (errno != EINTR && errno != EAGAIN)) {
                ::close(poll_fds[i].fd);
                poll_fds[i].fd = -1;
                --open_readers;
            }
        }
    }
    for (auto &poll_fd : poll_fds) {
        if (poll_fd.fd >= 0) {
            ::close(poll_fd.fd);
            poll_fd.fd = -1;
        }
    }
    int status = 0;
    while (::waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) {
            const std::string error = std::strerror(errno);
            fail("waitpid() failed: " + error);
        }
    }
    if (WIFEXITED(status)) {
        result.rc = WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        result.rc = 128 + WTERMSIG(status);
    } else {
        result.rc = 255;
    }
    return result;
}
std::string describe(const CommandResult &result) {
    std::ostringstream out;
    out << "command: " << result.command << "\n";
    out << "return code: " << result.rc << "\n";
    out << "stdout:\n" << result.out << "\n";
    out << "stderr:\n" << result.err;
    return out.str();
}
void require_exit(const CommandResult &result, bool expect_ok,
                  const std::string &what) {
    if (expect_ok) {
        require(result.rc == 0,
                what + " failed (expected success)\n" + describe(result));
    } else {
        require(result.rc != 0,
                what + " unexpectedly succeeded (expected failure)\n" +
                    describe(result));
    }
}
void require_contains(const std::string &haystack, const std::string &needle,
                      const std::string &what, const CommandResult &result) {
    if (haystack.find(needle) == std::string::npos) {
        fail(what + ": expected output to contain \"" + needle + "\"\n" +
             describe(result));
    }
}
std::string read_text(const fs::path &path) {
    std::ifstream input(path, std::ios::binary);
    require(input.good(), "cannot open file for reading: " + path.string());
    std::ostringstream buffer;
    buffer << input.rdbuf();
    require(!input.bad(), "failed while reading: " + path.string());
    return buffer.str();
}
std::vector<uint8_t> read_binary(const fs::path &path) {
    std::ifstream input(path, std::ios::binary);
    require(input.good(), "cannot open file for reading: " + path.string());
    input.seekg(0, std::ios::end);
    const std::streamoff size = input.tellg();
    require(size >= 0, "cannot determine size of " + path.string());
    input.seekg(0, std::ios::beg);
    std::vector<uint8_t> data(static_cast<std::size_t>(size));
    if (!data.empty()) {
        input.read(reinterpret_cast<char *>(data.data()),
                   static_cast<std::streamsize>(data.size()));
        require(input.good(), "failed while reading: " + path.string());
    }
    return data;
}
void write_binary(const fs::path &path, const std::vector<uint8_t> &data) {
    if (path.has_parent_path()) {
        fs::create_directories(path.parent_path());
    }
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    require(output.good(), "cannot open file for writing: " + path.string());
    if (!data.empty()) {
        output.write(reinterpret_cast<const char *>(data.data()),
                     static_cast<std::streamsize>(data.size()));
    }
    output.close();
    require(output.good(), "failed while writing: " + path.string());
}
void write_text(const fs::path &path, const std::string &text) {
    if (path.has_parent_path()) {
        fs::create_directories(path.parent_path());
    }
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    require(output.good(), "cannot open file for writing: " + path.string());
    output << text;
    output.close();
    require(output.good(), "failed while writing: " + path.string());
}
uint64_t get_file_size(const fs::path &path) {
    std::error_code error;
    const uint64_t size = fs::file_size(path, error);
    require(!error, "cannot stat file " + path.string() + ": " +
                        error.message());
    return size;
}
fs::path executable_path(const char *argv0) {
    std::error_code error;
    const fs::path proc_path =
        fs::read_symlink("/proc/self/exe", error);
    if (!error && !proc_path.empty()) {
        const fs::path canonical = fs::canonical(proc_path, error);
        if (!error) {
            return canonical;
        }
        return proc_path;
    }
    error.clear();
    const fs::path argv_path = fs::absolute(fs::path(argv0), error);
    if (!error) {
        return argv_path;
    }
    return fs::path(argv0);
}
fs::path locate_npc_home(const char *argv0) {
    const char *env_home = std::getenv("NPC_HOME");
    if (env_home != nullptr && *env_home != '\0') {
        std::error_code error;
        const fs::path candidate = fs::absolute(fs::path(env_home), error);
        if (!error &&
            fs::is_regular_file(candidate / "scripts" / "build_dtb.sh")) {
            return fs::canonical(candidate, error);
        }
    }
    const fs::path executable = executable_path(argv0);
    std::error_code error;
    const fs::path candidate =
        executable.parent_path().parent_path().parent_path();
    if (fs::is_regular_file(candidate / "scripts" / "build_dtb.sh")) {
        return fs::canonical(candidate, error);
    }
    fs::path current = fs::current_path(error);
    if (!error) {
        for (int depth = 0; depth < 8; ++depth) {
            if (fs::is_regular_file(current / "scripts" / "build_dtb.sh") &&
                fs::is_regular_file(current / "scripts" /
                                    "pack_linux_image.sh")) {
                return fs::canonical(current, error);
            }
            if (!current.has_parent_path() ||
                current.parent_path() == current) {
                break;
            }
            current = current.parent_path();
        }
    }
    fail("cannot locate NPC_HOME; run from npc/ or set NPC_HOME");
}
std::string sha256_file(const fs::path &path) {
    const CommandResult result = run_command(
        {g_ctx.sha256sum.string(), path.string()}, {});
    require_exit(result, true, "sha256sum " + path.string());
    std::istringstream input(result.out);
    std::string digest;
    input >> digest;
    require(digest.size() == 64,
            "sha256sum returned an invalid digest for " + path.string() +
                "\n" + describe(result));
    return digest;
}
}
