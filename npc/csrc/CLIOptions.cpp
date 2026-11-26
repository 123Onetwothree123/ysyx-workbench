module npc.CLIOptions;
namespace
{
enum class OptionAction
{
    ELFFile,
    DiffFile,
    ResultDir,
    RestoreFile,
    VGACheck,
    Batch,
    Help,
};
struct OptionDefinition
{
    std::string_view ShortName;
    std::string_view LongName;
    std::string_view MetaVariable;
    std::string_view HelpText;
    bool HasValue;
    OptionAction action;
    std::string_view MissingValueMessage;
};
// 解析、用法文本和缺值报错都从这张表派生，新增选项只改这里。
constexpr OptionDefinition OptionTable[]{
    {"e", "elf", "FILE", "加载 ELF 符号", true, OptionAction::ELFFile,
     "--elf 需要跟一个ELF文件路径"},
    {"d", "diff", "FILE", "指定 DiffTest REF .so", true, OptionAction::DiffFile,
     "--diff 需要跟一个REF .so文件路径"},
    {"r", "result-dir", "DIR", "性能结果目录", true, OptionAction::ResultDir,
     "--result-dir 需要跟一个目录路径"},
    {"", "restore", "FILE", "恢复 Verilator 检查点", true, OptionAction::RestoreFile,
     "--restore 需要跟一个 Verilator 状态文件路径"},
    {"", "vga-check", "", "开启 VGA 时序检查", false, OptionAction::VGACheck, ""},
    {"b", "batch", "", "批处理模式", false, OptionAction::Batch, ""},
    {"h", "help", "", "显示帮助", false, OptionAction::Help, ""},
};
// 选项说明从第 27 列（0 基 26）开始，保持既有 --help 版式。
constexpr std::size_t HelpColumn{26};
bool MatchesShort(const OptionDefinition &definition, std::string_view arg) noexcept
{
    return !definition.ShortName.empty() && arg.size() == definition.ShortName.size() + 1 &&
           arg[0] == '-' && arg.substr(1) == definition.ShortName;
}
bool MatchesLong(const OptionDefinition &definition, std::string_view arg) noexcept
{
    return arg.size() == definition.LongName.size() + 2 && arg.starts_with("--") &&
           arg.substr(2) == definition.LongName;
}
const OptionDefinition *FindOption(std::string_view arg) noexcept
{
    for (const auto &definition : OptionTable)
    {
        if (MatchesShort(definition, arg) || MatchesLong(definition, arg))
        {
            return &definition;
        }
    }
    return nullptr;
}
const OptionDefinition *FindLongName(std::string_view name) noexcept
{
    for (const auto &definition : OptionTable)
    {
        if (name == definition.LongName)
        {
            return &definition;
        }
    }
    return nullptr;
}
}
std::expected<CLIOptions, std::string> CLIOptions::Parse(int argc, char const *argv[])
{
    CLIOptions options;
    // 旧行为是帮助优先于任何解析错误，因此先按表扫描一遍帮助选项。
    for (auto i{1}; i < argc; ++i)
    {
        const auto *definition{FindOption(argv[i])};
        if (definition != nullptr && definition->action == OptionAction::Help)
        {
            options.HelpRequested = true;
            return options;
        }
    }
    const auto AssignValue = [&options](const OptionDefinition &definition,
                                        std::string_view value) -> std::optional<std::string> {
        const std::filesystem::path path{std::string{value}};
        switch (definition.action)
        {
        case OptionAction::ELFFile: options.ELFFile = path; break;
        case OptionAction::DiffFile: options.DiffFile = path; break;
        case OptionAction::ResultDir:
            if (path.empty())
            {
                return std::string{"--result-dir 不能是空路径"};
            }
            options.ResultDir = path;
            break;
        case OptionAction::RestoreFile: options.RestoreFile = path; break;
        default: break;
        }
        return std::nullopt;
    };
    const auto AssignFlag = [&options](OptionAction action) {
        switch (action)
        {
        case OptionAction::VGACheck: options.VGACheckEnabled = true; break;
        case OptionAction::Batch: options.BatchMode = true; break;
        default: break;
        }
    };
    std::optional<std::filesystem::path> image_file;
    for (auto i{1}; i < argc; ++i)
    {
        const auto arg{std::string_view{argv[i]}};
        if (const auto *definition{FindOption(arg)}; definition != nullptr)
        {
            if (definition->HasValue)
            {
                if (i + 1 >= argc)
                {
                    return std::unexpected{std::string{definition->MissingValueMessage}};
                }
                if (auto error{AssignValue(*definition, argv[++i])})
                {
                    return std::unexpected{std::move(*error)};
                }
            }
            else
            {
                AssignFlag(definition->action);
            }
            continue;
        }
        if (arg.starts_with("--"))
        {
            if (const auto equal{arg.find('=')}; equal != std::string_view::npos)
            {
                if (const auto *definition{FindLongName(arg.substr(2, equal - 2))};
                    definition != nullptr && definition->HasValue)
                {
                    if (auto error{AssignValue(*definition, arg.substr(equal + 1))})
                    {
                        return std::unexpected{std::move(*error)};
                    }
                    continue;
                }
            }
        }
        if (arg.starts_with("-"))
        {
            return std::unexpected{std::format("未知参数: {0}", arg)};
        }
        if (!image_file)
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
std::string CLIOptions::Usage(std::string_view program)
{
    std::string text{std::format("用法: {} [选项] [镜像文件]", program)};
    for (const auto &definition : OptionTable)
    {
        std::string option;
        if (definition.ShortName.empty())
        {
            option = std::format("      --{}", definition.LongName);
        }
        else
        {
            option = std::format("  -{}, --{}", definition.ShortName, definition.LongName);
        }
        if (definition.HasValue)
        {
            option = std::format("{} {}", option, definition.MetaVariable);
        }
        text += '\n';
        text += std::format("{:<{}}{}", option, HelpColumn, definition.HelpText);
    }
    return text;
}
bool CLIOptions::GetHelpRequested() const noexcept
{
    return HelpRequested;
}
const std::optional<std::filesystem::path> &CLIOptions::GetImageFile() const noexcept
{
    return ImageFile;
}
const std::optional<std::filesystem::path> &CLIOptions::GetELFFile() const noexcept
{
    return ELFFile;
}
const std::optional<std::filesystem::path> &CLIOptions::GetDiffFile() const noexcept
{
    return DiffFile;
}
const std::optional<std::filesystem::path> &CLIOptions::GetResultDir() const noexcept
{
    return ResultDir;
}
const std::optional<std::filesystem::path> &CLIOptions::GetRestoreFile() const noexcept
{
    return RestoreFile;
}
bool CLIOptions::GetVGACheck() const noexcept
{
    return VGACheckEnabled;
}
bool CLIOptions::GetBatchMode() const noexcept
{
    return BatchMode;
}
