export module npc.trace.disasm;
import std;
import npc.capstone;

export void init_disasm();
export void disassemble(char *str, int size, std::uint64_t pc, std::uint8_t *code, int nbyte);
// 指令字按小端拆成 2/4 字节；超出 4 字节按 4 字节截断。
export std::array<std::uint8_t, 4>
InstructionBytes(std::uint32_t instruction, std::size_t length);
// 反汇编前置 init_disasm() 的幂等初始化；失败返回空串，占位文案由调用方决定。
export std::string DisassembleWord(std::uint64_t pc, std::uint32_t instruction,
                                   std::size_t length = 4);
