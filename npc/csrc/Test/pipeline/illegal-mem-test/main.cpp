// 非法访存编码与MemTrap副作用定向测试
// 覆盖:
//   1-3. 编码非法的 load/store 必须在EXU提交异常(mcause=2), rd不被写
//   4.   正常load回归
//   5-6. MEM级访存故障(MemTrap)提交当拍, EXU里被冲刷的年轻指令不得有副作用:
//        csrrw不得写mtvec, mret不得把异常目标劫持到旧mepc
//   7-8. 不对齐访存必须报地址非对齐异常(load cause=4/store cause=6), 不得静默完成
//   9.   对齐访问回归
#include <am.h>
#include <klib.h>

// ysyxSoC的SRAM窗口之外, xbar回DECERR
static constexpr uint32_t BAD_ADDR = 0x0f008000u;

extern "C" {
volatile uint32_t trap_count = 0;
volatile uint32_t last_mcause = 0;
volatile uint32_t last_mepc = 0;
volatile uint32_t young_leak = 0;
volatile uint32_t resume_addr = 0; // 非0时handler把mepc改成它(用于跳过被冲刷的指令)
}

extern "C" __attribute__((naked, aligned(4))) void trap_handler() {
  asm volatile(
    "csrr t2, mcause\n\t"
    "la t3, last_mcause\n\t"
    "sw t2, 0(t3)\n\t"
    "csrr t2, mepc\n\t"
    "la t3, last_mepc\n\t"
    "sw t2, 0(t3)\n\t"
    // 年轻指令泄漏检查: 异常指令后紧跟"li t0,1", 若冲刷正确handler里t0应仍为0
    "bnez t0, 0f\n\t"
    "j 1f\n\t"
    "0:\n\t"
    "la t3, young_leak\n\t"
    "li t2, 1\n\t"
    "sw t2, 0(t3)\n\t"
    "1:\n\t"
    "la t3, trap_count\n\t"
    "lw t2, 0(t3)\n\t"
    "addi t2, t2, 1\n\t"
    "sw t2, 0(t3)\n\t"
    // resume_addr非0则跳到它, 否则mepc+=4跳过异常指令
    "la t3, resume_addr\n\t"
    "lw t2, 0(t3)\n\t"
    "bnez t2, 2f\n\t"
    "csrr t2, mepc\n\t"
    "addi t2, t2, 4\n\t"
    "2:\n\t"
    "csrw mepc, t2\n\t"
    "mret\n\t");
}

static int failures = 0;

static void check(const char *name, uint32_t want_cause, uint32_t want_mepc) {
  bool ok = last_mcause == want_cause && last_mepc == want_mepc && young_leak == 0;
  printf("[%s] mcause=%u(want %u) mepc=0x%08x(want 0x%08x) leak=%u => %s\n",
         name, (unsigned)last_mcause, (unsigned)want_cause,
         (unsigned)last_mepc, (unsigned)want_mepc, (unsigned)young_leak,
         ok ? "PASS" : "FAIL");
  if (!ok) failures++;
}

static void reset_obs() {
  last_mcause = 0xffffffffu;
  last_mepc = 0xffffffffu;
  young_leak = 0;
}

int main() {
  uint32_t exp_pc = 0;
  uint32_t rd_after = 0;
  uint32_t mtvec_before = 0, mtvec_after = 0;
  volatile uint32_t mret_bad = 0;
  static volatile uint32_t probe = 0x12345678u;
  uint32_t val = 0;

  asm volatile("csrw mtvec, %0" :: "r"((uint32_t)(void *)trap_handler & ~3u));

  // ---- 1. 非法 load 编码: opcode=0x03, funct3=011 (lw x0, 0(x0)) ----
  reset_obs();
  asm volatile(
    "li t0, 0\n\t"
    "la t1, 1f\n\t"
    "sw t1, %0\n\t"
    "1: .word 0x00003003\n\t"
    "li t0, 1\n\t"
    : "=m"(exp_pc) : : "t0", "t1", "memory");
  check("illegal-load", 2, exp_pc);

  // ---- 2. 非法 store 编码: opcode=0x23, funct3=011 (sw x0, 0(x0)) ----
  reset_obs();
  asm volatile(
    "li t0, 0\n\t"
    "la t1, 1f\n\t"
    "sw t1, %0\n\t"
    "1: .word 0x00003023\n\t"
    "li t0, 1\n\t"
    : "=m"(exp_pc) : : "t0", "t1", "memory");
  check("illegal-store", 2, exp_pc);

  // ---- 3. 非法 load(rd=a5)不得写rd: .word 0x00003783 = 非法 lw a5, 0(x0) ----
  reset_obs();
  asm volatile(
    "li t0, 0\n\t"
    "la t1, 1f\n\t"
    "sw t1, %0\n\t"
    "li a5, 0x12345678\n\t"
    "1: .word 0x00003783\n\t"
    "sw a5, %1\n\t"
    "li t0, 1\n\t"
    : "=m"(exp_pc), "=m"(rd_after) : : "t0", "t1", "a5", "memory");
  bool rd_ok = (rd_after == 0x12345678u);
  printf("[illegal-load-rd] a5=0x%08x(want 0x12345678) => %s\n",
         (unsigned)rd_after, rd_ok ? "PASS" : "FAIL");
  if (!rd_ok) failures++;
  check("illegal-load-rd-trap", 2, exp_pc);

  // ---- 4. 回归: 正常load仍然工作 ----
  val = probe;
  bool load_ok = (val == 0x12345678u);
  printf("[normal-load] val=0x%08x(want 0x12345678) => %s\n",
         (unsigned)val, load_ok ? "PASS" : "FAIL");
  if (!load_ok) failures++;

  // ---- 5. MemTrap提交当拍, 被冲刷的csrrw不得写mtvec ----
  asm volatile("csrr %0, mtvec" : "=r"(mtvec_before));
  reset_obs();
  asm volatile(
    "li t0, 0\n\t"
    "la t1, 1f\n\t"
    "sw t1, %0\n\t"
    "la t2, resume_addr\n\t"
    "la t3, 3f\n\t"
    "sw t3, 0(t2)\n\t"
    "1: lw t0, 0(%1)\n\t"
    "csrw mtvec, x0\n\t"        // 被冲刷的年轻csrrw
    "3:\n\t"
    : "=m"(exp_pc) : "r"(BAD_ADDR) : "t0", "t1", "t2", "t3", "memory");
  resume_addr = 0;
  asm volatile("csrr %0, mtvec" : "=r"(mtvec_after));
  check("memtrap-csr", 5, exp_pc);
  bool mtvec_ok = (mtvec_after == mtvec_before);
  printf("[memtrap-csr-mtvec] before=0x%08x after=0x%08x => %s\n",
         (unsigned)mtvec_before, (unsigned)mtvec_after, mtvec_ok ? "PASS" : "FAIL");
  if (!mtvec_ok) failures++;

  // ---- 6. MemTrap提交当拍, 被冲刷的mret不得劫持异常目标 ----
  reset_obs();
  asm volatile(
    "li t0, 0\n\t"
    "la t1, 9f\n\t"
    "csrw mepc, t1\n\t"          // 旧mepc=失败路径: 若mret劫持目标会落在这里
    "la t1, 1f\n\t"
    "sw t1, %0\n\t"
    "la t2, resume_addr\n\t"
    "la t1, 3f\n\t"
    "sw t1, 0(t2)\n\t"
    "1: lw t0, 0(%2)\n\t"
    "mret\n\t"                    // 被冲刷的年轻mret
    "3:\n\t"
    "j 4f\n\t"
    "9:\n\t"
    "mv t3, %1\n\t"
    "li t2, 1\n\t"
    "sw t2, 0(t3)\n\t"
    "4:\n\t"
    : "=m"(exp_pc)
    : "r"((uint32_t)&mret_bad), "r"(BAD_ADDR)
    : "t0", "t1", "t2", "t3", "memory");
  resume_addr = 0;
  bool mret_ok = (mret_bad == 0);
  printf("[memtrap-mret-target] bad=%u(want 0) => %s\n",
         (unsigned)mret_bad, mret_ok ? "PASS" : "FAIL");
  if (!mret_ok) failures++;
  check("memtrap-mret", 5, exp_pc);

  // ---- 7. 不对齐 word load: 应报cause=4, 且不得写rd ----
  reset_obs();
  asm volatile(
    "li t0, 0\n\t"
    "la t1, 1f\n\t"
    "sw t1, %0\n\t"
    "li a5, 0x12345678\n\t"
    "1: lw a5, 0(%2)\n\t"
    "sw a5, %1\n\t"
    "li t0, 1\n\t"
    : "=m"(exp_pc), "=m"(rd_after)
    : "r"((uint32_t)&probe + 1)
    : "t0", "t1", "a5", "memory");
  check("misaligned-load", 4, exp_pc);
  bool mis_rd_ok = (rd_after == 0x12345678u);
  printf("[misaligned-load-rd] a5=0x%08x(want 0x12345678) => %s\n",
         (unsigned)rd_after, mis_rd_ok ? "PASS" : "FAIL");
  if (!mis_rd_ok) failures++;

  // ---- 8. 不对齐 word store: 应报cause=6 ----
  reset_obs();
  asm volatile(
    "li t0, 0\n\t"
    "la t1, 1f\n\t"
    "sw t1, %0\n\t"
    "1: sw zero, 0(%1)\n\t"
    "li t0, 1\n\t"
    : "=m"(exp_pc) : "r"((uint32_t)&probe + 1) : "t0", "t1", "memory");
  check("misaligned-store", 6, exp_pc);

  // ---- 9. 回归: 对齐半字访问仍然正常 ----
  uint32_t hw = 0;
  asm volatile("lh %0, 0(%1)" : "=r"(hw) : "r"((uint32_t)&probe) : "memory");
  bool lh_ok = (hw == 0x5678u);
  printf("[aligned-lh] val=0x%04x(want 0x5678) => %s\n",
         (unsigned)hw, lh_ok ? "PASS" : "FAIL");
  if (!lh_ok) failures++;

  // ---- 汇总 ----
  printf("trap_count=%u (want 7)\n", (unsigned)trap_count);
  if (trap_count != 7) failures++;
  if (failures == 0) {
    printf("ILLEGAL-MEM TEST PASS\n");
    return 0;
  }
  printf("ILLEGAL-MEM TEST FAIL, failures=%d\n", failures);
  return 1;
}
