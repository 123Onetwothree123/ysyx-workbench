module;
#include <elf.h>
module npc.ImageLoader;
import npc.ysyxSoC;

#ifndef CONFIG_CACHE_PADDING
#define CONFIG_CACHE_PADDING 0
#endif
#ifndef CONFIG_MBASE
#define CONFIG_MBASE 0x30000000U
#endif
#ifndef CONFIG_MSIZE
#define CONFIG_MSIZE 0x10000000U
#endif
#ifndef CONFIG_RESET_PC
#define CONFIG_RESET_PC CONFIG_MBASE
#endif

// CACHE_PADDING 只影响 npc 独立仿真路径（VRISCV32E_NPC）：
// 镜像内容在内存中整体后移 N 字节，复位 PC 由 Makefile 同步加 N（NPC_RESET_PC）。
// SoC 路径的启动地址由 RTL 固定，不能在 csrc 侧移动镜像，否则启动会执行到填充字节。
#ifdef VRISCV32E_NPC
constexpr std::size_t kCachePadding{CONFIG_CACHE_PADDING};
// 镜像容量必须与 direct-NPC 的 AXI RAM/加载器窗口一致。
constexpr std::uintmax_t kImageCapacity{CONFIG_MSIZE};
#else
constexpr std::size_t kCachePadding{0};
constexpr std::uintmax_t kImageCapacity{0x10000000U}; // ysyxSoC MROM window
#endif

namespace
{
constexpr std::uint64_t kImageBase{CONFIG_MBASE};

std::expected<std::vector<std::uint8_t>, std::string> ReadWholeFile(
    const std::filesystem::path &Path,
    std::uintmax_t FileSize)
{
    if (FileSize > std::numeric_limits<std::size_t>::max())
    {
        return std::unexpected{"ELF 文件过大，无法放入进程地址空间"};
    }
    std::ifstream ifs(Path, std::ios::binary);
    if (!ifs)
    {
        return std::unexpected{std::format("文件打不开{}", Path.string())};
    }
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(FileSize));
    if (!bytes.empty())
    {
        ifs.read(reinterpret_cast<char *>(bytes.data()),
                 static_cast<std::streamsize>(bytes.size()));
    }
    if (!ifs)
    {
        return std::unexpected{std::format("读取文件失败{}", Path.string())};
    }
    return bytes;
}

// 将 ELF32/ELF64 的 PT_LOAD 段展开成从 CONFIG_MBASE 开始的连续仿真镜像。
// 物理地址使用 p_paddr；裸机 RISC-V ELF 通常 p_vaddr == p_paddr。
template <typename Ehdr, typename Phdr>
std::expected<std::size_t, std::string> LoadElfImage(
    const std::filesystem::path &Path,
    std::span<const std::uint8_t> File,
    std::uintmax_t FileSize)
{
    if (File.size() < sizeof(Ehdr))
    {
        return std::unexpected{"ELF 文件头不完整"};
    }
    Ehdr header{};
    std::memcpy(&header, File.data(), sizeof(header));
    if (header.e_ident[EI_MAG0] != ELFMAG0 ||
        header.e_ident[EI_MAG1] != ELFMAG1 ||
        header.e_ident[EI_MAG2] != ELFMAG2 ||
        header.e_ident[EI_MAG3] != ELFMAG3)
    {
        return std::unexpected{"镜像不是 ELF 文件，也不是裸二进制"};
    }
    if (header.e_ident[EI_DATA] != ELFDATA2LSB)
    {
        return std::unexpected{"仅支持小端 ELF 镜像"};
    }
    if (header.e_machine != EM_RISCV)
    {
        return std::unexpected{std::format(
            "ELF 目标架构不是 RISC-V：e_machine={}",
            static_cast<unsigned>(header.e_machine))};
    }
    if (header.e_type != ET_EXEC)
    {
        return std::unexpected{"ELF 镜像必须是 ET_EXEC（固定地址可执行文件）"};
    }
    if (header.e_phentsize != sizeof(Phdr) || header.e_phnum == 0)
    {
        return std::unexpected{std::format(
            "ELF 程序头格式不匹配：e_phentsize={}，期望 {}",
            static_cast<unsigned>(header.e_phentsize),
            sizeof(Phdr))};
    }
    if (header.e_phoff > FileSize ||
        header.e_phnum > (FileSize - header.e_phoff) / sizeof(Phdr))
    {
        return std::unexpected{"ELF 程序头表超出文件范围"};
    }

    std::uint64_t imageEnd{kImageBase};
    const auto hasLoadSegment{[&](const Phdr &Segment) {
        if (Segment.p_type != PT_LOAD || Segment.p_memsz == 0)
        {
            return true;
        }
        if (Segment.p_filesz > Segment.p_memsz)
        {
            return false;
        }
        if (Segment.p_paddr < kImageBase)
        {
            return false;
        }
        if (Segment.p_paddr >
            std::numeric_limits<std::uint64_t>::max() - Segment.p_memsz)
        {
            return false;
        }
        imageEnd = std::max(imageEnd,
                            static_cast<std::uint64_t>(Segment.p_paddr) +
                                static_cast<std::uint64_t>(Segment.p_memsz));
        return true;
    }};
    for (std::size_t Index{0}; Index < header.e_phnum; ++Index)
    {
        Phdr segment{};
        std::memcpy(&segment,
                    File.data() + header.e_phoff + Index * sizeof(Phdr),
                    sizeof(segment));
        if (!hasLoadSegment(segment))
        {
            return std::unexpected{std::format(
                "ELF 程序头 {} 非法（p_filesz/p_memsz/p_paddr 越界）", Index)};
        }
    }

    if (imageEnd - kImageBase > kImageCapacity)
    {
        return std::unexpected{std::format(
            "ELF 镜像末端 0x{:x} 超出仿真内存窗口 [{:#x}, {:#x})",
            imageEnd,
            kImageBase,
            kImageBase + kImageCapacity)};
    }

    std::vector<std::uint8_t> image(
        static_cast<std::size_t>(imageEnd - kImageBase), 0);
    for (std::size_t Index{0}; Index < header.e_phnum; ++Index)
    {
        Phdr segment{};
        std::memcpy(&segment,
                    File.data() + header.e_phoff + Index * sizeof(Phdr),
                    sizeof(segment));
        if (segment.p_type != PT_LOAD || segment.p_filesz == 0)
        {
            continue;
        }
        if (segment.p_offset > FileSize ||
            segment.p_filesz > FileSize - segment.p_offset)
        {
            return std::unexpected{std::format(
                "ELF 段 {} 的文件范围超出镜像大小", Index)};
        }
        const auto destination{static_cast<std::size_t>(
            static_cast<std::uint64_t>(segment.p_paddr) - kImageBase)};
        std::memcpy(image.data() + destination,
                    File.data() + segment.p_offset,
                    static_cast<std::size_t>(segment.p_filesz));
    }

    // 复位 PC 是 RTL 固定值。显式检查入口，避免把入口地址不同的
    // ELF 当成裸二进制加载后从错误位置开始执行。
    if (static_cast<std::uint64_t>(header.e_entry) !=
        static_cast<std::uint64_t>(CONFIG_RESET_PC))
    {
        return std::unexpected{std::format(
            "ELF 入口 0x{:x} 与仿真复位 PC 0x{:x} 不一致",
            static_cast<std::uint64_t>(header.e_entry),
            static_cast<std::uint64_t>(CONFIG_RESET_PC))};
    }

    std::println("ELF 镜像加载完成: {}，{} 个程序头，镜像 {} 字节",
                 Path.string(),
                 static_cast<unsigned>(header.e_phnum),
                 image.size());
    FlashMemory = std::move(image);
    mrom = FlashMemory;
    return static_cast<std::size_t>(imageEnd - kImageBase);
}
} // namespace

std::expected<std::size_t, std::string> ImageLoader::LoadFromCLI(const CLIOptions &Options)
{
    const auto &ImageFile{Options.GetImageFile()};
    if (ImageFile)
    {
        std::println("现在指定了镜像文件");
        const auto &path{*ImageFile};
        std::error_code error;
        const auto fileStatus{std::filesystem::status(path, error)};
        if (error)
        {
            return std::unexpected(std::format("无法查询镜像文件状态{}: {}", path.string(), error.message()));
        }
        if (!std::filesystem::exists(fileStatus))
        {
            return std::unexpected(std::format("他妈的文件路径没找到文件{0}", path.string()));
        }
        if (!std::filesystem::is_regular_file(fileStatus))
        {
            return std::unexpected(std::format("fuck，不是普通文件{0}", path.string()));
        }
        const auto FileSize{std::filesystem::file_size(path, error)};
        if (error)
        {
            return std::unexpected(std::format("无法获取镜像文件大小{}: {}", path.string(), error.message()));
        }
        const auto padding{static_cast<std::uintmax_t>(kCachePadding)};
        if (padding > kImageCapacity || FileSize > kImageCapacity - padding)
        {
            return std::unexpected(std::format(
                "镜像过大：{} 字节 + {} 字节填充，但当前仿真内存只有 {} 字节",
                FileSize,
                kCachePadding,
                kImageCapacity));
        }

        // 先探测 ELF 魔数：显式传入 .elf 时按程序头加载，保留原有
        // 裸 .bin 路径。ELF32 与 ELF64 都支持，由 e_ident[EI_CLASS] 选择。
        {
            std::ifstream probe(path, std::ios::binary);
            std::array<unsigned char, EI_NIDENT> ident{};
            probe.read(reinterpret_cast<char *>(ident.data()), ident.size());
            if (probe && ident[EI_MAG0] == ELFMAG0 &&
                ident[EI_MAG1] == ELFMAG1 && ident[EI_MAG2] == ELFMAG2 &&
                ident[EI_MAG3] == ELFMAG3)
            {
                auto file{ReadWholeFile(path, FileSize)};
                if (!file)
                {
                    return std::unexpected{file.error()};
                }
                if (ident[EI_CLASS] == ELFCLASS64)
                {
                    return LoadElfImage<Elf64_Ehdr, Elf64_Phdr>(
                        path, *file, FileSize);
                }
                if (ident[EI_CLASS] == ELFCLASS32)
                {
                    return LoadElfImage<Elf32_Ehdr, Elf32_Phdr>(
                        path, *file, FileSize);
                }
                return std::unexpected{std::format(
                    "不支持的 ELF class：{}",
                    static_cast<unsigned>(ident[EI_CLASS]))};
            }
        }

        const auto allocationSize{static_cast<std::size_t>(FileSize + padding)};
        std::ifstream ifs(path, std::ios::binary); // 二进制模式打开文件，避免 Windows 的 \r\n 自动转换
        if (!ifs)
        {
            return std::unexpected{std::format("文件打不开{0}", path.string())};
        }
        FlashMemory.resize(allocationSize); // resize 零初始化，前 kCachePadding 字节即填充
        ifs.read(reinterpret_cast<char *>(FlashMemory.data() + kCachePadding),
                 static_cast<std::streamsize>(FileSize));
        if (!ifs)
        {
            return std::unexpected{std::format("读取文件失败{0}", path.string())};
        }
        mrom = FlashMemory;
        std::println("文件加载了: {0}, size = {1} bytes, padding = {2} bytes", path.string(), FileSize, kCachePadding);
        return static_cast<std::size_t>(FileSize);
    }
    return std::unexpected("没有指定镜像文件");
}
