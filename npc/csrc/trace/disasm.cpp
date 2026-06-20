module;
#include <capstone/capstone.h>
module npc.trace.disasm;
static csh handle{};
void init_disasm()
{
    constexpr cs_arch arch{CS_ARCH_RISCV};
    constexpr cs_mode mode{CS_MODE_RISCV32};
    if (const cs_err err{cs_open(arch, mode, &handle)}; err != CS_ERR_OK)
    {
        std::println(stderr, "cs_open failed: {}", cs_strerror(err));
        std::abort();
    }
}
void disassemble(char *str, int size, std::uint64_t pc, std::uint8_t *code, int nbyte)
{
    cs_insn *insn{nullptr};
    const auto count{cs_disasm(handle, code, static_cast<size_t>(nbyte), pc, 0, &insn)};
    assert(count == 1);
    const auto ret{std::snprintf(str, size, "%s", insn->mnemonic)};
    if (insn->op_str[0] != '\0')
    {
        std::snprintf(str + ret, size - ret, "\t%s", insn->op_str);
    }
    cs_free(insn, count);
}
