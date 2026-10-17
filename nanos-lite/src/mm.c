#include <memory.h>

static void *pf = NULL;

void* new_page(size_t nr_page) {
  // return NULL;
  void *ret = pf;                     // 新分配区的起始位置（即旧的位置）
  pf = (char *)pf + nr_page * PGSIZE; // 顺序分配，分配后无需回收
  return ret;
}

#ifdef HAS_VME
static void* pg_alloc(int n) {
  // return NULL;
  assert(n % PGSIZE == 0);   // AM 保证申请的空间是页面大小的整数倍（参数是字节数）
  void *p = new_page(n / PGSIZE);
  memset(p, 0, n);           // 分配的页面需要清零
  return p;
}
#endif

void free_page(void *p) {
  panic("not implement yet");
}

/* The brk() system call handler. */
int mm_brk(uintptr_t brk) {
  return 0;
}

void init_mm() {
  pf = (void *)ROUNDUP(heap.start, PGSIZE);
  Log("free physical pages starting from %p", pf);

#ifdef HAS_VME
  vme_init(pg_alloc, free_page);
#endif
}
