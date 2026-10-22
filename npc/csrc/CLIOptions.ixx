export module npc.CLIOptions;
import std;

export class CLIOptions final
{
public:
    [[nodiscard]] static std::expected<CLIOptions, std::string> Parse(int argc, char const *argv[]);
    [[nodiscard]] const std::optional<std::filesystem::path> &GetImageFile() const noexcept;
    [[nodiscard]] const std::optional<std::filesystem::path> &GetElfFile() const noexcept;
    [[nodiscard]] const std::optional<std::filesystem::path> &GetDiffFile() const noexcept;
    [[nodiscard]] const std::optional<std::filesystem::path> &GetResultDir() const noexcept;
    [[nodiscard]] bool GetVGACheck() const noexcept;
    // 批处理模式：不读 stdin，直接执行 `c` 跑到 halt/$finish。
    // 供 CI/arch-test 等没有交互终端的调用方使用。
    [[nodiscard]] bool GetBatchMode() const noexcept;
private:
    std::optional<std::filesystem::path> ImageFile{};
    std::optional<std::filesystem::path> ElfFile{};
    std::optional<std::filesystem::path> DiffFile{};
    std::optional<std::filesystem::path> ResultDir{};
    bool VGACheckEnabled{false};
    bool BatchMode{false};
};
