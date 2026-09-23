#include <stdint.h>

namespace {

volatile uint32_t words[4] = {0x10203040u, 0u, 0u, 0u};

} // namespace

extern "C" {

volatile uint32_t trap_count = 0;
volatile uint32_t last_trap_cause = 0;
volatile uint32_t timer_observation = 0;

// Each test trap is a four-byte instruction.  Record its cause, advance mepc,
// and return so online DiffTest must synchronize both the trap edge and the
// handler's subsequent CSR/control-flow instructions.
__attribute__((naked, aligned(4))) void difftest_trap_entry() {
  asm volatile(
      "csrr t0, mcause\n"
      "la t1, last_trap_cause\n"
      "sw t0, 0(t1)\n"
      "la t1, trap_count\n"
      "lw t2, 0(t1)\n"
      "addi t2, t2, 1\n"
      "sw t2, 0(t1)\n"
      "csrr t0, mepc\n"
      "addi t0, t0, 4\n"
      "csrw mepc, t0\n"
      "mret\n");
}

} // extern "C"

extern "C" int main() {
  // CLINT mtime is intentionally nondeterministic between RTL cycles and the
  // host-side NEMU reference.  Online DiffTest must result-inject this load,
  // not execute a different timer implementation and report a false mismatch.
  volatile const uint32_t *const mtime =
      reinterpret_cast<volatile const uint32_t *>(0x0200bff8u);
  timer_observation = mtime[0] ^ mtime[1];

  uint32_t accumulator = 0;
  for (uint32_t i = 0; i < 16; ++i) {
    if ((i & 1u) != 0) {
      accumulator += i ^ 3u;
    } else {
      accumulator += i + 5u;
    }
  }

  words[1] = accumulator;
  words[2] = words[0] ^ words[1];
  words[3] = words[2] + 0x1234u;

  uintptr_t old_mtvec = 0;
  asm volatile("csrrw %0, mtvec, %1"
               : "=r"(old_mtvec)
               : "r"(difftest_trap_entry)
               : "memory");

  asm volatile("ecall" ::: "memory");
  const bool ecall_ok = trap_count == 1u && last_trap_cause == 11u;

  // NEMU's ordinary memory path permits this address, so a correct online
  // DiffTest must inject the DUT-classified cause instead of executing the
  // faulting load in the reference model.
  asm volatile("lw t0, 1(%0)" : : "r"(&words[0]) : "t0", "memory");
  const bool misaligned_ok = trap_count == 2u && last_trap_cause == 4u;

  // Likewise, the reference decoder must never execute this instruction: its
  // normal invalid-instruction path aborts rather than entering the DUT trap.
  asm volatile(".word 0xffffffff" ::: "memory");
  const bool illegal_ok = trap_count == 3u && last_trap_cause == 2u;

  asm volatile("csrw mtvec, %0" : : "r"(old_mtvec) : "memory");

  return (accumulator == 152u && words[2] == 0x102030d8u &&
          words[3] == 0x1020430cu && ecall_ok && misaligned_ok && illegal_ok)
             ? 0
             : 1;
}
