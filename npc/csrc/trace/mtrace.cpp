#include "mtrace.hpp"
#include <print>
void MtraceRecord(std::uint32_t pc, std::uint32_t addr, std::uint32_t wdata,
                  std::uint32_t rdata, std::uint8_t width, bool wen)
{
    if (wen)
    {
        std::println("Memory Write: PC=0x{:08x}, Addr=0x{:08x}, Data=0x{:08x}, Width={}",
                     pc, addr, wdata, width);
    }
    else
    {
        std::println("Memory Read:  PC=0x{:08x}, Addr=0x{:08x}, Data=0x{:08x}, Width={}",
                     pc, addr, rdata, width);
    }
}
