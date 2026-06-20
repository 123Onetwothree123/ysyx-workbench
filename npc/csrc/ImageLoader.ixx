export module npc.ImageLoader;
import std;
import npc.CLIOptions;

export class ImageLoader final
{
public:
    ImageLoader() = delete;
    [[nodiscard]] static std::expected<std::size_t, std::string> LoadFromCLI(const CLIOptions &Options);
};
