#include <am.h>
#include <cstddef>
#include <cstdint>
#include "mem-test.hpp"
int main(const char *)
{
    const auto begin = reinterpret_cast<std::uintptr_t>(::heap.start);
    const auto end = reinterpret_cast<std::uintptr_t>(::heap.end);
    const auto length = static_cast<std::size_t>(end - begin);
    const auto result = mem_test(begin, length);
    if (!result)
    {
        halt(1);
    }
    return 0;
}