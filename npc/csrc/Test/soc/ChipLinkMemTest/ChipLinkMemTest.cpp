#include "ChipLinkMemTest.hpp"

// 复用 peripheral 的宽度往返算法，避免第三份逐字复制。
#include "../../peripheral/common/mem_test.hpp"

extern "C" std::expected<void, std::uintptr_t> ChipLink_mem_test(
    std::uintptr_t begin, std::size_t length)
{
    return npc_test::mem_test(begin, length);
}
