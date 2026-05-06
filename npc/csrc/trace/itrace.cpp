#include "itrace.hpp"
// 定义全局iringbuf对象
iringbuf Iringbuf;
void RecordAInstruction(uint64_t pc, uint32_t instruction, int len)
{
    Iringbuf.push(pc, instruction, len);
}
void PrintIringbuf(uint64_t err_pc)
{
    Iringbuf.print(err_pc);
}
extern "C" void itrace_record(uint64_t pc, uint32_t inst, int len)
{
#ifdef CONFIG_ITRACE
    Iringbuf.push(pc, inst, len);
#endif
}