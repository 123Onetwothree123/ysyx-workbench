// 顺序/跨步/字节扫描一个64KB工作集：覆盖数据缓存的命中、整行回填与组冲突路径。
// 每个阶段用mcycle计时并打印，便于不同RTL配置之间对比。
#include <am.h>
#include <klib.h>
#include <stdint.h>

#define WORDS (16 * 1024) // 64KB工作集
#define BYTES (WORDS * 4)

static uint32_t buf[WORDS];
static volatile uint32_t sink;

static inline uint32_t rdcycle(void)
{
  uint32_t value;
  asm volatile("csrr %0, mcycle" : "=r"(value));
  return value;
}

static uint32_t scan_words(uint32_t step)
{
  uint32_t sum = 0;
  for (uint32_t index = 0; index < WORDS; index += step)
  {
    sum += buf[index];
  }
  return sum;
}

static uint32_t scan_bytes(uint32_t step)
{
  const volatile uint8_t *bytes = (const volatile uint8_t *)buf;
  uint32_t sum = 0;
  for (uint32_t index = 0; index < BYTES; index += step)
  {
    sum += bytes[index];
  }
  return sum;
}

int main()
{
  for (uint32_t index = 0; index < WORDS; index++)
  {
    buf[index] = index * 2654435761u + 12345u;
  }

  printf("cache-scan-test: %uKB working set\n", (unsigned)(BYTES / 1024));

  static const uint32_t word_steps[] = {1u, 2u, 4u, 8u, 16u, 32u, 64u, 128u, 256u, 512u, 1024u};
  for (unsigned step = 0; step < sizeof(word_steps) / sizeof(word_steps[0]); step++)
  {
    uint32_t begin = rdcycle();
    uint32_t sum = scan_words(word_steps[step]);
    uint32_t elapsed = rdcycle() - begin;
    printf("cache-scan-test: word stride=%u sum=0x%08x cycles=%u\n",
           (unsigned)word_steps[step], (unsigned)sum, (unsigned)elapsed);
    sink = sum;
  }

  static const uint32_t byte_steps[] = {1u, 3u, 7u, 16u};
  for (unsigned step = 0; step < sizeof(byte_steps) / sizeof(byte_steps[0]); step++)
  {
    uint32_t begin = rdcycle();
    uint32_t sum = scan_bytes(byte_steps[step]);
    uint32_t elapsed = rdcycle() - begin;
    printf("cache-scan-test: byte stride=%u sum=0x%08x cycles=%u\n",
           (unsigned)byte_steps[step], (unsigned)sum, (unsigned)elapsed);
    sink = sum;
  }

  printf("cache-scan-test: PASS\n");
  return 0;
}
