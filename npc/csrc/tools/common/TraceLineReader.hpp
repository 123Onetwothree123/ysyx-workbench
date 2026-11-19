#pragma once
#include <cerrno>
#include <charconv>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <format>
#include <optional>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <system_error>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>
namespace common
{
inline auto IsCommentLine(std::string_view line) -> bool
{
    return line.empty() || line[0] == '#' || line[0] == '/';
}
inline auto SplitFields(std::string_view line, std::string_view separators = " \t")
    -> std::vector<std::string_view>
{
    std::vector<std::string_view> fields{};
    std::size_t i{0};
    while (i < line.size())
    {
        while (i < line.size() && separators.find(line[i]) != std::string_view::npos)
            ++i;
        const auto begin{i};
        while (i < line.size() && separators.find(line[i]) == std::string_view::npos)
            ++i;
        if (i > begin)
            fields.push_back(line.substr(begin, i - begin));
    }
    return fields;
}
inline auto FieldCountIn(std::span<const std::string_view> fields,
                         std::size_t min_fields, std::size_t max_fields) -> bool
{
    return fields.size() >= min_fields && fields.size() <= max_fields;
}
// 数字必须占满整个字段（尾随字符校验）
template <typename T>
inline auto ParseFull(std::string_view text, int base = 10) -> std::optional<T>
{
    T value{};
    const auto* begin{text.data()};
    const auto* end{begin + text.size()};
    const auto [ptr, ec]{std::from_chars(begin, end, value, base)};
    if (ec != std::errc{} || ptr != end || ptr == begin)
        return std::nullopt;
    return value;
}
class LineReader
{
public:
    explicit LineReader(const std::string& path, bool allow_bz2 = true)
    {
        if (allow_bz2 && path.ends_with(".bz2"))
            OpenCompressed(path);
        else
            file_ = std::fopen(path.c_str(), "rb");
        if (!file_)
            throw std::runtime_error(std::format("无法打开 trace 文件: {}", path));
    }
    ~LineReader()
    {
        if (file_)
        {
            std::fclose(file_);
            file_ = nullptr;
        }
        if (child_pid_ >= 0)
        {
            int ignored_status{};
            while (::waitpid(static_cast<pid_t>(child_pid_), &ignored_status, 0) < 0 && errno == EINTR)
            {
            }
            child_pid_ = -1;
        }
        std::free(buffer_);
    }
    LineReader(const LineReader&) = delete;
    LineReader& operator=(const LineReader&) = delete;
    // 读一行（去掉行尾 \r\n）；EOF 返回 false，读/解压错误抛异常
    auto Next(std::string& line) -> bool
    {
        if (!file_)
            return false;
        ssize_t length{};
        while ((length = ::getline(&buffer_, &capacity_, file_)) != -1)
        {
            line.assign(buffer_, static_cast<std::size_t>(length));
            while (!line.empty() && (line.back() == '\n' || line.back() == '\r'))
                line.pop_back();
            return true;
        }
        if (std::ferror(file_))
            throw std::runtime_error("读取 trace 文件失败");
        FinishCompressed();
        return false;
    }
private:
    void OpenCompressed(const std::string& path)
    {
        // argv 启动 bzip2 防注入；保留 pid 区分 EOF/解压失败。
        int pipe_fds[2]{};
        if (::pipe(pipe_fds) != 0)
            throw std::runtime_error(std::format("无法为 bzip2 创建管道: {}", std::error_code{errno, std::generic_category()}.message()));
        const auto child{::fork()};
        if (child < 0)
        {
            const auto error{std::error_code{errno, std::generic_category()}.message()};
            ::close(pipe_fds[0]);
            ::close(pipe_fds[1]);
            throw std::runtime_error(std::format("无法启动 bzip2: {}", error));
        }
        if (child == 0)
        {
            ::close(pipe_fds[0]);
            if (::dup2(pipe_fds[1], STDOUT_FILENO) < 0)
                ::_exit(126);
            ::close(pipe_fds[1]);
            ::execlp("bzip2", "bzip2", "-dc", "--", path.c_str(), static_cast<char*>(nullptr));
            ::_exit(127);
        }
        ::close(pipe_fds[1]);
        file_ = ::fdopen(pipe_fds[0], "r");
        if (!file_)
        {
            const auto error{std::error_code{errno, std::generic_category()}.message()};
            ::close(pipe_fds[0]);
            ::kill(child, SIGTERM);
            int ignored_status{};
            while (::waitpid(child, &ignored_status, 0) < 0 && errno == EINTR)
            {
            }
            throw std::runtime_error(std::format("无法读取 bzip2 输出: {}", error));
        }
        child_pid_ = static_cast<int>(child);
    }
    void FinishCompressed()
    {
        if (child_pid_ < 0)
            return;
        if (file_)
        {
            std::fclose(file_);
            file_ = nullptr;
        }
        int status{};
        pid_t waited{};
        do
        {
            waited = ::waitpid(static_cast<pid_t>(child_pid_), &status, 0);
        } while (waited < 0 && errno == EINTR);
        child_pid_ = -1;
        if (waited < 0)
            throw std::runtime_error(std::format("等待 bzip2 失败: {}", std::error_code{errno, std::generic_category()}.message()));
        if (WIFEXITED(status) && WEXITSTATUS(status) == 0)
            return;
        if (WIFEXITED(status))
            throw std::runtime_error(std::format("bzip2 解压失败，退出码 {}", WEXITSTATUS(status)));
        if (WIFSIGNALED(status))
            throw std::runtime_error(std::format("bzip2 解压被信号 {} 终止", WTERMSIG(status)));
        throw std::runtime_error("bzip2 解压异常终止");
    }
    std::FILE* file_{};
    int child_pid_{-1};
    char* buffer_{};
    std::size_t capacity_{};
};
}
