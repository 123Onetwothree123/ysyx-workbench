export module npc.CLIOptions;
import std;

export class CLIOptions final
{
public:
    [[nodiscard]] static std::expected<CLIOptions, std::string> Parse(int argc, char const *argv[]);
    [[nodiscard]] const std::optional<std::filesystem::path> &GetImageFile() const noexcept;
    [[nodiscard]] const std::optional<std::filesystem::path> &GetElfFile() const noexcept;
    [[nodiscard]] const std::optional<std::filesystem::path> &GetDiffFile() const noexcept;
private:
    std::optional<std::filesystem::path> ImageFile{};
    std::optional<std::filesystem::path> ElfFile{};
    std::optional<std::filesystem::path> DiffFile{};
};
