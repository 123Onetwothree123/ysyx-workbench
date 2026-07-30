// wbuf-test: 写缓冲定向测试
// 针对LSU写缓冲+load旁路的每条新路径:
// T1 同字匹配:   sw后立即lw同地址 — 匹配→等排空, 必须读到新值
// T2 部分重叠:   sw一个字后lbu/lhu读其字节 — 匹配按字地址保守判定, 字节值必须对
// T3 超深度写簇: 连续8个store(>4项buffer, 触发等空位), 读回全部校验
// T4 load旁路:   sw后紧跟多次不同字地址lw(纯RAM无匹配) — 旁路上总线, 值必须对
// T5 MMIO门控:   RAM写与MMIO写(UART)混合后读回 — 冒烟验证"在写项全纯RAM才旁路"的门控路径
// T6 halt排空:   最后一笔store后立刻halt — ebreak是IsSideEffect, 会等buffer排空才结束仿真
#include <am.h>
#include <klib.h>

static int failures = 0;
static void check(const char *name, uint32_t got, uint32_t want) {
  bool ok = got == want;
  printf("[%s] got=0x%x want=0x%x => %s\n", name, got, want, ok ? "PASS" : "FAIL");
  if (!ok) failures++;
}

static volatile uint32_t buf[16];

int main() {
  // ---- T1: 同字匹配, sw 后立刻 lw ----
  uint32_t v1;
  buf[0] = 0xdeadbeef;
  asm volatile(
    "li t1, 0x12345678\n"
    "sw t1, 0(%[a])\n"
    "lw %[v], 0(%[a])\n"
    : [v] "=r"(v1)
    : [a] "r"(&buf[0])
    : "t1", "memory");
  check("t1-same-word", v1, 0x12345678);

  // ---- T2: 部分重叠, sw 一个字后按字节/半字读 ----
  uint32_t b0, b1, h1;
  buf[1] = 0;
  asm volatile(
    "li t1, 0x11223344\n"
    "sw t1, 0(%[a])\n"
    "lbu %[b0], 0(%[a])\n"
    "lbu %[b1], 1(%[a])\n"
    "lhu %[h1], 2(%[a])\n"
    : [b0] "=&r"(b0), [b1] "=&r"(b1), [h1] "=&r"(h1)
    : [a] "r"(&buf[1])
    : "t1", "memory");
  check("t2-byte0", b0, 0x44);
  check("t2-byte1", b1, 0x33);
  check("t2-half1", h1, 0x1122);

  // ---- T3: 超深度写簇, 连续8个store(>4项)后逐个读回 ----
  for (int i = 0; i < 8; i++) buf[i] = 0;
  uint32_t *p = (uint32_t *)&buf[0];
  asm volatile(
    "li t1, 0x0abc0000\n"
    ".rept 8\n"
    "sw t1, 0(%[a])\n"
    "addi %[a], %[a], 4\n"
    "addi t1, t1, 1\n"
    ".endr\n"
    : [a] "+r"(p)
    :
    : "t1", "memory");
  for (int i = 0; i < 8; i++) {
    check("t3-cluster", buf[i], 0x0abc0000u + i);
  }

  // ---- T4: load旁路, sw后紧跟多次不同字地址的lw ----
  for (int i = 0; i < 4; i++) buf[8 + i] = 0x5000 + i;
  uint32_t s0, s1, s2, s3;
  asm volatile(
    "li t1, 0xcafe0001\n"
    "sw t1, 0(%[st])\n"
    "lw %[s0], 0(%[ld])\n"
    "lw %[s1], 4(%[ld])\n"
    "lw %[s2], 8(%[ld])\n"
    "lw %[s3], 12(%[ld])\n"
    : [s0] "=&r"(s0), [s1] "=&r"(s1), [s2] "=&r"(s2), [s3] "=&r"(s3)
    : [st] "r"(&buf[0]), [ld] "r"(&buf[8])
    : "t1", "memory");
  check("t4-bypass0", s0, 0x5000);
  check("t4-bypass1", s1, 0x5001);
  check("t4-bypass2", s2, 0x5002);
  check("t4-bypass3", s3, 0x5003);

  // ---- T5: MMIO门控, RAM写与UART写混合后读回(冒烟) ----
  volatile uint8_t *uart = (volatile uint8_t *)0x10000000;
  buf[2] = 0;
  buf[2] = 0x51515151;          // RAM store进buffer
  *uart = 'W';                  // MMIO store进buffer, 之后load不得旁路它
  uint32_t v5 = buf[2];         // 同字匹配, 等排空后必须读到新值
  check("t5-ram-after-mmio", v5, 0x51515151);

  // ---- T6: 最后一笔store后立刻halt, ebreak等buffer排空, 仿真结束时内存一致 ----
  buf[3] = 0x60000000u | (uint32_t)failures;

  printf("wbuf-test %s (failures=%d)\n", failures ? "FAIL" : "PASS", failures);
  halt(failures ? 1 : 0);
  return 0;
}
