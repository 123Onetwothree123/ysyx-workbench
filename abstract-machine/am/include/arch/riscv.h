#ifndef ARCH_H__
#define ARCH_H__

#ifdef __riscv_e
#define NR_REGS 16
#else
#define NR_REGS 32
#endif

#include <stdint.h>
#include <stddef.h>

struct Context
{
  union
  {
    uintptr_t gpr[NR_REGS];
    struct
    {
      // PA3 讲义指出：riscv32 将地址空间指针与 x0 共用存储空间。
      // trap.S 不保存/恢复 x0，因此该槽位在上下文切换后仍然保留。
      void *pdir;
      uintptr_t gpr_rest[NR_REGS - 1];
    };
  };
#ifdef AM_RV64GC
  // RV64GC 目标额外保存完整的浮点上下文。f0 也是普通浮点寄存器，
  // 必须逐项保存；fcsr 包含 fflags/frm。IM/lp64 目标不包含这两个字段，
  // 布局与旧目标保持完全一致。
  uint64_t fpr[32];
  uint64_t fcsr;
#endif
  uintptr_t mcause, mstatus, mepc;
};

#ifdef AM_RV64GC
#define CONTEXT_FPR_BYTES (32 * sizeof(uint64_t))
#define CONTEXT_FP_BYTES  (CONTEXT_FPR_BYTES + sizeof(uint64_t))
_Static_assert(sizeof(struct Context) ==
                   NR_REGS * sizeof(uintptr_t) + CONTEXT_FP_BYTES +
                       3 * sizeof(uintptr_t),
               "Context size must match trap.S CONTEXT_SIZE");
_Static_assert(sizeof(struct Context) % 16 == 0,
               "RV64GC Context size must preserve 16-byte stack alignment");
_Static_assert(offsetof(struct Context, fpr) == NR_REGS * sizeof(uintptr_t),
               "fpr offset must match trap.S OFFSET_FPR");
_Static_assert(offsetof(struct Context, fcsr) ==
                   NR_REGS * sizeof(uintptr_t) + CONTEXT_FPR_BYTES,
               "fcsr offset must match trap.S OFFSET_FCSR");
_Static_assert(offsetof(struct Context, mcause) ==
                   NR_REGS * sizeof(uintptr_t) + CONTEXT_FP_BYTES,
               "mcause offset must match trap.S");
_Static_assert(offsetof(struct Context, mstatus) ==
                   NR_REGS * sizeof(uintptr_t) + CONTEXT_FP_BYTES +
                       sizeof(uintptr_t),
               "mstatus offset must match trap.S");
_Static_assert(offsetof(struct Context, mepc) ==
                   NR_REGS * sizeof(uintptr_t) + CONTEXT_FP_BYTES +
                       2 * sizeof(uintptr_t),
               "mepc offset must match trap.S");
#else
_Static_assert(sizeof(struct Context) == (NR_REGS + 3) * sizeof(uintptr_t),
               "Context size must match trap.S CONTEXT_SIZE");
_Static_assert(offsetof(struct Context, mcause) == NR_REGS * sizeof(uintptr_t),
               "mcause offset must match trap.S");
_Static_assert(offsetof(struct Context, mstatus) == (NR_REGS + 1) * sizeof(uintptr_t),
               "mstatus offset must match trap.S");
_Static_assert(offsetof(struct Context, mepc) == (NR_REGS + 2) * sizeof(uintptr_t),
               "mepc offset must match trap.S");
#endif
_Static_assert(offsetof(struct Context, gpr) == 0,
               "gpr[0] must be at offset 0");
_Static_assert(offsetof(struct Context, pdir) == 0,
               "pdir must share storage with gpr[0]");

#ifdef __riscv_e
#define GPR1 gpr[15] // a5
#else
#define GPR1 gpr[17] // a7
#endif

#define GPR2 gpr[10]
#define GPR3 gpr[11]
#define GPR4 gpr[12]
#define GPRx gpr[10]

#endif
