module;
#include <cerrno>
#include <csignal>
#include <stdio.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
module trace;
import std;
import types;

TraceReader::TraceReader(const std::string& path)
{
    // 支持 bzip2 压缩文件
    if (path.ends_with(".bz2")) {
        // Do not concatenate a user-controlled path into a shell command.
        // Besides command injection, popen() only reports whether the shell
        // started, so a missing/corrupt input used to look like a successful
        // empty trace.  Execute bzip2 with an argv vector and retain its PID so
        // EOF can be distinguished from decompression failure.
        int pipe_fds[2]{};
        if (::pipe(pipe_fds) != 0) {
            throw std::runtime_error(std::format(
                "无法为 bzip2 创建管道: {}",
                std::error_code{errno, std::generic_category()}.message()));
        }
        const auto child{::fork()};
        if (child < 0) {
            const auto error{std::error_code{errno, std::generic_category()}.message()};
            ::close(pipe_fds[0]);
            ::close(pipe_fds[1]);
            throw std::runtime_error(std::format("无法启动 bzip2: {}", error));
        }
        if (child == 0) {
            ::close(pipe_fds[0]);
            if (::dup2(pipe_fds[1], STDOUT_FILENO) < 0) {
                ::_exit(126);
            }
            ::close(pipe_fds[1]);
            ::execlp("bzip2", "bzip2", "-dc", "--", path.c_str(),
                     static_cast<char*>(nullptr));
            ::_exit(127);
        }
        ::close(pipe_fds[1]);
        file_ = ::fdopen(pipe_fds[0], "r");
        if (!file_) {
            const auto error{std::error_code{errno, std::generic_category()}.message()};
            ::close(pipe_fds[0]);
            ::kill(child, SIGTERM);
            int ignored_status{};
            while (::waitpid(child, &ignored_status, 0) < 0 && errno == EINTR) {
            }
            throw std::runtime_error(std::format(
                "无法读取 bzip2 输出: {}", error));
        }
        compressed_child_pid_ = static_cast<int>(child);
        is_pipe_ = true;
    } else {
        file_ = std::fopen(path.c_str(), "rb");
        is_pipe_ = false;
    }

    if (!file_)
        throw std::runtime_error("无法打开 trace 文件: " + path);
}

TraceReader::~TraceReader()
{
    if (file_) {
        std::fclose(file_);
        file_ = nullptr;
    }
    if (compressed_child_pid_ >= 0) {
        int ignored_status{};
        while (::waitpid(static_cast<pid_t>(compressed_child_pid_),
                         &ignored_status, 0) < 0 && errno == EINTR) {
        }
        compressed_child_pid_ = -1;
    }
}

void TraceReader::finish_compressed_input()
{
    if (!is_pipe_ || compressed_child_pid_ < 0)
        return;

    if (file_) {
        std::fclose(file_);
        file_ = nullptr;
    }
    int status{};
    pid_t waited{};
    do {
        waited = ::waitpid(static_cast<pid_t>(compressed_child_pid_), &status, 0);
    } while (waited < 0 && errno == EINTR);
    compressed_child_pid_ = -1;

    if (waited < 0) {
        throw std::runtime_error(std::format(
            "等待 bzip2 失败: {}",
            std::error_code{errno, std::generic_category()}.message()));
    }
    if (WIFEXITED(status) && WEXITSTATUS(status) == 0)
        return;
    if (WIFEXITED(status)) {
        throw std::runtime_error(std::format(
            "bzip2 解压失败，退出码 {}", WEXITSTATUS(status)));
    }
    if (WIFSIGNALED(status)) {
        throw std::runtime_error(std::format(
            "bzip2 解压被信号 {} 终止", WTERMSIG(status)));
    }
    throw std::runtime_error("bzip2 解压异常终止");
}

namespace
{
    std::optional<Addr> ParseNumber(std::string_view text, int base)
    {
        if (text.empty())
        {
            return std::nullopt;
        }
        Addr value{};
        const auto [ptr, ec]{std::from_chars(text.data(), text.data() + text.size(), value, base)};
        if (ec == std::errc{} && ptr == text.data() + text.size())
        {
            return value;
        }
        return std::nullopt;
    }

    std::optional<Addr> ParseMtraceAddress(std::string_view line, std::string_view marker)
    {
        const auto markerPosition{line.find(marker)};
        if (markerPosition == std::string_view::npos)
        {
            return std::nullopt;
        }

        auto addressText{line.substr(markerPosition + marker.size())};
        const auto digitEnd{addressText.find_first_not_of("0123456789abcdefABCDEF")};
        if (digitEnd == 0)
        {
            return std::nullopt;
        }
        if (digitEnd != std::string_view::npos)
        {
            const auto delimiter{static_cast<unsigned char>(addressText[digitEnd])};
            if (addressText[digitEnd] != ',' && !std::isspace(delimiter))
            {
                return std::nullopt;
            }
            addressText = addressText.substr(0, digitEnd);
        }
        return ParseNumber(addressText, 16);
    }
}

auto TraceReader::try_parse(const std::string& line) -> std::optional<Addr>
{
    if (line.empty())
        return std::nullopt;

    if (line[0] == '#' || line[0] == '/' || line[0] == '\n' || line[0] == '\r')
        return std::nullopt;

    // mtrace 格式: "Memory Read: PC=0x..., Addr=0x..., Data=..."
    if (line.starts_with("Memory Read") || line.starts_with("Memory Write")) {
        if (const auto address{ParseMtraceAddress(line, "Addr=0x")})
            return address;
        return ParseMtraceAddress(line, "Addr=0X");
    }

    if (line.starts_with("0x") || line.starts_with("0X"))
        return ParseNumber(std::string_view{line}.substr(2), 16);
    // 原 stoul(..., 0) 的 base 0 自动检测会八进制解释 "0123"
    // from_chars 统一按十进制, 消除了这个坑
    return ParseNumber(line, 10);
}

auto TraceReader::next() -> std::optional<Addr>
{
    if (!file_)
        return std::nullopt;

    char buf[256];
    while (std::fgets(buf, sizeof(buf), file_)) {
        std::string line(buf);
        while (!line.empty() && (line.back() == '\n' || line.back() == '\r'))
            line.pop_back();

        auto result = try_parse(line);
        if (result) {
            ++count_;
            return result;
        }
    }
    if (std::ferror(file_)) {
        throw std::runtime_error("读取 trace 文件失败");
    }
    finish_compressed_input();
    return std::nullopt;
}
