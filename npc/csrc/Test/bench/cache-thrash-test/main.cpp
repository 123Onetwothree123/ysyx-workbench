// 组冲突压力：对比“同一16B块内”“相邻块”“同一组不同块”“多路同组轮转”
// 以及“恰好放得下的小工作集”几种访问模式的周期数。
#include <am.h>
#include <klib.h>
#include <stdint.h>

#define ARENA_WORDS (8 * 1024)      // 32KB
#define SET_STRIDE_WORDS (512 / 4)  // 组间距=512B=32组×16B

static volatile uint32_t arena[ARENA_WORDS];
static volatile uint32_t sink;

static inline uint32_t rdcycle(void)
{
  uint32_t value;
  asm volatile("csrr %0, mcycle" : "=r"(value));
  return value;
}

// 每轮写两个地址，读回后累加；同块命中/同组冲突的差异直接反映在周期上。
static uint32_t pingpong(uint32_t first, uint32_t second, uint32_t rounds)
{
  for (uint32_t round = 0; round < rounds; round++)
  {
    arena[first] = arena[first] + 1u;
    arena[second] = arena[second] + 2u;
  }
  return arena[first] ^ arena[second];
}

// 按给定偏移序列轮转访问（序列长度决定工作集大小）。
static uint32_t chase(const uint32_t *offsets, uint32_t count, uint32_t rounds)
{
  uint32_t sum = 0;
  uint32_t slot = 0;
  for (uint32_t round = 0; round < rounds; round++)
  {
    uint32_t offset = offsets[slot];
    sum += arena[offset];
    arena[offset] = sum;
    slot++;
    if (slot == count)
    {
      slot = 0;
    }
  }
  return sum;
}

static uint32_t timed_pingpong(const char *name, uint32_t first, uint32_t second, uint32_t rounds)
{
  uint32_t begin = rdcycle();
  uint32_t sum = pingpong(first, second, rounds);
  uint32_t elapsed = rdcycle() - begin;
  printf("cache-thrash-test: %s cycles=%u sum=0x%08x\n", name, (unsigned)elapsed, (unsigned)sum);
  sink = sum;
  return elapsed;
}

static uint32_t timed_chase(const char *name, const uint32_t *offsets, uint32_t count, uint32_t rounds)
{
  uint32_t begin = rdcycle();
  uint32_t sum = chase(offsets, count, rounds);
  uint32_t elapsed = rdcycle() - begin;
  printf("cache-thrash-test: %s cycles=%u sum=0x%08x\n", name, (unsigned)elapsed, (unsigned)sum);
  sink = sum;
  return elapsed;
}

int main()
{
  for (uint32_t index = 0; index < ARENA_WORDS; index++)
  {
    arena[index] = index * 3u;
  }

  const uint32_t rounds = 60000u;

  // 1) 同一16B块里的两个字：第一次以后全部命中。
  uint32_t same_block = timed_pingpong("same-block", 0u, 3u, rounds);

  // 2) 相邻两个块（不同组）：两条行都能常驻，命中。
  uint32_t neighbor = timed_pingpong("neighbor-block", 4u, 8u, rounds);

  // 3) 同一组、相隔512B的两个块：每次访问都互相驱逐。
  uint32_t conflict2 = timed_pingpong("conflict-2set", 0u, SET_STRIDE_WORDS, rounds);

  // 4) 4路同组轮转。
  static const uint32_t four[] = {0u, SET_STRIDE_WORDS, 2u * SET_STRIDE_WORDS, 3u * SET_STRIDE_WORDS};
  uint32_t conflict4 = timed_chase("conflict-4set", four, 4u, rounds);

  // 5) 8路同组轮转。
  static const uint32_t eight[] = {
      0u, SET_STRIDE_WORDS, 2u * SET_STRIDE_WORDS, 3u * SET_STRIDE_WORDS,
      4u * SET_STRIDE_WORDS, 5u * SET_STRIDE_WORDS, 6u * SET_STRIDE_WORDS, 7u * SET_STRIDE_WORDS};
  uint32_t conflict8 = timed_chase("conflict-8set", eight, 8u, rounds);

  // 6) 恰好512B的小工作集：第二遍开始全部命中。
  uint32_t tiny[128];
  for (uint32_t index = 0; index < 128u; index++)
  {
    tiny[index] = index;
  }
  uint32_t tiny_cycles = timed_chase("tiny-512B-set", tiny, 128u, rounds);

  printf("cache-thrash-test: same_block=%u neighbor=%u conflict2=%u conflict4=%u conflict8=%u tiny=%u\n",
         (unsigned)same_block, (unsigned)neighbor, (unsigned)conflict2,
         (unsigned)conflict4, (unsigned)conflict8, (unsigned)tiny_cycles);
  printf("cache-thrash-test: PASS\n");
  return 0;
}
