module;
#include <elf.h>
module npc.ImageLoader;
import npc.Output;
import npc.config;
import npc.SimulationContext;

// CACHE_PADDING 仅 direct-NPC 生效，ResetPC 同步加 padding。
#ifdef VRISCV32E_NPC
constexpr std::size_t kCachePadding{npc::CachePadding};
// 镜像容量须与 direct-NPC 的 AXI RAM 窗口一致。
constexpr std::uintmax_t kImageCapacity{npc::MSize};
#else
constexpr std::size_t kCachePadding{0};
constexpr std::uintmax_t kImageCapacity{npc::SocMSize}; // ysyxSoC MROM window
#endif
namespace
{
// RV32 不允许把 64 位地址截断。
constexpr npc::XWord kImageBase{npc::MBase};

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

// PT_LOAD 段展开为从 MBase 开始的连续镜像，使用 p_paddr。
template <typename Ehdr, typename Phdr>
std::expected<std::size_t, std::string> LoadELFImage(
    const std::filesystem::path &Path,
    std::span<const std::uint8_t> File,
    std::uintmax_t FileSize,
    SimulationContext &Context)
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
        return std::unexpected{std::format("ELF 目标架构不是 RISC-V：e_machine={}", static_cast<unsigned>(header.e_machine))};
    }
    if (header.e_type != ET_EXEC)
    {
        return std::unexpected{"ELF 镜像必须是 ET_EXEC（固定地址可执行文件）"};
    }
    if (header.e_phentsize != sizeof(Phdr) || header.e_phnum == 0)
    {
        return std::unexpected{std::format("ELF 程序头格式不匹配：e_phentsize={}，期望 {}", static_cast<unsigned>(header.e_phentsize), sizeof(Phdr))};
    }
    if (header.e_phoff > FileSize ||
        header.e_phnum > (FileSize - header.e_phoff) / sizeof(Phdr))
    {
        return std::unexpected{"ELF 程序头表超出文件范围"};
    }
    npc::XWord imageEnd{kImageBase};
    const auto hasLoadSegment{[&](const Phdr &Segment) {
        if (Segment.p_type != PT_LOAD || Segment.p_memsz == 0)
        {
            return true;
        }
        if (Segment.p_filesz > Segment.p_memsz)
        {
            return false;
        }
        // RV32 收到高位 ELF64 段必须报错，不能截断。
        if (!npc::FitsXWord(Segment.p_paddr) ||
            !npc::FitsXWord(Segment.p_memsz))
        {
            return false;
        }
        const auto paddr{static_cast<npc::XWord>(Segment.p_paddr)};
        const auto memsz{static_cast<npc::XWord>(Segment.p_memsz)};
        if (paddr < kImageBase ||
            paddr > std::numeric_limits<npc::XWord>::max() - memsz)
        {
            return false;
        }
        imageEnd = std::max(imageEnd, paddr + memsz);
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
            return std::unexpected{std::format("ELF 程序头 {} 非法（p_filesz/p_memsz/p_paddr 越界）", Index)};
        }
    }
    if (imageEnd - kImageBase + kCachePadding > kImageCapacity)
    {
        return std::unexpected{std::format("ELF 镜像末端 0x{:x} 超出仿真内存窗口 [{:#x}, {:#x})",
            imageEnd,
            kImageBase,
            kImageBase + kImageCapacity)};
    }
    std::vector<std::uint8_t> image(
        static_cast<std::size_t>(imageEnd - kImageBase + kCachePadding), 0);
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
            return std::unexpected{std::format("ELF 段 {} 的文件范围超出镜像大小", Index)};
        }
        // 镜像从 padding 之后开始，ResetPC 也含 padding。
        const auto destination{static_cast<std::size_t>(
            static_cast<npc::XWord>(segment.p_paddr) - kImageBase +
            kCachePadding)};
        std::memcpy(image.data() + destination,
                    File.data() + segment.p_offset,
                    static_cast<std::size_t>(segment.p_filesz));
    }
    // 入口须等于 RTL 的 ResetPC。
    if (!npc::FitsXWord(header.e_entry) ||
        static_cast<npc::XWord>(header.e_entry) !=
            static_cast<npc::XWord>(npc::ResetPC))
    {
        return std::unexpected{std::format("ELF 入口 0x{:x} 与仿真复位 PC 0x{:x} 不一致", static_cast<std::uint64_t>(header.e_entry), static_cast<std::uint64_t>(npc::ResetPC))};
    }
    Context.Out().Info("ELF 镜像加载完成: {}，{} 个程序头，镜像 {} 字节",
                       Path.string(),
                       static_cast<unsigned>(header.e_phnum),
                       image.size());
    Context.Flash() = std::move(image);
    Context.Mrom() = Context.Flash();
    return Context.Flash().size();
}
}

std::expected<std::size_t, std::string> ImageLoader::LoadFromCLI(
    const CLIOptions &Options, SimulationContext &Context)
{
    auto &flash{Context.Flash()};
    auto &mrom{Context.Mrom()};
    auto &out{Context.Out()};
    const auto &ImageFile{Options.GetImageFile()};
    if (ImageFile)
    {
        out.Info("现在指定了镜像文件");
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
            return std::unexpected(std::format("镜像过大：{} 字节 + {} 字节填充，但当前仿真内存只有 {} 字节", FileSize, kCachePadding, kImageCapacity));
        }
        // 按 ELF 魔数选择加载路径，保留裸 .bin。
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
                    return LoadELFImage<Elf64_Ehdr, Elf64_Phdr>(
                        path, *file, FileSize, Context);
                }
                if (ident[EI_CLASS] == ELFCLASS32)
                {
                    return LoadELFImage<Elf32_Ehdr, Elf32_Phdr>(
                        path, *file, FileSize, Context);
                }
                return std::unexpected{std::format("不支持的 ELF class：{}", static_cast<unsigned>(ident[EI_CLASS]))};
            }
        }
        const auto allocationSize{static_cast<std::size_t>(FileSize + padding)};
        std::ifstream ifs(path, std::ios::binary);
        if (!ifs)
        {
            return std::unexpected{std::format("文件打不开{0}", path.string())};
        }
        flash.resize(allocationSize); // 前 kCachePadding 字节为零填充
        ifs.read(reinterpret_cast<char *>(flash.data() + kCachePadding),
                 static_cast<std::streamsize>(FileSize));
        if (!ifs)
        {
            return std::unexpected{std::format("读取文件失败{0}", path.string())};
        }
        mrom = flash;
        out.Info("文件加载了: {0}, size = {1} bytes, padding = {2} bytes", path.string(), FileSize, kCachePadding);
        return static_cast<std::size_t>(FileSize);
    }
    return std::unexpected("没有指定镜像文件");
}
