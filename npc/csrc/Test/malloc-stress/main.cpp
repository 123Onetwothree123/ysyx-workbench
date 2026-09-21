// klib伙伴分配器压力测试: 模拟fceux的大块/混合分配释放模式,压merge/buddy路径
// 每轮: 混合尺寸分配一批->写填充校验->按不同顺序释放->再分配
#include <am.h>
#include <errno.h>
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
  errno = 0;
  void *overflow = malloc(SIZE_MAX);
  if (overflow != NULL) {
    printf("malloc(SIZE_MAX) unexpectedly succeeded at 0x%x\n",
           (unsigned)(uintptr_t)overflow);
    free(overflow);
    ok = false;
  }
  if (errno != ENOMEM) {
    printf("malloc(SIZE_MAX) did not set ENOMEM\n");
    ok = false;
  }

  uint32_t *zeroed = (uint32_t *)calloc(64, sizeof(uint32_t));
  if (zeroed == NULL) {
    printf("calloc returned NULL\n");
    ok = false;
  } else if (!is_malloc_aligned(zeroed)) {
    printf("calloc returned a misaligned pointer\n");
    free(zeroed);
    ok = false;
  } else {
    for (size_t i = 0; i < 64; i++) {
      if (zeroed[i] != 0) {
        printf("calloc did not zero element %u\n", i);
        ok = false;
        break;
      }
    }
    free(zeroed);
  }

  errno = 0;
  void *calloc_overflow = calloc(SIZE_MAX, 2);
  if (calloc_overflow != NULL || errno != ENOMEM) {
    printf("calloc multiplication overflow was not rejected\n");
    free(calloc_overflow);
    ok = false;
  }
  errno = 0;
  calloc_overflow = calloc(2, SIZE_MAX);
  if (calloc_overflow != NULL || errno != ENOMEM) {
    printf("calloc reversed multiplication overflow was not rejected\n");
    free(calloc_overflow);
    ok = false;
  }

  // C允许零长度calloc返回NULL或一个可释放的唯一指针，两种结果都应安全。
  free(calloc(0, 64));
  free(calloc(64, 0));

  uint8_t *resized = (uint8_t *)malloc(16);
  if (resized == NULL) {
    printf("initial realloc test allocation failed\n");
    ok = false;
  } else {
    for (size_t i = 0; i < 16; i++) resized[i] = (uint8_t)(0x80u + i);
    uint8_t *same = (uint8_t *)realloc(resized, 24);
    if (same == NULL) {
      printf("realloc within the existing capacity failed\n");
      ok = false;
      free(resized);
    } else {
#if !defined(__ISA_NATIVE__)
      if (same != resized) {
        printf("realloc did not reuse a sufficient buddy block\n");
        ok = false;
      }
#endif
      for (size_t i = 0; i < 16; i++) {
        if (same[i] != (uint8_t)(0x80u + i)) ok = false;
      }
      uint8_t *grown = (uint8_t *)realloc(same, 200);
      if (grown == NULL) {
        printf("realloc grow failed\n");
        ok = false;
        free(same);
      } else if (!is_malloc_aligned(grown)) {
        printf("realloc grow returned a misaligned pointer\n");
        ok = false;
        free(grown);
      } else {
        for (size_t i = 0; i < 16; i++) {
          if (grown[i] != (uint8_t)(0x80u + i)) ok = false;
        }
        errno = 0;
        uint8_t *failed_grow = (uint8_t *)realloc(grown, SIZE_MAX);
        if (failed_grow != NULL) {
          printf("oversized realloc unexpectedly succeeded\n");
          free(failed_grow);
          grown = NULL;
          ok = false;
        } else if (errno != ENOMEM) {
          printf("failed realloc did not report ENOMEM\n");
          ok = false;
        }
        if (grown != NULL) {
          for (size_t i = 0; i < 16; i++) {
            if (grown[i] != (uint8_t)(0x80u + i)) ok = false;
          }
          free(grown);
        }
      }
    }
  }

  uint8_t *shrunk_source = (uint8_t *)malloc(4096);
  if (shrunk_source == NULL) {
    printf("realloc shrink test allocation failed\n");
    ok = false;
  } else {
    for (size_t i = 0; i < 32; i++) shrunk_source[i] = (uint8_t)(0x40u + i);
    uint8_t *shrunk = (uint8_t *)realloc(shrunk_source, 32);
    if (shrunk == NULL) {
      printf("realloc shrink failed\n");
      ok = false;
      free(shrunk_source);
    } else {
#if !defined(__ISA_NATIVE__)
      if (shrunk != shrunk_source) {
        printf("realloc shrink did not preserve the allocation\n");
        ok = false;
      }
#endif
      for (size_t i = 0; i < 32; i++) {
        if (shrunk[i] != (uint8_t)(0x40u + i)) ok = false;
      }
      free(shrunk);
    }
  }

  void *from_null = realloc(NULL, 64);
  if (from_null == NULL) {
    printf("realloc(NULL, size) failed\n");
    ok = false;
  } else if (!is_malloc_aligned(from_null)) {
    printf("realloc(NULL, size) returned a misaligned pointer\n");
    free(from_null);
    ok = false;
  } else {
    void *zero_result = realloc(from_null, 0);
    if (zero_result != NULL) {
      printf("realloc(ptr, 0) did not return NULL\n");
      free(zero_result);
      ok = false;
    }
  }

  // realloc(NULL, 0)等价于malloc(0)，NULL或可释放的唯一指针都合法。
  free(realloc(NULL, 0));

  for (int round = 0; round < MALLOC_STRESS_ROUNDS && ok; round++) {
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
