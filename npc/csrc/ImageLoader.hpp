#ifndef IMAGE_LOADER_HPP
#define IMAGE_LOADER_HPP
#include <cstddef>
#include <expected>
#include <string>
class CLIOptions;
class Memory;
class ImageLoader final
{
public:
    ImageLoader() = delete;
    [[nodiscard]] static std::expected<std::size_t, std::string> LoadFromCLI(const CLIOptions &Options, Memory &memory);
};
#endif
