#ifndef TRACE_INITIALIZER_HPP
#define TRACE_INITIALIZER_HPP
#include <expected>
#include <filesystem>
#include <optional>
#include <string>

class CliOptions;

class TraceInitializer final
{
public:
    TraceInitializer() = delete;
    [[nodiscard]] static std::expected<void, std::string> InitFromCli(const CliOptions &Options);

private:
    [[nodiscard]] static std::optional<std::filesystem::path> InferElfPath(const std::filesystem::path &ImageFile);
};

#endif
