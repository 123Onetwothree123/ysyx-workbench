#include "mtrace.hpp"
#ifdef CONFIG_MTRACE
#include <print>
#endif
extern "C" void MtraceRecord(uint64_t PC, int Address, int WriteData, int ReadData, uint8_t WriteMask, uint8_t wen)
{
#ifdef CONFIG_MTRACE
    const auto pc = static_cast<std::uint64_t>(PC);
    const auto address = static_cast<std::uint32_t>(Address);
    const auto write_data = static_cast<std::uint32_t>(WriteData);
    const auto read_data = static_cast<std::uint32_t>(ReadData);
    if (wen)
    {
        std::println("Memory Write: PC=0x{:08x}, Address=0x{:08x}, WriteData=0x{:08x}, WriteMask=0b{:04b}", pc, address, write_data, WriteMask);
    }
    else
    {
        std::println("Memory Read:  PC=0x{:08x}, Address=0x{:08x}, ReadData=0x{:08x}", pc, address, read_data);
    }
#endif
}
