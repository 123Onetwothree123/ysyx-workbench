#include "SDRAMMemTest.hpp"
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
    // 想快测就把上限调小（0 表示不限、测到安全上界为止）
    constexpr std::size_t LengthLimit{0x2000U};

    const auto DataEnd{reinterpret_cast<std::uintptr_t>(&_end)};
    const auto StackTop{reinterpret_cast<std::uintptr_t>(&_stack_pointer)};

    std::uintptr_t begin{};
    std::uintptr_t end{};
    if (DataEnd >= SDRAMBase && DataEnd < SDRAMEnd)
    {
        // 程序就在 SDRAM 里跑：从静态数据之上（4KiB 对齐）测到栈之下，避开自身
        begin = (DataEnd + 0xfffU) & ~std::uintptr_t{0xfffU};
        end = StackTop - StackReserve;
    }
    else
    {
        // 程序在别处（如 PSRAM）：整块 SDRAM 都能测
        begin = SDRAMBase;
        end = SDRAMEnd;
    }

    auto length{static_cast<std::size_t>(end - begin)};
    if (LengthLimit != 0 && length > LengthLimit)
    {
        length = LengthLimit;
    }

    printf("SDRAM测试区间: [0x%08x, 0x%08x) 长度=0x%x\n",
           static_cast<unsigned>(begin),
           static_cast<unsigned>(begin + length),
           static_cast<unsigned>(length));

    const auto result{sdram_mem_test(begin, length)};
    if (!result)
    {
        printf("SDRAM测试失败了 0x%08x\n", static_cast<unsigned>(result.error()));
        halt(1);
    }
    printf("SDRAM测试通过了\n");
    return 0;
}
