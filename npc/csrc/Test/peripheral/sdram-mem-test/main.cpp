#include "../common/mem_test.hpp"
#include <cstddef>
#include <cstdint>
#include <stdio.h>
#include <am.h>
// 链接脚本里的符号：静态数据末尾、栈顶
extern "C" char _end;
extern "C" char _stack_pointer;
int main(const char *)
{
    // SDRAM 物理区间（与 SoC 地址映射一致）
    constexpr std::uintptr_t SDRAMBase{0xa0000000U};
    constexpr std::uintptr_t SDRAMSize{0x2000000U}; // 32MiB
    constexpr std::uintptr_t SDRAMEnd{SDRAMBase + SDRAMSize};
    // 给栈留的余量，测试区上界要在栈之下
    constexpr std::uintptr_t StackReserve{0x10000U}; // 64KiB
    // 逐页寻址按 64KiB 步长覆盖全部 512 页；完整位宽往返只做首尾各 8KiB，
    // 避免在仿真里顺序扫完 32MiB。
    constexpr std::uintptr_t PageStride{0x10000U};
    constexpr std::size_t FullTestLength{0x2000U};

    const auto DataEnd{reinterpret_cast<std::uintptr_t>(&_end)};
    const auto StackTop{reinterpret_cast<std::uintptr_t>(&_stack_pointer)};

    std::uintptr_t TestBegin{};
    std::uintptr_t TestEnd{};
    if (DataEnd >= SDRAMBase && DataEnd < SDRAMEnd)
    {
        // 程序就在 SDRAM 里跑：从静态数据之上（4KiB 对齐）测到栈之下，避开自身
        TestBegin = (DataEnd + 0xfffU) & ~std::uintptr_t{0xfffU};
        TestEnd = StackTop - StackReserve;
    }
    else
    {
        // 程序在别处（如 PSRAM）：整块 SDRAM 都能测
        TestBegin = SDRAMBase;
        TestEnd = SDRAMEnd;
    }

    if (TestEnd <= TestBegin)
    {
        printf("SDRAM没有可测区间\n");
        halt(1);
    }

    // 逐页地址/数据往返：遍历全部 512 个页基址，但只测完整落在安全区内
    // 的页，跳过与自身代码/数据/栈重叠的页。
    std::size_t TestedPages{};
    std::size_t SkippedPages{};
    for (std::uintptr_t PageStart{SDRAMBase}; PageStart < SDRAMEnd; PageStart += PageStride)
    {
        const std::uintptr_t PageEnd{PageStart + PageStride};
        if (PageStart < TestBegin || PageEnd > TestEnd)
        {
            ++SkippedPages;
            continue;
        }
        const std::uintptr_t LastWordLocation{PageEnd - sizeof(std::uintptr_t)};
        auto *FirstWord{reinterpret_cast<volatile std::uintptr_t *>(PageStart)};
        auto *LastWord{reinterpret_cast<volatile std::uintptr_t *>(LastWordLocation)};
        *FirstWord = PageStart;
        *LastWord = LastWordLocation;
        if (*FirstWord != PageStart || *LastWord != LastWordLocation)
        {
            printf("SDRAM页测试失败了 0x%08zx\n", static_cast<std::size_t>(PageStart));
            halt(1);
        }
        ++TestedPages;
    }

    printf("SDRAM逐页寻址: 测试 %zu 页，跳过 %zu 页（自身代码/数据/栈）\n",
           TestedPages, SkippedPages);

    const auto SafeLength{static_cast<std::size_t>(TestEnd - TestBegin)};
    const auto CheckLength{SafeLength < FullTestLength * 2 ? SafeLength / 2 : FullTestLength};
    // 首尾各做一次完整位宽往返，覆盖页内连续地址。
    const auto FirstResult{npc_test::mem_test(TestBegin, CheckLength)};
    if (!FirstResult)
    {
        printf("SDRAM首段测试失败了 0x%08zx\n", static_cast<std::size_t>(FirstResult.error()));
        halt(1);
    }
    const auto LastResult{npc_test::mem_test(TestEnd - CheckLength, CheckLength)};
    if (!LastResult)
    {
        printf("SDRAM末段测试失败了 0x%08zx\n", static_cast<std::size_t>(LastResult.error()));
        halt(1);
    }
    printf("SDRAM测试通过了\n");
    return 0;
}
