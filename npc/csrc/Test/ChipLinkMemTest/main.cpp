#include "ChipLinkMemTest.hpp"
#include <stdio.h>
#include <am.h>
int main(const char *)
{
    const auto begin{reinterpret_cast<std::uintptr_t>(0xc0000000U)};
    const auto length{static_cast<std::size_t>(0x1000)};
    const auto result{ChipLink_mem_test(begin, length)};
    if (!result)
    {
        printf("ChipLink test FAILED 0x%08x\n", result.error());
        halt(1);
    }
    printf("ChipLink test PASSED\n");
    return 0;
}
