#include <elf.h>
import std;
import npc.trace.FtraceEvent;
import npc.trace.ftrace;

namespace
{
int failures{0};

constexpr std::uint32_t EncodeJalr(std::uint32_t Rd,
                                   std::uint32_t Rs1,
                                   std::int32_t Immediate)
{
    return (static_cast<std::uint32_t>(Immediate) & 0xfffu) << 20u |
           (Rs1 & 0x1fu) << 15u |
           (Rd & 0x1fu) << 7u |
           0x67u;
}

void check(bool condition, std::string_view message)
{
    if (condition)
        return;
    ++failures;
    std::println(std::cerr, "[FAIL] {}", message);
}

std::filesystem::path fixture_path(std::string_view name)
{
    const auto nonce{std::chrono::steady_clock::now().time_since_epoch().count()};
    return std::filesystem::temp_directory_path() /
           std::format("npc-ftrace-{}-{}.elf", name, nonce);
}

bool write_minimal_riscv_elf(const std::filesystem::path &path,
                             Elf32_Half type)
{
    Elf32_Ehdr header{};
    header.e_ident[EI_MAG0] = ELFMAG0;
    header.e_ident[EI_MAG1] = ELFMAG1;
    header.e_ident[EI_MAG2] = ELFMAG2;
    header.e_ident[EI_MAG3] = ELFMAG3;
    header.e_ident[EI_CLASS] = ELFCLASS32;
    header.e_ident[EI_DATA] = ELFDATA2LSB;
    header.e_ident[EI_VERSION] = EV_CURRENT;
    header.e_type = type;
    header.e_machine = EM_RISCV;
    header.e_version = EV_CURRENT;
    header.e_ehsize = sizeof(Elf32_Ehdr);
    header.e_shoff = sizeof(Elf32_Ehdr);
    header.e_shentsize = sizeof(Elf32_Shdr);
    header.e_shnum = 1;
    header.e_shstrndx = SHN_UNDEF;

    const Elf32_Shdr null_section{};
    std::ofstream output{path, std::ios::binary | std::ios::trunc};
    output.write(reinterpret_cast<const char *>(&header), sizeof(header));
    output.write(reinterpret_cast<const char *>(&null_section),
                 sizeof(null_section));
    return static_cast<bool>(output);
}

class FixtureFiles final
{
public:
    FixtureFiles()
        : executable{fixture_path("exec")},
          relocatable{fixture_path("rel")},
          dynamic{fixture_path("dyn")}
    {
    }

    ~FixtureFiles()
    {
        std::error_code ignored;
        std::filesystem::remove(executable, ignored);
        ignored.clear();
        std::filesystem::remove(relocatable, ignored);
        ignored.clear();
        std::filesystem::remove(dynamic, ignored);
    }

    std::filesystem::path executable;
    std::filesystem::path relocatable;
    std::filesystem::path dynamic;
};
}

int main()
{
    // --elf 必须真正打开并解析文件，而不是只记住路径。
    const auto missingElf{InitializeFtrace(
        "/definitely/not/a/real/npc-ftrace-test.elf", false)};
    check(!missingElf, "missing ELF must be rejected during initialization");

    FixtureFiles fixtures;
    check(write_minimal_riscv_elf(fixtures.executable, ET_EXEC),
          "failed to create ET_EXEC fixture");
    check(write_minimal_riscv_elf(fixtures.relocatable, ET_REL),
          "failed to create ET_REL fixture");
    check(write_minimal_riscv_elf(fixtures.dynamic, ET_DYN),
          "failed to create ET_DYN fixture");
    Ftrace elfTypeTrace;
    const auto executableElf{elfTypeTrace.LoadElf(fixtures.executable)};
    check(executableElf.has_value(),
          "Ftrace must accept a fixed-address RISC-V ET_EXEC file");
    const auto relocatableElf{elfTypeTrace.LoadElf(fixtures.relocatable)};
    check(!relocatableElf,
          "Ftrace must reject ET_REL because its symbols are section-relative");
    if (!relocatableElf)
    {
        check(relocatableElf.error().contains("ET_EXEC"),
              "ET_REL rejection must explain the required executable type");
    }
    const auto inspectionOnlyElf{
        elfTypeTrace.LoadElf(fixtures.relocatable, false)};
    check(inspectionOnlyElf.has_value(),
          "inspection-only readelf use must retain ET_REL support");
    const auto dynamicElf{elfTypeTrace.LoadElf(fixtures.dynamic)};
    check(!dynamicElf,
          "Ftrace must reject ET_DYN without a runtime load bias");

    Ftrace trace;
    trace.Enable();

    // jal x1, ...；NextPC 是 RTL 这条退休记录中的真实目标。
    trace.OnInstruction(0x80000000U, 0x000000efU, 0x80000100U);
    check(trace.HistorySize() == 1 && trace.Depth() == 1,
          "a retiring call must be recorded immediately");
    check(trace.History().back().GetType() == FtraceEventType::Call &&
              trace.History().back().GetTargetPC() == 0x80000100U,
          "call must use same-retirement debug_next_pc");

    // jalr x0, x1, 0
    trace.OnInstruction(0x80000120U, 0x00008067U, 0x80000004U);
    check(trace.HistorySize() == 2 && trace.Depth() == 0 &&
              trace.History().back().GetType() == FtraceEventType::Return,
          "x1 return must pop the call stack");

    trace.Reset();
    // jal x5, ... 与 jalr x0, x5, 0 是 RISC-V 定义的另一组
    // link-register call/return hint。
    trace.OnInstruction(0x80000200U, 0x000002efU, 0x80000300U);
    trace.OnInstruction(0x80000320U, 0x00028067U, 0x80000204U);
    check(trace.HistorySize() == 2 && trace.Depth() == 0,
          "x5 call/return pair must balance the call stack");
    check(trace.History().back().GetType() == FtraceEventType::Return &&
              trace.History().back().GetTargetPC() == 0x80000204U,
          "jalr x0,x5,0 must be recognized as return");

    trace.Reset();
    trace.OnInstruction(0x80000600U, 0x000000efU, 0x80000700U);
    trace.OnInstruction(0x80000720U,
                        EncodeJalr(0, 1, 12),
                        0x80000610U);
    check(trace.HistorySize() == 2 && trace.Depth() == 0 &&
              trace.History().back().GetType() == FtraceEventType::Return,
          "a return hint with non-zero immediate must pop the call stack");
    check(trace.History().back().GetTargetPC() == 0x80000610U,
          "non-zero-immediate return must retain the retired NextPC target");

    // jalr x5,x1,imm is a coroutine switch: pop the x1 frame, then push
    // the new x5 frame.  It must emit both events in architectural hint order
    // while preserving a non-empty stack's depth.
    trace.Reset();
    trace.OnInstruction(0x80000800U, 0x000000efU, 0x80000900U);
    trace.OnInstruction(0x80000920U,
                        EncodeJalr(5, 1, -8),
                        0x80000a00U);
    check(trace.HistorySize() == 3 && trace.Depth() == 1,
          "x1-to-x5 coroutine JALR must keep stack depth unchanged");
    check(trace.History()[1].GetType() == FtraceEventType::Return &&
              trace.History()[2].GetType() == FtraceEventType::Call,
          "coroutine JALR must record pop before push");
    check(trace.History()[1].GetTargetPC() == 0x80000a00U &&
              trace.History()[2].GetTargetPC() == 0x80000a00U,
          "both coroutine events must use the same retired NextPC target");

    // Equal link registers request push only, not pop-then-push.
    trace.Reset();
    trace.OnInstruction(0x80000b00U,
                        EncodeJalr(1, 1, 0),
                        0x80000c00U);
    check(trace.HistorySize() == 1 && trace.Depth() == 1 &&
              trace.History().back().GetType() == FtraceEventType::Call,
          "jalr with equal link registers must push without popping");

    // Check the opposite coroutine direction as well.
    trace.Reset();
    trace.OnInstruction(0x80000d00U, 0x000002efU, 0x80000e00U);
    trace.OnInstruction(0x80000e20U,
                        EncodeJalr(1, 5, 4),
                        0x80000f00U);
    check(trace.HistorySize() == 3 && trace.Depth() == 1 &&
              trace.History()[1].GetType() == FtraceEventType::Return &&
              trace.History()[2].GetType() == FtraceEventType::Call,
          "x5-to-x1 coroutine JALR must pop then push");

    // 这条 call 故意作为最后一个退休事件：它必须无需
    // 等待下一条指令就立即出现。
    trace.Reset();
    trace.OnInstruction(0x80000400U, 0x000000efU, 0x80000500U);
    check(trace.HistorySize() == 1 && trace.Depth() == 1,
          "final retiring call must not be lost");

    if (failures != 0)
        return 1;
    std::println("[PASS] ftrace retirement, x1/x5 RAS hints, coroutine, and ELF regressions");
    return 0;
}
