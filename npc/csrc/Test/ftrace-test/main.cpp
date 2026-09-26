import std;
import npc.trace.FtraceEvent;
import npc.trace.ftrace;

namespace
{
int failures{0};

void check(bool condition, std::string_view message)
{
    if (condition)
        return;
    ++failures;
    std::println(std::cerr, "[FAIL] {}", message);
}
}

int main()
{
    // --elf 必须真正打开并解析文件，而不是只记住路径。
    const auto missingElf{InitializeFtrace(
        "/definitely/not/a/real/npc-ftrace-test.elf", false)};
    check(!missingElf, "missing ELF must be rejected during initialization");

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

    // 这条 call 故意作为最后一个退休事件：它必须无需
    // 等待下一条指令就立即出现。
    trace.Reset();
    trace.OnInstruction(0x80000400U, 0x000000efU, 0x80000500U);
    check(trace.HistorySize() == 1 && trace.Depth() == 1,
          "final retiring call must not be lost");

    if (failures != 0)
        return 1;
    std::println("[PASS] ftrace retirement, next-PC, ELF, and x5 return regressions");
    return 0;
}
