// Pack the SBI firmware, RISC-V Linux Image, optional initramfs and DTB into
// one NPC-loadable raw binary.
//
// This is the C++ implementation of the former shell body.  The shell entry
// point remains as a compatibility wrapper for NEMU and direct callers.

#include "linux_image_common.hpp"

#include <algorithm>
#include <iostream>
#include <sstream>
#include <utility>

namespace {

using namespace npc::linux_image;

constexpr std::uint64_t kDefaultRamBase = 0x80000000;
constexpr std::uint64_t kDefaultRamSize = 0x04000000;
constexpr std::uint64_t kDefaultFwBase = 0x80000000;
constexpr std::uint64_t kDefaultFwSize = 0x00200000;
constexpr std::uint64_t kDefaultNextAddr = 0x80200000;
constexpr std::uint64_t kDefaultFdtAddr = 0x80f00000;
constexpr std::uint64_t kDefaultInitramfsAddr = 0x82000000;

void info(const std::string &text)
{
    std::cout << "[linux-image] " << text << '\n';
}

std::string requireYN(const std::string &name, const std::string &value)
{
    if (value != "y" && value != "n")
        throw std::runtime_error(name + "='" + value +
                                 "' 非法（只允许 y/n）");
    return value;
}

std::string envOrConfig(const char *environmentName, ConfigReader &config,
                        const char *key, const std::string &fallback)
{
    const auto environmentValue = envValue(environmentName);
    return !environmentValue.empty() ? environmentValue
                                     : config.value(key, fallback);
}

std::string boolEnvOrConfig(const char *environmentName, ConfigReader &config,
                            const char *key, bool fallback)
{
    const auto environmentValue = envValue(environmentName);
    if (!environmentValue.empty())
        return environmentValue;
    return config.boolean(key, fallback) ? "y" : "n";
}

std::string fallbackBoolEnv(const char *primaryName, const char *secondaryName,
                            ConfigReader &config, const char *key,
                            bool fallback)
{
    const auto primary = envValue(primaryName);
    if (!primary.empty())
        return primary;
    const auto secondary = envValue(secondaryName);
    if (!secondary.empty())
        return secondary;
    return config.boolean(key, fallback) ? "y" : "n";
}

std::string fallbackEnv(const char *primaryName, const char *secondaryName,
                        ConfigReader &config, const char *key,
                        const std::string &fallback)
{
    const auto primary = envValue(primaryName);
    if (!primary.empty())
        return primary;
    const auto secondary = envValue(secondaryName);
    if (!secondary.empty())
        return secondary;
    return config.value(key, fallback);
}

std::unordered_map<std::string, std::string> readInfo(const fs::path &path)
{
    std::ifstream input{path};
    if (!input)
        throw std::runtime_error("无法读取文件：" + path.string());
    std::unordered_map<std::string, std::string> values;
    std::string line;
    while (std::getline(input, line)) {
        const auto equals = line.find('=');
        if (equals == std::string::npos)
            continue;
        values[line.substr(0, equals)] = line.substr(equals + 1);
    }
    return values;
}

std::string infoValue(const std::unordered_map<std::string, std::string> &info,
                      const std::string &key)
{
    const auto found = info.find(key);
    return found == info.end() ? std::string{} : found->second;
}

std::uint64_t readLe64(const std::vector<std::uint8_t> &data,
                       std::size_t offset, const std::string &what)
{
    if (offset > data.size() || data.size() - offset < 8)
        throw std::runtime_error(what + " 文件太短，无法读取 64 位字段");
    std::uint64_t value = 0;
    for (int index = 0; index < 8; ++index)
        value |= static_cast<std::uint64_t>(data[offset + index])
                 << (index * 8);
    return value;
}

std::string bytesHex(const std::vector<std::uint8_t> &data,
                     std::size_t offset, std::size_t count)
{
    if (offset > data.size() || data.size() - offset < count)
        return {};
    std::ostringstream output;
    output << std::hex << std::setfill('0');
    for (std::size_t index = 0; index < count; ++index)
        output << std::setw(2) << static_cast<unsigned>(data[offset + index]);
    return output.str();
}

std::string initramfsFormat(const fs::path &path)
{
    const auto data = readFile(path);
    const auto prefix = [&](std::size_t count) {
        return bytesHex(data, 0, count);
    };
    if (prefix(6) == "303730373031" || prefix(6) == "303730373032" ||
        prefix(6) == "303730373037")
        return "cpio";
    if (prefix(2) == "1f8b")
        return "gzip";
    if (prefix(6) == "fd377a585a00")
        return "xz";
    if (prefix(4) == "28b52ffd")
        return "zstd";
    if (prefix(4) == "04224d18")
        return "lz4";
    if (prefix(3) == "425a68")
        return "bzip2";
    if (prefix(3) == "5d0000")
        return "lzma";
    throw std::runtime_error(
        path.string() +
        " 不是支持的 initramfs 格式（cpio/gzip/xz/zstd/lz4/bzip2/lzma，实际 magic=" +
        prefix(6) + "）");
}

struct Layout {
    std::uint64_t ramBase = 0;
    std::uint64_t ramSize = 0;
    std::uint64_t ramEnd = 0;
    std::uint64_t fwBase = 0;
    std::uint64_t fwSize = 0;
    std::uint64_t fwEnd = 0;
    std::uint64_t nextAddr = 0;
    std::uint64_t imageEnd = 0;
    std::uint64_t initramfsAddr = 0;
    std::uint64_t initramfsEnd = 0;
    std::uint64_t fdtAddr = 0;
    std::uint64_t fdtEnd = 0;
};

void checkRange(const std::string &name, std::uint64_t start,
                std::uint64_t end, const Layout &layout)
{
    if (start < layout.ramBase || end > layout.ramEnd || end <= start) {
        throw std::runtime_error(
            name + " 范围 [" + hexU64(start) + ", " + hexU64(end) +
            ") 超出 NPC RAM [" + hexU64(layout.ramBase) + ", " +
            hexU64(layout.ramEnd) + ") 或为空");
    }
}

void checkOverlap(const std::string &name1, std::uint64_t start1,
                  std::uint64_t end1, const std::string &name2,
                  std::uint64_t start2, std::uint64_t end2)
{
    if (start1 < end2 && start2 < end1) {
        throw std::runtime_error(
            name1 + " [" + hexU64(start1) + ", " + hexU64(end1) +
            ") 与 " + name2 + " [" + hexU64(start2) + ", " + hexU64(end2) +
            ") 重叠");
    }
}

void place(std::vector<std::uint8_t> &binary,
           const std::vector<std::uint8_t> &source, std::uint64_t offset,
           const std::string &name)
{
    if (offset > binary.size() || source.size() > binary.size() - offset) {
        throw std::runtime_error(name + " 写入范围超出输出镜像");
    }
    std::copy(source.begin(), source.end(),
              binary.begin() + static_cast<std::ptrdiff_t>(offset));
    if (!std::equal(source.begin(), source.end(),
                    binary.begin() + static_cast<std::ptrdiff_t>(offset))) {
        throw std::runtime_error(name + " 写入后校验失败");
    }
}

std::vector<std::pair<std::string, std::string>>
dtbEnvironment(const std::string &configFile, const std::string &dtbOutDir,
               const std::string &fdtPath, const std::string &fdtAddr,
               const std::string &fwBase, const std::string &fwSize,
               const std::string &nextAddr,
               const std::string &timebaseFreq, const std::string &bootargs,
               const std::string &initramfsEnable,
               const std::string &initramfsPath,
               const std::string &initramfsAddr, const std::string &ramBase,
               const std::string &ramSize, const std::string &npcHome,
               const std::string &workbenchHome)
{
    std::vector<std::pair<std::string, std::string>> environment{
        {"SBI_CONFIG_FILE", configFile},
        {"SBI_FDT_OUT_DIR", dtbOutDir},
        {"SBI_FDT_PATH", fdtPath},
        {"SBI_FDT_ADDR", fdtAddr},
        {"SBI_FW_BASE", fwBase},
        {"SBI_FW_SIZE", fwSize},
        {"SBI_NEXT_ADDR", nextAddr},
        {"SBI_TIMEBASE_FREQ", timebaseFreq},
        {"SBI_FDT_LINUX", "y"},
        {"SBI_INITRAMFS_ENABLE", initramfsEnable},
        {"SBI_INITRAMFS_PATH", initramfsPath},
        {"SBI_INITRAMFS_ADDR", initramfsAddr},
        {"SBI_BOOTARGS", bootargs},
        {"NPC_RAM_BASE", ramBase},
        {"NPC_RAM_SIZE", ramSize},
        {"NPC_HOME", npcHome},
        {"WORKBENCH_HOME", workbenchHome},
    };
    for (const char *name :
         {"SBI_XLEN", "SBI_RISCV_M", "SBI_RISCV_A", "SBI_RISCV_C",
          "SBI_RISCV_F", "SBI_RISCV_D", "SBI_HAS_S", "SBI_HAS_U",
          "SBI_HAS_MMU", "SBI_MMU_LEVELS", "SBI_MMU_GEOMETRY_MODE",
          "SBI_MMU_PAGE_SIZE", "SBI_MMU_PTE_SIZE",
          "SBI_MMU_PAGE_TABLE_SIZE", "SBI_MMU_PTE_FORMAT"}) {
        const auto value = envValue(name);
        if (!value.empty())
            environment.emplace_back(name, value);
    }
    return environment;
}

} // namespace

int main(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    try {
        const std::string npcHome = locateNpcHome(argv[0]);
        const std::string workbenchHome = locateWorkbenchHome(npcHome);
        ConfigReader config{npcHome, workbenchHome};

        const std::string linuxBootEnable = requireYN(
            "LINUX_BOOT_ENABLE",
            boolEnvOrConfig("LINUX_BOOT_ENABLE", config,
                            "CONFIG_LINUX_BOOT_ENABLE", false));
        const std::string imagePathValue =
            envOrConfig("LINUX_IMAGE_PATH", config,
                        "CONFIG_LINUX_IMAGE_PATH", "");
        const std::string linuxInitramfsEnable = requireYN(
            "LINUX_INITRAMFS_ENABLE",
            fallbackBoolEnv("LINUX_INITRAMFS_ENABLE",
                            "SBI_INITRAMFS_ENABLE", config,
                            "CONFIG_LINUX_INITRAMFS_ENABLE", false));
        const std::string linuxInitramfsPath = fallbackEnv(
            "LINUX_INITRAMFS_PATH", "SBI_INITRAMFS_PATH", config,
            "CONFIG_LINUX_INITRAMFS_PATH", "");
        const std::string linuxInitramfsAddr = fallbackEnv(
            "LINUX_INITRAMFS_ADDR", "SBI_INITRAMFS_ADDR", config,
            "CONFIG_LINUX_INITRAMFS_ADDR", hexU64(kDefaultInitramfsAddr));
        const std::string linuxBootargs = fallbackEnv(
            "LINUX_BOOTARGS", "SBI_BOOTARGS", config,
            "CONFIG_LINUX_BOOTARGS", "console=ttyS0,115200 earlycon=sbi");
        const std::string linuxOutDir =
            envDefault("LINUX_OUT_DIR",
                     (fs::path{npcHome} / "build" / "linux").string());

        const std::string sbiEnable = requireYN(
            "SBI_ENABLE",
            boolEnvOrConfig("SBI_ENABLE", config, "CONFIG_SBI_ENABLE", false));
        const std::string sbiImpl =
            envOrConfig("SBI_IMPL", config, "CONFIG_SBI_IMPL", "opensbi");
        const std::string sbiOutDir =
            envDefault("SBI_OUT_DIR",
                     (fs::path{npcHome} / "build" / sbiImpl).string());
        const std::string fwBaseValue =
            envOrConfig("SBI_FW_BASE", config, "CONFIG_SBI_FW_BASE",
                        hexU64(kDefaultFwBase));
        const std::string fwSizeValue =
            envOrConfig("SBI_FW_SIZE", config, "CONFIG_SBI_FW_SIZE",
                        hexU64(kDefaultFwSize));
        const std::string nextAddrValue =
            envOrConfig("SBI_NEXT_ADDR", config, "CONFIG_SBI_NEXT_ADDR",
                        hexU64(kDefaultNextAddr));
        const std::string fdtEnable = requireYN(
            "SBI_FDT_ENABLE",
            boolEnvOrConfig("SBI_FDT_ENABLE", config,
                            "CONFIG_SBI_FDT_ENABLE", false));
        const std::string fdtAddrValue =
            envOrConfig("SBI_FDT_ADDR", config, "CONFIG_SBI_FDT_ADDR",
                        hexU64(kDefaultFdtAddr));
        const std::string fdtPathValue =
            envOrConfig("SBI_FDT_PATH", config, "CONFIG_SBI_FDT_PATH",
                        "dts/npc.dts");
        const std::string fdtOutDir =
            envDefault("SBI_FDT_OUT_DIR",
                     (fs::path{linuxOutDir} / "dtb").string());
        const std::string timebaseFreq = envValue("SBI_TIMEBASE_FREQ");
        const std::string ramBaseValue =
            envDefault("NPC_RAM_BASE", hexU64(kDefaultRamBase));
        const std::string ramSizeValue =
            envDefault("NPC_RAM_SIZE", hexU64(kDefaultRamSize));

        if (linuxBootEnable != "y")
            throw std::runtime_error(
                "LINUX_BOOT_ENABLE=" + linuxBootEnable +
                "，Linux 启动镜像未启用");
        if (imagePathValue.empty())
            throw std::runtime_error(
                "LINUX_IMAGE_PATH 为空；请提供用户自己的 Linux Image 路径");
        if (sbiEnable != "y")
            throw std::runtime_error(
                "SBI_ENABLE=" + sbiEnable +
                "；请先启用并构建 SBI 固件");
        if (fdtEnable != "y")
            throw std::runtime_error(
                "SBI_FDT_ENABLE=" + fdtEnable + "；Linux 需要 a1=DTB");

        std::string fwBin;
        std::string fwInfo;
        if (sbiImpl == "opensbi") {
            fwBin = (fs::path{sbiOutDir} / "fw.bin").string();
            fwInfo = (fs::path{sbiOutDir} / "fw.info").string();
        } else if (sbiImpl == "rustsbi") {
            fwBin = (fs::path{sbiOutDir} / "rustsbi-npc.bin").string();
            fwInfo = (fs::path{sbiOutDir} / "rustsbi-npc.info").string();
        } else {
            throw std::runtime_error(
                "SBI_IMPL='" + sbiImpl +
                "' 非法（只允许 opensbi/rustsbi）");
        }

        Layout layout;
        layout.ramBase = parseU64(ramBaseValue, "NPC_RAM_BASE");
        layout.ramSize = parseU64(ramSizeValue, "NPC_RAM_SIZE");
        layout.fwBase = parseU64(fwBaseValue, "SBI_FW_BASE");
        layout.fwSize = parseU64(fwSizeValue, "SBI_FW_SIZE");
        layout.nextAddr = parseU64(nextAddrValue, "SBI_NEXT_ADDR");
        layout.fdtAddr = parseU64(fdtAddrValue, "SBI_FDT_ADDR");
        layout.ramEnd =
            checkedAdd(layout.ramBase, layout.ramSize, "NPC RAM");
        layout.fwEnd = checkedAdd(layout.fwBase, layout.fwSize, "SBI 固件区");
        if (layout.ramSize == 0)
            throw std::runtime_error("NPC_RAM_SIZE 必须为正");
        if (layout.fwSize == 0)
            throw std::runtime_error("SBI_FW_SIZE 必须为正");
        if (layout.fwBase < layout.ramBase)
            throw std::runtime_error(
                "SBI_FW_BASE=" + hexU64(layout.fwBase) +
                " 低于 NPC RAM 基地址 " + hexU64(layout.ramBase));
        if (layout.fwEnd > layout.ramEnd)
            throw std::runtime_error(
                "SBI 固件区 [" + hexU64(layout.fwBase) + ", " +
                hexU64(layout.fwEnd) + ") 超出 NPC RAM");
        if (layout.nextAddr < layout.ramBase)
            throw std::runtime_error(
                "SBI_NEXT_ADDR=" + hexU64(layout.nextAddr) +
                " 低于 NPC RAM 基地址 " + hexU64(layout.ramBase));

        const fs::path imagePath = resolvePath(
            imagePathValue, workbenchHome, envValue("HOME", "/"));
        if (!fs::is_regular_file(imagePath)) {
            throw std::runtime_error(
                "Linux Image 不存在：" + imagePathValue + "（解析为 " +
                imagePath.string() + "）");
        }
        const auto imageData = readFile(imagePath);
        if (imageData.size() < 64) {
            throw std::runtime_error(
                imagePath.string() + " 只有 " +
                std::to_string(imageData.size()) +
                " 字节，不是有效的 RISC-V Linux Image（至少需要 64 字节 header）");
        }
        const std::string imageMagic = bytesHex(imageData, 56, 8);
        const std::string imageMagic2 = bytesHex(imageData, 64, 4);
        if (imageMagic != "5249534356000000") {
            throw std::runtime_error(
                imagePath.string() +
                " 缺少 RISC-V Linux Image magic（offset 0x38 应为 "
                "\"RISCV\\0\\0\\0\"，实际 " + imageMagic + "）");
        }
        if (imageMagic2 != "52534305") {
            throw std::runtime_error(
                imagePath.string() +
                " 缺少 RISC-V Linux Image magic2（offset 0x40 应为 "
                "\"RSC\\x05\"，实际 " + imageMagic2 + "）");
        }
        const auto imageSize = readLe64(imageData, 16, "Linux Image");
        const auto imageTextOffset = readLe64(imageData, 8, "Linux Image");
        const auto imageBytes = static_cast<std::uint64_t>(imageData.size());
        if (imageSize < 64)
            throw std::runtime_error(
                imagePath.string() + " header image_size=" +
                std::to_string(imageSize) + " 非法");
        if (imageSize > layout.ramSize)
            throw std::runtime_error(
                imagePath.string() + " header image_size=" +
                std::to_string(imageSize) + " 超过 NPC RAM 大小 " +
                std::to_string(layout.ramSize));
        const auto expectedTextOffset =
            layout.nextAddr - layout.ramBase;
        if (imageTextOffset != 0 &&
            imageTextOffset != expectedTextOffset) {
            throw std::runtime_error(
                imagePath.string() + " header text_offset=" +
                hexU64(imageTextOffset) + " 与 SBI_NEXT_ADDR=" +
                hexU64(layout.nextAddr) + " 不匹配（期望 " +
                hexU64(expectedTextOffset) + "）");
        }
        const auto imageSpan = std::max(imageSize, imageBytes);
        layout.imageEnd =
            checkedAdd(layout.nextAddr, imageSpan, "Linux Image");

        std::string initramfsPathValue;
        std::uint64_t initramfsBytes = 0;
        std::string initramfsFormatValue = "none";
        fs::path initramfsPath;
        if (linuxInitramfsEnable == "y") {
            if (linuxInitramfsPath.empty())
                throw std::runtime_error(
                    "LINUX_INITRAMFS_ENABLE=y 但 LINUX_INITRAMFS_PATH 为空");
            initramfsPath = resolvePath(
                linuxInitramfsPath, workbenchHome, envValue("HOME", "/"));
            if (!fs::is_regular_file(initramfsPath)) {
                throw std::runtime_error(
                    "initramfs 不存在：" + linuxInitramfsPath + "（解析为 " +
                    initramfsPath.string() + "）");
            }
            initramfsBytes = fileSize(initramfsPath);
            if (initramfsBytes == 0)
                throw std::runtime_error("initramfs 文件为空：" +
                                         initramfsPath.string());
            layout.initramfsAddr =
                parseU64(linuxInitramfsAddr, "LINUX_INITRAMFS_ADDR");
            if (layout.initramfsAddr % 4096 != 0) {
                throw std::runtime_error(
                    "LINUX_INITRAMFS_ADDR=" +
                    hexU64(layout.initramfsAddr) + " 必须按 4 KiB 对齐");
            }
            layout.initramfsEnd =
                checkedAdd(layout.initramfsAddr, initramfsBytes,
                           "initramfs");
            initramfsFormatValue = initramfsFormat(initramfsPath);
        }

        if (!fs::is_regular_file(fwBin)) {
            throw std::runtime_error(
                "SBI 固件不存在：" + fwBin +
                "。请先运行 'make sbi' 或 'make linux-image'。");
        }
        if (!fs::is_regular_file(fwInfo)) {
            throw std::runtime_error(
                "SBI 固件信息不存在：" + fwInfo +
                "。请先运行 'make sbi' 或 'make linux-image'。");
        }
        const auto fwBytes = fileSize(fwBin);
        if (fwBytes == 0)
            throw std::runtime_error("SBI 固件为空：" + fwBin);
        if (fwBytes > layout.fwSize)
            throw std::runtime_error(
                "SBI 固件 " + std::to_string(fwBytes) +
                " 字节超过 SBI_FW_SIZE=" + hexU64(layout.fwSize));
        const auto fwInfoValues = readInfo(fwInfo);
        const std::string infoFwBase = infoValue(fwInfoValues, "fw_base");
        const std::string infoFwSize = infoValue(fwInfoValues, "fw_size");
        const std::string infoFwSha = infoValue(fwInfoValues, "fw_sha256");
        const std::string infoFdtEnable =
            infoValue(fwInfoValues, "fdt_enable");
        const std::string infoFdtAddr = infoValue(fwInfoValues, "fdt_addr");
        const std::string infoFdtSha = infoValue(fwInfoValues, "fdt_sha256");
        if (parseU64(infoFwBase, "FW_INFO fw_base") != layout.fwBase) {
            throw std::runtime_error(
                fwInfo + " 的 fw_base=" + infoFwBase +
                " 与 SBI_FW_BASE=" + hexU64(layout.fwBase) + " 不一致");
        }
        if (parseU64(infoFwSize, "FW_INFO fw_size") != layout.fwSize) {
            throw std::runtime_error(
                fwInfo + " 的 fw_size=" + infoFwSize +
                " 与 SBI_FW_SIZE=" + hexU64(layout.fwSize) + " 不一致");
        }
        if (infoFwSha.empty() || infoFwSha != sha256File(fwBin)) {
            throw std::runtime_error(
                "SBI 固件哈希与 " + fwInfo +
                " 不一致；请重新运行 make sbi/linux-image");
        }
        if (infoFdtEnable != "y")
            throw std::runtime_error(
                fwInfo + " 记录 fdt_enable=" + infoFdtEnable +
                "；固件没有内嵌 DTB");
        if (parseU64(infoFdtAddr, "FW_INFO fdt_addr") != layout.fdtAddr) {
            throw std::runtime_error(
                fwInfo + " 的 fdt_addr=" + infoFdtAddr +
                " 与 SBI_FDT_ADDR=" + hexU64(layout.fdtAddr) + " 不一致");
        }
        if (infoFdtSha.empty())
            throw std::runtime_error(
                fwInfo +
                " 缺少 fdt_sha256；请重新运行 make sbi/linux-image（旧固件无法校验内嵌 DTB）");

        info("按当前 Kconfig/initramfs 重新生成 DTB：" +
             (fs::path{fdtOutDir} / "npc.dtb").string());
        runInherit(
            {(fs::path{npcHome} / "scripts" / "build_dtb.sh").string()},
            dtbEnvironment(
                firstNonEmpty({envValue("SBI_CONFIG_FILE"),
                               envValue("NPC_CONFIG_FILE"),
                               (fs::path{npcHome} / ".config").string()}),
                fdtOutDir, fdtPathValue, hexU64(layout.fdtAddr),
                hexU64(layout.fwBase), hexU64(layout.fwSize),
                hexU64(layout.nextAddr), timebaseFreq, linuxBootargs,
                linuxInitramfsEnable, linuxInitramfsPath, linuxInitramfsAddr,
                hexU64(layout.ramBase), hexU64(layout.ramSize), npcHome,
                workbenchHome));

        const fs::path dtbPath = fs::path{fdtOutDir} / "npc.dtb";
        if (!fs::is_regular_file(dtbPath))
            throw std::runtime_error("DTB 生成脚本未生成 " + dtbPath.string());
        const auto dtbBytes = fileSize(dtbPath);
        if (firstHex(dtbPath, 4) != "d00dfeed")
            throw std::runtime_error(
                dtbPath.string() + " magic 不是 0xd00dfeed（实际 " +
                firstHex(dtbPath, 4) + "）");
        const std::string dtbSha = sha256File(dtbPath);
        if (dtbSha != infoFdtSha) {
            throw std::runtime_error(
                "当前 DTB 与 SBI 固件内嵌 DTB 不一致（dtb=" + dtbSha +
                " fw_info=" + infoFdtSha +
                "）；请重新运行 make linux-image");
        }
        layout.fdtEnd = checkedAdd(layout.fdtAddr, dtbBytes, "DTB");
        if (linuxInitramfsEnable == "y") {
            layout.initramfsEnd =
                checkedAdd(layout.initramfsAddr, initramfsBytes, "initramfs");
        }

        checkRange("SBI 固件", layout.fwBase, layout.fwEnd, layout);
        checkRange("Linux Image", layout.nextAddr, layout.imageEnd, layout);
        checkOverlap("SBI 固件", layout.fwBase, layout.fwEnd, "Linux Image",
                     layout.nextAddr, layout.imageEnd);
        if (linuxInitramfsEnable == "y") {
            checkRange("initramfs", layout.initramfsAddr, layout.initramfsEnd,
                       layout);
            checkOverlap("initramfs", layout.initramfsAddr,
                         layout.initramfsEnd, "SBI 固件", layout.fwBase,
                         layout.fwEnd);
            checkOverlap("initramfs", layout.initramfsAddr,
                         layout.initramfsEnd, "Linux Image", layout.nextAddr,
                         layout.imageEnd);
            checkOverlap("initramfs", layout.initramfsAddr,
                         layout.initramfsEnd, "DTB", layout.fdtAddr,
                         layout.fdtEnd);
        }
        checkRange("DTB", layout.fdtAddr, layout.fdtEnd, layout);
        checkOverlap("DTB", layout.fdtAddr, layout.fdtEnd, "SBI 固件",
                     layout.fwBase, layout.fwEnd);
        checkOverlap("DTB", layout.fdtAddr, layout.fdtEnd, "Linux Image",
                     layout.nextAddr, layout.imageEnd);

        std::uint64_t maxEnd = std::max({layout.fwEnd, layout.imageEnd,
                                         layout.fdtEnd});
        if (linuxInitramfsEnable == "y")
            maxEnd = std::max(maxEnd, layout.initramfsEnd);
        const auto binBytes = maxEnd - layout.ramBase;
        if (binBytes == 0 || binBytes > layout.ramSize) {
            throw std::runtime_error(
                "打包大小 " + hexU64(binBytes) + " 超出 NPC RAM 大小 " +
                hexU64(layout.ramSize));
        }

        fs::create_directories(linuxOutDir);
        const fs::path outBin = fs::path{linuxOutDir} / "linux-image.bin";
        const fs::path outInfo = fs::path{outBin.string() + ".info"};
        const fs::path outSha = fs::path{outBin.string() + ".sha256"};
        const fs::path tmpBin =
            fs::path{outBin.string() + ".tmp." + std::to_string(::getpid())};

        info("布局：RAM [" + hexU64(layout.ramBase) + ", " +
             hexU64(layout.ramEnd) + ")");
        info("  SBI       [" + hexU64(layout.fwBase) + ", " +
             hexU64(layout.fwEnd) + ")  " + std::to_string(fwBytes) +
             " 字节 (" + sbiImpl + ")");
        info("  Image     [" + hexU64(layout.nextAddr) + ", " +
             hexU64(layout.imageEnd) + ")  file=" +
             std::to_string(imageBytes) + " header_size=" +
             std::to_string(imageSize));
        if (linuxInitramfsEnable == "y") {
            info("  initramfs [" + hexU64(layout.initramfsAddr) + ", " +
                 hexU64(layout.initramfsEnd) + ")  " +
                 std::to_string(initramfsBytes) + " 字节 (" +
                 initramfsFormatValue + ")");
        } else {
            info("  initramfs 关闭（DTB 不生成 linux,initrd-*）");
        }
        info("  DTB       [" + hexU64(layout.fdtAddr) + ", " +
             hexU64(layout.fdtEnd) + ")  " + std::to_string(dtbBytes) +
             " 字节");
        info("  输出      " + outBin.string() + " (" +
             std::to_string(binBytes) + " 字节)");

        std::vector<std::uint8_t> binary(static_cast<std::size_t>(binBytes),
                                         0);
        place(binary, readFile(fwBin),
              layout.fwBase - layout.ramBase, "SBI 固件");
        place(binary, imageData, layout.nextAddr - layout.ramBase,
              "Linux Image");
        if (linuxInitramfsEnable == "y") {
            place(binary, readFile(initramfsPath),
                  layout.initramfsAddr - layout.ramBase, "initramfs");
        }
        place(binary, readFile(dtbPath), layout.fdtAddr - layout.ramBase,
              "DTB");
        writeFile(tmpBin, binary);
        std::error_code error;
        fs::rename(tmpBin, outBin, error);
        if (error) {
            fs::remove(tmpBin, error);
            throw std::runtime_error("重命名输出镜像失败: " +
                                     error.message());
        }
        const auto binarySha = sha256File(outBin);

        std::ostringstream infoText;
        infoText << "format=1\n"
                 << "impl=" << sbiImpl << '\n'
                 << "binary=" << outBin.string() << '\n'
                 << "binary_bytes=" << binBytes << '\n'
                 << "binary_sha256=" << binarySha << '\n'
                 << "ram_base=" << hexU64(layout.ramBase) << '\n'
                 << "ram_size=" << hexU64(layout.ramSize) << '\n'
                 << "fw_path=" << fwBin << '\n'
                 << "fw_base=" << hexU64(layout.fwBase) << '\n'
                 << "fw_size=" << hexU64(layout.fwSize) << '\n'
                 << "fw_bytes=" << fwBytes << '\n'
                 << "fw_sha256=" << sha256File(fwBin) << '\n'
                 << "image_path=" << imagePath.string() << '\n'
                 << "image_addr=" << hexU64(layout.nextAddr) << '\n'
                 << "image_bytes=" << imageBytes << '\n'
                 << "image_header_size=" << imageSize << '\n'
                 << "image_text_offset=" << hexU64(imageTextOffset) << '\n'
                 << "image_sha256=" << sha256File(imagePath) << '\n'
                 << "initramfs_enable=" << linuxInitramfsEnable << '\n';
        if (linuxInitramfsEnable == "y") {
            infoText << "initramfs_path=" << initramfsPath.string() << '\n'
                     << "initramfs_addr=" << hexU64(layout.initramfsAddr)
                     << '\n'
                     << "initramfs_bytes=" << initramfsBytes << '\n'
                     << "initramfs_end=" << hexU64(layout.initramfsEnd)
                     << '\n'
                     << "initramfs_format=" << initramfsFormatValue << '\n'
                     << "initramfs_sha256=" << sha256File(initramfsPath)
                     << '\n';
        }
        const auto dtbInfo = readInfo(fs::path{fdtOutDir} / "npc.dtb.info");
        infoText << "dtb_path=" << dtbPath.string() << '\n'
                 << "dtb_addr=" << hexU64(layout.fdtAddr) << '\n'
                 << "dtb_bytes=" << dtbBytes << '\n'
                 << "dtb_sha256=" << dtbSha << '\n'
                 << "timebase_freq=" << infoValue(dtbInfo, "timebase_freq")
                 << '\n'
                 << "bootargs=" << linuxBootargs << '\n';
        const fs::path infoTmp = fs::path{outInfo.string() + ".tmp"};
        writeText(infoTmp, infoText.str());
        fs::rename(infoTmp, outInfo);
        const fs::path shaTmp = fs::path{outSha.string() + ".tmp"};
        writeText(shaTmp, binarySha + "  " +
                              outBin.filename().string() + "\n");
        fs::rename(shaTmp, outSha);

        info("完成：" + outBin.string());
        info("sha256：" + binarySha);
        info("摘要：" + outInfo.string());
        info("校验：" + outSha.string());
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "错误：" << error.what() << '\n';
        return 2;
    }
}
