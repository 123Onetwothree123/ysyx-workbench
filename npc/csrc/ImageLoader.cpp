#include "ImageLoader.hpp"
#include "CLIOptions.hpp"
#include "Memory/Memory.hpp"
#include <print>
#include <filesystem>
#include <fstream>

std::expected<std::size_t, std::string> ImageLoader::LoadFromCLI(const CLIOptions &Options, Memory &memory)
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
        std::vector<char> buffer(FileSize); // 读取缓冲区
        ifs.read(buffer.data(), static_cast<std::streamsize>(FileSize));
        if (!ifs)
        {
            return std::unexpected(std::format("读文件失败{0}", path.string()));
        }
        // 灌进Memory
        for (std::size_t i = 0; i < FileSize; ++i)
        {
            memory.StoreByte(0x80000000 + i, static_cast<std::uint8_t>(buffer[i]));
        }
        std::println("文件加载了: {0}, size = {1} bytes", path.string(), FileSize);
        return FileSize;
    }
    return std::unexpected("没有指定镜像文件");
}
