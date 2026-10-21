#include <am.h>
#include <riscv/riscv.h>
#include <klib.h>
#include <klib-macros.h>

/*
 * direct-NPC 的 Sv32 虚存支持。
 *
 * NPC 核已经在 RTL 中实现标准 Sv32（M 模式旁路，S/U 模式经 satp 翻译），
 * 这里只需要按 AM 的 VME 接口建立内核/用户页表。实现与 NEMU 平台保持
 * 一致，物理地址图替换为 direct-NPC 的 PMA：
 *   - CLINT     0x02000000
 *   - UART      0x10000000
 *   - RAM       0x80000000, 64MiB
 *
 * 注意：Nanos-lite 在 init_irq() 里才进入 S 模式，因此 vme_init() 可能在
 * M 模式下先写好 satp；M 模式访问天然旁路翻译，进入 S 模式后立即生效。
 */

typedef uintptr_t PTE;

#ifndef PGSIZE
#define PGSIZE 4096
#endif

#define NPC_RAM_SIZE (64 * 1024 * 1024)

extern char _pmem_start;

static Area segments[] = {
    RANGE(0x02000000, 0x02010000),                       // CLINT
    RANGE(0x10000000, 0x10000004),                       // UART
    RANGE(&_pmem_start, (uintptr_t)&_pmem_start + NPC_RAM_SIZE),
};

#define USER_SPACE RANGE(0x40000000, 0x80000000)

static AddrSpace kas = {};
static void *(*pgalloc_usr)(int) = NULL;
static void (*pgfree_usr)(void *) = NULL;
static int vme_enable = 0;

static inline void flush_tlb(void) {
  asm volatile("sfence.vma zero, zero" ::: "memory");
}

static inline void set_satp(void *pdir) {
  uintptr_t mode = 1ul << (__riscv_xlen - 1);
  asm volatile("csrw satp, %0" : : "r"(mode | ((uintptr_t)pdir >> 12)) : "memory");
  // satp 改变后旧地址空间的 TLB 项必须失效；RTL 在 satp 写入时也会
  // 全量 flush，这里显式执行一次，保证 AM 语义不依赖具体实现细节。
  flush_tlb();
  // Nanos-lite 在切换地址空间前可能刚把用户镜像写入新的物理页。ICache
  // 以物理地址为 tag，物理页复用后必须让指令侧看到最新内容。
  asm volatile("fence.i" ::: "memory");
}

static inline uintptr_t get_satp(void) {
  uintptr_t satp;
  asm volatile("csrr %0, satp" : "=r"(satp));
  return satp << 12;
}

bool vme_init(void *(*pgalloc_f)(int), void (*pgfree_f)(void *)) {
  pgalloc_usr = pgalloc_f;
  pgfree_usr = pgfree_f;

  kas.ptr = pgalloc_f(PGSIZE);
  assert(kas.ptr != NULL);

  for (int i = 0; i < LENGTH(segments); i++) {
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
  PTE *updir = (PTE *)(pgalloc_usr(PGSIZE));
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

  // 只回收用户地址空间对应的二级页表和叶子物理页。页目录中的内核映射
  // 是从内核页目录复制来的，不能在这里释放。
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
    // 同一个地址空间内的普通 syscall/yield 不必重写 satp，避免每次陷入
    // 都触发一次全量 TLB 刷新。
    if ((void *)get_satp() != c->pdir) {
      set_satp(c->pdir);
    }
  }
}

void map(AddrSpace *as, void *va, void *pa, int prot) {
  // Sv32 两级页表：as->ptr 是页目录（第一级页表）的基地址。
  PTE *pgdir = (PTE *)as->ptr;
  uint32_t vpn1 = ((uintptr_t)va >> 22) & 0x3ff;
  uint32_t vpn0 = ((uintptr_t)va >> 12) & 0x3ff;

  PTE *pgtbl;
  if (pgdir[vpn1] & PTE_V) {
    pgtbl = (PTE *)((pgdir[vpn1] >> 10) << 12);
  } else {
    pgtbl = (PTE *)pgalloc_usr(PGSIZE);
    assert(pgtbl != NULL);
    pgdir[vpn1] = (((uintptr_t)pgtbl >> 12) << 10) | PTE_V;
  }

  // NPC 的 PMA/MMU 已负责物理地址合法性；AM 这里不实现 W^X 保护，
  // RWX 统一置 1，A/D 预置 1（当前 profile 使用 AD_FAULT 检查）。
  // 只有用户地址空间中的页设置 U 位，内核页保持 U=0。
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
  c->mstatus = (MODE_U << 8) | MSTATUS_SPIE | MSTATUS_MXR | MSTATUS_SUM;
  c->gpr[2] = (uintptr_t)as->area.end; // 初始用户栈顶
  c->pdir = as->ptr;                   // 用户页目录

  return c;
}
