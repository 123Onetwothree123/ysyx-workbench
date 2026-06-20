module;
#include <stdio.h>
#include <stdlib.h>
#include <capstone/capstone.h>
module npc.trace.disasm;
static csh handle{};
void init_disasm()
{
    constexpr cs_arch arch{CS_ARCH_RISCV};
    constexpr cs_mode mode{CS_MODE_RISCV32};
    if (const cs_err err{cs_open(arch, mode, &handle)}; err != CS_ERR_OK)
    {
        fprintf(stderr, "cs_open failed: %s\n", cs_strerror(err));
        abort();
    }
}
void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte)
{
    cs_insn *insn{nullptr};
    const auto count{cs_disasm(handle, code, static_cast<size_t>(nbyte), pc, 0, &insn)};

    const auto ret{snprintf(str, size, "%s", insn->mnemonic)};
    if (insn->op_str[0] != '\0')
    {
        snprintf(str + ret, size - ret, "\t%s", insn->op_str);
    }
    cs_free(insn, count);
}
