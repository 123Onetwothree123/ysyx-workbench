// NPC Linux 启动环境配置/打包矩阵测试。
//
// 只使用 dummy Image/initramfs/固件，不下载、编译或运行 Linux。
// 覆盖：
//   - Kconfig(.config) -> DTB 的 riscv,isa / riscv,isa-extensions / mmu-type；
//   - SYNTH_FREQ -> timebase-frequency；
//   - initramfs 地址 + 实际大小 -> linux,initrd-start/end；
//   - C++ pack_linux_image 的地址校验、重叠检测、负向失败与 sha256 输出；
//   - CUSTOM MMU + Linux 必须明确失败。
//
// 用法：
//   cd npc && make linux-boot-test
//   npc/tools/build/linux_boot_test
//

#include <array>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iostream>
#include <map>
#include <optional>
#include <poll.h>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <utility>
#include <vector>

namespace fs = std::filesystem;

namespace {

constexpr uint64_t IMAGE_ADDR = 0x80200000;
constexpr uint64_t INITRAMFS_ADDR = 0x82000000;
constexpr uint64_t FDT_ADDR = 0x83F00000;
constexpr uint64_t RAM_BASE = 0x80000000;
constexpr uint64_t RAM_SIZE = 0x04000000;
constexpr uint64_t FW_BASE = 0x80000000;
constexpr uint64_t FW_SIZE = 0x00200000;

constexpr std::string_view DEFAULT_BOOTARGS =
    "console=ttyS0,115200 earlycon=sbi";

using Env = std::map<std::string, std::string>;

struct Failure : std::runtime_error {
    using std::runtime_error::runtime_error;
};

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

struct CommandResult {
    int rc = 0;
    std::string out;
    std::string err;
    std::string command;
};

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
                          const Env &overrides = {},
                          const std::optional<fs::path> &cwd = std::nullopt) {
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
        // 配置由测试生成的 .config 和显式 overrides 决定；不要继承调用者
        // 环境里可能残留的 SBI_*/LINUX_*/NPC_RAM_* 覆盖，否则测试结果会
        // 依赖外部 shell 状态。
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
        ::dprintf(STDERR_FILENO, "failed to execute %s: %s\n", argv_raw[0],
                  std::strerror(errno));
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

std::string sha256_file(const fs::path &path);

struct Context {
    fs::path npc_home;
    fs::path workbench_home;
    fs::path build_dtb;
    fs::path pack_linux_image;
    fs::path fdtget;
    fs::path sha256sum;
};

Context g_ctx;

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

// ---------------------------------------------------------------------------
// 测试配置生成
// ---------------------------------------------------------------------------

struct Config {
    uint64_t xlen = 64;
    bool m = true;
    bool a = true;
    bool c = false;
    bool f = false;
    bool d = false;
    bool has_s = true;
    bool has_u = true;
    bool has_mmu = true;
    uint64_t levels = 3;
    std::string geometry = "standard";
    uint64_t page_size = 4096;
    uint64_t pte_size = 8;
    uint64_t table_size = 4096;
    std::string pte_format = "riscv64";
    uint64_t synth_freq = 450;
    uint64_t fw_base = FW_BASE;
    uint64_t fw_size = FW_SIZE;
    uint64_t next_addr = IMAGE_ADDR;
    uint64_t fdt_addr = FDT_ADDR;
    bool fdt_enable = true;
    std::string fdt_path = "dts/npc.dts";
    uint64_t fdt_reserve_size = 0x10000;
    uint64_t ram_base = RAM_BASE;
    uint64_t ram_size = RAM_SIZE;
    bool linux = true;
    std::string image_path = "Image";
    std::string bootargs = std::string(DEFAULT_BOOTARGS);
    bool initramfs = false;
    std::string initramfs_path;
    uint64_t initramfs_addr = INITRAMFS_ADDR;
};

void emit_config_bool(std::ostringstream &out, const std::string &key,
                      bool value) {
    if (value) {
        out << "CONFIG_" << key << "=y\n";
    } else {
        out << "# CONFIG_" << key << " is not set\n";
    }
}

void emit_config_int(std::ostringstream &out, const std::string &key,
                     uint64_t value) {
    out << "CONFIG_" << key << '=';
    if (value >= 0x100) {
        out << "0x" << std::hex << value << std::dec;
    } else {
        out << value;
    }
    out << '\n';
}

void emit_config_string(std::ostringstream &out, const std::string &key,
                        const std::string &value) {
    out << "CONFIG_" << key << "=\"" << value << "\"\n";
}

std::string json_escape(const std::string &value) {
    std::string result;
    for (const char character : value) {
        switch (character) {
        case '"': result += "\\\""; break;
        case '\\': result += "\\\\"; break;
        case '\n': result += "\\n"; break;
        case '\r': result += "\\r"; break;
        case '\t': result += "\\t"; break;
        default: result.push_back(character); break;
        }
    }
    return result;
}

void emit_json_string(std::ostringstream &out, bool &first,
                      const std::string &key, const std::string &value) {
    if (!first)
        out << ",\n";
    first = false;
    out << "  \"" << key << "\": \"" << json_escape(value) << "\"";
}

fs::path write_config(const fs::path &path, const Config &config) {
    std::ostringstream out;
    emit_config_int(out, "XLEN", config.xlen);
    emit_config_bool(out, "RISCV_M", config.m);
    emit_config_bool(out, "RISCV_A", config.a);
    emit_config_bool(out, "RISCV_C", config.c);
    emit_config_bool(out, "RISCV_F", config.f);
    emit_config_bool(out, "RISCV_D", config.d);
    emit_config_bool(out, "HAS_S", config.has_s);
    emit_config_bool(out, "HAS_U", config.has_u);
    emit_config_bool(out, "HAS_MMU", config.has_mmu);
    emit_config_int(out, "MMU_LEVELS", config.levels);
    emit_config_string(out, "MMU_GEOMETRY_MODE", config.geometry);
    emit_config_int(out, "MMU_PAGE_SIZE_BYTES", config.page_size);
    emit_config_int(out, "MMU_PTE_SIZE_BYTES", config.pte_size);
    emit_config_int(out, "MMU_PAGE_TABLE_SIZE_BYTES", config.table_size);
    emit_config_string(out, "MMU_PTE_FORMAT", config.pte_format);
    emit_config_int(out, "SYNTH_FREQ", config.synth_freq);
    emit_config_int(out, "SBI_FW_BASE", config.fw_base);
    emit_config_int(out, "SBI_FW_SIZE", config.fw_size);
    emit_config_int(out, "SBI_NEXT_ADDR", config.next_addr);
    emit_config_int(out, "SBI_FDT_ADDR", config.fdt_addr);
    emit_config_bool(out, "SBI_FDT_ENABLE", config.fdt_enable);
    emit_config_string(out, "SBI_FDT_PATH", config.fdt_path);
    emit_config_int(out, "SBI_FDT_RESERVE_SIZE", config.fdt_reserve_size);
    emit_config_int(out, "NPC_RAM_BASE", config.ram_base);
    emit_config_int(out, "NPC_RAM_SIZE", config.ram_size);
    emit_config_bool(out, "LINUX_BOOT_ENABLE", config.linux);
    emit_config_string(out, "LINUX_IMAGE_PATH", config.image_path);
    emit_config_string(out, "LINUX_BOOTARGS", config.bootargs);
    emit_config_bool(out, "LINUX_INITRAMFS_ENABLE", config.initramfs);
    emit_config_string(out, "LINUX_INITRAMFS_PATH", config.initramfs_path);
    emit_config_int(out, "LINUX_INITRAMFS_ADDR", config.initramfs_addr);
    if (config.f) {
        emit_config_bool(out, "FPU_IMPL_HARDFLOAT", true);
        emit_config_int(out, "FPU_FLEN", 64);
    }
    write_text(path, out.str());

    std::ostringstream json;
    json << "{\n";
    bool first = true;
    emit_json_string(json, first, "XLEN", std::to_string(config.xlen));
    emit_json_string(json, first, "RISCV_M", config.m ? "y" : "n");
    emit_json_string(json, first, "RISCV_A", config.a ? "y" : "n");
    emit_json_string(json, first, "RISCV_C", config.c ? "y" : "n");
    emit_json_string(json, first, "RISCV_F", config.f ? "y" : "n");
    emit_json_string(json, first, "RISCV_D", config.d ? "y" : "n");
    emit_json_string(json, first, "HAS_S", config.has_s ? "y" : "n");
    emit_json_string(json, first, "HAS_U", config.has_u ? "y" : "n");
    emit_json_string(json, first, "HAS_MMU", config.has_mmu ? "y" : "n");
    emit_json_string(json, first, "MMU_LEVELS", std::to_string(config.levels));
    emit_json_string(json, first, "MMU_GEOMETRY_MODE", config.geometry);
    emit_json_string(json, first, "MMU_PAGE_SIZE_BYTES", std::to_string(config.page_size));
    emit_json_string(json, first, "MMU_PTE_SIZE_BYTES", std::to_string(config.pte_size));
    emit_json_string(json, first, "MMU_PAGE_TABLE_SIZE_BYTES", std::to_string(config.table_size));
    emit_json_string(json, first, "MMU_PTE_FORMAT", config.pte_format);
    emit_json_string(json, first, "SYNTH_FREQ", std::to_string(config.synth_freq));
    emit_json_string(json, first, "SBI_FW_BASE", std::to_string(config.fw_base));
    emit_json_string(json, first, "SBI_FW_SIZE", std::to_string(config.fw_size));
    emit_json_string(json, first, "SBI_NEXT_ADDR", std::to_string(config.next_addr));
    emit_json_string(json, first, "SBI_FDT_ADDR", std::to_string(config.fdt_addr));
    emit_json_string(json, first, "SBI_FDT_ENABLE", config.fdt_enable ? "y" : "n");
    emit_json_string(json, first, "SBI_FDT_PATH", config.fdt_path);
    emit_json_string(json, first, "SBI_FDT_RESERVE_SIZE", std::to_string(config.fdt_reserve_size));
    emit_json_string(json, first, "NPC_RAM_BASE", std::to_string(config.ram_base));
    emit_json_string(json, first, "NPC_RAM_SIZE", std::to_string(config.ram_size));
    emit_json_string(json, first, "LINUX_BOOT_ENABLE", config.linux ? "y" : "n");
    emit_json_string(json, first, "LINUX_IMAGE_PATH", config.image_path);
    emit_json_string(json, first, "LINUX_BOOTARGS", config.bootargs);
    emit_json_string(json, first, "LINUX_INITRAMFS_ENABLE", config.initramfs ? "y" : "n");
    emit_json_string(json, first, "LINUX_INITRAMFS_PATH", config.initramfs_path);
    emit_json_string(json, first, "LINUX_INITRAMFS_ADDR", std::to_string(config.initramfs_addr));
    if (config.f) {
        emit_json_string(json, first, "FPU_IMPL", "hardfloat");
        emit_json_string(json, first, "FPU_FLEN", "64");
    }
    json << "\n}\n";
    write_text(path.string() + ".json", json.str());
    return path;
}

// ---------------------------------------------------------------------------
// dummy 文件生成
// ---------------------------------------------------------------------------

void store_u32(std::vector<uint8_t> &data, std::size_t offset, uint32_t value) {
    for (std::size_t i = 0; i < 4; ++i) {
        data[offset + i] = static_cast<uint8_t>(value >> (8 * i));
    }
}

void store_u64(std::vector<uint8_t> &data, std::size_t offset, uint64_t value) {
    for (std::size_t i = 0; i < 8; ++i) {
        data[offset + i] = static_cast<uint8_t>(value >> (8 * i));
    }
}

using Magic = std::array<uint8_t, 8>;

Magic default_magic() { return {'R', 'I', 'S', 'C', 'V', 0, 0, 0}; }

Magic magic_from_text(std::string_view text) {
    require(text.size() == 8, "test magic must contain exactly 8 bytes");
    Magic magic{};
    for (std::size_t i = 0; i < magic.size(); ++i) {
        magic[i] = static_cast<uint8_t>(text[i]);
    }
    return magic;
}

struct ImageSpec {
    uint64_t file_size = 4096;
    std::optional<uint64_t> header_image_size;
    uint64_t text_offset = 0x200000;
    Magic magic = default_magic();
};

fs::path make_image(const fs::path &path, const ImageSpec &spec = {}) {
    require(spec.file_size >= 68,
            "dummy Image must be at least 68 bytes to hold the header");
    std::vector<uint8_t> data(spec.file_size, 0);
    const uint64_t image_size =
        spec.header_image_size.value_or(spec.file_size);
    store_u32(data, 0, 0x00000013); // code0: nop
    store_u32(data, 4, 0);
    store_u64(data, 8, spec.text_offset);
    store_u64(data, 16, image_size);
    store_u64(data, 24, 0);
    store_u32(data, 32, 2);
    store_u32(data, 36, 0);
    store_u64(data, 40, 0);
    std::memcpy(data.data() + 0x38, spec.magic.data(), spec.magic.size());
    const std::array<uint8_t, 4> magic2 = {'R', 'S', 'C', 0x05};
    std::memcpy(data.data() + 0x40, magic2.data(), magic2.size());
    write_binary(path, data);
    return path;
}

void append_cpio_field(std::string &out, uint32_t value) {
    char buffer[9] = {};
    std::snprintf(buffer, sizeof(buffer), "%08x", value);
    out += buffer;
}

void append_cpio_entry(std::string &out, uint32_t ino, uint32_t mode,
                       uint32_t nlink, const std::string &name,
                       const std::string &data) {
    std::string name_field = name;
    name_field.push_back('\0');
    out += "070701";
    append_cpio_field(out, ino);
    append_cpio_field(out, mode);
    append_cpio_field(out, 0); // uid
    append_cpio_field(out, 0); // gid
    append_cpio_field(out, nlink);
    append_cpio_field(out, 0); // mtime
    append_cpio_field(out, static_cast<uint32_t>(data.size()));
    append_cpio_field(out, 0); // devmajor
    append_cpio_field(out, 0); // devminor
    append_cpio_field(out, 0); // rdevmajor
    append_cpio_field(out, 0); // rdevminor
    append_cpio_field(out, static_cast<uint32_t>(name_field.size()));
    append_cpio_field(out, 0); // check
    out += name_field;
    while (out.size() % 4 != 0) {
        out.push_back('\0');
    }
    out += data;
    while (out.size() % 4 != 0) {
        out.push_back('\0');
    }
}

std::string build_cpio_archive() {
    std::string out;
    append_cpio_entry(out, 1, 0100755, 1, "init", "#!/bin/sh\n");
    append_cpio_entry(out, 0, 0, 1, "TRAILER!!!", "");
    return out;
}

fs::path make_cpio(const fs::path &path, uint64_t size = 4096) {
    const std::string archive = build_cpio_archive();
    require(size >= archive.size(),
            "requested cpio size " + std::to_string(size) +
                " is smaller than the minimum archive " +
                std::to_string(archive.size()));
    std::vector<uint8_t> data(static_cast<std::size_t>(size), 0);
    std::memcpy(data.data(), archive.data(), archive.size());
    write_binary(path, data);
    return path;
}

// ---------------------------------------------------------------------------
// 脚本调用封装
// ---------------------------------------------------------------------------

Env common_env(const fs::path &config, const fs::path &dtb_dir) {
    Env env;
    env["NPC_HOME"] = g_ctx.npc_home.string();
    env["WORKBENCH_HOME"] = g_ctx.workbench_home.string();
    env["SBI_CONFIG_JSON"] = config.string() + ".json";
    env["SBI_FDT_OUT_DIR"] = dtb_dir.string();
    env["SBI_FDT_PATH"] = "dts/npc.dts";
    env["SBI_FDT_ENABLE"] = "y";
    env["SBI_FDT_LINUX"] = "y";
    return env;
}

CommandResult run_dtb(const fs::path &config, const fs::path &dtb_dir,
                      bool expect_ok = true, Env extra = {}) {
    fs::create_directories(dtb_dir);
    Env env = common_env(config, dtb_dir);
    for (const auto &[key, value] : extra) {
        env[key] = value;
    }
    const CommandResult result =
        run_command({g_ctx.build_dtb.string()}, env, g_ctx.npc_home);
    require_exit(result, expect_ok, "build_dtb.sh");
    return result;
}

std::optional<std::string> fdtget(const fs::path &dtb, const std::string &node,
                                  const std::string &property,
                                  const std::string &type) {
    const CommandResult result = run_command(
        {g_ctx.fdtget.string(), "-t", type, dtb.string(), node, property});
    if (result.rc != 0) {
        return std::nullopt;
    }
    return trim(result.out);
}

std::optional<std::string> fdtget_string(const fs::path &dtb,
                                         const std::string &node,
                                         const std::string &property) {
    return fdtget(dtb, node, property, "s");
}

std::optional<uint64_t> parse_hex_value(const std::string &text) {
    if (text.empty()) {
        return std::nullopt;
    }
    char *end = nullptr;
    errno = 0;
    const unsigned long long value =
        std::strtoull(text.c_str(), &end, 16);
    if (errno != 0 || end == text.c_str()) {
        return std::nullopt;
    }
    return static_cast<uint64_t>(value);
}

std::optional<uint64_t> fdtget_int(const fs::path &dtb,
                                   const std::string &node,
                                   const std::string &property) {
    const auto value = fdtget(dtb, node, property, "x");
    if (!value.has_value()) {
        return std::nullopt;
    }
    std::istringstream input(*value);
    std::string token;
    std::string last;
    while (input >> token) {
        last = token;
    }
    return parse_hex_value(last);
}

std::vector<uint64_t> fdtget_cells(const fs::path &dtb, const std::string &node,
                                   const std::string &property) {
    const auto value = fdtget(dtb, node, property, "x");
    std::vector<uint64_t> cells;
    if (!value.has_value()) {
        return cells;
    }
    std::istringstream input(*value);
    std::string token;
    while (input >> token) {
        const auto cell = parse_hex_value(token);
        if (!cell.has_value()) {
            fail("fdtget returned a non-hex cell '" + token + "' for " + node +
                 ":" + property);
        }
        cells.push_back(*cell);
    }
    return cells;
}

std::string dtb_info(const fs::path &dtb) {
    const fs::path info = dtb.parent_path() / "npc.dtb.info";
    std::error_code error;
    if (fs::is_regular_file(info, error)) {
        return read_text(info);
    }
    return "<missing " + info.string() + ">";
}

std::string require_fdt_string(const fs::path &dtb, const std::string &node,
                               const std::string &property) {
    const auto value = fdtget_string(dtb, node, property);
    if (!value.has_value()) {
        fail("property " + node + ":" + property + " is missing from " +
             dtb.string() + "\nDTB info:\n" + dtb_info(dtb));
    }
    return *value;
}

uint64_t require_fdt_int(const fs::path &dtb, const std::string &node,
                         const std::string &property) {
    const auto value = fdtget_int(dtb, node, property);
    if (!value.has_value()) {
        fail("property " + node + ":" + property + " is missing from " +
             dtb.string() + "\nDTB info:\n" + dtb_info(dtb));
    }
    return *value;
}

void require_fdt_absent(const fs::path &dtb, const std::string &node,
                        const std::string &property) {
    const auto value = fdtget_string(dtb, node, property);
    if (value.has_value()) {
        fail("property " + node + ":" + property +
             " unexpectedly exists (value=\"" + *value + "\") in " +
             dtb.string() + "\nDTB info:\n" + dtb_info(dtb));
    }
}

void check_string(const std::string &actual, const std::string &expected,
                  const std::string &what) {
    if (actual != expected) {
        fail(what + ": expected \"" + expected + "\", got \"" + actual + "\"");
    }
}

void check_u64(uint64_t actual, uint64_t expected, const std::string &what) {
    if (actual != expected) {
        fail(what + ": expected " + hex_u64(expected) + " (" +
             decimal_u64(expected) + "), got " + hex_u64(actual) + " (" +
             decimal_u64(actual) + ")");
    }
}

// ---------------------------------------------------------------------------
// pack_linux_image.sh 测试环境
// ---------------------------------------------------------------------------

struct PackSetup {
    fs::path config;
    fs::path image;
    fs::path initramfs;
    fs::path dtb_dir;
    fs::path dtb;
    fs::path fw_dir;
    fs::path out_dir;
    Env env;
};

fs::path write_fake_fw(const fs::path &out_dir, const fs::path &dtb,
                       uint64_t fdt_addr = FDT_ADDR,
                       uint64_t timebase = 450000000) {
    fs::create_directories(out_dir);
    const fs::path fw = out_dir / "fw.bin";
    const fs::path info = out_dir / "fw.info";
    const std::vector<uint8_t> fw_data(4096, 0x13);
    write_binary(fw, fw_data);

    std::error_code error;
    const fs::path absolute_dtb = fs::canonical(dtb, error);
    const fs::path dtb_path = error ? dtb : absolute_dtb;
    std::ostringstream text;
    text << "impl=opensbi\n";
    text << "fw_base=" << hex_u64(FW_BASE) << '\n';
    text << "fw_size=" << hex_u64(FW_SIZE) << '\n';
    text << "fdt_enable=y\n";
    text << "fdt_path=" << dtb_path.string() << '\n';
    text << "fdt_addr=" << hex_u64(fdt_addr) << '\n';
    text << "fdt_bytes=" << get_file_size(dtb_path) << '\n';
    text << "fdt_sha256=" << sha256_file(dtb_path) << '\n';
    text << "timebase_freq=" << timebase << '\n';
    text << "fw_bytes=" << fw_data.size() << '\n';
    text << "fw_sha256=" << sha256_file(fw) << '\n';
    write_text(info, text.str());
    return fw;
}

Env pack_env(const fs::path &config, const fs::path &fw_dir,
             const fs::path &dtb_dir, const fs::path &out_dir,
             const fs::path &image, bool with_initramfs,
             const fs::path &initramfs, uint64_t initramfs_addr,
             uint64_t fdt_addr = FDT_ADDR, uint64_t next_addr = IMAGE_ADDR) {
    Env env = common_env(config, dtb_dir);
    env["SBI_ENABLE"] = "y";
    env["SBI_IMPL"] = "opensbi";
    env["SBI_OUT_DIR"] = fw_dir.string();
    env["SBI_FDT_ADDR"] = hex_u64(fdt_addr);
    env["SBI_NEXT_ADDR"] = hex_u64(next_addr);
    env["SBI_TIMEBASE_FREQ"] = "450000000";
    env["LINUX_IMAGE_PATH"] = image.string();
    env["LINUX_INITRAMFS_ENABLE"] = with_initramfs ? "y" : "n";
    env["LINUX_INITRAMFS_PATH"] =
        with_initramfs ? initramfs.string() : std::string();
    env["LINUX_INITRAMFS_ADDR"] = hex_u64(initramfs_addr);
    env["LINUX_BOOTARGS"] = std::string(DEFAULT_BOOTARGS);
    env["LINUX_OUT_DIR"] = out_dir.string();
    return env;
}

PackSetup standard_pack_setup(const fs::path &work, const ImageSpec &image_spec,
                              bool with_initramfs = true,
                              uint64_t initramfs_size = 4096,
                              uint64_t initramfs_addr = INITRAMFS_ADDR) {
    PackSetup setup;
    setup.config = work / "pack.config";
    setup.image = make_image(work / "Image", image_spec);

    Config config;
    config.linux = true;
    config.initramfs = with_initramfs;
    config.initramfs_addr = initramfs_addr;
    if (with_initramfs) {
        setup.initramfs = make_cpio(work / "initramfs.cpio", initramfs_size);
        config.initramfs_path = setup.initramfs.string();
    }
    write_config(setup.config, config);

    setup.dtb_dir = work / "pack-dtb";
    run_dtb(setup.config, setup.dtb_dir);
    setup.dtb = setup.dtb_dir / "npc.dtb";

    setup.fw_dir = work / "pack-fw";
    write_fake_fw(setup.fw_dir, setup.dtb, FDT_ADDR);
    setup.out_dir = work / "pack-out";
    setup.env = pack_env(setup.config, setup.fw_dir, setup.dtb_dir,
                         setup.out_dir, setup.image, with_initramfs,
                         setup.initramfs, initramfs_addr, FDT_ADDR, IMAGE_ADDR);
    return setup;
}

CommandResult run_pack(const Env &env, bool expect_ok = true) {
    const CommandResult result =
        run_command({g_ctx.pack_linux_image.string()}, env, g_ctx.npc_home);
    require_exit(result, expect_ok, "pack_linux_image.sh");
    return result;
}

void require_span_equal(const std::vector<uint8_t> &binary, uint64_t offset,
                        const std::vector<uint8_t> &expected,
                        const std::string &what) {
    require(offset + expected.size() <= binary.size(),
            what + ": binary is too short for offset " + hex_u64(offset) +
                " + " + std::to_string(expected.size()) + " bytes");
    const bool equal =
        std::equal(expected.begin(), expected.end(), binary.begin() + offset);
    if (!equal) {
        fail(what + ": packed bytes at offset " + hex_u64(offset) +
             " do not match the source file");
    }
}

// ---------------------------------------------------------------------------
// 测试用例
// ---------------------------------------------------------------------------

void test_isa_matrix(const fs::path &work) {
    struct Case {
        std::string name;
        Config config;
        std::string isa;
        std::string extensions;
        std::string mmu;
    };

    std::vector<Case> cases;

    {
        Config config;
        config.xlen = 32;
        config.levels = 2;
        config.pte_size = 4;
        config.pte_format = "riscv32";
        config.linux = false;
        cases.push_back({"rv32ima", config, "rv32ima_zicsr_zifencei",
                         "i m a zicsr zifencei", "riscv,sv32"});
    }
    {
        Config config;
        config.linux = false;
        cases.push_back({"rv64ima", config, "rv64ima_zicsr_zifencei",
                         "i m a zicsr zifencei", "riscv,sv39"});
    }
    {
        Config config;
        config.c = true;
        config.f = true;
        config.d = true;
        config.linux = false;
        cases.push_back({"rv64imafdc", config, "rv64imafdc_zicsr_zifencei",
                         "i m a f d c zicsr zifencei", "riscv,sv39"});
    }
    {
        Config config;
        config.f = true;
        config.d = true;
        config.linux = false;
        cases.push_back({"rv64imafd-no-c", config,
                         "rv64imafd_zicsr_zifencei",
                         "i m a f d zicsr zifencei", "riscv,sv39"});
    }
    {
        Config config;
        config.c = true;
        config.f = true;
        config.linux = false;
        cases.push_back({"rv64imafc-no-d", config,
                         "rv64imafc_zicsr_zifencei",
                         "i m a f c zicsr zifencei", "riscv,sv39"});
    }
    {
        Config config;
        config.c = true;
        config.linux = false;
        cases.push_back({"rv64imac-no-f", config,
                         "rv64imac_zicsr_zifencei",
                         "i m a c zicsr zifencei", "riscv,sv39"});
    }
    {
        Config config;
        config.c = true;
        config.f = true;
        config.d = true;
        config.levels = 4;
        config.linux = false;
        cases.push_back({"rv64-sv48", config, "rv64imafdc_zicsr_zifencei",
                         "i m a f d c zicsr zifencei", "riscv,sv48"});
    }

    for (const Case &test_case : cases) {
        const fs::path config_path =
            work / (test_case.name + ".config");
        const fs::path dtb_dir = work / (test_case.name + "-dtb");
        write_config(config_path, test_case.config);
        run_dtb(config_path, dtb_dir, true, {{"SBI_FDT_LINUX", "n"}});
        const fs::path dtb = dtb_dir / "npc.dtb";

        check_string(require_fdt_string(dtb, "/cpus/cpu@0", "riscv,isa"),
                     test_case.isa, test_case.name + " riscv,isa");
        check_string(require_fdt_string(dtb, "/cpus/cpu@0", "riscv,isa-base"),
                     "rv" + std::to_string(test_case.config.xlen) + "i",
                     test_case.name + " riscv,isa-base");
        check_string(
            require_fdt_string(dtb, "/cpus/cpu@0", "riscv,isa-extensions"),
            test_case.extensions, test_case.name + " riscv,isa-extensions");
        check_string(require_fdt_string(dtb, "/cpus/cpu@0", "mmu-type"),
                     test_case.mmu, test_case.name + " mmu-type");
        check_u64(require_fdt_int(dtb, "/cpus", "timebase-frequency"),
                  450'000'000, test_case.name + " timebase-frequency");
        check_string(require_fdt_string(dtb, "/chosen", "bootargs"),
                     std::string(DEFAULT_BOOTARGS),
                     test_case.name + " bootargs");
    }
}

void test_timebase_follows_synth_freq(const fs::path &work) {
    for (const uint64_t mhz : {100ULL, 450ULL, 800ULL}) {
        const fs::path config_path =
            work / ("tb-" + std::to_string(mhz) + ".config");
        const fs::path dtb_dir =
            work / ("tb-" + std::to_string(mhz) + "-dtb");
        Config config;
        config.linux = false;
        config.synth_freq = mhz;
        write_config(config_path, config);
        run_dtb(config_path, dtb_dir, true, {{"SBI_FDT_LINUX", "n"}});
        check_u64(require_fdt_int(dtb_dir / "npc.dtb", "/cpus",
                                  "timebase-frequency"),
                  mhz * 1'000'000,
                  "timebase for " + std::to_string(mhz) + " MHz");
    }
}

void test_initramfs_properties(const fs::path &work) {
    const fs::path initramfs = make_cpio(work / "initramfs.cpio", 4096);
    const fs::path config_path = work / "initrd.config";

    Config config;
    config.linux = true;
    config.initramfs = true;
    config.initramfs_path = initramfs.string();
    config.initramfs_addr = 0x82100000;
    write_config(config_path, config);

    const fs::path dtb_dir = work / "initrd-dtb";
    run_dtb(config_path, dtb_dir);
    const fs::path dtb = dtb_dir / "npc.dtb";
    check_u64(require_fdt_int(dtb, "/chosen", "linux,initrd-start"),
              0x82100000, "linux,initrd-start");
    check_u64(require_fdt_int(dtb, "/chosen", "linux,initrd-end"),
              0x82101000, "linux,initrd-end");
}

void test_initramfs_disabled_has_no_properties(const fs::path &work) {
    const fs::path config_path = work / "noinitrd.config";
    Config config;
    config.linux = true;
    config.initramfs = false;
    write_config(config_path, config);

    const fs::path dtb_dir = work / "noinitrd-dtb";
    run_dtb(config_path, dtb_dir);
    const fs::path dtb = dtb_dir / "npc.dtb";
    require_fdt_absent(dtb, "/chosen", "linux,initrd-start");
    require_fdt_absent(dtb, "/chosen", "linux,initrd-end");
}

void test_dtb_and_initramfs_addresses_follow_config(const fs::path &work) {
    const fs::path initramfs = make_cpio(work / "addr.cpio", 4096);
    const fs::path config_path = work / "addr.config";

    Config config;
    config.linux = true;
    config.initramfs = true;
    config.initramfs_path = initramfs.string();
    config.initramfs_addr = 0x82300000;
    config.fdt_addr = 0x83E00000;
    write_config(config_path, config);

    const fs::path dtb_dir = work / "addr-dtb";
    run_dtb(config_path, dtb_dir);
    const fs::path dtb = dtb_dir / "npc.dtb";
    check_u64(require_fdt_int(dtb, "/chosen", "linux,initrd-start"),
              0x82300000, "linux,initrd-start");

    const std::vector<uint64_t> cells =
        fdtget_cells(dtb, "/reserved-memory/fdt@83e00000", "reg");
    require(cells.size() >= 4,
            "reserved-memory fdt@83e00000:reg should contain 4 cells\nDTB "
            "info:\n" +
                dtb_info(dtb));
    check_u64(cells[1], 0x83E00000, "reserved-memory fdt address");
    check_u64(cells[3], 0x10000, "reserved-memory fdt size");

    const std::string info = read_text(dtb_dir / "npc.dtb.info");
    require(info.find("fdt_addr=0x83e00000") != std::string::npos,
            "npc.dtb.info does not record fdt_addr=0x83e00000:\n" + info);
}

void test_custom_mmu_linux_rejected(const fs::path &work) {
    const fs::path config_path = work / "custom.config";
    Config config;
    config.linux = true;
    config.geometry = "custom";
    config.page_size = 1024;
    config.table_size = 16384;
    config.levels = 3;
    write_config(config_path, config);

    const CommandResult result =
        run_dtb(config_path, work / "custom-dtb", false,
                {{"SBI_FDT_LINUX", "y"}});
    require_contains(result.err, "不是标准 Sv32/Sv39/Sv48",
                     "CUSTOM MMU + Linux rejection message", result);
}

void test_custom_mmu_non_linux_omits_mmu_type(const fs::path &work) {
    const fs::path config_path = work / "custom-nolinux.config";
    Config config;
    config.linux = false;
    config.geometry = "custom";
    config.page_size = 1024;
    config.table_size = 16384;
    config.levels = 3;
    write_config(config_path, config);

    const fs::path dtb_dir = work / "custom-nolinux-dtb";
    run_dtb(config_path, dtb_dir, true, {{"SBI_FDT_LINUX", "n"}});
    require_fdt_absent(dtb_dir / "npc.dtb", "/cpus/cpu@0", "mmu-type");
}

void test_pack_positive_and_sha256(const fs::path &work) {
    PackSetup setup =
        standard_pack_setup(work, ImageSpec{}, true, 4096, INITRAMFS_ADDR);
    run_pack(setup.env);

    const fs::path binary = setup.out_dir / "linux-image.bin";
    require(fs::is_regular_file(binary),
            "packed binary was not created: " + binary.string());

    const std::vector<uint8_t> packed = read_binary(binary);
    const uint64_t dtb_size = get_file_size(setup.dtb);
    check_u64(packed.size(), FDT_ADDR - RAM_BASE + dtb_size,
              "packed binary size");

    require_span_equal(packed, IMAGE_ADDR - RAM_BASE,
                       read_binary(setup.image), "Linux Image span");
    require_span_equal(packed, INITRAMFS_ADDR - RAM_BASE,
                       read_binary(setup.initramfs), "initramfs span");
    require_span_equal(packed, FDT_ADDR - RAM_BASE, read_binary(setup.dtb),
                       "DTB span");

    const std::string info = read_text(setup.out_dir / "linux-image.bin.info");
    for (const std::string needle :
         {"image_addr=0x80200000", "initramfs_addr=0x82000000",
          "dtb_addr=0x83f00000", "binary_sha256=", "timebase_freq=450000000"}) {
        require(info.find(needle) != std::string::npos,
                "linux-image.bin.info does not contain \"" + needle +
                    "\":\n" + info);
    }

    const std::string binary_sha = sha256_file(binary);
    require(info.find("binary_sha256=" + binary_sha) != std::string::npos,
            "linux-image.bin.info does not record the binary sha256\n" +
                info);
    const std::string sha_file =
        read_text(setup.out_dir / "linux-image.bin.sha256");
    require(sha_file.find(binary_sha) != std::string::npos,
            "linux-image.bin.sha256 does not contain the binary sha256:\n" +
                sha_file);

    const CommandResult check = run_command(
        {g_ctx.sha256sum.string(), "-c", "linux-image.bin.sha256"}, {},
        setup.out_dir);
    require_exit(check, true, "sha256sum -c linux-image.bin.sha256");
    require_contains(check.out, "OK", "sha256sum -c output", check);
}

void test_pack_without_initramfs(const fs::path &work) {
    PackSetup setup =
        standard_pack_setup(work, ImageSpec{}, false, 4096, INITRAMFS_ADDR);
    run_pack(setup.env);

    const fs::path binary = setup.out_dir / "linux-image.bin";
    require(fs::is_regular_file(binary),
            "packed binary was not created: " + binary.string());
    require_fdt_absent(setup.dtb, "/chosen", "linux,initrd-start");
    const std::string info = read_text(setup.out_dir / "linux-image.bin.info");
    require(info.find("initramfs_enable=n") != std::string::npos,
            "linux-image.bin.info should record initramfs_enable=n:\n" + info);
}

void test_pack_image_bad_magic(const fs::path &work) {
    ImageSpec spec;
    spec.magic = magic_from_text("NOTRISCV");
    PackSetup setup = standard_pack_setup(work, spec);
    const CommandResult result = run_pack(setup.env, false);
    require_contains(result.err, "magic", "bad Image magic rejection", result);
}

void test_pack_image_text_offset_mismatch(const fs::path &work) {
    ImageSpec spec;
    spec.text_offset = 0x400000;
    PackSetup setup = standard_pack_setup(work, spec);
    const CommandResult result = run_pack(setup.env, false);
    require_contains(result.err, "text_offset",
                     "Image text_offset mismatch rejection", result);
}

void test_pack_image_overlaps_initramfs(const fs::path &work) {
    ImageSpec spec;
    spec.header_image_size = (INITRAMFS_ADDR - IMAGE_ADDR) + 1;
    PackSetup setup = standard_pack_setup(work, spec);
    const CommandResult result = run_pack(setup.env, false);
    require_contains(result.err, "重叠", "Image/initramfs overlap rejection",
                     result);
}

void test_pack_initramfs_overlaps_dtb(const fs::path &work) {
    const fs::path initramfs =
        make_cpio(work / "overlap.cpio",
                  (FDT_ADDR - INITRAMFS_ADDR) + 0x1000);
    const fs::path config_path = work / "overlap.config";
    Config config;
    config.linux = true;
    config.initramfs = true;
    config.initramfs_path = initramfs.string();
    write_config(config_path, config);

    const CommandResult result =
        run_dtb(config_path, work / "overlap-dtb", false);
    require_contains(result.err, "重叠", "initramfs/DTB overlap rejection",
                     result);
}

void test_pack_initramfs_out_of_ram(const fs::path &work) {
    const fs::path initramfs =
        make_cpio(work / "oor.cpio",
                  (RAM_BASE + RAM_SIZE - 0x83E00000) + 0x1000);
    const fs::path config_path = work / "oor.config";
    Config config;
    config.linux = true;
    config.initramfs = true;
    config.initramfs_path = initramfs.string();
    config.initramfs_addr = 0x83E00000;
    write_config(config_path, config);

    const CommandResult result =
        run_dtb(config_path, work / "oor-dtb", false);
    require_contains(result.err, "超出 NPC RAM",
                     "initramfs out-of-RAM rejection", result);
}

void test_pack_initramfs_unaligned(const fs::path &work) {
    const fs::path initramfs = make_cpio(work / "unaligned.cpio", 4096);
    const fs::path config_path = work / "unaligned.config";
    Config config;
    config.linux = true;
    config.initramfs = true;
    config.initramfs_path = initramfs.string();
    config.initramfs_addr = 0x82000004;
    write_config(config_path, config);

    const CommandResult result =
        run_dtb(config_path, work / "unaligned-dtb", false);
    require_contains(result.err, "4 KiB 对齐",
                     "initramfs alignment rejection", result);
}

void test_pack_missing_image(const fs::path &work) {
    PackSetup setup = standard_pack_setup(work, ImageSpec{});
    fs::remove(setup.image);
    const CommandResult result = run_pack(setup.env, false);
    require_contains(result.err, "Linux Image 不存在",
                     "missing Linux Image rejection", result);
}

void test_pack_fdt_address_out_of_ram(const fs::path &work) {
    const fs::path config_path = work / "bad-fdt.config";
    Config config;
    config.linux = true;
    config.fdt_addr = 0x83FF8000;
    write_config(config_path, config);

    const CommandResult result =
        run_dtb(config_path, work / "bad-fdt-dtb", false,
                {{"SBI_FDT_ADDR", "0x83ff8000"}});
    require_contains(result.err, "超出 NPC RAM",
                     "DTB out-of-RAM rejection", result);
}

// ---------------------------------------------------------------------------
// 测试运行器
// ---------------------------------------------------------------------------

struct TestCase {
    std::string name;
    std::function<void(const fs::path &)> body;
};

fs::path make_temp_root() {
    const fs::path base = fs::temp_directory_path() / "opencode";
    std::error_code error;
    fs::create_directories(base, error);
    require(!error, "cannot create temporary directory " + base.string() +
                        ": " + error.message());

    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    const auto ticks =
        std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();
    std::ostringstream name;
    name << "npc-linux-boot-" << ::getpid() << '-' << ticks;
    const fs::path root = base / name.str();
    fs::remove_all(root, error);
    error.clear();
    fs::create_directories(root, error);
    require(!error, "cannot create test directory " + root.string() + ": " +
                        error.message());
    return root;
}

} // namespace

int main(int argc, char **argv) {
    (void)argc;
    try {
        g_ctx.npc_home = locate_npc_home(argv[0]);
        g_ctx.workbench_home = g_ctx.npc_home.parent_path();
        g_ctx.build_dtb = g_ctx.npc_home / "scripts" / "build_dtb.sh";
        g_ctx.pack_linux_image =
            g_ctx.npc_home / "scripts" / "pack_linux_image.sh";
        require(fs::is_regular_file(g_ctx.build_dtb),
                "missing script: " + g_ctx.build_dtb.string());
        require(fs::is_regular_file(g_ctx.pack_linux_image),
                "missing script: " + g_ctx.pack_linux_image.string());
        g_ctx.fdtget = find_tool("fdtget");
        g_ctx.sha256sum = find_tool("sha256sum");
    } catch (const std::exception &error) {
        std::cerr << "linux_boot_test: setup failed: " << error.what() << '\n';
        return 2;
    }

    const std::vector<TestCase> tests = {
        {"isa_matrix", test_isa_matrix},
        {"timebase_follows_synth_freq", test_timebase_follows_synth_freq},
        {"initramfs_properties", test_initramfs_properties},
        {"initramfs_disabled_has_no_properties",
         test_initramfs_disabled_has_no_properties},
        {"dtb_and_initramfs_addresses_follow_config",
         test_dtb_and_initramfs_addresses_follow_config},
        {"custom_mmu_linux_rejected", test_custom_mmu_linux_rejected},
        {"custom_mmu_non_linux_omits_mmu_type",
         test_custom_mmu_non_linux_omits_mmu_type},
        {"pack_positive_and_sha256", test_pack_positive_and_sha256},
        {"pack_without_initramfs", test_pack_without_initramfs},
        {"pack_image_bad_magic", test_pack_image_bad_magic},
        {"pack_image_text_offset_mismatch",
         test_pack_image_text_offset_mismatch},
        {"pack_image_overlaps_initramfs",
         test_pack_image_overlaps_initramfs},
        {"pack_initramfs_overlaps_dtb", test_pack_initramfs_overlaps_dtb},
        {"pack_initramfs_out_of_ram", test_pack_initramfs_out_of_ram},
        {"pack_initramfs_unaligned", test_pack_initramfs_unaligned},
        {"pack_missing_image", test_pack_missing_image},
        {"pack_fdt_address_out_of_ram", test_pack_fdt_address_out_of_ram},
    };

    fs::path temp_root;
    try {
        temp_root = make_temp_root();
    } catch (const std::exception &error) {
        std::cerr << "linux_boot_test: cannot create temporary workspace: "
                  << error.what() << '\n';
        return 2;
    }

    std::size_t passed = 0;
    std::size_t failed = 0;
    std::cout << "NPC Linux boot environment tests\n";
    std::cout << "NPC_HOME: " << g_ctx.npc_home.string() << '\n';
    std::cout << "Temporary workspace: " << temp_root.string() << "\n\n";
    std::cout.flush();

    for (const TestCase &test : tests) {
        const fs::path work = temp_root / test.name;
        std::error_code error;
        fs::create_directories(work, error);
        if (error) {
            std::cerr << "linux_boot_test: cannot create " << work << ": "
                      << error.message() << '\n';
            return 2;
        }

        std::cout << "[ RUN      ] " << test.name << '\n';
        std::cout.flush();
        try {
            test.body(work);
            std::cout << "[       OK ] " << test.name << '\n';
            ++passed;
        } catch (const std::exception &exception) {
            std::cout << "[  FAILED  ] " << test.name << ": "
                      << exception.what() << '\n';
            ++failed;
        }
        std::cout.flush();
        fs::remove_all(work, error);
    }

    std::error_code cleanup_error;
    fs::remove_all(temp_root, cleanup_error);

    std::cout << '\n' << passed << " passed, " << failed << " failed\n";
    return failed == 0 ? 0 : 1;
}
