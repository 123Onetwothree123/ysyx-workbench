#include <am.h>
#include <riscv/riscv.h>
#include <klib.h>
#include <klib-macros.h>

/*
 * direct-NPC 的 VME 实现。
 *
 * RV32 使用标准 Sv32（2 级、10+10 位 VPN、4B PTE、satp.MODE=1）；
 * RV64 使用标准 Sv39（3 级、9+9+9 位 VPN、8B PTE、satp.MODE=8）。
 * NPC 核在 M 模式旁路翻译，S/U 模式经 satp 翻译。这里按 AM 的 VME
 * 接口建立内核/用户页表，物理地址图与 direct-NPC 的 PMA 一致：
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

#if __riscv_xlen == 64
#define VME_LEVELS     3
#define VME_VPN_BITS   9
#define VME_MODE       8          // Sv39
#define VME_PPN_MASK   ((((uintptr_t)1) << 44) - 1)
#else
#define VME_LEVELS     2
#define VME_VPN_BITS   10
#define VME_MODE       1          // Sv32
#define VME_PPN_MASK   ((((uintptr_t)1) << 22) - 1)
#endif

#define VME_ENTRIES    (1u << VME_VPN_BITS)
#define VME_INDEX_MASK (VME_ENTRIES - 1)
#define VME_PAGE_SHIFT 12

static AddrSpace kas = {};
static void *(*pgalloc_usr)(int) = NULL;
static void (*pgfree_usr)(void *) = NULL;
static int vme_enable = 0;

static inline void flush_tlb(void) {
  asm volatile("sfence.vma zero, zero" ::: "memory");
}

static inline uintptr_t pte_ppn(PTE pte) {
  return ((pte >> 10) & VME_PPN_MASK) << VME_PAGE_SHIFT;
}

static inline PTE make_pte(uintptr_t pa, uintptr_t flags) {
  return (((pa >> VME_PAGE_SHIFT) & VME_PPN_MASK) << 10) | flags;
}

static inline void set_satp(void *pdir) {
  uintptr_t mode = (uintptr_t)VME_MODE << (__riscv_xlen - (__riscv_xlen == 64 ? 4 : 1));
  asm volatile("csrw satp, %0" : : "r"(mode | (((uintptr_t)pdir >> 12) & VME_PPN_MASK)) : "memory");
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
  return (satp & VME_PPN_MASK) << VME_PAGE_SHIFT;
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

// 递归释放 [lo, hi) 覆盖的用户页表子树。内核共享的子树不会进入这个
// 范围，因此这里可以安全地释放中间页表和叶子物理页。
static void free_user_subtree(PTE *table, int level, uintptr_t base,
                              uintptr_t lo, uintptr_t hi) {
  uintptr_t span = (uintptr_t)1 << (VME_PAGE_SHIFT + level * VME_VPN_BITS);
  for (uint32_t i = 0; i < VME_ENTRIES; i++) {
    uintptr_t start = base + (uintptr_t)i * span;
    uintptr_t end = start + span;
    if (end <= lo || start >= hi) {
      continue;
    }
    PTE pte = table[i];
    if ((pte & PTE_V) == 0) {
      continue;
    }
    if (pte & (PTE_R | PTE_W | PTE_X)) {
      // 叶子映射：释放数据页。
      pgfree_usr((void *)pte_ppn(pte));
    } else if (level > 0) {
      PTE *child = (PTE *)pte_ppn(pte);
      free_user_subtree(child, level - 1, start, lo, hi);
      pgfree_usr(child);
    }
    table[i] = 0;
  }
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

  // 只回收用户地址空间对应的子树。页目录中的内核映射是从内核页目录
  // 复制来的，不能在这里释放。
  free_user_subtree(pgdir, VME_LEVELS - 1, 0, user_start, user_end);
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
  PTE *table = (PTE *)as->ptr;
  uintptr_t vaddr = (uintptr_t)va;

  // 从根页表逐级走到叶子；不存在的中间页表按需分配并清零。
  for (int level = VME_LEVELS - 1; level > 0; level--) {
    uint32_t index = (vaddr >> (VME_PAGE_SHIFT + level * VME_VPN_BITS)) &
                     VME_INDEX_MASK;
    if (table[index] & PTE_V) {
      assert((table[index] & (PTE_R | PTE_W | PTE_X)) == 0);
      table = (PTE *)pte_ppn(table[index]);
    } else {
      PTE *next = (PTE *)pgalloc_usr(PGSIZE);
      assert(next != NULL);
      memset(next, 0, PGSIZE);
      table[index] = make_pte((uintptr_t)next, PTE_V);
      table = next;
    }
  }

  uint32_t vpn0 = (vaddr >> VME_PAGE_SHIFT) & VME_INDEX_MASK;

  // NPC 的 PMA/MMU 已负责物理地址合法性；AM 这里不实现 W^X 保护，
  // RWX 统一置 1，A/D 预置 1（当前 profile 使用 AD_FAULT 检查）。
  // 只有用户地址空间中的页设置 U 位，内核页保持 U=0。
  uintptr_t is_user = vaddr >= (uintptr_t)as->area.start &&
                      vaddr < (uintptr_t)as->area.end;
  uintptr_t flags = PTE_V | PTE_R | PTE_W | PTE_X | PTE_A | PTE_D;
  if (is_user) {
    flags |= PTE_U;
  }
  table[vpn0] = make_pte((uintptr_t)pa, flags);
}

void *unmap(AddrSpace *as, void *va) {
  assert(as != NULL && as->ptr != NULL);

  PTE *tables[VME_LEVELS];
  uint32_t indices[VME_LEVELS];
  uintptr_t vaddr = (uintptr_t)va;
  PTE *table = (PTE *)as->ptr;

  for (int level = VME_LEVELS - 1; level >= 0; level--) {
    uint32_t index = (vaddr >> (VME_PAGE_SHIFT + level * VME_VPN_BITS)) &
                     VME_INDEX_MASK;
    tables[level] = table;
    indices[level] = index;
    PTE pte = table[index];
    if ((pte & PTE_V) == 0) {
      return NULL;
    }
    if (level > 0) {
      assert((pte & (PTE_R | PTE_W | PTE_X)) == 0);
      table = (PTE *)pte_ppn(pte);
    } else {
      void *pa = (void *)pte_ppn(pte);
      table[index] = 0;

      // 自底向上回收已经变空的中间页表。
      for (int l = 0; l < VME_LEVELS - 1; l++) {
        bool empty = true;
        PTE *current = tables[l] == NULL ? NULL : tables[l];
        if (current == NULL) {
          break;
        }
        for (uint32_t i = 0; i < VME_ENTRIES; i++) {
          if (current[i] & PTE_V) {
            empty = false;
            break;
          }
        }
        if (!empty) {
          break;
        }
        pgfree_usr(current);
        tables[l + 1][indices[l + 1]] = 0;
      }
      return pa;
    }
  }
  return NULL;
}

Context *ucontext(AddrSpace *as, Area kstack, void *entry) {
  assert(as != NULL && as->ptr != NULL);

  Context *c = (Context *)((char *)kstack.end - sizeof(Context));
  memset(c, 0, sizeof(Context));

  c->mepc = (uintptr_t)entry;
  // SPP=U，sret 后开中断；RV64 还需要 UXL/SXL=2（64 位用户/监督态）。
  c->mstatus = (MODE_U << 8) | MSTATUS_SPIE | MSTATUS_MXR | MSTATUS_SUM |
               MSTATUS_SXL | MSTATUS_UXL | MSTATUS_FS_INIT;
  c->gpr[2] = (uintptr_t)as->area.end; // 初始用户栈顶
  c->pdir = as->ptr;                   // 用户页目录

  return c;
}
