#include "CliOptions.hpp"
#include <format>
#include <string_view>
#include <utility>
std::expected<CliOptions, std::string> CliOptions::Parse(int argc, char const *argv[])
{
    CliOptions options;
    std::optional<std::filesystem::path> image_file;
    for (auto i{1}; i < argc; ++i)
    {
        const auto arg{std::string_view{argv[i]}};
        if (arg == "--elf" || arg == "-e")
        {
            if (i + 1 >= argc)
            {
                return std::unexpected{"--elf 需要跟一个ELF文件路径"};
            }
            options.ElfFile = std::filesystem::path{argv[++i]};
        }
        else if (arg.starts_with("--elf="))
        {
            options.ElfFile = std::filesystem::path{std::string{arg.substr(6)}};
        }
        else if (arg == "--ftrace")
        {
            options.FtraceEnabled = true;
        }
        else if (arg == "--no-ftrace")
        {
            options.FtraceEnabled = false;
        }
        else if (arg == "--batch" || arg == "-b")
        {
            options.BatchMode = true;
        }
        else if (arg == "--diff")
        {
            if (i + 1 >= argc)
            {
                return std::unexpected{"--diff 需要跟一个NEMU动态库路径"};
            }
            options.DiffRefSo = std::filesystem::path{argv[++i]};
        }
        else if (arg.starts_with("--diff="))
        {
            options.DiffRefSo = std::filesystem::path{std::string{arg.substr(7)}};
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
            return std::unexpected{std::format("多余的镜像文件参数: {0}", arg)};
        }
    }
    options.ImageFile = std::move(image_file);
    return options;
}
const std::optional<std::filesystem::path> &CliOptions::GetImageFile() const noexcept
{
    return ImageFile;
}
const std::optional<std::filesystem::path> &CliOptions::GetElfFile() const noexcept
{
    return ElfFile;
}
const std::optional<std::filesystem::path> &CliOptions::GetDiffRefSo() const noexcept
{
    return DiffRefSo;
}
bool CliOptions::IsFtraceEnabled() const noexcept
{
    return FtraceEnabled;
}
bool CliOptions::IsBatchMode() const noexcept
{
    return BatchMode;
}
