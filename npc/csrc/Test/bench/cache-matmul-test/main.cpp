// 矩阵乘法：48x48的整数矩阵（3个9KB矩阵超过数据缓存容量），
// 用两种循环顺序（i-k-j 与 i-j-k）做同一个乘法，比较周期数并校验结果一致。
#include <am.h>
#include <klib.h>
#include <stdint.h>

#define N 48
#define ELEMENTS (N * N)

static uint32_t lhs[N][N];
static uint32_t rhs[N][N];
static uint32_t out[N][N];
static volatile uint32_t sink;

static inline uint32_t rdcycle(void)
{
  uint32_t value;
  asm volatile("csrr %0, mcycle" : "=r"(value));
  return value;
}

static uint32_t checksum(void)
{
  uint32_t sum = 0;
  for (uint32_t row = 0; row < N; row++)
  {
    for (uint32_t column = 0; column < N; column++)
    {
      sum = sum * 31u + out[row][column];
    }
  }
  return sum;
}

// i-k-j顺序：对out的一行连续累加，空间局部性好。
static void multiply_rows(void)
{
  for (uint32_t row = 0; row < N; row++)
  {
    for (uint32_t column = 0; column < N; column++)
    {
      out[row][column] = 0;
    }
    for (uint32_t inner = 0; inner < N; inner++)
    {
      uint32_t factor = lhs[row][inner];
      for (uint32_t column = 0; column < N; column++)
      {
        out[row][column] += factor * rhs[inner][column];
      }
    }
  }
}

// i-j-k顺序：对rhs按列遍历，测试不同访问模式。
static void multiply_columns(void)
{
  for (uint32_t row = 0; row < N; row++)
  {
    for (uint32_t column = 0; column < N; column++)
    {
      uint32_t sum = 0;
      for (uint32_t inner = 0; inner < N; inner++)
      {
        sum += lhs[row][inner] * rhs[inner][column];
      }
      out[row][column] = sum;
    }
  }
}

int main()
{
  for (uint32_t row = 0; row < N; row++)
  {
    for (uint32_t column = 0; column < N; column++)
    {
      lhs[row][column] = (row * 31u + column * 17u + 7u) & 0xffu;
      rhs[row][column] = (row * 13u + column * 29u + 3u) & 0xffu;
    }
  }

  printf("cache-matmul-test: %ux%u (%uKB per matrix)\n",
         (unsigned)N, (unsigned)N, (unsigned)(ELEMENTS * 4 / 1024));

  uint32_t begin = rdcycle();
  multiply_rows();
  uint32_t row_cycles = rdcycle() - begin;
  uint32_t row_sum = checksum();

  begin = rdcycle();
  multiply_columns();
  uint32_t column_cycles = rdcycle() - begin;
  uint32_t column_sum = checksum();

  printf("cache-matmul-test: i-k-j cycles=%u checksum=0x%08x\n", (unsigned)row_cycles, (unsigned)row_sum);
  printf("cache-matmul-test: i-j-k cycles=%u checksum=0x%08x\n", (unsigned)column_cycles, (unsigned)column_sum);
  sink = row_sum ^ column_sum;

  if (row_sum != column_sum)
  {
    printf("cache-matmul-test: FAIL checksum mismatch\n");
    return 1;
  }

  printf("cache-matmul-test: PASS\n");
  return 0;
}
