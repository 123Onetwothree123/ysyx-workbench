module;
#include <cstdio>
module minirvemu.ProgramLoader;

std::optional<std::size_t> ProgramLoader::GetFileSize(const std::filesystem::path &filePath)
{
    if (std::filesystem::exists(filePath))
        return static_cast<std::size_t>(std::filesystem::file_size(filePath));
    return std::nullopt;
}

std::optional<std::vector<std::uint8_t>> ProgramLoader::LoadBinary(const std::filesystem::path &filePath)
{
    if (!std::filesystem::exists(filePath) || !std::filesystem::is_regular_file(filePath))
    {
        std::println(stderr, "Error: File does not exist or is not a regular file: {}", filePath.string());
        return std::nullopt;
    }
    std::uintmax_t size{std::filesystem::file_size(filePath)};
    if (size == 0)
    {
        std::println(stderr, "Warning: File is empty: {}", filePath.string());
        return std::vector<std::uint8_t>{};
    }
    if (std::ifstream file{filePath, std::ios::binary}; file.is_open())
    {
        std::vector<std::uint8_t> buffer(size);
        if (file.read(reinterpret_cast<char *>(buffer.data()), static_cast<std::streamsize>(size)))
            return buffer;
        std::println(stderr, "Error: Failed to read data from {}", filePath.string());
    }
    else
    {
        std::println(stderr, "Error: Could not open file {}", filePath.string());
    }
    return std::nullopt;
}
