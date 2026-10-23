// Physical memory layout for the NPC port.
//
// NPC direct-simulation platform (see npc/src/Common/ysyx_26030103_PMA.scala
// and npc/src/riscv32e_npc.scala):
//
// 02000000 -- CLINT (mtime/mtimecmp only)
// 0c000000 -- PLIC (routed by the NPC crossbar even though it is not listed
//             as a PMA region; it is not cacheable)
// 10000000 -- UART (NPC models only the TX holding register; reads are
//             DECERR and there is no input path)
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
#define PLIC_SENABLE(hart)   (PLIC + 0x2080 + (hart) * 0x100)
#define PLIC_SPRIORITY(hart) (PLIC + 0x201000 + (hart) * 0x2000)
#define PLIC_SCLAIM(hart)    (PLIC + 0x201004 + (hart) * 0x2000)

// RAM visible to the kernel.
#define KERNBASE 0x80000000L
#define PHYSTOP  (KERNBASE + 64 * 1024 * 1024)

#define TRAMPOLINE (MAXVA - PGSIZE)
#define KSTACK(p)  (TRAMPOLINE - ((p) + 1) * 2 * PGSIZE)

#define TRAPFRAME (TRAMPOLINE - PGSIZE)
