#ifndef CLI_OPTIONS_HPP
#define CLI_OPTIONS_HPP
#include <expected>
#include <filesystem>
#include <optional>
#include <string>
class CliOptions final
{
public:
    [[nodiscard]] static std::expected<CliOptions, std::string> Parse(int argc, char const *argv[]);
    [[nodiscard]] const std::optional<std::filesystem::path> &GetImageFile() const noexcept;
    [[nodiscard]] const std::optional<std::filesystem::path> &GetElfFile() const noexcept;
    [[nodiscard]] const std::optional<std::filesystem::path> &GetDiffRefSo() const noexcept;
    [[nodiscard]] bool IsFtraceEnabled() const noexcept;
    [[nodiscard]] bool IsBatchMode() const noexcept;
private:
    std::optional<std::filesystem::path> ImageFile{};
    std::optional<std::filesystem::path> ElfFile{};
    std::optional<std::filesystem::path> DiffRefSo{};
    bool FtraceEnabled{true};
    bool BatchMode{false};
};
#endif
