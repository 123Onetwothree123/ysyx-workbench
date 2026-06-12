#ifndef IMAGE_LOADER_HPP
#define IMAGE_LOADER_HPP
#include <cstddef>
#include <expected>
#include <string>
class CLIOptions;
class ImageLoader final
{
public:
    ImageLoader() = delete;
    // SoC模式下镜像通过flash_read等DPI-C回调加载，不再需要Memory
    [[nodiscard]] static std::expected<std::size_t, std::string> LoadFromCLI(const CLIOptions &Options);
};
#endif
