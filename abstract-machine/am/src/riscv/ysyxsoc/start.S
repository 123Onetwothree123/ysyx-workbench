.section entry, "ax"
.globl _start
.type _start, @function

_start:
  mv s0, zero
  la sp, _stack_pointer

  # test: 14 flash reads ONLY (no SRAM writes)
  la t0, _data_lma
  li t4, 14          # 56 bytes / 4 = 14 words
  li t5, 0x0F000000
1:
  lw t3, 0(t0)
  # no store - just read
  addi t0, t0, 4
  addi t4, t4, -1
  bnez t4, 1b

  call _trm_init

.size _start, . - _start
