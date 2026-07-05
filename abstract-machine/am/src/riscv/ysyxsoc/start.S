.section entry, "ax"
.globl _start
.type _start, @function

_start:
  mv s0, zero
  la sp, _stack_pointer

  # debug: mark entry
  li a5, 0x10000000
  li a4, 0x31   # '1'
  sb a4, 0(a5)

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

  # debug: data copy done
  li a4, 0x32   # '2'
  sb a4, 0(a5)

  la t0, _bss_start
  la t1, _bss_end
3:
  bge t0, t1, 4f
  sw zero, 0(t0)
  addi t0, t0, 4
  j 3b
4:

  # debug: bss done
  li a4, 0x33   # '3'
  sb a4, 0(a5)

  call _trm_init

.size _start, . - _start
