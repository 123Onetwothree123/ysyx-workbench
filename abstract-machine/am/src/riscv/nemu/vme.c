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
  as->ptr = updir;
  as->area = USER_SPACE;
  as->pgsize = PGSIZE;
  // map kernel space
  memcpy(updir, kas.ptr, PGSIZE);
}

void unprotect(AddrSpace *as) {
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
    pgdir[vpn1] = (((uintptr_t)pgtbl >> 12) << 10) | PTE_V;
  }
  // 填写页表项：物理页号 + 权限位（PA 不实现保护机制，prot 暂不使用，统一给 RWX）
  pgtbl[vpn0] = (((uintptr_t)pa >> 12) << 10) | PTE_V | PTE_R | PTE_W | PTE_X;
}

Context *ucontext(AddrSpace *as, Area kstack, void *entry) {
  return NULL;
}
