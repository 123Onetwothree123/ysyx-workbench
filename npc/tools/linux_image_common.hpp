// Shared helpers for the C++ NPC build/audit tools.
//
// These tools intentionally keep the process boundary at the external
// programs (cpp, dtc, sha256sum).  Build-system and binary-layout rules live
// in C++; vendor programs remain ordinary child processes.

#pragma once

#include <algorithm>
#include <array>
#include <cstdlib>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include <fcntl.h>
#include <sys/wait.h>
#include <unistd.h>

namespace npc::linux_image {

namespace fs = std::filesystem;

inline std::string envValue(const char *name, std::string fallback = {})
{
    const char *value = std::getenv(name);
    return value != nullptr ? std::string{value} : std::move(fallback);
}

inline std::string envDefault(const char *name, std::string fallback)
{
    const auto value = envValue(name);
    return value.empty() ? std::move(fallback) : value;
}

inline std::string firstNonEmpty(std::initializer_list<std::string> values)
{
    for (const auto &value : values) {
        if (!value.empty())
            return value;
    }
    return {};
}

inline std::string stripOuterQuotes(std::string value)
{
    if (value.size() >= 2 && value.front() == '"' && value.back() == '"')
        return value.substr(1, value.size() - 2);
    return value;
}

inline std::string removeTrailingNewlines(std::string value)
{
    while (!value.empty() &&
           (value.back() == '\n' || value.back() == '\r'))
        value.pop_back();
    return value;
}

inline std::string trim(std::string value)
{
    const auto first = value.find_first_not_of(" \t\r\n");
    if (first == std::string::npos)
        return {};
    const auto last = value.find_last_not_of(" \t\r\n");
    return value.substr(first, last - first + 1);
}

inline std::uint64_t parseU64(const std::string &text, const std::string &name)
{
    if (text.empty())
        throw std::runtime_error(name + " 不能为空");
    std::size_t consumed = 0;
    unsigned long long value = 0;
    try {
        value = std::stoull(text, &consumed, 0);
    } catch (const std::exception &) {
        throw std::runtime_error(name + "='" + text +
                                 "' 不是合法整数（支持十进制或 0x 十六进制）");
    }
    if (consumed != text.size())
        throw std::runtime_error(name + "='" + text +
                                 "' 不是合法整数（支持十进制或 0x 十六进制）");
    return static_cast<std::uint64_t>(value);
}

inline std::uint64_t checkedAdd(std::uint64_t left, std::uint64_t right,
                                const std::string &what)
{
    if (left > std::numeric_limits<std::uint64_t>::max() - right)
        throw std::runtime_error(what + " 地址+大小溢出");
    return left + right;
}

inline std::string hexU64(std::uint64_t value)
{
    std::ostringstream output;
    output << "0x" << std::hex << value;
    return output.str();
}

inline std::string hexName(std::uint64_t value)
{
    std::ostringstream output;
    output << std::hex << value;
    return output.str();
}

inline std::uint64_t fileSize(const fs::path &path)
{
    std::error_code error;
    const auto size = fs::file_size(path, error);
    if (error)
        throw std::runtime_error("无法获取文件大小: " + path.string() + ": " +
                                 error.message());
    return static_cast<std::uint64_t>(size);
}

inline std::vector<std::uint8_t> readFile(const fs::path &path)
{
    std::ifstream input{path, std::ios::binary};
    if (!input)
        throw std::runtime_error("无法打开文件: " + path.string());
    input.seekg(0, std::ios::end);
    const auto end = input.tellg();
    if (end < 0)
        throw std::runtime_error("无法获取文件大小: " + path.string());
    input.seekg(0, std::ios::beg);
    std::vector<std::uint8_t> data(static_cast<std::size_t>(end));
    if (!data.empty()) {
        input.read(reinterpret_cast<char *>(data.data()),
                   static_cast<std::streamsize>(data.size()));
    }
    if (!input && !input.eof())
        throw std::runtime_error("读取文件失败: " + path.string());
    return data;
}

inline void writeFile(const fs::path &path,
                      const std::vector<std::uint8_t> &data)
{
    std::ofstream output{path, std::ios::binary | std::ios::trunc};
    if (!output)
        throw std::runtime_error("无法创建文件: " + path.string());
    if (!data.empty()) {
        output.write(reinterpret_cast<const char *>(data.data()),
                     static_cast<std::streamsize>(data.size()));
    }
    if (!output)
        throw std::runtime_error("写入文件失败: " + path.string());
}

inline void writeText(const fs::path &path, const std::string &text)
{
    std::ofstream output{path, std::ios::binary | std::ios::trunc};
    if (!output)
        throw std::runtime_error("无法创建文件: " + path.string());
    output << text;
    if (!output)
        throw std::runtime_error("写入文件失败: " + path.string());
}

inline void copyFile(const fs::path &from, const fs::path &to)
{
    const auto data = readFile(from);
    writeFile(to, data);
}

inline std::string sha256File(const fs::path &path);

struct CommandResult {
    int exitCode = -1;
    std::string output;
};

inline std::string commandName(const std::vector<std::string> &args)
{
    std::ostringstream output;
    for (std::size_t index = 0; index < args.size(); ++index) {
        if (index != 0)
            output << ' ';
        output << args[index];
    }
    return output.str();
}

inline CommandResult runCapture(const std::vector<std::string> &args)
{
    if (args.empty())
        throw std::runtime_error("空命令");
    int pipefd[2];
    if (::pipe(pipefd) != 0)
        throw std::runtime_error("创建管道失败: " + std::string{std::strerror(errno)});

    const pid_t pid = ::fork();
    if (pid < 0) {
        ::close(pipefd[0]);
        ::close(pipefd[1]);
        throw std::runtime_error("fork 失败: " + std::string{std::strerror(errno)});
    }
    if (pid == 0) {
        ::close(pipefd[0]);
        if (::dup2(pipefd[1], STDOUT_FILENO) < 0)
            ::_exit(127);
        ::close(pipefd[1]);
        std::vector<char *> argv;
        argv.reserve(args.size() + 1);
        for (const auto &arg : args)
            argv.push_back(const_cast<char *>(arg.c_str()));
        argv.push_back(nullptr);
        ::execvp(argv.front(), argv.data());
        std::cerr << "执行失败：" << commandName(args) << ": "
                  << std::strerror(errno) << '\n';
        ::_exit(127);
    }

    ::close(pipefd[1]);
    std::string output;
    std::array<char, 4096> buffer{};
    ssize_t count = 0;
    while ((count = ::read(pipefd[0], buffer.data(), buffer.size())) > 0)
        output.append(buffer.data(), static_cast<std::size_t>(count));
    ::close(pipefd[0]);

    int status = 0;
    while (::waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR)
            throw std::runtime_error("等待子进程失败: " +
                                     std::string{std::strerror(errno)});
    }
    int exitCode = 1;
    if (WIFEXITED(status))
        exitCode = WEXITSTATUS(status);
    else if (WIFSIGNALED(status))
        exitCode = 128 + WTERMSIG(status);
    return {exitCode, std::move(output)};
}

inline void runInherit(const std::vector<std::string> &args,
                       const std::vector<std::pair<std::string, std::string>>
                           &environment = {},
                       const std::optional<fs::path> &stdoutPath = std::nullopt)
{
    if (args.empty())
        throw std::runtime_error("空命令");
    const pid_t pid = ::fork();
    if (pid < 0)
        throw std::runtime_error("fork 失败: " + std::string{std::strerror(errno)});
    if (pid == 0) {
        for (const auto &[key, value] : environment)
            ::setenv(key.c_str(), value.c_str(), 1);
        if (stdoutPath.has_value()) {
            const int fd = ::open(stdoutPath->c_str(),
                                  O_WRONLY | O_CREAT | O_TRUNC, 0644);
            if (fd < 0)
                ::_exit(127);
            if (::dup2(fd, STDOUT_FILENO) < 0)
                ::_exit(127);
            ::close(fd);
        }
        std::vector<char *> argv;
        argv.reserve(args.size() + 1);
        for (const auto &arg : args)
            argv.push_back(const_cast<char *>(arg.c_str()));
        argv.push_back(nullptr);
        ::execvp(argv.front(), argv.data());
        std::cerr << "执行失败：" << commandName(args) << ": "
                  << std::strerror(errno) << '\n';
        ::_exit(127);
    }

    int status = 0;
    while (::waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR)
            throw std::runtime_error("等待子进程失败: " +
                                     std::string{std::strerror(errno)});
    }
    if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        const int code = WIFEXITED(status) ? WEXITSTATUS(status)
                                           : 128 + WTERMSIG(status);
        throw std::runtime_error("命令失败（退出码 " + std::to_string(code) +
                                 "）：" + commandName(args));
    }
}

inline std::string sha256File(const fs::path &path)
{
    const auto result = runCapture({"sha256sum", path.string()});
    if (result.exitCode != 0)
        throw std::runtime_error("sha256sum 失败: " + path.string());
    std::istringstream input(result.output);
    std::string digest;
    input >> digest;
    if (digest.size() != 64)
        throw std::runtime_error("sha256sum 返回非法摘要: " + path.string());
    return digest;
}

inline std::string cppEscape(std::string value)
{
    std::string result;
    result.reserve(value.size());
    for (const char character : value) {
        if (character == '\\' || character == '"')
            result.push_back('\\');
        result.push_back(character);
    }
    return result;
}

inline fs::path resolvePath(const std::string &value, const fs::path &base,
                            const fs::path &home)
{
    if (value.empty())
        return {};
    if (value.front() == '/')
        return fs::path{value};
    if (value.starts_with("~/"))
        return home / value.substr(2);
    return base / value;
}

inline std::uint64_t readLe64(const fs::path &path, std::uint64_t offset)
{
    const auto data = readFile(path);
    if (offset > data.size() || data.size() - offset < 8)
        throw std::runtime_error("文件太短，无法读取 64 位字段: " + path.string());
    std::uint64_t value = 0;
    for (int index = 0; index < 8; ++index)
        value |= static_cast<std::uint64_t>(data[offset + index]) << (index * 8);
    return value;
}

inline std::string firstHex(const fs::path &path, std::size_t count)
{
    const auto data = readFile(path);
    const auto length = std::min(count, data.size());
    std::ostringstream output;
    output << std::hex << std::setfill('0');
    for (std::size_t index = 0; index < length; ++index)
        output << std::setw(2) << static_cast<unsigned>(data[index]);
    return output.str();
}

class ConfigReader {
public:
    ConfigReader(std::string npcHome, std::string workbenchHome)
        : npcHome_(std::move(npcHome)),
          workbenchHome_(std::move(workbenchHome))
    {
        jsonPath_ = firstNonEmpty({
            envValue("SBI_CONFIG_JSON"),
            envValue("NPC_CONFIG_JSON"),
            envValue("NPC_CONFIG_EFFECTIVE_JSON"),
            (fs::path{npcHome_} / "build" / "generated" /
             "npc_config.effective.json")
                .string(),
        });
        configPath_ = firstNonEmpty({
            envValue("SBI_CONFIG_FILE"),
            envValue("NPC_CONFIG_FILE"),
            (fs::path{npcHome_} / ".config").string(),
        });

        std::error_code error;
        if (fs::is_regular_file(jsonPath_, error)) {
            jsonSource_ = true;
            configGen_ = envDefault("NPC_CONFIG_GEN",
                                    (fs::path{npcHome_} / "tools" / "build" /
                                     "npc_config_gen")
                                        .string());
            if (!fs::is_regular_file(configGen_, error) ||
                ::access(configGen_.c_str(), X_OK) != 0) {
                throw std::runtime_error(
                    "找不到 npc_config_gen：" + configGen_ +
                    "；先运行 make -C npc npc-config-tool 或设置 "
                    "NPC_CONFIG_GEN");
            }
            return;
        }
        if (fs::is_regular_file(configPath_, error)) {
            jsonSource_ = false;
            loadLegacyConfig();
            return;
        }
        throw std::runtime_error(
            "找不到配置 JSON：" + jsonPath_ +
            "；先运行 make -C npc npc-config 生成，或设置 "
            "SBI_CONFIG_FILE 使用 legacy 配置");
    }

    [[nodiscard]] bool isJson() const { return jsonSource_; }
    [[nodiscard]] const std::string &sourcePath() const
    {
        return jsonSource_ ? jsonPath_ : configPath_;
    }
    [[nodiscard]] const std::string &npcHome() const { return npcHome_; }
    [[nodiscard]] const std::string &workbenchHome() const
    {
        return workbenchHome_;
    }

    std::string raw(const std::string &key)
    {
        if (jsonSource_) {
            const auto cached = cache_.find(key);
            if (cached != cache_.end())
                return cached->second;
            const auto result = runCapture(
                {configGen_, "--config-json", jsonPath_, "--get", key});
            if (result.exitCode != 0)
                throw std::runtime_error("读取配置 JSON 失败：" + jsonPath_ +
                                         "（键 " + key + "）");
            std::string value = removeTrailingNewlines(result.output);
            cache_.emplace(key, value);
            return value;
        }
        const auto found = legacy_.find(key);
        return found == legacy_.end() ? std::string{} : found->second;
    }

    std::string value(const std::string &key, const std::string &fallback = {})
    {
        const auto rawValue = raw(key);
        if (rawValue.empty())
            return fallback;
        return jsonSource_ ? rawValue : stripOuterQuotes(rawValue);
    }

    bool boolean(const std::string &key, bool fallback)
    {
        if (!jsonSource_ && notSet_.contains(key))
            return false;
        const auto rawValue = value(key, fallback ? "y" : "n");
        return rawValue == "y" || rawValue == "Y" || rawValue == "1" ||
               rawValue == "true" || rawValue == "TRUE";
    }

private:
    void loadLegacyConfig()
    {
        std::ifstream input{configPath_};
        if (!input)
            throw std::runtime_error("无法读取配置文件：" + configPath_);
        std::string line;
        while (std::getline(input, line)) {
            if (line.starts_with("# ") && line.ends_with(" is not set")) {
                const auto key = line.substr(2, line.size() - 2 -
                                                   std::string{" is not set"}.size());
                legacy_[key] = "";
                notSet_.insert(key);
                continue;
            }
            const auto equals = line.find('=');
            if (equals == std::string::npos)
                continue;
            legacy_[line.substr(0, equals)] = line.substr(equals + 1);
        }
    }

    std::string npcHome_;
    std::string workbenchHome_;
    std::string jsonPath_;
    std::string configPath_;
    std::string configGen_;
    bool jsonSource_ = false;
    std::unordered_map<std::string, std::string> legacy_;
    std::unordered_set<std::string> notSet_;
    std::unordered_map<std::string, std::string> cache_;
};

inline std::string locateNpcHome(const char *argv0)
{
    (void)argv0;
    const std::string fromEnv = envValue("NPC_HOME");
    if (!fromEnv.empty())
        return fs::absolute(fromEnv).lexically_normal().string();

    std::error_code error;
    const fs::path executable = fs::read_symlink("/proc/self/exe", error);
    if (!error) {
        const fs::path candidate =
            executable.parent_path().parent_path().parent_path();
        if (fs::is_regular_file(candidate / "scripts" / "build_dtb.sh"))
            return fs::canonical(candidate, error).string();
    }

    fs::path current = fs::current_path(error);
    if (!error) {
        for (int depth = 0; depth < 8; ++depth) {
            if (fs::is_regular_file(current / "scripts" / "build_dtb.sh"))
                return fs::canonical(current, error).string();
            if (!current.has_parent_path() || current.parent_path() == current)
                break;
            current = current.parent_path();
        }
    }
    throw std::runtime_error(
        "无法定位 NPC_HOME；请在 npc/ 下运行或设置 NPC_HOME");
}

inline std::string locateWorkbenchHome(const std::string &npcHome)
{
    return envDefault("WORKBENCH_HOME",
                      fs::path{npcHome}.parent_path().string());
}

} // namespace npc::linux_image
