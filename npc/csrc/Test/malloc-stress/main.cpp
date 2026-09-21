// klib伙伴分配器压力测试: 模拟fceux的大块/混合分配释放模式,压merge/buddy路径
// 每轮: 混合尺寸分配一批->写填充校验->按不同顺序释放->再分配
#include <am.h>
#include <klib.h>

static constexpr int SLOTS = 64;
static uint8_t *ptrs[SLOTS];
static size_t sizes[SLOTS];

static bool is_malloc_aligned(const void *p) {
  return ((uintptr_t)p % alignof(max_align_t)) == 0;
}

static bool fill_check(uint8_t *p, size_t n, uint8_t seed) {
  if (n > 4096) n = 4096;
  for (size_t i = 0; i < n; i++) p[i] = (uint8_t)(seed + i);
  for (size_t i = 0; i < n; i++) {
    if (p[i] != (uint8_t)(seed + i)) {
      printf("payload corrupted at 0x%x+%u: expect %02x got %02x\n",
             (unsigned)(uintptr_t)p, i, (uint8_t)(seed + i), p[i]);
      return false;
    }
  }
  return true;
}

int main() {
  printf("malloc-stress: heap=[0x%x, 0x%x), alignment=%u\n",
         (unsigned)(uintptr_t)heap.start, (unsigned)(uintptr_t)heap.end,
         (unsigned)alignof(max_align_t));
  const size_t pattern[] = {
    32, 64, 100, 4096, 40 * 1024, 1024 * 1024, 64, 256 * 1024,
    128, 512 * 1024, 48, 2048, 64 * 1024, 96, 1024, 8 * 1024,
  };
  bool ok = true;

  // size + 内部块头不得回绕成一个小分配。
  void *overflow = malloc(SIZE_MAX);
  if (overflow != NULL) {
    printf("malloc(SIZE_MAX) unexpectedly succeeded at 0x%x\n",
           (unsigned)(uintptr_t)overflow);
    free(overflow);
    ok = false;
  }

  for (int round = 0; round < 200 && ok; round++) {
    // 1. 按模式分配
    int n = 0;
    for (size_t i = 0; i < sizeof(pattern) / sizeof(pattern[0]); i++) {
      size_t sz = pattern[(i + round) % (sizeof(pattern) / sizeof(pattern[0]))];
      ptrs[n] = (uint8_t *)malloc(sz);
      sizes[n] = sz;
      if (ptrs[n] == NULL) {
        printf("round %d: malloc(%u) returned NULL\n", round, sz);
        ok = false;
        break;
      }
      if (!is_malloc_aligned(ptrs[n])) {
        printf("round %d: malloc(%u) returned misaligned 0x%x\n",
               round, sz, (unsigned)(uintptr_t)ptrs[n]);
        free(ptrs[n]);
        ptrs[n] = NULL;
        ok = false;
        break;
      }
      n++;
      if (n >= SLOTS) break;
    }
    // 2. 填充并校验
    for (int i = 0; i < n && ok; i++) {
      if (ptrs[i]) ok &= fill_check(ptrs[i], sizes[i], (uint8_t)(i * 7 + round));
    }
    // 3. 奇偶交错释放(触发buddy合并)
    for (int i = 0; i < n; i += 2) {
      if (ptrs[i]) { free(ptrs[i]); ptrs[i] = NULL; }
    }
    // 4. 再分配一批小的
    for (int i = 0; i < n && ok; i += 2) {
      ptrs[i] = (uint8_t *)malloc(64 + (size_t)(i * 13 % 3000));
      if (ptrs[i] == NULL || !is_malloc_aligned(ptrs[i])) {
        printf("round %d: replacement malloc failed or was misaligned\n", round);
        ok = false;
        break;
      }
      ok &= fill_check(ptrs[i], 64, (uint8_t)i);
    }
    // 5. 全部释放
    for (int i = 0; i < n; i++) {
      if (ptrs[i]) { free(ptrs[i]); ptrs[i] = NULL; }
    }
    // 6. 每10轮做一次超大块分配(逼merge到高等级)
    if (ok && round % 10 == 9) {
      void *big = malloc(4 * 1024 * 1024);
      printf("round %d: big 4MB malloc -> 0x%x\n", round,
             (unsigned)(uintptr_t)big);
      if (big == NULL) {
        ok = false;
      } else if (!is_malloc_aligned(big)) {
        free(big);
        ok = false;
      } else {
        ok &= fill_check((uint8_t *)big, 4 * 1024 * 1024, (uint8_t)round);
        free(big);
      }
    }
  }
  printf(ok ? "MALLOC STRESS PASS\n" : "MALLOC STRESS FAIL\n");
  halt(ok ? 0 : 1);
  return ok ? 0 : 1;
}
