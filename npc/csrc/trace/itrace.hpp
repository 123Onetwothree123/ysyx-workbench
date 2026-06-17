#ifndef ITRACE_HPP
#define ITRACE_HPP
#include <cstdint>
#include <cstddef>
#include <array>
#include "iringbuf.hpp"
extern iringbuf Iringbuf;
void init_disasm();
void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);
void RecordAInstruction(uint64_t pc, uint32_t instruction, int len);
void PrintIringbuf(uint64_t err_pc);
#endif
