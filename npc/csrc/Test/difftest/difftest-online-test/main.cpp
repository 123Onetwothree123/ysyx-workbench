#include <stdint.h>

namespace {

volatile uint32_t words[4] = {0x10203040u, 0u, 0u, 0u};

} // namespace

extern "C" {

volatile uint32_t trap_count = 0;
volatile uint32_t last_trap_cause = 0;
volatile uint32_t last_trap_tval = 0;
volatile uint32_t timer_observation = 0;
volatile uint32_t zicsr_observation = 0;

static bool test_zicsr_and_mstatus_warl() {
  uint32_t old = 0;
  uint32_t value = 0xa5a55a5au;
  asm volatile("csrw mcause, %0" : : "r"(value));

  value = 0x12345678u;
  asm volatile("csrrw %0, mcause, %1" : "=r"(old) : "r"(value));
  if (old != 0xa5a55a5au) return false;

  value = 0x0000f00fu;
  asm volatile("csrrs %0, mcause, %1" : "=r"(old) : "r"(value));
  if (old != 0x12345678u) return false;

  value = 0x00340070u;
  asm volatile("csrrc %0, mcause, %1" : "=r"(old) : "r"(value));
  if (old != 0x1234f67fu) return false;

  asm volatile("csrrwi %0, mcause, 26" : "=r"(old));
  if (old != 0x1200f60fu) return false;
  asm volatile("csrrsi %0, mcause, 5" : "=r"(old));
  if (old != 0x1au) return false;
  asm volatile("csrrci %0, mcause, 3" : "=r"(old));
  if (old != 0x1fu) return false;
  asm volatile("csrrci %0, mcause, 0" : "=r"(old));
  if (old != 0x1cu) return false;

  value = 0xffffffffu;
  asm volatile("csrw mstatus, %0" : : "r"(value));
  asm volatile("csrr %0, mstatus" : "=r"(old));
  if (old != 0x1888u) return false;
  value = 0x800u; // M-only: every written MPP encoding reads back as M(11)
  asm volatile("csrw mstatus, %0" : : "r"(value));
  asm volatile("csrr %0, mstatus" : "=r"(old));
  if (old != 0x1800u) return false;
  asm volatile("csrw mstatus, zero" ::: "memory");
  return true;
}

// Each test trap is a four-byte instruction.  Record its cause, advance mepc,
// and return so online DiffTest must synchronize both the trap edge and the
// handler's subsequent CSR/control-flow instructions.
__attribute__((naked, aligned(4))) void difftest_trap_entry() {
  asm volatile(
      "csrr t0, mcause\n"
      "la t1, last_trap_cause\n"
      "sw t0, 0(t1)\n"
      // 读取 mtval 才能验证 DiffTest 是否把 DUT 的真实 tval 注入 REF；
      // 只看 mcause 时，stval/mtval 的差异会拖到 handler 后续指令才暴露。
      "csrr t0, mtval\n"
      "la t1, last_trap_tval\n"
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
  // Keep this across the deliberately minimal trap handler below.  That
  // handler uses temporary registers and does not build a full C context.
  zicsr_observation = test_zicsr_and_mstatus_warl() ? 1u : 0u;

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

  uintptr_t ecall_pc = 0;
  asm volatile("la %0, 1f\n"
               "1: ecall\n"
               : "=r"(ecall_pc)
               :
               : "memory");
  const bool ecall_ok = trap_count == 1u && last_trap_cause == 11u &&
                        last_trap_tval == ecall_pc;

  // NEMU's ordinary memory path permits this address, so a correct online
  // DiffTest must inject the DUT-classified cause/tval instead of executing
  // the faulting load in the reference model.
  uintptr_t misaligned_pc = 0;
  asm volatile("la %0, 1f\n"
               "1: lw t0, 1(%1)\n"
               : "=r"(misaligned_pc)
               : "r"(&words[0])
               : "t0", "memory");
  const bool misaligned_ok =
      trap_count == 2u && last_trap_cause == 4u &&
      last_trap_tval == reinterpret_cast<uintptr_t>(&words[0]) + 1u;

  // Likewise, the reference decoder must never execute this instruction: its
  // normal invalid-instruction path aborts rather than entering the DUT trap.
  uintptr_t illegal_pc = 0;
  asm volatile("la %0, 1f\n"
               "1: .word 0xffffffff\n"
               : "=r"(illegal_pc)
               :
               : "memory");
  const bool illegal_ok = trap_count == 3u && last_trap_cause == 2u &&
                          last_trap_tval == illegal_pc;

  asm volatile("csrw mtvec, %0" : : "r"(old_mtvec) : "memory");

#ifdef TEST_DIRECT_NPC
  // This address is inside the 256 KiB direct RAM but well beyond this small
  // image.  The +0x20 avoids the allocator's block headers, which legitimately
  // initialize parts of the heap before main.  RTL and REF must both observe
  // this otherwise untouched tail word as zero.  Read it after the deliberately
  // minimal trap handler is no longer active so its temporary-register
  // clobbers cannot corrupt the observation.
  volatile const uint32_t *const cleared_ram_tail =
      reinterpret_cast<volatile const uint32_t *>(0x8003f020u);
  const bool cleared_tail_ok = *cleared_ram_tail == 0u;
#else
  const bool cleared_tail_ok = true;
#endif

  uint32_t failures = 0;
  if (zicsr_observation != 1u) failures |= 1u << 0;
  if (accumulator != 152u || words[2] != 0x102030d8u ||
      words[3] != 0x1020430cu)
    failures |= 1u << 1;
  if (!cleared_tail_ok) failures |= 1u << 2;
  if (!ecall_ok) failures |= 1u << 3;
  if (!misaligned_ok) failures |= 1u << 4;
  if (!illegal_ok) failures |= 1u << 5;
  return static_cast<int>(failures);
}
