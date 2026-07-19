module npc.CLIOptions;
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
        else if (arg == "--diff" || arg == "-d")
        {
            if (i + 1 >= argc)
            {
                return std::unexpected("--diff 需要跟一个REF .so文件路径");
            }
            options.DiffFile = std::filesystem::path{argv[++i]};
        }
        else if (arg.starts_with("--diff="))
        {
            options.DiffFile = std::filesystem::path{std::string{arg.substr(7)}};
        }
        else if (arg == "--result-dir" || arg == "-r")
        {
            if (i + 1 >= argc)
            {
                return std::unexpected("--result-dir 需要跟一个目录路径");
            }
            options.ResultDir = std::filesystem::path{argv[++i]};
        }
        else if (arg.starts_with("--result-dir="))
        {
            options.ResultDir = std::filesystem::path{std::string{arg.substr(13)}};
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
const std::optional<std::filesystem::path> &CLIOptions::GetDiffFile() const noexcept
{
    return DiffFile;
}
const std::optional<std::filesystem::path> &CLIOptions::GetResultDir() const noexcept
{
    return ResultDir;
}
