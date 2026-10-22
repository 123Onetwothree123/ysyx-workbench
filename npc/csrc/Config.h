#pragma once

#include <cstdint>
#include <limits>
#include <type_traits>

/*
 * NPC 运行时唯一的 XLEN 配置入口。
 *
 * 两个宏都来自构建配置层，不允许在 csrc 里另建 32/64 开关：
 *   - CONFIG_XLEN：Kconfig 的 CPU XLEN（32/64）；
 *   - CONFIG_SOC_XLEN：Makefile 已解析 SOC_XLEN_FOLLOW_CPU 后的有效
 *     SoC XLEN（32/64），即 ADDRESSING.md 中的 EffectiveSocXLEN。
 *
 * 直接脱离 npc/Makefile 编译局部回归（例如 csrc/Test/readelf-test）时，
 * 保持历史默认 RV32；这只是一个缺省值，不会成为第二配置源。
 */
#ifndef CONFIG_XLEN
#define CONFIG_XLEN 32
#endif

#ifndef CONFIG_SOC_XLEN
#define CONFIG_SOC_XLEN 32
#endif

namespace npc
{
static_assert(CONFIG_XLEN == 32 || CONFIG_XLEN == 64,
              "CONFIG_XLEN 只允许 32 或 64");
static_assert(CONFIG_SOC_XLEN == 32 || CONFIG_SOC_XLEN == 64,
              "CONFIG_SOC_XLEN 只允许 32 或 64");

// CPU 架构宽度。地址、GPR、PC、CSR 以及调试状态布局统一使用它。
inline constexpr unsigned XLen{CONFIG_XLEN};
// 有效 SoC 总线宽度。它只描述 CPU 侧 SoC 接口，不代表 CPU 架构宽度。
inline constexpr unsigned SocXLen{CONFIG_SOC_XLEN};

// RV32 保持 32 位，RV64 才是 64 位；禁止用宿主机的 size_t 代替。
using XWord = std::conditional_t<XLen == 64, std::uint64_t, std::uint32_t>;
using SocXWord =
    std::conditional_t<SocXLen == 64, std::uint64_t, std::uint32_t>;

// 按 XLEN 对齐的十六进制显示宽度，避免 RV64 地址只打印低 32 位。
inline constexpr int XHexDigits{static_cast<int>(XLen / 4U)};
inline constexpr int SocXHexDigits{static_cast<int>(SocXLen / 4U)};

// 判断一个整数是否能用 CPU 字宽完整表示，用于拒绝静默截断。
template <typename T>
[[nodiscard]] constexpr bool FitsXWord(T Value) noexcept
{
    if constexpr (std::is_signed_v<T>)
    {
        if (Value < 0)
        {
            return false;
        }
    }
    using Unsigned = std::make_unsigned_t<T>;
    return static_cast<Unsigned>(Value) <=
           static_cast<Unsigned>(std::numeric_limits<XWord>::max());
}
} // namespace npc
