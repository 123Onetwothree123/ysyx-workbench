// 指令缓存压力：64个分散的小函数（代码足迹约2KB以上，远超512B I$）按表依次调用，
// 与一个可以常驻I$的热点小函数做对比。函数指针表本身也会被数据缓存访问。
#include <am.h>
#include <klib.h>
#include <stdint.h>

typedef uint32_t (*function_t)(uint32_t);

#define MAKE_FUNCTION(id) \
  __attribute__((noinline)) static uint32_t func##id(uint32_t value) \
  { \
    return ((value * 2654435761u) ^ (0x9e3779b9u + (uint32_t)(id)*40503u)) + \
           (value >> ((uint32_t)((id) % 13) + 1u)); \
  }

MAKE_FUNCTION(0) MAKE_FUNCTION(1) MAKE_FUNCTION(2) MAKE_FUNCTION(3)
MAKE_FUNCTION(4) MAKE_FUNCTION(5) MAKE_FUNCTION(6) MAKE_FUNCTION(7)
MAKE_FUNCTION(8) MAKE_FUNCTION(9) MAKE_FUNCTION(10) MAKE_FUNCTION(11)
MAKE_FUNCTION(12) MAKE_FUNCTION(13) MAKE_FUNCTION(14) MAKE_FUNCTION(15)
MAKE_FUNCTION(16) MAKE_FUNCTION(17) MAKE_FUNCTION(18) MAKE_FUNCTION(19)
MAKE_FUNCTION(20) MAKE_FUNCTION(21) MAKE_FUNCTION(22) MAKE_FUNCTION(23)
MAKE_FUNCTION(24) MAKE_FUNCTION(25) MAKE_FUNCTION(26) MAKE_FUNCTION(27)
MAKE_FUNCTION(28) MAKE_FUNCTION(29) MAKE_FUNCTION(30) MAKE_FUNCTION(31)
MAKE_FUNCTION(32) MAKE_FUNCTION(33) MAKE_FUNCTION(34) MAKE_FUNCTION(35)
MAKE_FUNCTION(36) MAKE_FUNCTION(37) MAKE_FUNCTION(38) MAKE_FUNCTION(39)
MAKE_FUNCTION(40) MAKE_FUNCTION(41) MAKE_FUNCTION(42) MAKE_FUNCTION(43)
MAKE_FUNCTION(44) MAKE_FUNCTION(45) MAKE_FUNCTION(46) MAKE_FUNCTION(47)
MAKE_FUNCTION(48) MAKE_FUNCTION(49) MAKE_FUNCTION(50) MAKE_FUNCTION(51)
MAKE_FUNCTION(52) MAKE_FUNCTION(53) MAKE_FUNCTION(54) MAKE_FUNCTION(55)
MAKE_FUNCTION(56) MAKE_FUNCTION(57) MAKE_FUNCTION(58) MAKE_FUNCTION(59)
MAKE_FUNCTION(60) MAKE_FUNCTION(61) MAKE_FUNCTION(62) MAKE_FUNCTION(63)

static const function_t spread[] = {
    func0,  func1,  func2,  func3,  func4,  func5,  func6,  func7,
    func8,  func9,  func10, func11, func12, func13, func14, func15,
    func16, func17, func18, func19, func20, func21, func22, func23,
    func24, func25, func26, func27, func28, func29, func30, func31,
    func32, func33, func34, func35, func36, func37, func38, func39,
    func40, func41, func42, func43, func44, func45, func46, func47,
    func48, func49, func50, func51, func52, func53, func54, func55,
    func56, func57, func58, func59, func60, func61, func62, func63};

static volatile uint32_t sink;

static inline uint32_t rdcycle(void)
{
  uint32_t value;
  asm volatile("csrr %0, mcycle" : "=r"(value));
  return value;
}

// 小循环体，代码完全装得进I$。
__attribute__((noinline)) static uint32_t hot(uint32_t value)
{
  for (uint32_t index = 0; index < 6; index++)
  {
    value = value * 1103515245u + 12345u;
  }
  return value;
}

int main()
{
  uint32_t state = 0x12345678u;

  uint32_t begin = rdcycle();
  for (uint32_t round = 0; round < 20000u; round++)
  {
    state = hot(state);
  }
  uint32_t hot_cycles = rdcycle() - begin;

  begin = rdcycle();
  for (uint32_t round = 0; round < 512u; round++)
  {
    for (unsigned index = 0; index < sizeof(spread) / sizeof(spread[0]); index++)
    {
      state = spread[index](state);
    }
  }
  uint32_t spread_cycles = rdcycle() - begin;

  printf("cache-icache-test: hot cycles=%u\n", (unsigned)hot_cycles);
  printf("cache-icache-test: spread cycles=%u sum=0x%08x\n", (unsigned)spread_cycles, (unsigned)state);
  sink = state;

  printf("cache-icache-test: PASS\n");
  return 0;
}
