#include "ProgramLoader.h"
#include <fstream>
#include <iostream>
std::optional<size_t> ProgramLoader::GetFileSize(const std::filesystem::path &filePath)
{
    if (std::filesystem::exists(filePath))
    {
        return static_cast<size_t>(std::filesystem::file_size(filePath));
    }
    return std::nullopt;
}
std::optional<std::vector<uint8_t>> ProgramLoader::LoadBinary(const std::filesystem::path &filePath)
{
    // 防止偷袭，先检查一波
    if (!std::filesystem::exists(filePath) || !std::filesystem::is_regular_file(filePath))
    {
        std::cerr << "Error: File does not exist or is not a regular file: " << filePath << std::endl;
        return std::nullopt;
    }
    uintmax_t size = std::filesystem::file_size(filePath);
    if (size == 0)
    {
        std::cerr << "Warning: File is empty: " << filePath << std::endl;
        return std::vector<uint8_t>();
    }
    // 2进制模式打开
    if (std::ifstream file(filePath, std::ios::binary); file.is_open())
    {
        std::vector<uint8_t> buffer(size);
        if (file.read(reinterpret_cast<char *>(buffer.data()), size))
        {
            return buffer;
        }
        else
        {
            std::cerr << "Error: Failed to read data from " << filePath << std::endl;
        }
    }
    else
    {
        std::cerr << "Error: Could not open file " << filePath << std::endl;
    }
    return std::nullopt;
}