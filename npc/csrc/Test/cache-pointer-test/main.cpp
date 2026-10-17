// 指针追逐：把4096个节点(32KB)按伪随机顺序串成环，逐节点跟随next指针；
// 与一个只有32个节点(256B，放得进缓存)的小环做对比。负载依赖+缺失延迟敏感。
#include <am.h>
#include <klib.h>
#include <stdint.h>

#define NODES 4096
#define SMALL_NODES 32
#define PASSES 8

static uint32_t next[NODES];
static uint32_t payload[NODES];
static uint32_t small_next[SMALL_NODES];
static uint32_t small_payload[SMALL_NODES];
static volatile uint32_t sink;

static inline uint32_t rdcycle(void)
{
  uint32_t value;
  asm volatile("csrr %0, mcycle" : "=r"(value));
  return value;
}

static uint32_t next_random(uint32_t *state)
{
  *state = *state * 1664525u + 1013904223u;
  return *state >> 8;
}

int main()
{
  // 用Fisher-Yates洗牌生成一条遍历所有节点的随机次序环。
  static uint32_t order[NODES];
  for (uint32_t index = 0; index < NODES; index++)
  {
    order[index] = index;
    payload[index] = index * 2654435761u + 12345u;
  }
  uint32_t state = 0x9e3779b9u;
  for (uint32_t index = NODES - 1; index > 0; index--)
  {
    uint32_t swap = next_random(&state) % (index + 1);
    uint32_t keep = order[index];
    order[index] = order[swap];
    order[swap] = keep;
  }
  for (uint32_t index = 0; index < NODES; index++)
  {
    next[order[index]] = order[(index + 1) % NODES];
  }

  // 小环：连续32个节点，工作集256B。
  for (uint32_t index = 0; index < SMALL_NODES; index++)
  {
    small_next[index] = (index + 1) % SMALL_NODES;
    small_payload[index] = index * 2246822519u + 7u;
  }

  uint32_t begin = rdcycle();
  uint32_t node = 0;
  uint32_t sum = 0;
  for (uint32_t step = 0; step < NODES * PASSES; step++)
  {
    sum += payload[node];
    node = next[node];
  }
  uint32_t large_cycles = rdcycle() - begin;

  begin = rdcycle();
  node = 0;
  uint32_t small_sum = 0;
  for (uint32_t step = 0; step < NODES * PASSES; step++)
  {
    small_sum += small_payload[node];
    node = small_next[node];
  }
  uint32_t small_cycles = rdcycle() - begin;

  printf("cache-pointer-test: 32KB chain cycles=%u sum=0x%08x\n", (unsigned)large_cycles, (unsigned)sum);
  printf("cache-pointer-test: 256B chain cycles=%u sum=0x%08x\n", (unsigned)small_cycles, (unsigned)small_sum);
  sink = sum ^ small_sum;

  if (node != 0)
  {
    printf("cache-pointer-test: FAIL chain broken\n");
    return 1;
  }

  printf("cache-pointer-test: PASS\n");
  return 0;
}
