#include <memory.h>
#include <proc.h>

static void *pf = NULL;

// 被释放的物理页通过页首指针串成空闲链表，供后续分配复用。
typedef struct FreePage {
  struct FreePage *next;
} FreePage;

static FreePage *free_list = NULL;

void* new_page(size_t nr_page) {
  if (nr_page == 0) {
    return NULL;
  }

  // 页表申请通常只需要一页，优先复用已释放的页面。
  if (nr_page == 1 && free_list != NULL) {
    FreePage *p = free_list;
    free_list = p->next;
    return p;
  }

  if (pf == NULL ||
      (char *)pf + nr_page * PGSIZE > (char *)heap.end) {
    return NULL;
  }

  void *ret = pf;                     // 新分配区的起始位置（即旧的位置）
  pf = (char *)pf + nr_page * PGSIZE; // 没有空闲页时按顺序分配
  return ret;
}

#ifdef HAS_VME
static void* pg_alloc(int n) {
  // return NULL;
  assert(n % PGSIZE == 0);   // AM 保证申请的空间是页面大小的整数倍（参数是字节数）
  void *p = new_page(n / PGSIZE);
  assert(p != NULL);
  memset(p, 0, n);           // 分配的页面需要清零
  return p;
}
#endif

void free_page(void *p) {
  if (p == NULL) {
    return;
  }
  assert(((uintptr_t)p & (PGSIZE - 1)) == 0);

  FreePage *page = (FreePage *)p;
  page->next = free_list;
  free_list = page;
}

/* brk() 系统调用处理函数。 */
int mm_brk(uintptr_t brk) {
  PCB *p = current;
  if (p == NULL || p->as.ptr == NULL) {
    return -1;
  }

  uintptr_t user_start = (uintptr_t)p->as.area.start;
  uintptr_t stack_low = (uintptr_t)p->as.area.end - STACK_SIZE;

  if (brk < user_start || brk > stack_low) {
    return -1;
  }

  if (brk == p->brk) {
    return 0;
  }

  if (brk > p->brk) {
    // 只映射超过 max_brk 的新页面，避免重复分配。
    uintptr_t start = ROUNDUP(p->max_brk, PGSIZE);
    uintptr_t end = ROUNDUP(brk, PGSIZE);

    for (uintptr_t va = start; va < end; va += PGSIZE) {
      void *pa = new_page(1);
      if (pa == NULL) {
        return -1;
      }
      memset(pa, 0, PGSIZE);
      map(&p->as, (void *)va, pa, 0);
      p->max_brk = va + PGSIZE;
    }

    p->max_brk = brk > p->brk_min ? brk : p->brk_min;
    p->brk = brk;
    return 0;
  }

  // brk 缩小时回收整页的堆空间；包含 brk 的最后半页保留。
  // brk_min 以下的页面属于程序装入区域，不能释放。
  uintptr_t free_start = ROUNDUP(brk, PGSIZE);
  uintptr_t protect_end = ROUNDUP(p->brk_min, PGSIZE);
  if (free_start < protect_end) {
    free_start = protect_end;
  }
  uintptr_t free_end = ROUNDUP(p->max_brk, PGSIZE);
  for (uintptr_t va = free_start; va < free_end; va += PGSIZE) {
    void *pa = unmap(&p->as, (void *)va);
    if (pa != NULL) {
      free_page(pa);
    }
  }

  p->max_brk = brk > p->brk_min ? brk : p->brk_min;
  p->brk = brk;
  return 0;
}

void init_mm() {
  pf = (void *)ROUNDUP(heap.start, PGSIZE);

#ifdef HAS_VME
  vme_init(pg_alloc, free_page);
#endif
}
