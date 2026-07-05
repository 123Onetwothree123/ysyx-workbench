.section entry, "ax"
.globl _start
.type _start, @function

_start:
  mv s0, zero
  la sp, _stack_pointer

  la t0, _data_lma
  la t1, _data_start
  la t2, _edata

  # only copy 2 words to test
  lw t3, 0(t0)
  sw t3, 0(t1)
  lw t3, 4(t0)
  sw t3, 4(t1)

  call _trm_init

.size _start, . - _start
