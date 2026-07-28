// 4KiB小规模内存测试: 只测堆起始处4KiB,避开栈区(原版mem-test测整个堆会压掉自己的栈)
// 按u8/u16/u32/u64四种宽度做"写入地址模式再读回校验"
#include <am.h>
#include <klib.h>

static constexpr uint32_t TEST_LEN = 4096;

template <typename T>
static bool test_width(uintptr_t begin, uint32_t length, const char *name) {
  volatile T *mem = reinterpret_cast<volatile T *>(begin);
  uint32_t count = length / sizeof(T);
  for (uint32_t i = 0; i < count; i++) {
    mem[i] = static_cast<T>(begin + i * sizeof(T));
  }
  for (uint32_t i = 0; i < count; i++) {
    T expected = static_cast<T>(begin + i * sizeof(T));
    T actual = mem[i];
    if (actual != expected) {
      printf("[%s] FAIL at 0x%08x: expect 0x%x, got 0x%x\n", name,
             (unsigned)(begin + i * sizeof(T)), (unsigned)expected, (unsigned)actual);
      return false;
    }
  }
  printf("[%s] PASS (%u bytes)\n", name, (unsigned)(count * sizeof(T)));
  return true;
}

int main() {
  uintptr_t begin = reinterpret_cast<uintptr_t>(heap.start);
  printf("mem-test-4k: begin=0x%08x len=0x%x\n", (unsigned)begin, (unsigned)TEST_LEN);
  bool ok = true;
  ok &= test_width<uint8_t>(begin, TEST_LEN, "u8");
  ok &= test_width<uint16_t>(begin, TEST_LEN, "u16");
  ok &= test_width<uint32_t>(begin, TEST_LEN, "u32");
  ok &= test_width<uint64_t>(begin, TEST_LEN, "u64");
  printf(ok ? "MEM TEST 4K PASS\n" : "MEM TEST 4K FAIL\n");
  return ok ? 0 : 1;
}
