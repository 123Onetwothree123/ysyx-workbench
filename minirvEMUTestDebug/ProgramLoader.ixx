export module minirvemu.ProgramLoader;
import std;
using namespace std;

export class ProgramLoader
{
public:
    ProgramLoader() = default;
    ~ProgramLoader() = default;
    static std::optional<size_t> GetFileSize(const std::filesystem::path &filePath);
    static std::optional<std::vector<uint8_t>> LoadBinary(const std::filesystem::path &filePath);
};
