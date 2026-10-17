// 工作集扫描：同一份64KB存储区，分别按1KB/4KB/16KB/64KB大小做顺序与伪随机访问，
// 对比命中率随工作集的变化。随机数用xorshift，种子固定，结果可复现。
#include <am.h>
#include <klib.h>
#include <stdint.h>

#define WORDS (16 * 1024) // 64KB
#define ACCESSES (100000u)

static uint32_t arena[WORDS];
static volatile uint32_t sink;

static inline uint32_t rdcycle(void)
{
  uint32_t value;
  asm volatile("csrr %0, mcycle" : "=r"(value));
  return value;
}

static uint32_t xorshift(uint32_t *state)
{
  uint32_t value = *state;
  value ^= value << 13;
  value ^= value >> 17;
  value ^= value << 5;
  *state = value;
  return value;
}

static uint32_t ordered_access(uint32_t mask)
{
  uint32_t sum = 0;
  for (uint32_t index = 0; index < ACCESSES; index++)
  {
    sum += arena[index & mask];
  }
  return sum;
}

static uint32_t random_access(uint32_t mask)
{
  uint32_t state = 0x12345678u;
  uint32_t sum = 0;
  for (uint32_t index = 0; index < ACCESSES; index++)
  {
    sum += arena[xorshift(&state) & mask];
  }
  return sum;
}

static void report(const char *kind, uint32_t working_bytes, uint32_t mask, uint32_t (*access)(uint32_t))
{
  uint32_t begin = rdcycle();
  uint32_t sum = access(mask);
  uint32_t elapsed = rdcycle() - begin;
  printf("cache-randacc-test: %s %uKB sum=0x%08x cycles=%u\n",
         kind, (unsigned)(working_bytes / 1024), (unsigned)sum, (unsigned)elapsed);
  sink = sum;
}

int main()
{
  for (uint32_t index = 0; index < WORDS; index++)
  {
    arena[index] = index * 2246822519u + 374761393u;
  }

  static const uint32_t sizes_bytes[] = {1024u, 4096u, 16384u, 65536u};
  for (unsigned index = 0; index < sizeof(sizes_bytes) / sizeof(sizes_bytes[0]); index++)
  {
    uint32_t words = sizes_bytes[index] / 4u;
    uint32_t mask = words - 1u;
    report("ordered ", sizes_bytes[index], mask, ordered_access);
    report("random  ", sizes_bytes[index], mask, random_access);
  }

  printf("cache-randacc-test: PASS\n");
  return 0;
}
