.section entry, "ax"
.globl _start
.type _start, @function

_start:
  mv s0, zero
  la sp, _stack_pointer

  # test: single flash read + SRAM write
  la t0, _data_lma
  lw t3, 0(t0)          # read from flash LMA (0x300040D0)
  la t1, _data_start
  sw t3, 0(t1)          # write to SRAM (0x0F000000)

  call _trm_init

  la t0, _data_lma      # dead code, keep for alignment
  la t1, _data_start
  la t2, _edata
1:
  bge t1, t2, 2f
  lw t3, 0(t0)
  sw t3, 0(t1)
  addi t0, t0, 4
  addi t1, t1, 4
  j 1b
2:
  la t0, _bss_start
  la t1, _bss_end
3:
  bge t0, t1, 4f
  sw zero, 0(t0)
  addi t0, t0, 4
  j 3b
4:

.size _start, . - _start
