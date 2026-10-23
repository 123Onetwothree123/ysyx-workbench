// Physical memory layout for the NPC port.
//
// NPC direct-simulation platform (see npc/src/Common/ysyx_26030103_PMA.scala
// and npc/src/riscv32e_npc.scala):
//
// 02000000 -- CLINT (mtime/mtimecmp only)
// 0c000000 -- PLIC (routed by the NPC crossbar even though it is not listed
//             as a PMA region; it is not cacheable)
// 10000000 -- UART (minimal 16550: THR/RBR, IER, IIR/FCR, LSR; RX is
//             injected by the simulator and raises PLIC source 10)
// 80000000 -- RAM, 64 MiB, 0x80000000..0x84000000
//
// The kernel itself is loaded at 0x80000000.  fs.img is linked into the
// kernel image as a read/write .ramdisk section immediately after .bss;
// kernel/kalloc.c only frees pages after the linker symbol `end`, so the
// ramdisk is never handed to the page allocator.

#define UART0     0x10000000L
#define UART0_IRQ 10

// No virtio disk.  The block device is an in-memory ramdisk (kernel/ramdisk.c);
// its size is derived from the linker-provided _ramdisk_start/_ramdisk_end.

// core-local interrupt controller (CLINT)
#define CLINT_BASE          0x02000000L
#define CLINT_MTIMECMP(hart) (CLINT_BASE + 0x4000 + (hart) * 8)
#define CLINT_MTIME          (CLINT_BASE + 0xBFF8)

// NPC's crossbar decodes this window independently of the PMA list.
#define PLIC                 0x0c000000L
#define PLIC_PRIORITY        (PLIC + 0x0)
#define PLIC_PENDING         (PLIC + 0x1000)
#ifdef XV6_PLATFORM_NPC
// NPC's PLIC accepts both the simplified context 0 layout
//   enable      0x0c002000
//   threshold   0x0c200000
//   claim/complete 0x0c200004
// and the standard SiFive S-mode hart 0 context 1
// (enable 0x2080, threshold 0x201000).  This port uses context 0; the
// QEMU reference build keeps the standard layout.
#define PLIC_SENABLE(hart)   (PLIC + 0x2000)
#define PLIC_SPRIORITY(hart) (PLIC + 0x200000)
#define PLIC_SCLAIM(hart)    (PLIC + 0x200004)
#else
#define PLIC_SENABLE(hart)   (PLIC + 0x2080 + (hart) * 0x100)
#define PLIC_SPRIORITY(hart) (PLIC + 0x201000 + (hart) * 0x2000)
#define PLIC_SCLAIM(hart)    (PLIC + 0x201004 + (hart) * 0x2000)
#endif

// RAM visible to the kernel.
//
// The direct-NPC simulator provides 64 MiB, but xv6's kinit() writes junk
// over every free page.  Under Verilator that costs roughly 8 pages/second,
// i.e. ~30 minutes for the full 64 MiB.  The allocator therefore defaults to
// the first 4 MiB (still enough for init/sh and the buffer cache).  Build with
// -DPHYSTOP='(KERNBASE + 64*1024*1024)' to exercise all of RAM.
#define KERNBASE 0x80000000L
#ifndef PHYSTOP
#define PHYSTOP  (KERNBASE + 4 * 1024 * 1024)
#endif

#define TRAMPOLINE (MAXVA - PGSIZE)
#define KSTACK(p)  (TRAMPOLINE - ((p) + 1) * 2 * PGSIZE)

#define TRAPFRAME (TRAMPOLINE - PGSIZE)
