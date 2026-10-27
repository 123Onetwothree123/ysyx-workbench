// Generate the NPC device tree from the effective Kconfig projection.
//
// The shell entry point remains as a thin compatibility wrapper because NEMU
// and the Linux packaging path invoke it directly.  All configuration
// interpretation, address validation, ISA/MMU derivation and output metadata
// live here.

#include "linux_image_common.hpp"

#include <iostream>
#include <sstream>

namespace {

using namespace npc::linux_image;

constexpr std::uint64_t kDefaultFdtAddr = 0x80f00000;
constexpr std::uint64_t kDefaultFwBase = 0x80000000;
constexpr std::uint64_t kDefaultFwSize = 0x00200000;
constexpr std::uint64_t kDefaultNextAddr = 0x80200000;
constexpr std::uint64_t kDefaultFdtReserve = 0x00010000;
constexpr std::uint64_t kDefaultNextReserve = 0x00001000;
constexpr std::uint64_t kDefaultRamBase = 0x80000000;
constexpr std::uint64_t kDefaultRamSize = 0x04000000;

void info(const std::string &text)
{
    std::cout << "[dtb] " << text << '\n';
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

std::string requireYN(const std::string &name, const std::string &value)
{
    if (value != "y" && value != "n")
        throw std::runtime_error(name + "='" + value +
                                 "' 非法（只允许 y/n）");
    return value;
}

struct Layout {
    std::uint64_t fdtAddr = 0;
    std::uint64_t fwBase = 0;
    std::uint64_t fwSize = 0;
    std::uint64_t nextAddr = 0;
    std::uint64_t fdtReserve = 0;
    std::uint64_t nextReserve = 0;
    std::uint64_t ramBase = 0;
    std::uint64_t ramSize = 0;
    std::uint64_t xlen = 0;
    std::uint64_t mmuLevels = 0;
    std::uint64_t mmuPageSize = 0;
    std::uint64_t mmuPteSize = 0;
    std::uint64_t mmuPageTableSize = 0;
    std::uint64_t synthFreqMhz = 0;
    std::uint64_t timebaseFreq = 0;
    std::uint64_t initramfsAddr = 0;
    std::uint64_t initramfsSize = 0;
    std::uint64_t initramfsEnd = 0;
    std::uint64_t fdtEnd = 0;
    std::uint64_t fwEnd = 0;
    std::uint64_t ramEnd = 0;
    fs::path initramfsPath;
};

std::string mmuTypeFor(const Layout &layout, const std::string &geometryMode,
                       const std::string &pteFormat, bool hasMmu,
                       bool &valid, bool &standard)
{
    valid = false;
    standard = false;
    if (!hasMmu)
        return {};

    if (geometryMode != "standard")
        return {};

    if (layout.xlen == 32) {
        if (layout.mmuLevels == 2 && layout.mmuPageSize == 4096 &&
            layout.mmuPteSize == 4 && layout.mmuPageTableSize == 4096 &&
            pteFormat == "riscv32") {
            valid = true;
            standard = true;
            return "riscv,sv32";
        }
        return {};
    }

    if (layout.mmuPageSize == 4096 && layout.mmuPteSize == 8 &&
        layout.mmuPageTableSize == 4096 && pteFormat == "riscv64") {
        if (layout.mmuLevels == 3) {
            valid = true;
            standard = true;
            return "riscv,sv39";
        }
        if (layout.mmuLevels == 4) {
            valid = true;
            standard = true;
            return "riscv,sv48";
        }
    }
    return {};
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

        if (!config.isJson()) {
            info("配置 JSON 不存在；回退解析 legacy 文件 " +
                 config.sourcePath());
        }

        const std::string fdtPathValue =
            envOrConfig("SBI_FDT_PATH", config, "CONFIG_SBI_FDT_PATH",
                        "dts/npc.dts");
        const std::string fdtAddrValue =
            envOrConfig("SBI_FDT_ADDR", config, "CONFIG_SBI_FDT_ADDR",
                        hexU64(kDefaultFdtAddr));
        const std::string fwBaseValue =
            envOrConfig("SBI_FW_BASE", config, "CONFIG_SBI_FW_BASE",
                        hexU64(kDefaultFwBase));
        const std::string fwSizeValue =
            envOrConfig("SBI_FW_SIZE", config, "CONFIG_SBI_FW_SIZE",
                        hexU64(kDefaultFwSize));
        const std::string nextAddrValue =
            envOrConfig("SBI_NEXT_ADDR", config, "CONFIG_SBI_NEXT_ADDR",
                        hexU64(kDefaultNextAddr));
        const std::string fdtOutDir =
            envDefault("SBI_FDT_OUT_DIR",
                     (fs::path{npcHome} / "build" / "dtb").string());
        const std::string fdtReserveValue =
            envDefault("SBI_FDT_RESERVE_SIZE", hexU64(kDefaultFdtReserve));
        const std::string nextReserveValue =
            envDefault("SBI_NEXT_RESERVE_SIZE", hexU64(kDefaultNextReserve));
        const std::string ramBaseValue =
            envDefault("NPC_RAM_BASE", hexU64(kDefaultRamBase));
        const std::string ramSizeValue =
            envDefault("NPC_RAM_SIZE", hexU64(kDefaultRamSize));

        const std::string xlenValue =
            envOrConfig("SBI_XLEN", config, "CONFIG_XLEN", "64");
        const std::string riscvM =
            requireYN("SBI_RISCV_M",
                      boolEnvOrConfig("SBI_RISCV_M", config, "CONFIG_RISCV_M",
                                      true));
        const std::string riscvA =
            requireYN("SBI_RISCV_A",
                      boolEnvOrConfig("SBI_RISCV_A", config, "CONFIG_RISCV_A",
                                      true));
        const std::string riscvC =
            requireYN("SBI_RISCV_C",
                      boolEnvOrConfig("SBI_RISCV_C", config, "CONFIG_RISCV_C",
                                      false));
        const std::string riscvF =
            requireYN("SBI_RISCV_F",
                      boolEnvOrConfig("SBI_RISCV_F", config, "CONFIG_RISCV_F",
                                      false));
        const std::string riscvD =
            requireYN("SBI_RISCV_D",
                      boolEnvOrConfig("SBI_RISCV_D", config, "CONFIG_RISCV_D",
                                      false));
        const std::string hasS =
            requireYN("SBI_HAS_S",
                      boolEnvOrConfig("SBI_HAS_S", config, "CONFIG_HAS_S",
                                      true));
        const std::string hasU =
            requireYN("SBI_HAS_U",
                      boolEnvOrConfig("SBI_HAS_U", config, "CONFIG_HAS_U",
                                      false));
        const std::string hasMmu =
            requireYN("SBI_HAS_MMU",
                      boolEnvOrConfig("SBI_HAS_MMU", config, "CONFIG_HAS_MMU",
                                      true));
        const std::string mmuLevelsValue =
            envOrConfig("SBI_MMU_LEVELS", config, "CONFIG_MMU_LEVELS", "3");
        const std::string mmuPageSizeValue = envOrConfig(
            "SBI_MMU_PAGE_SIZE", config, "CONFIG_MMU_PAGE_SIZE_BYTES", "4096");
        const std::string mmuPteSizeValue = envOrConfig(
            "SBI_MMU_PTE_SIZE", config, "CONFIG_MMU_PTE_SIZE_BYTES", "8");
        const std::string mmuPageTableSizeValue =
            envOrConfig("SBI_MMU_PAGE_TABLE_SIZE", config,
                        "CONFIG_MMU_PAGE_TABLE_SIZE_BYTES", "4096");
        std::string mmuGeometryMode =
            envOrConfig("SBI_MMU_GEOMETRY_MODE", config,
                        "CONFIG_MMU_GEOMETRY_MODE", "");
        if (mmuGeometryMode.empty()) {
            mmuGeometryMode = config.boolean("CONFIG_MMU_GEOMETRY_CUSTOM", false)
                                  ? "custom"
                                  : "standard";
        }
        std::string mmuPteFormat =
            envOrConfig("SBI_MMU_PTE_FORMAT", config,
                        "CONFIG_MMU_PTE_FORMAT", "");
        if (mmuPteFormat.empty()) {
            if (config.boolean("CONFIG_MMU_PTE_CUSTOM", false))
                mmuPteFormat = "custom";
            else if (config.boolean("CONFIG_MMU_PTE_RISCV64", false))
                mmuPteFormat = "riscv64";
            else
                mmuPteFormat = "riscv32";
        }

        const std::string synthFreqValue =
            envOrConfig("SBI_SYNTH_FREQ", config, "CONFIG_SYNTH_FREQ", "450");
        const std::string linuxValue = requireYN(
            "SBI_FDT_LINUX",
            boolEnvOrConfig("SBI_FDT_LINUX", config,
                            "CONFIG_LINUX_BOOT_ENABLE", false));
        const std::string initramfsEnable = requireYN(
            "SBI_INITRAMFS_ENABLE",
            boolEnvOrConfig("SBI_INITRAMFS_ENABLE", config,
                            "CONFIG_LINUX_INITRAMFS_ENABLE", false));
        const std::string initramfsPathValue =
            envOrConfig("SBI_INITRAMFS_PATH", config,
                        "CONFIG_LINUX_INITRAMFS_PATH", "");
        const std::string initramfsAddrValue =
            envOrConfig("SBI_INITRAMFS_ADDR", config,
                        "CONFIG_LINUX_INITRAMFS_ADDR", "0x82000000");
        const std::string bootargs =
            envOrConfig("SBI_BOOTARGS", config, "CONFIG_LINUX_BOOTARGS",
                        "console=ttyS0,115200 earlycon=sbi");

        Layout layout;
        layout.fdtAddr = parseU64(fdtAddrValue, "SBI_FDT_ADDR");
        layout.fwBase = parseU64(fwBaseValue, "SBI_FW_BASE");
        layout.fwSize = parseU64(fwSizeValue, "SBI_FW_SIZE");
        layout.nextAddr = parseU64(nextAddrValue, "SBI_NEXT_ADDR");
        layout.fdtReserve =
            parseU64(fdtReserveValue, "SBI_FDT_RESERVE_SIZE");
        layout.nextReserve =
            parseU64(nextReserveValue, "SBI_NEXT_RESERVE_SIZE");
        layout.ramBase = parseU64(ramBaseValue, "NPC_RAM_BASE");
        layout.ramSize = parseU64(ramSizeValue, "NPC_RAM_SIZE");
        layout.xlen = parseU64(xlenValue, "XLEN");
        layout.mmuLevels = parseU64(mmuLevelsValue, "MMU_LEVELS");
        layout.mmuPageSize = parseU64(mmuPageSizeValue, "MMU_PAGE_SIZE");
        layout.mmuPteSize = parseU64(mmuPteSizeValue, "MMU_PTE_SIZE");
        layout.mmuPageTableSize =
            parseU64(mmuPageTableSizeValue, "MMU_PAGE_TABLE_SIZE");
        layout.synthFreqMhz = parseU64(synthFreqValue, "SYNTH_FREQ");

        if (layout.xlen != 32 && layout.xlen != 64)
            throw std::runtime_error("CONFIG_XLEN=" +
                                     std::to_string(layout.xlen) +
                                     " 非法：只允许 32 或 64");
        if (riscvD == "y" && riscvF != "y")
            throw std::runtime_error("CONFIG_RISCV_D=y 但 CONFIG_RISCV_F!=y：D 不能脱离 F");

        std::string isa = "rv" + std::to_string(layout.xlen) + "i";
        const std::string isaBase = isa;
        std::vector<std::string> extensions{"i"};
        std::string extensionList = "\"i\"";
        const auto appendExtension = [&](const std::string &extension) {
            isa += extension;
            extensions.push_back(extension);
            extensionList += ", \"" + extension + "\"";
        };
        if (riscvM == "y")
            appendExtension("m");
        if (riscvA == "y")
            appendExtension("a");
        if (riscvF == "y")
            appendExtension("f");
        if (riscvD == "y")
            appendExtension("d");
        if (riscvC == "y")
            appendExtension("c");
        isa += "_zicsr_zifencei";
        extensions.emplace_back("zicsr");
        extensions.emplace_back("zifencei");
        extensionList += ", \"zicsr\", \"zifencei\"";

        bool mmuTypeValid = false;
        bool mmuStandard = false;
        const std::string mmuType =
            mmuTypeFor(layout, mmuGeometryMode, mmuPteFormat,
                       hasMmu == "y", mmuTypeValid, mmuStandard);

        if (!mmuTypeValid) {
            if (linuxValue == "y") {
                throw std::runtime_error(
                    "Kconfig 的 MMU 几何（XLEN=" +
                    std::to_string(layout.xlen) + " mode=" + mmuGeometryMode +
                    " levels=" + std::to_string(layout.mmuLevels) +
                    " page=" + std::to_string(layout.mmuPageSize) +
                    " pte=" + std::to_string(layout.mmuPteSize) +
                    " table=" + std::to_string(layout.mmuPageTableSize) +
                    " format=" + mmuPteFormat +
                    "）不是标准 Sv32/Sv39/Sv48，不能生成 Linux DTB");
            }
            info("非标准 MMU 几何：DTB 不写 mmu-type（避免伪装成标准类型）");
        } else {
            info("MMU 类型：" + mmuType);
        }

        if (linuxValue == "y" && hasS != "y")
            throw std::runtime_error("生成 Linux DTB 需要 CONFIG_HAS_S=y");

        const std::string timebaseValue =
            envDefault("SBI_TIMEBASE_FREQ",
                       std::to_string(layout.synthFreqMhz * 1000000ULL));
        layout.timebaseFreq = parseU64(timebaseValue, "SBI_TIMEBASE_FREQ");
        if (layout.timebaseFreq == 0)
            throw std::runtime_error("SBI_TIMEBASE_FREQ 必须为正");
        if (layout.timebaseFreq > 0xffffffffULL)
            throw std::runtime_error("SBI_TIMEBASE_FREQ=" +
                                     std::to_string(layout.timebaseFreq) +
                                     " 超出 DTB timebase-frequency 的 32 位范围");

        layout.initramfsAddr =
            parseU64(initramfsAddrValue, "SBI_INITRAMFS_ADDR");
        if (initramfsEnable == "y") {
            if (initramfsPathValue.empty())
                throw std::runtime_error(
                    "CONFIG_LINUX_INITRAMFS_ENABLE=y 但 LINUX_INITRAMFS_PATH 为空");
            layout.initramfsPath = resolvePath(
                initramfsPathValue, workbenchHome, envValue("HOME", "/"));
            if (!fs::is_regular_file(layout.initramfsPath)) {
                throw std::runtime_error(
                    "initramfs 文件不存在：" + initramfsPathValue +
                    "（解析为 " + layout.initramfsPath.string() + "）");
            }
            const auto actualSize = fileSize(layout.initramfsPath);
            if (actualSize == 0)
                throw std::runtime_error("initramfs 文件为空：" +
                                         layout.initramfsPath.string());
            const std::string explicitSize = envValue("SBI_INITRAMFS_SIZE");
            if (!explicitSize.empty()) {
                layout.initramfsSize =
                    parseU64(explicitSize, "SBI_INITRAMFS_SIZE");
                if (layout.initramfsSize != actualSize) {
                    throw std::runtime_error(
                        "SBI_INITRAMFS_SIZE=" +
                        std::to_string(layout.initramfsSize) +
                        " 与文件实际大小 " + std::to_string(actualSize) +
                        " 不一致");
                }
            } else {
                layout.initramfsSize = actualSize;
            }
            if (layout.initramfsAddr == 0)
                throw std::runtime_error(
                    "启用 initramfs 时 SBI_INITRAMFS_ADDR 不能为 0");
            if (layout.initramfsAddr % 4096 != 0) {
                throw std::runtime_error(
                    "LINUX_INITRAMFS_ADDR=" +
                    hexU64(layout.initramfsAddr) + " 必须按 4 KiB 对齐");
            }
            layout.initramfsEnd =
                checkedAdd(layout.initramfsAddr, layout.initramfsSize,
                           "initramfs");
        }

        if (layout.fdtAddr % 8 != 0) {
            throw std::runtime_error(
                "SBI_FDT_ADDR=" + hexU64(layout.fdtAddr) +
                " 必须按 8 字节对齐（DTB 规范要求）");
        }
        if (layout.fdtReserve == 0 || layout.fdtReserve % 4096 != 0) {
            throw std::runtime_error(
                "SBI_FDT_RESERVE_SIZE=" + hexU64(layout.fdtReserve) +
                " 必须是正的 4 KiB 倍数");
        }
        if (layout.nextReserve == 0)
            throw std::runtime_error("SBI_NEXT_RESERVE_SIZE 必须为正");
        if (layout.ramSize == 0)
            throw std::runtime_error("NPC_RAM_SIZE 必须为正");

        layout.ramEnd =
            checkedAdd(layout.ramBase, layout.ramSize, "NPC RAM");
        layout.fwEnd = checkedAdd(layout.fwBase, layout.fwSize, "SBI 固件区");
        layout.fdtEnd =
            checkedAdd(layout.fdtAddr, layout.fdtReserve, "DTB 保留区");
        const auto nextEnd =
            checkedAdd(layout.nextAddr, layout.nextReserve, "next-stage 保留页");

        if (layout.nextAddr < layout.ramBase || nextEnd > layout.ramEnd) {
            throw std::runtime_error(
                "next-stage 保留页 [" + hexU64(layout.nextAddr) + ", " +
                hexU64(nextEnd) + ") 超出 NPC RAM");
        }
        if (layout.fdtAddr < layout.ramBase || layout.fdtEnd > layout.ramEnd) {
            throw std::runtime_error(
                "DTB 范围 [" + hexU64(layout.fdtAddr) + ", " +
                hexU64(layout.fdtEnd) + ") 超出 NPC RAM [" +
                hexU64(layout.ramBase) + ", " + hexU64(layout.ramEnd) + ")");
        }
        if (layout.fdtAddr < layout.fwEnd &&
            layout.fdtEnd > layout.fwBase) {
            throw std::runtime_error(
                "DTB 范围 [" + hexU64(layout.fdtAddr) + ", " +
                hexU64(layout.fdtEnd) + ") 与 SBI 固件区 [" +
                hexU64(layout.fwBase) + ", " + hexU64(layout.fwEnd) +
                ") 重叠");
        }
        if (layout.fdtAddr < nextEnd && layout.fdtEnd > layout.nextAddr) {
            throw std::runtime_error(
                "DTB 范围 [" + hexU64(layout.fdtAddr) + ", " +
                hexU64(layout.fdtEnd) + ") 与 next-stage 保留页 [" +
                hexU64(layout.nextAddr) + ", " + hexU64(nextEnd) + ") 重叠");
        }
        if (layout.initramfsSize > 0) {
            if (layout.initramfsAddr < layout.ramBase ||
                layout.initramfsEnd > layout.ramEnd) {
                throw std::runtime_error(
                    "initramfs 范围 [" + hexU64(layout.initramfsAddr) + ", " +
                    hexU64(layout.initramfsEnd) + ") 超出 NPC RAM [" +
                    hexU64(layout.ramBase) + ", " + hexU64(layout.ramEnd) +
                    ")");
            }
            if (layout.initramfsAddr < layout.fwEnd &&
                layout.initramfsEnd > layout.fwBase) {
                throw std::runtime_error(
                    "initramfs 范围 [" + hexU64(layout.initramfsAddr) + ", " +
                    hexU64(layout.initramfsEnd) + ") 与 SBI 固件区重叠");
            }
            if (layout.initramfsAddr < layout.fdtEnd &&
                layout.initramfsEnd > layout.fdtAddr) {
                throw std::runtime_error(
                    "initramfs 范围 [" + hexU64(layout.initramfsAddr) + ", " +
                    hexU64(layout.initramfsEnd) + ") 与 DTB 保留区重叠");
            }
            if (layout.initramfsAddr < nextEnd &&
                layout.initramfsEnd > layout.nextAddr) {
                throw std::runtime_error("initramfs 范围与 next-stage 入口页重叠");
            }
        }
        if (layout.fdtAddr < layout.nextAddr) {
            if (layout.fdtEnd > layout.nextAddr)
                throw std::runtime_error(
                    "DTB 位于 next-stage 之前时不能越过 SBI_NEXT_ADDR");
        } else {
            const auto gapMiB =
                (layout.fdtAddr - layout.nextAddr) / 1024 / 1024;
            info("next-stage 可用区间 [" + hexU64(layout.nextAddr) + ", " +
                 hexU64(layout.fdtAddr) + ") 约 " +
                 std::to_string(gapMiB) +
                 " MiB；请确保内核镜像不超过该范围");
        }

        const fs::path source =
            fdtPathValue.front() == '/' ? fs::path{fdtPathValue}
                                        : fs::path{npcHome} / fdtPathValue;
        if (!fs::is_regular_file(source)) {
            throw std::runtime_error("SBI_FDT_PATH 不存在：" + fdtPathValue +
                                     "（解析为 " + source.string() + "）");
        }
        if (linuxValue == "y" && source.extension() == ".dtb")
            throw std::runtime_error(
                "生成 Linux DTB 时 SBI_FDT_PATH 必须是 .dts：预编译 .dtb 无法按 Kconfig/initramfs 重新生成");

        fs::create_directories(fdtOutDir);
        const fs::path dtbOut = fs::path{fdtOutDir} / "npc.dtb";
        const fs::path expandedOut = fs::path{fdtOutDir} / "npc.dts";
        const fs::path infoOut = fs::path{fdtOutDir} / "npc.dtb.info";
        const fs::path tmpDtb = fs::path{dtbOut.string() + ".tmp"};

        if (source.extension() == ".dts") {
            std::vector<std::string> cppArgs{
                "cpp",
                "-P",
                "-nostdinc",
                "-undef",
                "-x",
                "assembler-with-cpp",
                "-DNPC_RAM_BASE=" + hexU64(layout.ramBase),
                "-DNPC_RAM_SIZE=" + hexU64(layout.ramSize),
                "-DNPC_RAM_BASE_NAME=" + hexName(layout.ramBase),
                "-DSBI_FW_BASE=" + hexU64(layout.fwBase),
                "-DSBI_FW_SIZE=" + hexU64(layout.fwSize),
                "-DSBI_FW_BASE_NAME=" + hexName(layout.fwBase),
                "-DSBI_NEXT_ADDR=" + hexU64(layout.nextAddr),
                "-DSBI_NEXT_ADDR_NAME=" + hexName(layout.nextAddr),
                "-DSBI_NEXT_RESERVE_SIZE=" + hexU64(layout.nextReserve),
                "-DSBI_FDT_ADDR=" + hexU64(layout.fdtAddr),
                "-DSBI_FDT_ADDR_NAME=" + hexName(layout.fdtAddr),
                "-DSBI_FDT_RESERVE_SIZE=" + hexU64(layout.fdtReserve),
                "-DNPC_TIMEBASE_FREQ=" + std::to_string(layout.timebaseFreq),
                "-DNPC_RISCV_ISA=\"" + cppEscape(isa) + "\"",
                "-DNPC_RISCV_ISA_BASE=\"" + cppEscape(isaBase) + "\"",
                "-DNPC_RISCV_ISA_EXTENSIONS=" + extensionList,
                "-DNPC_MMU_TYPE=\"" + cppEscape(mmuType) + "\"",
                "-DNPC_MMU_TYPE_VALID=" + std::to_string(mmuTypeValid ? 1 : 0),
                "-DNPC_BOOTARGS=\"" + cppEscape(bootargs) + "\"",
                "-DSBI_INITRAMFS_ADDR=" + hexU64(layout.initramfsAddr),
                "-DSBI_INITRAMFS_SIZE=" + hexU64(layout.initramfsSize),
                "-DSBI_INITRAMFS_END=" + hexU64(layout.initramfsEnd),
                source.string(),
                "-o",
                expandedOut.string(),
            };
            info("展开 DTS：" + source.string());
            runInherit(cppArgs);

            info("编译 DTB：" + dtbOut.string());
            runInherit({"dtc", "-q", "-I", "dts", "-O", "dtb",
                        "-o", tmpDtb.string(), expandedOut.string()});
            std::error_code error;
            fs::rename(tmpDtb, dtbOut, error);
            if (error)
                throw std::runtime_error("重命名 DTB 失败: " + error.message());
        } else if (source.extension() == ".dtb") {
            info("使用预编译 DTB：" + source.string());
            std::error_code error;
            fs::remove(expandedOut, error);
            copyFile(source, tmpDtb);
            error.clear();
            fs::rename(tmpDtb, dtbOut, error);
            if (error)
                throw std::runtime_error("重命名 DTB 失败: " + error.message());
        } else {
            throw std::runtime_error(
                "无法识别的设备树扩展名：" + fdtPathValue +
                "（只支持 .dts / .dtb）");
        }

        if (!fs::is_regular_file(dtbOut))
            throw std::runtime_error("DTB 生成失败：" + dtbOut.string());
        const std::string magic = firstHex(dtbOut, 4);
        if (magic != "d00dfeed")
            throw std::runtime_error(dtbOut.string() +
                                     " 的 magic 不是 0xd00dfeed（实际 " +
                                     magic + "）");

        const auto dtbSize = fileSize(dtbOut);
        if (dtbSize == 0 || dtbSize > layout.fdtReserve) {
            throw std::runtime_error(
                "DTB 大小 " + std::to_string(dtbSize) +
                " 超出 SBI_FDT_RESERVE_SIZE=" + hexU64(layout.fdtReserve));
        }
        if (layout.fdtAddr < layout.nextAddr &&
            dtbSize > layout.nextAddr - layout.fdtAddr) {
            throw std::runtime_error(
                "DTB 实际大小 " + std::to_string(dtbSize) +
                " 越过 SBI_NEXT_ADDR");
        }

        const std::string dtbSha = sha256File(dtbOut);
        std::ostringstream infoText;
        infoText << "source=" << source.string() << '\n'
                 << "isa=" << isa << '\n'
                 << "isa_base=" << isaBase << '\n'
                 << "isa_extensions=";
        for (std::size_t index = 0; index < extensions.size(); ++index) {
            if (index != 0)
                infoText << ' ';
            infoText << extensions[index];
        }
        infoText << '\n'
                 << "has_s=" << hasS << '\n'
                 << "has_u=" << hasU << '\n'
                 << "mmu_type=" << (mmuType.empty() ? "none" : mmuType) << '\n'
                 << "mmu_standard=" << (mmuStandard ? 1 : 0) << '\n'
                 << "timebase_freq=" << layout.timebaseFreq << '\n'
                 << "synth_freq_mhz=" << layout.synthFreqMhz << '\n'
                 << "bootargs=" << bootargs << '\n'
                 << "linux_compatible=" << linuxValue << '\n'
                 << "fdt_addr=" << hexU64(layout.fdtAddr) << '\n'
                 << "fdt_reserve_size=" << hexU64(layout.fdtReserve) << '\n'
                 << "dtb_bytes=" << dtbSize << '\n'
                 << "dtb_sha256=" << dtbSha << '\n'
                 << "fw_base=" << hexU64(layout.fwBase) << '\n'
                 << "fw_size=" << hexU64(layout.fwSize) << '\n'
                 << "next_addr=" << hexU64(layout.nextAddr) << '\n'
                 << "ram_base=" << hexU64(layout.ramBase) << '\n'
                 << "ram_size=" << hexU64(layout.ramSize) << '\n'
                 << "initramfs_enable=" << initramfsEnable << '\n';
        if (layout.initramfsSize > 0) {
            infoText << "initramfs_path=" << layout.initramfsPath.string()
                     << '\n'
                     << "initramfs_addr=" << hexU64(layout.initramfsAddr)
                     << '\n'
                     << "initramfs_size=" << hexU64(layout.initramfsSize)
                     << '\n'
                     << "initramfs_end=" << hexU64(layout.initramfsEnd)
                     << '\n';
        }
        writeText(infoOut, infoText.str());

        info("完成：" + dtbOut.string() + "（" + std::to_string(dtbSize) +
             " 字节，加载地址 " + hexU64(layout.fdtAddr) + "）");
        info("ISA：" + isa + "；timebase=" +
             std::to_string(layout.timebaseFreq) + "Hz；mmu-type=" +
             (mmuType.empty() ? "none" : mmuType));
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "错误：" << error.what() << '\n';
        return 2;
    }
}
