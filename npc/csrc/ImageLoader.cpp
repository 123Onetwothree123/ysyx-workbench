#include "ImageLoader.hpp"
#include "CLIOptions.hpp"
#include "ysyxSoC/ysyxSoC.hpp"
#include <print>
#include <filesystem>
#include <fstream>
#include <format>
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
        mrom.resize(FileSize);
        ifs.read(reinterpret_cast<char *>(mrom.data()), FileSize);
        if (!ifs)
        {
            return std::unexpected(std::format("读取文件失败{0}", path.string()));
        }
        std::println("文件加载了: {0}, size = {1} bytes", path.string(), FileSize);
        return FileSize;
    }
    return std::unexpected("没有指定镜像文件");
}
