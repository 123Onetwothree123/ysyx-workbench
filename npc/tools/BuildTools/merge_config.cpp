// Merge a Kconfig profile into .config and run the existing config tools.
//
// This replaces the path/fragment/process orchestration that used to live in
// scripts/merge_config.sh.  c_conf and kconfig_contract remain external
// binaries because they are the Kconfig implementation itself.

#include <unistd.h>

import npc.buildtools;
import std;

namespace {

using namespace npc::linux_image;

void usage()
{
    std::cerr
        << "用法: merge_config PROFILE\n\n"
        << "PROFILE 可以是 configs/profiles/ 下的名称（可省略 .profile），\n"
        << "也可以是 profile 文件路径。片段路径相对 npc/configs/ 解析。\n\n"
        << "示例:\n"
        << "  scripts/merge_config.sh rv64_linux\n"
        << "  scripts/merge_config.sh rv64_linux_uboot_128m\n"
        << "  scripts/merge_config.sh configs/profiles/rv64_linux.profile\n";
}

fs::path resolveProfile(const fs::path &npcHome, const std::string &argument)
{
    const fs::path configsDir = npcHome / "configs";
    const fs::path profilesDir = configsDir / "profiles";
    const fs::path invocationDir = fs::current_path();

    std::error_code error;
    if (fs::path{argument}.is_absolute()) {
        if (fs::is_regular_file(argument, error))
            return fs::canonical(argument);
        throw std::runtime_error("profile 不存在: " + argument);
    }

    if (argument.find('/') == std::string::npos) {
        for (const auto &candidate :
             {profilesDir / (argument + ".profile"),
              profilesDir / argument}) {
            if (fs::is_regular_file(candidate, error))
                return fs::canonical(candidate);
            error.clear();
        }
    }

    for (const auto &candidate :
         {invocationDir / argument, npcHome / argument}) {
        if (fs::is_regular_file(candidate, error))
            return fs::canonical(candidate);
        error.clear();
    }

    throw std::runtime_error(
        "找不到 profile '" + argument + "'（已查找 " +
        (profilesDir / (argument + ".profile")).string() + " 和相对路径）");
}

std::string readTextFile(const fs::path &path)
{
    std::ifstream input{path, std::ios::binary};
    if (!input)
        throw std::runtime_error("无法读取文件：" + path.string());
    std::ostringstream buffer;
    buffer << input.rdbuf();
    if (!input.good() && !input.eof())
        throw std::runtime_error("读取文件失败：" + path.string());
    return buffer.str();
}

class CurrentPathGuard {
public:
    explicit CurrentPathGuard(const fs::path &next)
        : previous_(fs::current_path())
    {
        fs::current_path(next);
    }
    ~CurrentPathGuard()
    {
        std::error_code error;
        fs::current_path(previous_, error);
    }
    CurrentPathGuard(const CurrentPathGuard &) = delete;
    CurrentPathGuard &operator=(const CurrentPathGuard &) = delete;

private:
    fs::path previous_;
};

class TemporaryDirectory {
public:
    TemporaryDirectory()
    {
        const auto ticks = std::chrono::steady_clock::now()
                               .time_since_epoch()
                               .count();
        path_ = fs::temp_directory_path() /
                ("npc-merge-config." + std::to_string(::getpid()) + "." +
                 std::to_string(ticks));
        std::error_code error;
        fs::create_directories(path_, error);
        if (error)
            throw std::runtime_error("无法创建临时目录 " + path_.string() +
                                     ": " + error.message());
    }
    ~TemporaryDirectory()
    {
        std::error_code error;
        fs::remove_all(path_, error);
    }
    TemporaryDirectory(const TemporaryDirectory &) = delete;
    TemporaryDirectory &operator=(const TemporaryDirectory &) = delete;

    [[nodiscard]] const fs::path &path() const { return path_; }

private:
    fs::path path_;
};

std::string profileDisplay(const fs::path &profile, const fs::path &npcHome)
{
    std::error_code error;
    const auto relative = fs::relative(profile, npcHome, error);
    if (!error && !relative.empty() && relative.native().rfind("..", 0) != 0)
        return relative.string();
    return profile.string();
}

} // namespace

int main(int argc, char **argv)
{
    try {
        if (argc == 2 &&
            (std::string{argv[1]} == "-h" ||
             std::string{argv[1]} == "--help")) {
            usage();
            return 0;
        }
        if (argc != 2) {
            usage();
            return 2;
        }

        const std::string npcHomeString = locateNpcHome(argv[0]);
        const fs::path npcHome = npcHomeString;
        const fs::path configsDir = npcHome / "configs";
        const fs::path conf = npcHome / "tools" / "build" / "c_conf";
        const fs::path contract =
            npcHome / "tools" / "build" / "kconfig_contract";
        const fs::path kconfig = npcHome / "Kconfig";

        std::error_code error;
        if (!fs::is_regular_file(conf, error) ||
            ::access(conf.c_str(), X_OK) != 0) {
            throw std::runtime_error(
                "找不到 " + conf.string() +
                "；请先在 npc/ 下运行 make menuconfig 或 make "
                "profile-defconfig 构建 c_conf");
        }
        error.clear();
        if (!fs::is_regular_file(kconfig, error))
            throw std::runtime_error("找不到 Kconfig: " + kconfig.string());
        error.clear();
        if (!fs::is_regular_file(contract, error) ||
            ::access(contract.c_str(), X_OK) != 0) {
            throw std::runtime_error(
                "找不到配置契约工具: " + contract.string() +
                "；请先在 npc/ 下运行 make kconfig-contract-tool 或 make "
                "profile-defconfig");
        }

        const fs::path profile = resolveProfile(npcHome, argv[1]);
        const std::string profileName = profileDisplay(profile, npcHome);
        TemporaryDirectory temporary;
        const fs::path merged = temporary.path() / "merged.config";

        std::ostringstream output;
        output << "# Automatically generated by npc/tools/merge_config.cpp. "
                  "DO NOT EDIT.\n"
               << "# Profile: " << profileName << '\n'
               << "# Fragments are concatenated in order; later symbols win.\n"
               << '\n';

        std::ifstream profileInput{profile};
        if (!profileInput)
            throw std::runtime_error("无法读取 profile：" + profile.string());

        std::string rawLine;
        while (std::getline(profileInput, rawLine)) {
            if (!rawLine.empty() && rawLine.back() == '\r')
                rawLine.pop_back();
            const std::string line = trim(rawLine);
            if (line.empty() || line.starts_with("#"))
                continue;

            const fs::path fragment =
                fs::path{line}.is_absolute() ? fs::path{line}
                                             : configsDir / line;
            if (!fs::is_regular_file(fragment)) {
                throw std::runtime_error(
                    "profile '" + profile.string() +
                    "' 引用的片段不存在: " + line + " (解析为 " +
                    fragment.string() + ")");
            }
            const std::string fragmentText = readTextFile(fragment);
            output << "# ===== fragment: " << line << " =====\n"
                   << fragmentText;
            if (!fragmentText.ends_with("\n"))
                output << '\n';
            output << '\n';
        }
        profileInput.close();
        if (!profileInput.eof())
            throw std::runtime_error("读取 profile 失败：" + profile.string());

        const std::string mergedText = output.str();
        bool hasConfigLine = false;
        std::istringstream mergedInput{mergedText};
        std::string mergedLine;
        while (std::getline(mergedInput, mergedLine)) {
            if (mergedLine.starts_with("CONFIG_") ||
                mergedLine.starts_with("# CONFIG_")) {
                hasConfigLine = true;
                break;
            }
        }
        if (!hasConfigLine)
            throw std::runtime_error(
                "profile '" + profile.string() + "' 没有产生任何 CONFIG_ 行");

        writeText(merged, mergedText);
        CurrentPathGuard cwd{npcHome};
        runInherit({conf.string(), "-s", "--defconfig=" + merged.string(),
                    kconfig.string()});
        runInherit({conf.string(), "-s", "--syncconfig", kconfig.string()});
        runInherit({contract.string(), "--config", ".config"});

        std::cout << "已从 profile " << profileName << " 生成 .config\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "merge_config: " << error.what() << '\n';
        return 1;
    }
}
