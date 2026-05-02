#ifndef ITRACE_HPP
#define ITRACE_HPP
#include <cstdint>
#include <cstddef>
#include <array>
#include"iringbuf.hpp"
//全局iringbuf对象
extern iringbuf Iringbuf;
// 初始化capstone反汇编引擎
void init_disasm();
// 反汇编一条指令，结果写入str
void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);
// iringbuf移植过来
// 记录一条命令
void RecordAInstruction(uint64_t pc, uint32_t instruction, int len);
// 打印缓冲区
void PrintIringbuf(uint64_t err_pc);
extern "C" void itrace_record(uint64_t pc, uint32_t inst, int len);
#endif
