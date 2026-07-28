// 转发技术定向测试: 各类RAW依赖的正确性(转发后结果必须与串行语义一致)
#include <am.h>
#include <klib.h>

static int failures = 0;
static void check(const char *name, uint32_t got, uint32_t want) {
  bool ok = got == want;
  printf("[%s] got=0x%x want=0x%x => %s\n", name, got, want, ok ? "PASS" : "FAIL");
  if (!ok) failures++;
}

static uint32_t buf[4] = {0x11223344, 0, 0x77, 0};

int main() {
  uint32_t r = 0, v;

  // 1. ALU链: 连续同rd依赖,验证多级同时命中时选最年轻生产者
  v = 1;
  asm volatile(
    "li %0, 2\n\t"
    "add %0, %0, %1\n\t"
    "add %0, %0, %1\n\t"
    "add %0, %0, %1\n\t"
    "add %0, %0, %1\n\t"
    : "+r"(r) : "r"(v));
  check("alu-chain", r, 6);

  // 2. load-use: load后立刻使用
  asm volatile(
    "lw %0, 0(%1)\n\t"
    "addi %0, %0, 1\n\t"
    : "=r"(r) : "r"(&buf[0]));
  check("load-use", r, 0x11223345);

  // 3. jal返回地址立即被使用(snpc旁路): ra应等于紧随jal的la指令自身的地址
  uint32_t ra, la;
  asm volatile(
    "jal %0, 1f\n\t"
    "1: la %1, 1b\n\t"
    : "=r"(ra), "=r"(la));
  check("jal-snpc", ra, la);

  // 4. CSR读出值立即被使用(CSR旁路): mtvec读->写->读
  uint32_t old_mtvec;
  asm volatile("csrrs %0, mtvec, x0\n\t" : "=r"(old_mtvec));
  asm volatile(
    "csrrw x0, mtvec, %1\n\t"
    "csrrs %0, mtvec, x0\n\t"
    : "=r"(r) : "r"(old_mtvec));
  check("csr-fwd", r, old_mtvec);

  // 5. store写数据依赖上一条ALU结果(StoreData旁路)
  buf[1] = 0;
  asm volatile(
    "li %0, 0x55\n\t"
    "add %0, %0, %0\n\t"
    "sw %0, 0(%1)\n\t"
    : "=&r"(r) : "r"(&buf[1]) : "memory");
  check("store-data-fwd", buf[1], 0xaa);

  // 6. 分支条件依赖上一条ALU结果(BranchA/B旁路): 0x42不该等于0x21,应走第二条beq
  v = 0x21;
  asm volatile(
    "add %0, %1, %1\n\t"
    "beq %0, %1, 2f\n\t"
    "li %0, 1\n\t"
    "beq %0, %0, 1f\n\t"
    "2: li %0, 0\n\t"
    "1:\n\t"
    : "+r"(r) : "r"(v));
  check("branch-fwd", r, 1);

  // 7. load-use后立即进分支(load的StatesDone转发给分支)
  v = 0x77;
  asm volatile(
    "lw %0, 0(%2)\n\t"
    "beq %0, %1, 1f\n\t"
    "j 2f\n\t"
    "1: li %0, 1\n\t"
    "2:\n\t"
    : "+r"(r) : "r"(v), "r"(&buf[2]));
  check("load-branch", r, 1);

  printf(failures == 0 ? "HAZARD TEST PASS\n" : "HAZARD TEST FAIL (%d)\n", failures);
  return failures;
}
