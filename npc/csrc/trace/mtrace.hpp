#ifndef MTRACE_HPP
#define MTRACE_HPP
#include <cstdint>
void MtraceRecord(std::uint32_t pc, std::uint32_t addr, std::uint32_t wdata,
                  std::uint32_t rdata, std::uint8_t width, bool wen);
#endif
