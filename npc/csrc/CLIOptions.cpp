#include "CLIOptions.hpp"
#include <format>
#include <string_view>
#include <utility>
std::expected<CLIOptions, std::string> CLIOptions::Parse(int argc, char const *argv[])
{
    CLIOptions options;
    std::optional<std::filesystem::path> image_file;
    for (auto i{1}; i < argc; ++i)
    {
        const auto arg{std::string_view{argv[i]}};
        if (arg == "--elf" || arg == "-e")
        {
            if (i + 1 >= argc)
            {
                return std::unexpected("--elf 需要跟一个ELF文件路径");
            }
            options.ElfFile = std::filesystem::path{argv[++i]};
        }
        else if (arg.starts_with("--elf="))
        {
            options.ElfFile = std::filesystem::path{std::string{arg.substr(6)}};
        }
        else if (arg.starts_with("-"))
        {
            return std::unexpected{std::format("未知参数: {0}", arg)};
        }
        else if (!image_file)
        {
            image_file = std::filesystem::path{std::string{arg}};
        }
        else
        {
            return std::unexpected{std::format("这是多余的镜像文件参数: {0}", arg)};
        }
    }
    options.ImageFile = std::move(image_file);
    return options;
}
const std::optional<std::filesystem::path> &CLIOptions::GetImageFile() const noexcept
{
    return ImageFile;
}
const std::optional<std::filesystem::path> &CLIOptions::GetElfFile() const noexcept
{
    return ElfFile;
}