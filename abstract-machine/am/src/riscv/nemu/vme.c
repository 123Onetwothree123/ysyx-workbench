#include <am.h>
#include <nemu.h>
#include <klib.h>

static AddrSpace kas = {};
static void* (*pgalloc_usr)(int) = NULL;
static void (*pgfree_usr)(void*) = NULL;
static int vme_enable = 0;

static Area segments[] = {      // Kernel memory mappings
  NEMU_PADDR_SPACE
};

#define USER_SPACE RANGE(0x40000000, 0x80000000)

static inline void set_satp(void *pdir) {
  uintptr_t mode = 1ul << (__riscv_xlen - 1);
  asm volatile("csrw satp, %0" : : "r"(mode | ((uintptr_t)pdir >> 12)));
}

static inline uintptr_t get_satp() {
  uintptr_t satp;
  asm volatile("csrr %0, satp" : "=r"(satp));
  return satp << 12;
}

bool vme_init(void* (*pgalloc_f)(int), void (*pgfree_f)(void*)) {
  pgalloc_usr = pgalloc_f;
  pgfree_usr = pgfree_f;

  kas.ptr = pgalloc_f(PGSIZE);

  int i;
  for (i = 0; i < LENGTH(segments); i ++) {
    void *va = segments[i].start;
    for (; va < segments[i].end; va += PGSIZE) {
      map(&kas, va, va, 0);
    }
  }

  set_satp(kas.ptr);
  vme_enable = 1;

  return true;
}

void protect(AddrSpace *as) {
  PTE *updir = (PTE*)(pgalloc_usr(PGSIZE));
  assert(updir != NULL);
  as->ptr = updir;
  as->area = USER_SPACE;
  as->pgsize = PGSIZE;
  // 只在用户地址空间的页目录中复制内核映射，不复制内核页表的所有权。
  memcpy(updir, kas.ptr, PGSIZE);
}

void unprotect(AddrSpace *as) {
  if (as == NULL || as->ptr == NULL) {
    return;
  }
  assert(pgfree_usr != NULL);

  PTE *pgdir = (PTE *)as->ptr;
  uintptr_t user_start = (uintptr_t)as->area.start;
  uintptr_t user_end = (uintptr_t)as->area.end;
  if (user_end <= user_start) {
    pgfree_usr(pgdir);
    as->ptr = NULL;
    return;
  }

  uint32_t first = (uint32_t)(user_start >> 22);
  uint32_t last = (uint32_t)((user_end - 1) >> 22);

  // 只回收用户地址空间对应的二级页表和叶子物理页。
  // 页目录中的内核映射是从内核页目录复制来的，不能在这里释放。
  for (uint32_t vpn1 = first; vpn1 <= last; vpn1++) {
    PTE pte1 = pgdir[vpn1];
    if ((pte1 & PTE_V) == 0) {
      continue;
    }

    assert((pte1 & (PTE_R | PTE_W | PTE_X)) == 0);
    PTE *pgtbl = (PTE *)((pte1 >> 10) << 12);
    for (int vpn0 = 0; vpn0 < 1024; vpn0++) {
      PTE pte0 = pgtbl[vpn0];
      if (pte0 & PTE_V) {
        pgfree_usr((void *)((pte0 >> 10) << 12));
      }
    }

    pgfree_usr(pgtbl);
    pgdir[vpn1] = 0;
  }

  pgfree_usr(pgdir);
  as->ptr = NULL;
}

void __am_switch_to_kernel(void) {
  // 释放当前用户地址空间前，先回到内核页目录，避免 satp 仍指向待释放的页表。
  if (vme_enable) {
    set_satp(kas.ptr);
  }
}

void __am_get_cur_as(Context *c) {
  c->pdir = (vme_enable ? (void *)get_satp() : NULL);
}

void __am_switch(Context *c) {
  if (vme_enable && c->pdir != NULL) {
    set_satp(c->pdir);
  }
}

void map(AddrSpace *as, void *va, void *pa, int prot) {
  // Sv32 两级页表：as->ptr 是页目录（第一级页表）的基地址
  uintptr_t *pgdir = (uintptr_t *)as->ptr;
  uint32_t vpn1 = ((uintptr_t)va >> 22) & 0x3ff; // 虚拟地址的 vpn[1]
  uint32_t vpn0 = ((uintptr_t)va >> 12) & 0x3ff; // 虚拟地址的 vpn[0]

  uintptr_t *pgtbl;
  if (pgdir[vpn1] & PTE_V) {
    // 第二级页表已存在：从页目录项中取出它的物理页号
    pgtbl = (uintptr_t *)((pgdir[vpn1] >> 10) << 12);
  } else {
    // 申请一页空闲的物理页作为新的页表，并把它的物理页号记录到页目录项中
    pgtbl = (uintptr_t *)pgalloc_usr(PGSIZE);
    assert(pgtbl != NULL);
    pgdir[vpn1] = (((uintptr_t)pgtbl >> 12) << 10) | PTE_V;
  }
  // 填写页表项：物理页号 + 权限位。PA 不实现保护机制，RWX 统一置 1；
  // A/D 置 1 方便 DiffTest。只有用户地址空间中的页设置 U 位，内核页保持 U=0。
  uintptr_t is_user = (uintptr_t)va >= (uintptr_t)as->area.start &&
                      (uintptr_t)va < (uintptr_t)as->area.end;
  uintptr_t flags = PTE_V | PTE_R | PTE_W | PTE_X | PTE_A | PTE_D;
  if (is_user) {
    flags |= PTE_U;
  }
  pgtbl[vpn0] = (((uintptr_t)pa >> 12) << 10) | flags;
}

void *unmap(AddrSpace *as, void *va) {
  assert(as != NULL && as->ptr != NULL);

  PTE *pgdir = (PTE *)as->ptr;
  uint32_t vpn1 = ((uintptr_t)va >> 22) & 0x3ff;
  uint32_t vpn0 = ((uintptr_t)va >> 12) & 0x3ff;

  PTE pte1 = pgdir[vpn1];
  if ((pte1 & PTE_V) == 0) {
    return NULL;
  }
  assert((pte1 & (PTE_R | PTE_W | PTE_X)) == 0);

  PTE *pgtbl = (PTE *)((pte1 >> 10) << 12);
  PTE pte0 = pgtbl[vpn0];
  if ((pte0 & PTE_V) == 0) {
    return NULL;
  }

  pgtbl[vpn0] = 0;

  // 如果这个二级页表已经没有任何有效映射，就把页表页也回收掉。
  bool empty = true;
  for (int i = 0; i < 1024; i++) {
    if (pgtbl[i] & PTE_V) {
      empty = false;
      break;
    }
  }
  if (empty) {
    pgfree_usr(pgtbl);
    pgdir[vpn1] = 0;
  }

  return (void *)((pte0 >> 10) << 12);
}

Context *ucontext(AddrSpace *as, Area kstack, void *entry) {
  assert(as != NULL && as->ptr != NULL);

  Context *c = (Context *)((char *)kstack.end - sizeof(Context));
  memset(c, 0, sizeof(Context));

  c->mepc = (uintptr_t)entry;
  c->mstatus = (MODE_U << 11) | MSTATUS_MXR | MSTATUS_SUM;
  c->gpr[2] = (uintptr_t)as->area.end; // 初始用户栈顶
  c->pdir = as->ptr;               // 用户页目录

  return c;
}
