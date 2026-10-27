// Audit a rootfs executable without running it on the target.
//
// Parses the ELF header/program headers/dynamic segment directly, and uses
// readelf only for the RISC-V "Tag_RISCV_arch" / "Tag_RISCV_abi" attributes.
// Prints three tab-separated fields on success:
//
//   FLAGS<TAB>RISCV_ARCH<TAB>RISCV_ABI
//
// The shell rootfs builder uses these only for its generated summary.  All
// rejection rules live here, not in grep pipelines.

import npc.buildtools;
import std;

namespace {

using namespace npc::linux_image;

constexpr std::uint16_t kEtExec = 2;
constexpr std::uint16_t kEtDyn = 3;
constexpr std::uint16_t kEmRiscv = 243;
constexpr std::uint32_t kPtInterp = 3;
constexpr std::uint32_t kPtDynamic = 2;
constexpr std::int64_t kDtNull = 0;
constexpr std::int64_t kDtNeeded = 1;

std::uint16_t read16(const std::vector<std::uint8_t> &data, std::size_t offset,
                     const std::string &what)
{
    if (offset > data.size() || data.size() - offset < 2)
        throw std::runtime_error(what + " 越界");
    return static_cast<std::uint16_t>(data[offset]) |
           static_cast<std::uint16_t>(data[offset + 1] << 8);
}

std::uint32_t read32(const std::vector<std::uint8_t> &data, std::size_t offset,
                     const std::string &what)
{
    if (offset > data.size() || data.size() - offset < 4)
        throw std::runtime_error(what + " 越界");
    return static_cast<std::uint32_t>(data[offset]) |
           (static_cast<std::uint32_t>(data[offset + 1]) << 8) |
           (static_cast<std::uint32_t>(data[offset + 2]) << 16) |
           (static_cast<std::uint32_t>(data[offset + 3]) << 24);
}

std::uint64_t read64(const std::vector<std::uint8_t> &data, std::size_t offset,
                     const std::string &what)
{
    if (offset > data.size() || data.size() - offset < 8)
        throw std::runtime_error(what + " 越界");
    std::uint64_t value = 0;
    for (int index = 0; index < 8; ++index)
        value |= static_cast<std::uint64_t>(data[offset + index])
                 << (index * 8);
    return value;
}

std::string quoteValue(const std::string &line, const std::string &key)
{
    const auto keyPosition = line.find(key);
    if (keyPosition == std::string::npos)
        return {};
    const auto firstQuote = line.find('"', keyPosition + key.size());
    if (firstQuote == std::string::npos)
        return {};
    const auto secondQuote = line.find('"', firstQuote + 1);
    if (secondQuote == std::string::npos)
        return {};
    return line.substr(firstQuote + 1, secondQuote - firstQuote - 1);
}

struct Attributes {
    std::string arch;
    std::string abi;
};

Attributes readRiscvAttributes(const fs::path &path)
{
    const std::string readelf = envDefault("READELF", "readelf");
    const auto result = runCapture({readelf, "-A", path.string()});
    if (result.exitCode != 0)
        throw std::runtime_error("readelf -A 失败：" + path.string());

    Attributes attributes;
    std::istringstream input(result.output);
    std::string line;
    while (std::getline(input, line)) {
        if (attributes.arch.empty())
            attributes.arch = quoteValue(line, "Tag_RISCV_arch:");
        if (attributes.abi.empty())
            attributes.abi = quoteValue(line, "Tag_RISCV_abi:");
    }
    return attributes;
}

std::string flagsText(std::uint32_t flags)
{
    std::ostringstream output;
    output << "0x" << std::hex << flags;
    if ((flags & 0x1U) != 0)
        output << ", RVC";
    if ((flags & 0x6U) == 0x4U)
        output << ", double-float ABI";
    return output.str();
}

void require(bool condition, const std::string &prefix,
             const std::string &message)
{
    if (!condition)
        throw std::runtime_error(prefix + message);
}

} // namespace

int main(int argc, char **argv)
{
    try {
        std::string kind = "busybox";
        bool allowDynamic = false;
        fs::path path;
        for (int index = 1; index < argc; ++index) {
            const std::string argument = argv[index];
            if (argument == "--kind") {
                if (++index >= argc)
                    throw std::runtime_error("--kind 缺少参数");
                kind = argv[index];
            } else if (argument == "--allow-dynamic") {
                allowDynamic = true;
            } else if (argument == "-h" || argument == "--help") {
                std::cerr
                    << "用法: audit_rootfs_elf [--kind busybox|fastfetch] "
                       "[--allow-dynamic] ELF\n";
                return 0;
            } else if (argument.starts_with("-")) {
                throw std::runtime_error("未知参数：" + argument);
            } else if (path.empty()) {
                path = argument;
            } else {
                throw std::runtime_error("只能指定一个 ELF 文件");
            }
        }
        if (path.empty())
            throw std::runtime_error("缺少 ELF 文件");
        if (kind != "busybox" && kind != "fastfetch")
            throw std::runtime_error("--kind 只支持 busybox/fastfetch");

        const std::string prefix =
            kind == "fastfetch" ? "fastfetch ELF 审计失败：" : "ELF 审计失败：";
        const auto data = readFile(path);
        require(data.size() >= 64, prefix, "文件太小，不是 ELF64：" +
                                                path.string());
        require(data[0] == 0x7f && data[1] == 'E' && data[2] == 'L' &&
                    data[3] == 'F',
                prefix, "缺少 ELF magic：" + path.string());
        require(data[4] == 2, prefix, "不是 ELF64：" + path.string());
        require(data[5] == 1, prefix, "不是小端 ELF：" + path.string());
        require(read16(data, 18, "e_machine") == kEmRiscv, prefix,
                "Machine 不是 RISC-V：" + path.string());

        const auto type = read16(data, 16, "e_type");
        const auto flags = read32(data, 48, "e_flags");
        const auto phoff = read64(data, 32, "e_phoff");
        const auto phentsize = read16(data, 54, "e_phentsize");
        const auto phnum = read16(data, 56, "e_phnum");
        require(phentsize >= 56, prefix, "程序头大小异常");
        require(phoff <= data.size() &&
                    static_cast<std::uint64_t>(phnum) * phentsize <=
                        data.size() - phoff,
                prefix, "程序头表越界");

        bool hasInterpreter = false;
        bool hasNeeded = false;
        for (std::uint16_t index = 0; index < phnum; ++index) {
            const auto offset =
                static_cast<std::size_t>(phoff) +
                static_cast<std::size_t>(index) * phentsize;
            const auto type32 = read32(data, offset, "p_type");
            if (type32 == kPtInterp)
                hasInterpreter = true;
            if (type32 != kPtDynamic)
                continue;
            const auto dynamicOffset = read64(data, offset + 8, "p_offset");
            const auto dynamicSize = read64(data, offset + 32, "p_filesz");
            require(dynamicOffset <= data.size() &&
                        dynamicSize <= data.size() - dynamicOffset,
                    prefix, "动态段越界");
            for (std::uint64_t entry = 0; entry + 16 <= dynamicSize;
                 entry += 16) {
                const auto tag = static_cast<std::int64_t>(
                    read64(data, static_cast<std::size_t>(dynamicOffset + entry),
                           "dynamic tag"));
                if (tag == kDtNull)
                    break;
                if (tag == kDtNeeded)
                    hasNeeded = true;
            }
        }

        if (kind == "busybox") {
            require(type == kEtExec, prefix,
                    "不是静态 EXEC（可能是 PIE/DYN）：" + path.string());
        } else {
            require(type == kEtExec || type == kEtDyn, prefix,
                    "不是可执行文件：" + path.string());
            if (type == kEtDyn && !allowDynamic) {
                require(!hasInterpreter, prefix,
                        "static PIE 不能带动态解释器：" + path.string());
            }
        }
        if (!allowDynamic) {
            require(!hasInterpreter, prefix,
                    "存在动态解释器（INTERP）：" + path.string());
            require(!hasNeeded, prefix,
                    "存在动态库依赖（NEEDED）：" + path.string());
        }
        require((flags & 0x6U) == 0x4U, prefix,
                "不是 lp64d/double-float ABI：" + path.string());

        const auto attributes = readRiscvAttributes(path);
        require(attributes.arch.starts_with("rv64"), prefix,
                "Tag_RISCV_arch='" + attributes.arch + "' 不是 rv64*。");
        require(attributes.arch.find("_f") != std::string::npos &&
                    attributes.arch.find("_d") != std::string::npos,
                prefix,
                "Tag_RISCV_arch='" + attributes.arch +
                    "' 未同时包含 F/D 扩展。");
        require(attributes.arch.find("_c") != std::string::npos, prefix,
                "Tag_RISCV_arch='" + attributes.arch +
                    "' 不包含 C 扩展，未达到 RV64GC。");

        std::cout << flagsText(flags) << '\t' << attributes.arch << '\t'
                  << attributes.abi << '\n';
        return 0;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 2;
    }
}
