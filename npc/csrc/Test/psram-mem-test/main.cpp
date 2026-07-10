#include "PSRAMMemTest.hpp"
#include <stdio.h>
#include <am.h>
int main(const char *)
{
    const auto begin{reinterpret_cast<std::uintptr_t>(0x80000000U)};
    const auto length{static_cast<std::size_t>(0x4)};
    const auto result{psram_mem_test(begin, length)};
    if (!result)
    {
        printf("失败了 0x%08x\n", result.error());
        halt(1);
    }
    printf("测试通过了");
    return 0;
}
