#if __riscv_xlen == 64
#define TABLE_ENTRY .dword
#define TABLE_LOAD  ld
#define PTR         8
#else
#define TABLE_ENTRY .word
#define TABLE_LOAD  lw
#define PTR         4
#endif

.section entry, "ax"
.globl _start
.type _start, @function

_start:
  mv s0, zero

  # FSBL 通过 PC 相对地址读取 64/32 位地址表，避免 RV64 的 lui
  # 对 0x80000000 以上地址做符号扩展。
  lla t0, .Lfsbl_table
  TABLE_LOAD sp, (0 * PTR)(t0)
  TABLE_LOAD t3, (1 * PTR)(t0)  # _ssbl_lma
  TABLE_LOAD t4, (2 * PTR)(t0)  # _ssbl_start
  TABLE_LOAD t5, (3 * PTR)(t0)  # _ssbl_end
  TABLE_LOAD t6, (4 * PTR)(t0)  # ssbl_entry

1:
  bgeu t4, t5, 2f
  lw t0, 0(t3)
  sw t0, 0(t4)
  addi t3, t3, 4
  addi t4, t4, 4
  j 1b
2:

  # fence.i: 保证后续取指能看到上面 store 写入的 SSBL 代码
  .word 0x0000100f

  jalr zero, 0(t6)

.align 3
.Lfsbl_table:
  TABLE_ENTRY _stack_pointer
  TABLE_ENTRY _ssbl_lma
  TABLE_ENTRY _ssbl_start
  TABLE_ENTRY _ssbl_end
  TABLE_ENTRY ssbl_entry

.section .ssbl, "ax"
.globl ssbl_entry
.type ssbl_entry, @function

ssbl_entry:
  # SSBL 在 on-chip SRAM 中运行，用同一张表拿到 SDRAM 地址。
  lla t0, .Lssbl_table
  TABLE_LOAD t3, (0 * PTR)(t0)  # _text_lma
  TABLE_LOAD t4, (1 * PTR)(t0)  # _text_start
  TABLE_LOAD t5, (2 * PTR)(t0)  # _text_end
1:
  bgeu t4, t5, 2f
  lw t1, 0(t3)
  sw t1, 0(t4)
  addi t3, t3, 4
  addi t4, t4, 4
  j 1b
2:

  lla t0, .Lssbl_table
  TABLE_LOAD t3, (3 * PTR)(t0)  # _data_extra_lma
  TABLE_LOAD t4, (4 * PTR)(t0)  # _data_extra_start
  TABLE_LOAD t5, (5 * PTR)(t0)  # _data_extra_end
1:
  bgeu t4, t5, 2f
  lw t1, 0(t3)
  sw t1, 0(t4)
  addi t3, t3, 4
  addi t4, t4, 4
  j 1b
2:

  lla t0, .Lssbl_table
  TABLE_LOAD t3, (6 * PTR)(t0)  # _data_lma
  TABLE_LOAD t4, (7 * PTR)(t0)  # _data_start
  TABLE_LOAD t5, (8 * PTR)(t0)  # _edata
1:
  bgeu t4, t5, 2f
  lw t1, 0(t3)
  sw t1, 0(t4)
  addi t3, t3, 4
  addi t4, t4, 4
  j 1b
2:

  lla t0, .Lssbl_table
  TABLE_LOAD t3, (9 * PTR)(t0)   # _bss_start
  TABLE_LOAD t4, (10 * PTR)(t0)  # _bss_end
1:
  bgeu t3, t4, 2f
  sw zero, 0(t3)
  addi t3, t3, 4
  j 1b
2:

  # fence.i: 保证后续取指能看到上面 store 写入 PSRAM/SDRAM 的程序代码
  .word 0x0000100f

  lla t0, .Lssbl_table
  TABLE_LOAD t1, (11 * PTR)(t0)  # _trm_init
  jalr zero, 0(t1)

.align 3
.Lssbl_table:
  TABLE_ENTRY _text_lma
  TABLE_ENTRY _text_start
  TABLE_ENTRY _text_end
  TABLE_ENTRY _data_extra_lma
  TABLE_ENTRY _data_extra_start
  TABLE_ENTRY _data_extra_end
  TABLE_ENTRY _data_lma
  TABLE_ENTRY _data_start
  TABLE_ENTRY _edata
  TABLE_ENTRY _bss_start
  TABLE_ENTRY _bss_end
  TABLE_ENTRY _trm_init

.size ssbl_entry, . - ssbl_entry
