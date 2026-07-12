#include "SDRAMMemTest.hpp"
#include <stdio.h>
#include <am.h>
int main(const char *)
{
    const auto begin{reinterpret_cast<std::uintptr_t>(0xa0000000U)};
    const auto length4KiB{static_cast<std::size_t>(0x1000)};
    const auto result4KiB{sdram_mem_test(begin, length4KiB)};
    const auto length{static_cast<std::size_t>(0x2000000)};
    const auto result{sdram_mem_test(begin, length)};
    if (!result4KiB)
    {
        printf("4KiB测试失败了 0x%08x\n", result4KiB.error());
        halt(1);
    }
    if (!result)
    {
        printf("测试失败了 0x%08x\n", result.error());
        halt(1);
    }
    printf("测试通过了\n");
    return 0;
}
