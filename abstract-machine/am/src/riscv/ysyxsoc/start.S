.section entry, "ax"
.globl _start
.type _start, @function

_start:
  mv s0, zero
  la sp, _stack_pointer

  la t0, _data_lma
  la t1, _data_start

  # unrolled data copy - 14 words
  lw t3,  0(t0); sw t3,  0(t1); fence
  lw t3,  4(t0); sw t3,  4(t1); fence
  lw t3,  8(t0); sw t3,  8(t1); fence
  lw t3, 12(t0); sw t3, 12(t1); fence
  lw t3, 16(t0); sw t3, 16(t1); fence
  lw t3, 20(t0); sw t3, 20(t1); fence
  lw t3, 24(t0); sw t3, 24(t1); fence
  lw t3, 28(t0); sw t3, 28(t1); fence
  lw t3, 32(t0); sw t3, 32(t1); fence
  lw t3, 36(t0); sw t3, 36(t1); fence
  lw t3, 40(t0); sw t3, 40(t1); fence
  lw t3, 44(t0); sw t3, 44(t1); fence
  lw t3, 48(t0); sw t3, 48(t1); fence
  lw t3, 52(t0); sw t3, 52(t1); fence

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
