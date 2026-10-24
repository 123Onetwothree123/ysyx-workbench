#ifndef RISCV_H__
#define RISCV_H__

#include <stdint.h>

static inline uint8_t  inb(uintptr_t addr) { return *(volatile uint8_t  *)addr; }
static inline uint16_t inw(uintptr_t addr) { return *(volatile uint16_t *)addr; }
static inline uint32_t inl(uintptr_t addr) { return *(volatile uint32_t *)addr; }

static inline void outb(uintptr_t addr, uint8_t  data) { *(volatile uint8_t  *)addr = data; }
static inline void outw(uintptr_t addr, uint16_t data) { *(volatile uint16_t *)addr = data; }
static inline void outl(uintptr_t addr, uint32_t data) { *(volatile uint32_t *)addr = data; }

#define PTE_V 0x01
#define PTE_R 0x02
#define PTE_W 0x04
#define PTE_X 0x08
#define PTE_U 0x10
#define PTE_A 0x40
#define PTE_D 0x80

enum { MODE_U, MODE_S, MODE_M = 3 };
#define MSTATUS_SIE  (1 << 1)
#define MSTATUS_SPIE (1 << 5)
#define MSTATUS_SPP  (1 << 8)
#define MSTATUS_MXR  (1 << 19)
#define MSTATUS_SUM  (1 << 18)

// mstatus.FS[14:13]：0=Off，1=Initial，2=Clean，3=Dirty。
// AM_RV64GC 目标的 F/D 上下文只有在 FS!=Off 时才允许执行浮点指令。
#define MSTATUS_FS_SHIFT   13
#define MSTATUS_FS_OFF     (0ull << MSTATUS_FS_SHIFT)
#define MSTATUS_FS_INITIAL (1ull << MSTATUS_FS_SHIFT)
#define MSTATUS_FS_CLEAN   (2ull << MSTATUS_FS_SHIFT)
#define MSTATUS_FS_DIRTY   (3ull << MSTATUS_FS_SHIFT)
#define MSTATUS_FS_MASK    (3ull << MSTATUS_FS_SHIFT)

// 只有 RV64GC 目标的 Context 保存浮点状态，因此也只在这些目标上
// 让新上下文以 FS=Dirty 启动。IM/lp64 目标保持旧 mstatus 值不变。
#ifdef AM_RV64GC
#define MSTATUS_FS_INIT MSTATUS_FS_DIRTY
#else
#define MSTATUS_FS_INIT 0
#endif

#if __riscv_xlen == 64
#define MSTATUS_SXL  (2ull << 34)
#define MSTATUS_UXL  (2ull << 32)
#else
#define MSTATUS_SXL  0
#define MSTATUS_UXL  0
#endif

#endif
