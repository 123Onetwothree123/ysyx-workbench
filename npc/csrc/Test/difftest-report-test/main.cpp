// 失败报告格式化回归。
//
// 这里不依赖 Verilator/RTL，直接构造四类“人为 mismatch”快照：
//   GPR 差异、CSR 差异（含 RV64 mtval 高半区）、PC 差异、trap 差异；
// 以及旧 REF 缺少 difftest_get_last_instruction 时的降级文本。
// 断言重点是：首次失败即可看到 PC、指令字/反汇编、具体差异项。

#include <cstdint>
#include <iostream>
#include <string>
#include <string_view>
#include <utility>

#include "../../difftest/DifftestReport.h"

namespace {

int failures = 0;

void check(bool condition, const std::string &message) {
  if (!condition) {
    ++failures;
    std::cerr << "[FAIL] " << message << '\n';
  }
}

void contains(const std::string &text, std::string_view needle,
              const std::string &what) {
  check(text.find(needle) != std::string::npos,
        what + "：缺少 '" + std::string{needle} + "'\n--- 实际输出 ---\n" +
            text);
}

npc::difftest::DifftestReport makeBase(std::string event, unsigned xlen) {
  npc::difftest::DifftestReport report{};
  report.Event = std::move(event);
  report.XLen = xlen;
  report.Cycle = 12345;
  report.DUTRetirePC = 0x80001234;
  report.HasDUTNextPC = true;
  report.DUTNextPC = 0x80001238;
  report.RefPC = 0x80001238;
  report.HasDUTInstruction = true;
  report.DUTInstructionPC = 0x80001234;
  report.DUTInstruction = 0x0000100f;
  report.DUTAssembly = "fence.i";
  report.DUTInstructionNote = "本步退休指令";
  report.HasRefInstruction = true;
  report.RefInstructionIsCurrent = true;
  report.RefInstructionPC = 0x80001234;
  report.RefInstruction = 0xfe010113;
  report.RefAssembly = "addi\tsp, sp, -32";
  return report;
}

void checkGPRMismatch() {
  auto report = makeBase("retire-post", 64);
  report.RefState = npc::difftest::ReportGPRState{};
  report.DUTState = npc::difftest::ReportGPRState{};
  report.RefState->GPR[2] = 0x80002000;
  report.DUTState->GPR[2] = 0x80001fe0;
  report.RefState->GPR[4] = 0x80003000;
  report.DUTState->GPR[4] = 0x80003000;
  report.RefCSR = npc::difftest::ReportCSRState{};
  report.DUTCSR = npc::difftest::ReportCSRState{};

  const auto text = npc::difftest::FormatDifftestReport(report);
  contains(text, "event=retire-post", "GPR 用例 event");
  contains(text, "cycle=12345", "GPR 用例 cycle");
  contains(text, "DUT retire PC = 0x0000000080001234", "GPR 用例 DUT PC");
  contains(text, "REF PC        = 0x0000000080001238", "GPR 用例 REF PC");
  contains(text, "DUT 指令      = 0x0000100f", "GPR 用例 DUT 指令字");
  contains(text, "fence.i", "GPR 用例 DUT 反汇编");
  contains(text, "REF 指令      = 0xfe010113", "GPR 用例 REF 指令字");
  contains(text, "addi", "GPR 用例 REF 反汇编");
  contains(text, "[!] DUT 与 REF 执行的指令字不同", "GPR 用例指令差异提示");
  contains(text, "差异 GPR（1/32）", "GPR 差异计数");
  contains(text, "x2  sp", "GPR 用例 x2 名称");
  contains(text, "REF=0x0000000080002000", "GPR 用例 REF x2 值");
  contains(text, "DUT=0x0000000080001fe0", "GPR 用例 DUT x2 值");
  contains(text, "SP(x2): REF=0x0000000080002000  DUT=0x0000000080001fe0",
           "SP 摘要");
  contains(text, "CSR 差异：无", "GPR 用例 CSR 无差异");
}

void checkCSRMismatch() {
  auto report = makeBase("retire-post-csr", 64);
  report.RefState = npc::difftest::ReportGPRState{};
  report.DUTState = npc::difftest::ReportGPRState{};
  report.RefCSR = npc::difftest::ReportCSRState{
      .Mstatus = 0xa00001800,
      .Mtvec = 0x80000100,
      .Mepc = 0x80001234,
      .Mcause = 0x0000000f,
      .HasMtval = true,
      .Mtval = 0xdeadbeefcafebabe,
  };
  report.DUTCSR = npc::difftest::ReportCSRState{
      .Mstatus = 0xa00001888,
      .Mtvec = 0x80000200,
      .Mepc = 0x80001238,
      .Mcause = 0x0000000d,
      .HasMtval = true,
      .Mtval = 0xdeadbeefcafe0001,
  };

  const auto text = npc::difftest::FormatDifftestReport(report);
  contains(text, "CSR 差异：", "CSR 用例标题");
  contains(text, "mstatus   REF=0x0000000a00001800  DUT=0x0000000a00001888",
           "CSR mstatus 64 位");
  contains(text, "mtvec     REF=0x0000000080000100  DUT=0x0000000080000200",
           "CSR mtvec");
  contains(text, "mepc      REF=0x0000000080001234  DUT=0x0000000080001238",
           "CSR mepc");
  contains(text, "mcause    REF=0x000000000000000f  DUT=0x000000000000000d",
           "CSR mcause");
  contains(text, "mtval     REF=0xdeadbeefcafebabe  DUT=0xdeadbeefcafe0001",
           "CSR mtval 不截断高 32 位");
}

void checkPCMismatch() {
  auto report = makeBase("retire-pre", 32);
  report.DUTRetirePC = 0x80001000;
  report.DUTInstructionPC = 0x80001000;
  report.RefPC = 0x80000ffc;
  report.RefInstructionIsCurrent = false;
  report.RefInstructionPC = 0x80000ffc;
  report.RefInstruction = 0x00100073;
  report.RefAssembly = "ebreak";
  report.RefInstructionNote = "上一步 REF 指令（本步尚未执行）";

  const auto text = npc::difftest::FormatDifftestReport(report);
  contains(text, "event=retire-pre", "PC 用例 event");
  contains(text, "DUT retire PC = 0x80001000", "PC 用例 DUT PC");
  contains(text, "REF PC        = 0x80000ffc", "PC 用例 REF PC");
  contains(text, "DUT 指令      = 0x0000100f", "PC 用例 DUT 指令");
  contains(text, "REF 指令      = 0x00100073", "PC 用例 REF 指令");
  contains(text, "ebreak", "PC 用例 REF 反汇编");
  contains(text, "[上一步执行]", "PC 用例说明 REF 未执行本步");
  check(text.find("差异 GPR") == std::string::npos,
        "PC 用例没有 GPR 快照时不应伪造寄存器差异");
}

void checkTrapMismatch() {
  auto report = makeBase("trap-post", 64);
  report.HasTrap = true;
  report.TrapCause = 15;
  report.TrapEPC = 0x8000456789abcdefULL;
  report.TrapTvalAvailable = true;
  report.TrapTval = 0x8000456789abc000ULL;
  report.TrapTarget = 0x80000100;
  report.HasTrapInstruction = true;
  report.TrapInstructionPC = report.TrapEPC;
  report.TrapInstruction = 0x00a13023;
  report.TrapAssembly = "sd\ta0, 0(sp)";
  report.HasRefMtval = true;
  report.RefMtval = 0;
  report.HasRefStval = true;
  report.RefStval = 0x8000456789abc000ULL;
  report.RefState = npc::difftest::ReportGPRState{};
  report.DUTState = npc::difftest::ReportGPRState{};

  auto refCSR = npc::difftest::ReportCSRState{};
  auto dutCSR = npc::difftest::ReportCSRState{};
  dutCSR.HasMtval = true;
  dutCSR.Mtval = report.TrapTval;
  refCSR.HasMtval = true;
  refCSR.Mtval = report.TrapTval;
  refCSR.MtvalLabel = "stval";
  report.RefCSR = refCSR;
  report.DUTCSR = dutCSR;

  const auto text = npc::difftest::FormatDifftestReport(report);
  contains(text, "event=trap-post", "trap 用例 event");
  contains(text, "trap: cause=0xf (store/AMO page fault)", "trap cause 解码");
  contains(text, "epc=0x8000456789abcdef", "trap epc 64 位");
  contains(text, "tval=0x8000456789abc000", "trap tval 64 位");
  contains(text, "target=0x0000000080000100", "trap target");
  contains(text, "trap PC 指令  = 0x00a13023", "trap PC 指令字");
  contains(text, "sd\ta0, 0(sp)", "trap PC 反汇编");
  contains(text, "REF trap CSR: mtval=0x0000000000000000 "
                 "stval=0x8000456789abc000",
           "REF mtval/stval 全宽输出");
  contains(text, "stval", "委托 trap 的 tval CSR 名称");
}

void checkFPMismatch() {
  auto report = makeBase("retire-post-fp", 64);
  report.RefState = npc::difftest::ReportGPRState{};
  report.DUTState = npc::difftest::ReportGPRState{};
  report.RefCSR = npc::difftest::ReportCSRState{};
  report.DUTCSR = npc::difftest::ReportCSRState{};
  report.RefFP = npc::difftest::ReportFPState{};
  report.DUTFP = npc::difftest::ReportFPState{};
  report.RefFP->FReg[0] = 0x3ff0000000000000ULL;
  report.DUTFP->FReg[0] = 0x3ff0000000000001ULL;
  report.RefFP->FReg[31] = 0x7ff8000000000000ULL;
  report.DUTFP->FReg[31] = 0x7ff8000000000000ULL;
  report.RefFP->FCSR = 0x05;
  report.DUTFP->FCSR = 0x25;

  const auto text = npc::difftest::FormatDifftestReport(report);
  contains(text, "event=retire-post-fp", "FP 用例 event");
  contains(text, "差异 FPR（1/32）", "FP 差异计数");
  contains(text, "f0  REF=0x3ff0000000000000  DUT=0x3ff0000000000001",
           "FP f0 差异");
  contains(text, "fcsr 差异：REF=0x05  DUT=0x25", "fcsr 差异");
  check(text.find("f31") == std::string::npos,
        "FP 用例不应报告没有差异的 f31");
}

void checkRefInstructionUnavailable() {
  auto report = makeBase("retire-post", 64);
  report.HasRefInstruction = false;
  report.RefInstructionNote = "REF 缺少 difftest_get_last_instruction";
  report.RefState = npc::difftest::ReportGPRState{};
  report.DUTState = npc::difftest::ReportGPRState{};

  const auto text = npc::difftest::FormatDifftestReport(report);
  contains(text, "REF 指令      = <不可用", "旧 REF 降级标记");
  contains(text, "REF 缺少 difftest_get_last_instruction", "旧 REF 降级原因");
}

} // namespace

int main() {
  checkGPRMismatch();
  checkCSRMismatch();
  checkFPMismatch();
  checkPCMismatch();
  checkTrapMismatch();
  checkRefInstructionUnavailable();

  if (failures != 0) {
    std::cerr << failures << " DiffTest report check(s) failed\n";
    return 1;
  }
  std::cout << "[PASS] DiffTest mismatch report: GPR/CSR/FP/PC/trap + REF fallback\n";
  return 0;
}
