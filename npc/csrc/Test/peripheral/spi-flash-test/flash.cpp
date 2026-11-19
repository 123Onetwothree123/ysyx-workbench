#include "flash.hpp"
#include <am.h>
#include <klib-macros.h>
#include <klib.h>

int main(const char *args)
{
    std::uint32_t errors{};
    for (std::uint32_t a{0}; a < 256; a += 4)
    {
        auto got{flash_read(a)};
        auto exp{static_cast<std::uint32_t>((a + 3) << 24 | (a + 2) << 16 | (a + 1) << 8 | a)};
        if (got != exp)
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
