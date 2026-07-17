#include <klib-macros.h>
#include <klib.h>
#include "flash.hpp"

int main(const char *args)
{
    volatile char *flash{reinterpret_cast<volatile char *>(FLASH_BASE)};
    std::uint32_t errors{};
    for (int i{0}; i < 256; i++)
    {
        auto expect{i};
        auto got{flash[i]};
        if (got != expect)
        {
            errors++;
        }
    }
    if (errors == 0)
    {
        putstr("PASS\n");
    }
    else
    {
        putstr("FAIL\n");
    }
    return 0;
}
