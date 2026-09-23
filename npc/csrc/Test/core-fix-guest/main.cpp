// CPU-visible regression for the core/AXI fixes.
//
// This is an AbstractMachine guest rather than a host-side RTL unit test.  It
// covers:
//   * SB/SH preserving the other bytes of an AXI RAM word;
//   * mepc/mtvec WARL behavior for IALIGN=32 and mtvec.MODE;
//   * instruction-address-misaligned traps from JALR, JAL and taken branch;
//   * a not-taken branch with a nominally misaligned target not trapping;
//   * architectural EBREAK reaching mtvec instead of stopping simulation;
//   * fence.i making a self-modified, already-prefetched instruction visible.
#include <am.h>
#include <klib.h>

extern "C" {
volatile uint32_t trap_count = 0;
volatile uint32_t last_mcause = 0xffffffffu;
volatile uint32_t last_mepc = 0xffffffffu;
volatile uint32_t expected_pc = 0;

volatile uint32_t jalr_unexpected_path = 0;
volatile uint32_t jalr_rd_after = 0;
volatile uint32_t jal_rd_after = 0;
volatile uint32_t fencei_result = 0;
}

// Keep this handler within the RV32E register subset so the same source can be
// linked with the repository's direct-NPC AbstractMachine runtime.
extern "C" __attribute__((naked, aligned(4))) void trap_handler() {
  asm volatile(
      "csrr t0, mcause\n\t"
      "la t1, last_mcause\n\t"
      "sw t0, 0(t1)\n\t"
      "csrr t0, mepc\n\t"
      "la t1, last_mepc\n\t"
      "sw t0, 0(t1)\n\t"
      "la t1, trap_count\n\t"
      "lw t0, 0(t1)\n\t"
      "addi t0, t0, 1\n\t"
      "sw t0, 0(t1)\n\t"
      // All faulting instructions below are one 32-bit instruction.  Resume
      // immediately after the instruction that raised the exception.
      "csrr t0, mepc\n\t"
      "addi t0, t0, 4\n\t"
      "csrw mepc, t0\n\t"
      "mret\n\t");
}

static int failures = 0;

static void check_value(const char *name, uint32_t got, uint32_t want) {
  const bool ok = got == want;
  printf("[%s] got=0x%08x want=0x%08x => %s\n", name, (unsigned)got,
         (unsigned)want, ok ? "PASS" : "FAIL");
  if (!ok) {
    ++failures;
  }
}

static void reset_trap_observation() {
  last_mcause = 0xffffffffu;
  last_mepc = 0xffffffffu;
}

static void check_misaligned_trap(const char *name, uint32_t count_before) {
  const bool ok = trap_count == count_before + 1 && last_mcause == 0 &&
                  last_mepc == expected_pc;
  printf("[%s] traps=%u->%u mcause=%u mepc=0x%08x expected=0x%08x => %s\n",
         name, (unsigned)count_before, (unsigned)trap_count,
         (unsigned)last_mcause, (unsigned)last_mepc, (unsigned)expected_pc,
         ok ? "PASS" : "FAIL");
  if (!ok) {
    ++failures;
  }
}

// The direct NPC AM target currently advertises RV32E_Zicsr rather than
// Zifencei to the compiler.  Emit the architectural FENCE.I encoding directly
// so this regression can still be built by that existing toolchain setup.
static inline void fence_i() {
  asm volatile(".word 0x0000100f" ::: "memory");
}

static void test_partial_stores() {
  alignas(4) static volatile uint32_t word = 0;

  word = 0xa1b2c3d4u;
  fence_i();
  asm volatile("sb %0, 1(%1)" : : "r"(0xeeu), "r"(&word) : "memory");
  // The D-cache mirrors a store only after a successful B response.  Flushing
  // it before the load forces the value back from AXI RAM and exposes bad RMW.
  fence_i();
  check_value("sb-preserves-neighbours", word, 0xa1b2eed4u);

  word = 0x11223344u;
  fence_i();
  asm volatile("sh %0, 2(%1)" : : "r"(0xa1b2u), "r"(&word) : "memory");
  fence_i();
  check_value("sh-preserves-neighbours", word, 0xa1b23344u);
}

static void test_csr_warl() {
  uint32_t got = 0;

  // With C disabled, IALIGN=32 makes mepc[1:0] read as zero.
  const uint32_t mepc_probe = 0x81234567u;
  asm volatile("csrw mepc, %0" : : "r"(mepc_probe));
  asm volatile("csrr %0, mepc" : "=r"(got));
  check_value("mepc-ialign32-warl", got, mepc_probe & ~3u);

  const uint32_t base = (uint32_t)(uintptr_t)trap_handler & ~3u;
  const uint32_t probes[4] = {base, base | 1u, base | 2u, base | 3u};
  const uint32_t expected[4] = {base, base | 1u, base, base};
  const char *names[4] = {"mtvec-direct", "mtvec-vectored",
                          "mtvec-reserved-2", "mtvec-reserved-3"};
  for (unsigned i = 0; i < 4; ++i) {
    asm volatile("csrw mtvec, %0" : : "r"(probes[i]));
    asm volatile("csrr %0, mtvec" : "=r"(got));
    check_value(names[i], got, expected[i]);
  }

  // All following synchronous exceptions use Direct mode.
  asm volatile("csrw mtvec, %0" : : "r"(base));
}

static void test_misaligned_jalr() {
  const uint32_t before = trap_count;
  reset_trap_observation();
  jalr_unexpected_path = 0;
  jalr_rd_after = 0;

  asm volatile(
      "li a0, 0x13579\n\t"
      // a1/a2 are deliberately prepared while the PC is aligned.  If a buggy
      // core starts executing at label 1 with PC[1]=1, the fallback block uses
      // no PC-relative instructions before realigning at label 3.
      "la a1, jalr_unexpected_path\n\t"
      "la a2, 3f\n\t"
      "la t0, 1f\n\t"
      "addi t0, t0, 2\n\t"
      "la t1, 2f\n\t"
      "la t2, expected_pc\n\t"
      "sw t1, 0(t2)\n\t"
      "2: jalr a0, 0(t0)\n\t"
      "la t0, jalr_rd_after\n\t"
      "sw a0, 0(t0)\n\t"
      "j 4f\n\t"
      ".balign 4\n\t"
      "1:\n\t"
      "li t0, 1\n\t"
      "sw t0, 0(a1)\n\t"
      "jalr zero, 0(a2)\n\t"
      "3:\n\t"
      "la t0, jalr_rd_after\n\t"
      "sw a0, 0(t0)\n\t"
      "4:\n\t"
      :
      :
      : "a0", "a1", "a2", "t0", "t1", "t2", "memory");

  check_misaligned_trap("jalr-misaligned", before);
  check_value("jalr-no-target-execution", jalr_unexpected_path, 0);
  check_value("jalr-no-link-write", jalr_rd_after, 0x13579u);
}

static void test_misaligned_jal() {
  const uint32_t before = trap_count;
  reset_trap_observation();
  jal_rd_after = 0;

  asm volatile(
      "li a0, 0x2468a\n\t"
      "la t0, 1f\n\t"
      "la t1, expected_pc\n\t"
      "sw t0, 0(t1)\n\t"
      // jal a0, +2.  The half-word target is illegal when IALIGN=32.
      "1: .word 0x0020056f\n\t"
      "la t0, jal_rd_after\n\t"
      "sw a0, 0(t0)\n\t"
      :
      :
      : "a0", "t0", "t1", "memory");

  check_misaligned_trap("jal-misaligned", before);
  check_value("jal-no-link-write", jal_rd_after, 0x2468au);
}

static void test_misaligned_branch() {
  uint32_t before = trap_count;
  reset_trap_observation();

  asm volatile(
      "la t0, 1f\n\t"
      "la t1, expected_pc\n\t"
      "sw t0, 0(t1)\n\t"
      // beq zero, zero, +2: taken target violates IALIGN=32.
      "1: .word 0x00000163\n\t"
      :
      :
      : "t0", "t1", "memory");
  check_misaligned_trap("taken-branch-misaligned", before);

  // A not-taken branch must not raise an exception merely because its encoded
  // target would be misaligned.
  before = trap_count;
  reset_trap_observation();
  asm volatile(
      // bne zero, zero, +2: condition is false, so no control transfer occurs.
      ".word 0x00001163\n\t"
      :
      :
      : "memory");
  check_value("not-taken-branch-no-trap", trap_count, before);
}

static void test_architectural_ebreak() {
  const uint32_t before = trap_count;
  reset_trap_observation();
  asm volatile(
      "la t0, 1f\n\t"
      "la t1, expected_pc\n\t"
      "sw t0, 0(t1)\n\t"
      "1: ebreak\n\t"
      :
      :
      : "t0", "t1", "memory");

  const bool ok = trap_count == before + 1 && last_mcause == 3 &&
                  last_mepc == expected_pc;
  printf("[architectural-ebreak] traps=%u->%u mcause=%u "
         "mepc=0x%08x expected=0x%08x => %s\n",
         (unsigned)before, (unsigned)trap_count, (unsigned)last_mcause,
         (unsigned)last_mepc, (unsigned)expected_pc, ok ? "PASS" : "FAIL");
  if (!ok) {
    ++failures;
  }
}

static void test_fence_i_prefetch() {
  fencei_result = 0;
  asm volatile(
      "la t0, 1f\n\t"
      "li t1, 0x00100513\n\t" // addi a0, zero, 1
      "sw t1, 0(t0)\n\t"
      ".word 0x0000100f\n\t" // fence.i
      // This instruction may already be in IF/ID when fence.i reaches EXU.
      "1: li a0, 0\n\t"
      "la t0, fencei_result\n\t"
      "sw a0, 0(t0)\n\t"
      :
      :
      : "a0", "t0", "t1", "memory");
  check_value("fence.i-self-modifying-prefetch", fencei_result, 1);
}

int main() {
  printf("core-fix-guest: CPU-visible regression start\n");

  test_partial_stores();
  test_csr_warl();
  test_misaligned_jalr();
  test_misaligned_jal();
  test_misaligned_branch();
  test_architectural_ebreak();
  test_fence_i_prefetch();

  printf("trap_count=%u expected=4\n", (unsigned)trap_count);
  if (trap_count != 4) {
    ++failures;
  }

  if (failures == 0) {
    printf("CORE FIX GUEST PASS\n");
  } else {
    printf("CORE FIX GUEST FAIL, failures=%d\n", failures);
  }
  return failures == 0 ? 0 : 1;
}
