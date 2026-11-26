module;
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <span>

export module ysyx_26030103_Platform;

export namespace ysyx_26030103
{
enum class LoadPolicy
{
    ZeroFill,
    RequireFull,
};

inline std::uint32_t LoadLittleEndian32(std::span<const std::uint8_t> memory,
                                        std::size_t offset,
                                        LoadPolicy policy)
{
    if (offset >= memory.size())
    {
        return 0;
    }
    const auto available{memory.size() - offset};
    if (policy == LoadPolicy::RequireFull &&
        available < sizeof(std::uint32_t))
    {
        return 0;
    }
    std::uint32_t value{0};
    std::memcpy(&value,
                memory.data() + offset,
                std::min<std::size_t>(sizeof(std::uint32_t), available));
    return value;
}

// 先比较padding，避免减法回绕
inline bool ImageFitsWindow(std::uintmax_t imageBytes,
                            std::uintmax_t paddingBytes,
                            std::uintmax_t windowBytes)
{
    return paddingBytes <= windowBytes &&
           imageBytes <= windowBytes - paddingBytes;
}
}
