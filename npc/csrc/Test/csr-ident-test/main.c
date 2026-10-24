// Machine identity CSR / misa 裸机回归。
//
// 覆盖：
//   * mhartid 单 hart 固定读 0，mimpid 固定读 0；
//   * mvendorid/marchid 的既有常量；
//   * misa 的 MXL/I 与 HAS_S/HAS_U 对应的 S/U 位；
//   * misa/mimpid/mhartid 的只读语义（带写意图的 CSRRW 产生 cause=2）。
//
// 本测试停留在 M 模式，不经过 cte_init，也不依赖 SBI。

#include <am.h>
#include <klib.h>

#ifndef NPC_HAS_S
#define NPC_HAS_S 0
#endif
#ifndef NPC_HAS_U
#define NPC_HAS_U 0
#endif

static volatile uint32_t trap_count;
static volatile uint32_t last_mcause;
static volatile uint32_t last_mepc;
static volatile uint32_t expected_pc;
static int failures;

static void check_value(const char *name, uint32_t got, uint32_t want) {
  const bool ok = got == want;
  printf("[%s] got=0x%08x want=0x%08x => %s\n", name, (unsigned)got,
         (unsigned)want, ok ? "PASS" : "FAIL");
  if (!ok) {
    ++failures;
  }
}

// 只处理本测试预期的非法指令：记录 mcause/mepc，跳过该 4 字节指令。
extern void trap_entry(void);
__attribute__((naked, aligned(4))) void trap_entry(void) {
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
      "csrr t0, mepc\n\t"
      "addi t0, t0, 4\n\t"
      "csrw mepc, t0\n\t"
      "mret\n\t");
}

#define CHECK_READONLY_WRITE(name, insn_word)                                 \
  do {                                                                        \
    const uint32_t before = trap_count;                                       \
    last_mcause = 0xffffffffu;                                                \
    last_mepc = 0xffffffffu;                                                  \
    asm volatile(                                                             \
        "la t0, 1f\n\t"                                                       \
        "la t1, expected_pc\n\t"                                              \
        "sw t0, 0(t1)\n\t"                                                    \
        "1: .word " insn_word "\n\t"                                          \
        :                                                                     \
        :                                                                     \
        : "t0", "t1", "memory");                                              \
    const bool ok = trap_count == before + 1 && last_mcause == 2 &&           \
                    last_mepc == expected_pc;                                 \
    printf("[%s] traps=%u->%u mcause=%u mepc=0x%08x expected=0x%08x => %s\n", \
           name, (unsigned)before, (unsigned)trap_count,                      \
           (unsigned)last_mcause, (unsigned)last_mepc,                        \
           (unsigned)expected_pc, ok ? "PASS" : "FAIL");                      \
    if (!ok) {                                                                \
      ++failures;                                                             \
    }                                                                         \
  } while (0)

static void test_identity_reads(void) {
  uint32_t got = 0;
  asm volatile("csrr %0, mhartid" : "=r"(got));
  check_value("mhartid-zero", got, 0);
  asm volatile("csrr %0, mimpid" : "=r"(got));
  check_value("mimpid-zero", got, 0);
  asm volatile("csrr %0, mvendorid" : "=r"(got));
  check_value("mvendorid", got, 0x79737978u);
  asm volatile("csrr %0, marchid" : "=r"(got));
  check_value("marchid", got, 0x018d3017u);
}

static void test_misa(void) {
#if __riscv_xlen == 64
  unsigned long misa = 0;
  asm volatile("csrr %0, misa" : "=r"(misa));
  check_value("misa-mxl-rv64", (uint32_t)(misa >> 62), 2);
  const uint32_t low = (uint32_t)misa;
#else
  uint32_t misa = 0;
  asm volatile("csrr %0, misa" : "=r"(misa));
  check_value("misa-mxl-rv32", misa >> 30, 1);
  const uint32_t low = misa;
#endif
  check_value("misa-i", (low >> 8) & 1u, 1);
  check_value("misa-s", (low >> 18) & 1u, NPC_HAS_S);
  check_value("misa-u", (low >> 20) & 1u, NPC_HAS_U);
}

static void test_readonly_traps(void) {
  asm volatile("csrw mtvec, %0" : : "r"(trap_entry) : "memory");
  CHECK_READONLY_WRITE("misa-readonly-write", "0x30101073");
  CHECK_READONLY_WRITE("mimpid-readonly-write", "0xf1301073");
  CHECK_READONLY_WRITE("mhartid-readonly-write", "0xf1401073");
}

int main(void) {
  printf("csr-ident-test: start\n");
  test_identity_reads();
  test_misa();
  test_readonly_traps();
  printf("csr-ident-test: %s\n", failures == 0 ? "PASS" : "FAIL");
  return failures == 0 ? 0 : 1;
}
