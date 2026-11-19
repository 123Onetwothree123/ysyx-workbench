// 流水线异常处理定向测试(B5)
// 在M态下逐项验证: ecall(11)/非法指令(2)/load访存错(5)/store访存错(7)/取指错(1)
// 每项检查: mcause正确, mepc精确指向异常指令, 异常指令之后的年轻指令被冲刷(t0泄漏检查)
#include <am.h>
#include <klib.h>
#include "../common/trap_test.hpp"

// ysyxSoC的SRAM窗口是0x0f000000+0x8000(32KB),0x0f008000不在任何从设备窗口内,xbar回DECERR
static constexpr uint32_t BAD_ADDR = 0x0f008000u;

int main() {
  uint32_t exp_pc = 0;
  uint32_t dummy = 0;

  // 安装trap handler, direct模式
  asm volatile("csrw mtvec, %0" :: "r"(reinterpret_cast<std::uintptr_t>(trap_handler) & ~std::uintptr_t{3}));

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
