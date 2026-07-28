// klib伙伴分配器压力测试: 模拟fceux的大块/混合分配释放模式,压merge/buddy路径
// 每轮: 混合尺寸分配一批->写填充校验->按不同顺序释放->再分配
#include <am.h>
#include <klib.h>

static constexpr int SLOTS = 64;
static uint8_t *ptrs[SLOTS];
static size_t sizes[SLOTS];

static bool fill_check(uint8_t *p, size_t n, uint8_t seed) {
  if (n > 4096) n = 4096;
  for (size_t i = 0; i < n; i++) p[i] = (uint8_t)(seed + i);
  for (size_t i = 0; i < n; i++) {
    if (p[i] != (uint8_t)(seed + i)) {
      printf("payload corrupted at %p+%u: expect %02x got %02x\n", p, i,
             (uint8_t)(seed + i), p[i]);
      return false;
    }
  }
  return true;
}

int main() {
  printf("malloc-stress: heap=[%p, %p)\n", heap.start, heap.end);
  const size_t pattern[] = {
    32, 64, 100, 4096, 40 * 1024, 1024 * 1024, 64, 256 * 1024,
    128, 512 * 1024, 48, 2048, 64 * 1024, 96, 1024, 8 * 1024,
  };
  bool ok = true;
  for (int round = 0; round < 200 && ok; round++) {
    // 1. 按模式分配
    int n = 0;
    for (size_t i = 0; i < sizeof(pattern) / sizeof(pattern[0]); i++) {
      size_t sz = pattern[(i + round) % (sizeof(pattern) / sizeof(pattern[0]))];
      ptrs[n] = (uint8_t *)malloc(sz);
      sizes[n] = sz;
      if (ptrs[n] == NULL) {
        printf("round %d: malloc(%u) returned NULL\n", round, sz);
        // NULL不致命(可能真的没空间),跳过
        sizes[n] = 0;
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
    for (int i = 0; i < n; i += 2) {
      ptrs[i] = (uint8_t *)malloc(64 + (size_t)(i * 13 % 3000));
      if (ptrs[i]) fill_check(ptrs[i], 64, (uint8_t)i);
    }
    // 5. 全部释放
    for (int i = 0; i < n; i++) {
      if (ptrs[i]) { free(ptrs[i]); ptrs[i] = NULL; }
    }
    // 6. 每10轮做一次超大块分配(逼merge到高等级)
    if (round % 10 == 9) {
      void *big = malloc(4 * 1024 * 1024);
      printf("round %d: big 4MB malloc -> %p\n", round, big);
      if (big) free(big);
    }
  }
  printf(ok ? "MALLOC STRESS PASS\n" : "MALLOC STRESS FAIL\n");
  return ok ? 0 : 1;
}
