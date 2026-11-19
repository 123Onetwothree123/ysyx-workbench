#include "../common/mem_test.hpp"
#include <stdio.h>
#include <am.h>
int main(const char *)
{
    const std::uintptr_t begin{0x80010000U};
    const auto length{static_cast<std::size_t>(0x3F0000)};
    const auto result{npc_test::mem_test(begin, length)};
    if (!result)
    {
        printf("失败了 0x%08zx\n", static_cast<std::size_t>(result.error()));
        halt(1);
    }
    printf("测试通过了");
    return 0;
}
