#ifndef MTRACE_HPP
#define MTRACE_HPP
#include <cstdint>
extern "C" void MtraceRecord(uint64_t PC, int Address, int WriteData, int ReadData, uint8_t WriteMask, uint8_t wen);
#endif
