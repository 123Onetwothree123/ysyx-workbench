module npc.ImageLoader;
import npc.ysyxSoC;

#ifndef CONFIG_CACHE_PADDING
#define CONFIG_CACHE_PADDING 0
#endif

// CACHE_PADDING 只影响 npc 独立仿真路径（VRISCV32E_NPC）：
// 镜像内容在内存中整体后移 N 字节，复位 PC 由 Makefile 同步加 N（NPC_RESET_PC）。
// SoC 路径的启动地址由 RTL 固定，不能在 csrc 侧移动镜像，否则启动会执行到填充字节。
#ifdef VRISCV32E_NPC
constexpr std::size_t kCachePadding{CONFIG_CACHE_PADDING};
#else
constexpr std::size_t kCachePadding{0};
#endif

std::expected<std::size_t, std::string> ImageLoader::LoadFromCLI(const CLIOptions &Options)
{
    const auto &ImageFile{Options.GetImageFile()};
    if (ImageFile)
    {
        std::println("现在指定了镜像文件");
        const auto &path{*ImageFile};
        if (!std::filesystem::exists(path))
        {
            return std::unexpected(std::format("他妈的文件路径没找到文件{0}", path.string()));
        }
        if (!std::filesystem::is_regular_file(path))
        {
            return std::unexpected(std::format("fuck，不是普通文件{0}", path.string()));
        }
        auto FileSize{std::filesystem::file_size(path)};
        std::ifstream ifs(path, std::ios::binary); // review笔记：二进制模式打开文件，然后发现这样写还可以防止Windows的\r\n被自动转换
        if (!ifs)
        {
            return std::unexpected(std::format("文件打不开{0}", path.string()));
        }
        FlashMemory.resize(FileSize + kCachePadding); // resize 零初始化，前 kCachePadding 字节即填充
        ifs.read(reinterpret_cast<char *>(FlashMemory.data() + kCachePadding), FileSize);
        if (!ifs)
        {
            return std::unexpected(std::format("读取文件失败{0}", path.string()));
        }
        mrom = FlashMemory;
        std::println("文件加载了: {0}, size = {1} bytes, padding = {2} bytes", path.string(), FileSize, kCachePadding);
        return FileSize;
    }
    return std::unexpected("没有指定镜像文件");
}
