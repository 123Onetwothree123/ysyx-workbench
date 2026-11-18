// 拷贝/移动/填充：对64/256/1K/4K/16K/32K字节做memcpy、memmove与memset，
// 覆盖连续load/store、重叠区域以及写缓冲路径。
#include <am.h>
#include <klib.h>
#include <stdint.h>
#include <string.h>

#define CAPACITY (32 * 1024)

static uint8_t source[CAPACITY];
static uint8_t target[CAPACITY];
static volatile uint32_t sink;

static inline uint32_t rdcycle(void)
{
  uint32_t value;
  asm volatile("csrr %0, mcycle" : "=r"(value));
  return value;
}

static uint32_t checksum(const uint8_t *data, uint32_t bytes)
{
  uint32_t sum = 0;
  for (uint32_t index = 0; index < bytes; index++)
  {
    sum = sum * 131u + data[index];
  }
  return sum;
}

static void init_source(uint32_t bytes)
{
  for (uint32_t index = 0; index < bytes; index++)
  {
    source[index] = (uint8_t)(index * 37u + 11u);
  }
}

static int verify_copy(const char *name, uint32_t bytes, uint32_t cycles)
{
  uint32_t expect = checksum(source, bytes);
  uint32_t actual = checksum(target, bytes);
  printf("cache-memcpy-test: %s bytes=%u cycles=%u sum=0x%08x\n",
         name, (unsigned)bytes, (unsigned)cycles, (unsigned)actual);
  sink = actual;
  if (actual != expect)
  {
    printf("cache-memcpy-test: %s FAIL expected=0x%08x\n", name, (unsigned)expect);
    return 1;
  }
  return 0;
}

int main()
{
  static const uint32_t sizes[] = {64u, 256u, 1024u, 4096u, 16384u, 32768u};
  int failures = 0;

  for (unsigned index = 0; index < sizeof(sizes) / sizeof(sizes[0]); index++)
  {
    uint32_t bytes = sizes[index];
    init_source(bytes);

    uint32_t begin = rdcycle();
    memcpy(target, source, bytes);
    uint32_t copied = rdcycle() - begin;
    failures += verify_copy("memcpy", bytes, copied);

    // 重叠memmove：先自拷贝到target，再把target内数据整体后移4字节。
    memcpy(target, source, bytes);
    begin = rdcycle();
    memmove(target + 4, target, bytes - 4);
    uint32_t moved = rdcycle() - begin;
    uint32_t expect = checksum(target + 4, bytes - 4);
    uint32_t actual = checksum(source, bytes - 4);
    printf("cache-memcpy-test: memmove bytes=%u cycles=%u sum=0x%08x\n",
           (unsigned)bytes, (unsigned)moved, (unsigned)actual);
    sink = actual;
    if (actual != expect)
    {
      printf("cache-memcpy-test: memmove FAIL expected=0x%08x\n", (unsigned)expect);
      failures++;
    }

    begin = rdcycle();
    memset(target, 0x5a, bytes);
    uint32_t filled = rdcycle() - begin;
    uint32_t fill_sum = checksum(target, bytes);
    printf("cache-memcpy-test: memset bytes=%u cycles=%u sum=0x%08x\n",
           (unsigned)bytes, (unsigned)filled, (unsigned)fill_sum);
    sink = fill_sum;
  }

  if (failures != 0)
  {
    printf("cache-memcpy-test: FAIL %d\n", failures);
    return 1;
  }

  printf("cache-memcpy-test: PASS\n");
  return 0;
}
