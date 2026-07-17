#ifndef PROGRAM_LOADER_H
#define PROGRAM_LOADER_H
#include <vector>
#include <cstdint>
#include <filesystem>
#include <string>
#include <optional>
class ProgramLoader
{
private:
public:
    ProgramLoader()=default;
    ~ProgramLoader()=default;
    //返回包含字节数据
    static std::optional<std::vector<std::uint8_t>> LoadBinary(const std::filesystem::path& filePath);
    static std::optional<std::size_t> GetFileSize(const std::filesystem::path& filePath);
};
#endif