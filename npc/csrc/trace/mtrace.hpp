#ifndef MTRACE_HPP
#define MTRACE_HPP
#include <cstdint>
extern "C" void MtraceRecord(std::uint64_t PC, int Address, int WriteData, int ReadData, std::uint8_t WriteMask, std::uint8_t wen);
#endif
