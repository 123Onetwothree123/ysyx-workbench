.section entry, "ax"
.globl _start
.type _start, @function

_start:
  mv s0, zero
  la sp, _stack_pointer

  la t0, _data_lma
  la t1, _data_start
  la t2, _edata
1:
  bge t1, t2, 2f
  lb t3, 0(t0)
  sb t3, 0(t1)
  addi t0, t0, 1
  addi t1, t1, 1
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

  call _trm_init

.size _start, . - _start
