module;
module npc.trace.disasm;

import npc.config;
static csh handle{};
static bool initialized{false};
void init_disasm()
{
    // DiffTest 失败报告与 itrace/iringbuf 可能先后触发初始化；
    // 重复 cs_open 会泄漏句柄并替换全局状态，这里保持幂等。
    if (initialized)
        return;
    constexpr cs_arch arch{CS_ARCH_RISCV};
    // Capstone 必须与实际 XLEN 对齐，否则 RV64 的高位地址和指令语义
    // 会被按 RV32 解释。
    constexpr cs_mode mode{npc::XLen == 64 ? CS_MODE_RISCV64
                                            : CS_MODE_RISCV32};
    if (const cs_err err{cs_open(arch, mode, &handle)}; err != CS_ERR_OK)
    {
        std::println(std::cerr, "cs_open failed: {}", cs_strerror(err));
        std::abort();
    }
    initialized = true;
}
void disassemble(char *str, int size, std::uint64_t pc, std::uint8_t *code, int nbyte)
{
    if (size <= 0)
    {
        return;
    }
    cs_insn *insn{nullptr};
    const auto count{cs_disasm(handle, code, static_cast<std::size_t>(nbyte), pc, 0, &insn)};
    if (count == 0)
    {
        str[0] = '\0';
        return;
    }

    const auto ret{std::snprintf(str, size, "%s", insn->mnemonic)};
    if (ret > 0 && ret < size && insn->op_str[0] != '\0')
    {
        std::snprintf(str + ret, size - ret, "\t%s", insn->op_str);
    }
    cs_free(insn, count);
}
