#include "TraceInitializer.hpp"
#include "CliOptions.hpp"
#include "trace/ftrace.hpp"
#include <print>
std::optional<std::filesystem::path> TraceInitializer::InferElfPath(const std::filesystem::path &ImageFile)
{
    auto candidate{ImageFile};
    candidate.replace_extension(".elf");
    if (candidate != ImageFile && std::filesystem::exists(candidate))
    {
        return candidate;
    }
    return std::nullopt;
}
std::expected<void, std::string> TraceInitializer::InitFromCli(const CliOptions &Options)
{
    auto elf_file{Options.GetElfFile()};
#ifdef CONFIG_FTRACE
    if (!elf_file && Options.IsFtraceEnabled())
    {
        if (Options.GetImageFile())
        {
            elf_file = InferElfPath(*Options.GetImageFile());
        }
    }
#endif
    if (elf_file)
    {
        auto result{InitializeFtrace(*elf_file, Options.IsFtraceEnabled())};
        if (!result)
        {
            return result;
        }
        std::println("ELF加载了: {}, functions = {}", elf_file->string(), GlobalFtrace.FunctionCount());
    }
#ifdef CONFIG_FTRACE
    else if (Options.IsFtraceEnabled() && Options.GetImageFile())
    {
        return std::unexpected{"CONFIG_FTRACE=y 需要 --elf FILE，或者镜像旁边存在同名 .elf"};
    }
#endif
    return {};
}
