#ifndef PIPELINE_COMMON_TRAP_TEST_HPP
#define PIPELINE_COMMON_TRAP_TEST_HPP
#include <cstdint>
#include <cstdio>

// 给汇编handler用的全局变量,必须extern "C"防止C++改名
extern "C" {
volatile std::uint32_t trap_count = 0;
volatile std::uint32_t last_mcause = 0;
volatile std::uint32_t last_mepc = 0;
volatile std::uint32_t young_leak = 0;
volatile std::uint32_t resume_addr = 0;
}

inline int failures = 0;

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

inline void check(const char *name, std::uint32_t want_cause,
                  std::uint32_t want_mepc) {
  bool ok = last_mcause == want_cause && last_mepc == want_mepc && young_leak == 0;
  std::printf("[%s] mcause=%u(want %u) mepc=0x%08x(want 0x%08x) leak=%u => %s\n",
              name, (unsigned)last_mcause, (unsigned)want_cause,
              (unsigned)last_mepc, (unsigned)want_mepc, (unsigned)young_leak,
              ok ? "PASS" : "FAIL");
  if (!ok) failures++;
}

inline void reset_obs() {
  last_mcause = 0xffffffffu;
  last_mepc = 0xffffffffu;
  young_leak = 0;
}

#endif
