.section entry, "ax"
.globl _start
.type _start, @function

_start:
  mv s0, zero
  la sp, _stack_pointer

  # copy .text from flash to sram
  la t0, _text_lma
  la t1, _text_start
  la t2, _text_end
1:
  bge t1, t2, 2f
  lw t3, 0(t0)
  sw t3, 0(t1)
  addi t0, t0, 4
  addi t1, t1, 4
  j 1b
2:

  # copy .rodata from flash to sram
  la t0, _rodata_lma
  la t1, _rodata_start
  la t2, _rodata_end
1:
  bge t1, t2, 2f
  lw t3, 0(t0)
  sw t3, 0(t1)
  addi t0, t0, 4
  addi t1, t1, 4
  j 1b
2:

  # copy .data from flash to sram
  la t0, _data_lma
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

  # BSS zeroing
  la t0, _bss_start
  la t1, _bss_end
1:
  bge t0, t1, 2f
  sw zero, 0(t0)
  addi t0, t0, 4
  j 1b
2:

  call _trm_init

.size _start, . - _start
