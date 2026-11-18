// 流水线异常处理定向测试(B5)
// 在M态下逐项验证: ecall(11)/非法指令(2)/load访存错(5)/store访存错(7)/取指错(1)
// 每项检查: mcause正确, mepc精确指向异常指令, 异常指令之后的年轻指令被冲刷(t0泄漏检查)
#include <am.h>
#include <klib.h>

// 给汇编handler用的全局变量,必须extern "C"防止C++改名
extern "C" {
volatile uint32_t trap_count = 0;
volatile uint32_t last_mcause = 0;
volatile uint32_t last_mepc = 0;
volatile uint32_t young_leak = 0;  // 异常指令之后的年轻指令若被冲刷,handler看到t0应为0
volatile uint32_t resume_addr = 0; // 非0时handler把mepc改成它(取指错测试用,原mepc会反复fault)
}

// ysyxSoC的SRAM窗口是0x0f000000+0x8000(32KB),0x0f008000不在任何从设备窗口内,xbar回DECERR
static constexpr uint32_t BAD_ADDR = 0x0f008000u;

extern "C" __attribute__((naked, aligned(4))) void trap_handler() {
  asm volatile(
    "csrr t2, mcause\n\t"
    "la t3, last_mcause\n\t"
    "sw t2, 0(t3)\n\t"
    "csrr t2, mepc\n\t"
    "la t3, last_mepc\n\t"
    "sw t2, 0(t3)\n\t"
    // 年轻指令泄漏检查: 异常指令后紧跟"li t0,1",若流水线冲刷正确,handler里t0应仍为0
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
    // resume_addr非0则跳到它,否则mepc+=4跳过异常指令
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
  uint32_t dummy = 0;

  // 安装trap handler, direct模式
  asm volatile("csrw mtvec, %0" :: "r"((uint32_t)(void *)trap_handler & ~3u));

  // ---- 1. ecall, mcause=11 ----
  reset_obs();
  asm volatile(
    "li t0, 0\n\t"
    "la t1, 1f\n\t"
    "sw t1, %0\n\t"
    "1: ecall\n\t"
    "li t0, 1\n\t"
    : "=m"(exp_pc) : : "t0", "t1", "memory");
  check("ecall", 11, exp_pc);

  // ---- 2. 非法指令, mcause=2 ----
  reset_obs();
  asm volatile(
    "li t0, 0\n\t"
    "la t1, 1f\n\t"
    "sw t1, %0\n\t"
    "1: .word 0x00000000\n\t"
    "li t0, 1\n\t"
    : "=m"(exp_pc) : : "t0", "t1", "memory");
  check("illegal", 2, exp_pc);

  // ---- 3. load访问错误, mcause=5 ----
  reset_obs();
  asm volatile(
    "li t0, 0\n\t"
    "la t1, 1f\n\t"
    "sw t1, %0\n\t"
    "1: lw %1, 0(%2)\n\t"
    "li t0, 1\n\t"
    : "=m"(exp_pc), "=r"(dummy)
    : "r"(BAD_ADDR)
    : "t0", "t1", "memory");
  check("load-fault", 5, exp_pc);

  // ---- 4. store访问错误, mcause=7 ----
  reset_obs();
  asm volatile(
    "li t0, 0\n\t"
    "la t1, 1f\n\t"
    "sw t1, %0\n\t"
    "1: sw %1, 0(%2)\n\t"
    "li t0, 1\n\t"
    : "=m"(exp_pc)
    : "r"(0xdeadbeefu), "r"(BAD_ADDR)
    : "t0", "t1", "memory");
  check("store-fault", 7, exp_pc);

  // ---- 5. 取指访问错误, mcause=1, mepc=取指失败的地址 ----
  // jalr跳到无映射地址,取指回DECERR;handler把mepc改成汇编局部标号2处恢复执行
  reset_obs();
  asm volatile(
    "li t0, 0\n\t"
    "addi sp, sp, -4\n\t"
    "sw ra, 0(sp)\n\t"
    "la t1, 2f\n\t"
    "la t2, resume_addr\n\t"
    "sw t1, 0(t2)\n\t"
    "jalr ra, %0, 0\n\t"
    "2:\n\t"
    "lw ra, 0(sp)\n\t"
    "addi sp, sp, 4\n\t"
    "li t0, 1\n\t"
    :: "r"(BAD_ADDR)
    : "t0", "t1", "t2", "memory");
  check("fetch-fault", 1, BAD_ADDR);
  resume_addr = 0;

  // ---- 汇总 ----
  printf("trap_count=%u (want 5)\n", (unsigned)trap_count);
  if (trap_count != 5) failures++;
  if (failures == 0) {
    printf("EXCEPTION TEST PASS\n");
    return 0;
  }
  printf("EXCEPTION TEST FAIL, failures=%d\n", failures);
  return 1;
}
