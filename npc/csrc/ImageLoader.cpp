#include "ImageLoader.hpp"
#include "CliOptions.hpp"
#include "memory.hpp"
#include <print>

std::expected<std::size_t, std::string> ImageLoader::LoadFromCli(const CliOptions &Options)
{
    const auto &image_file{Options.GetImageFile()};
    if (image_file)
    {
        const auto result{load_file(*image_file)};
        if (!result)
        {
            return std::unexpected{result.error()};
        }
        std::println("文件加载了: {}, size = {} bytes", image_file->string(), result.value());
        return result.value();
    }
    const auto image_size{load_builtin_image()};
    std::println("没有指定镜像，使用内置镜像，size = {} bytes", image_size);
    return image_size;
}
