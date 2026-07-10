.section entry, "ax"
.globl _start
.type _start, @function

_start:
  mv s0, zero
  lui sp, %hi(_stack_pointer)
  addi sp, sp, %lo(_stack_pointer)

  # copy .text from flash to PSRAM
  lui t0, %hi(_text_lma)
  addi t0, t0, %lo(_text_lma)
  lui t1, %hi(_text_start)
  addi t1, t1, %lo(_text_start)
  lui t2, %hi(_text_end)
  addi t2, t2, %lo(_text_end)
1:
  bge t1, t2, 2f
  lw t3, 0(t0)
  sw t3, 0(t1)
  addi t0, t0, 4
  addi t1, t1, 4
  j 1b
2:

  # copy .data.extra from flash to PSRAM
  lui t0, %hi(_data_extra_lma)
  addi t0, t0, %lo(_data_extra_lma)
  lui t1, %hi(_data_extra_start)
  addi t1, t1, %lo(_data_extra_start)
  lui t2, %hi(_data_extra_end)
  addi t2, t2, %lo(_data_extra_end)
1:
  bge t1, t2, 2f
  lw t3, 0(t0)
  sw t3, 0(t1)
  addi t0, t0, 4
  addi t1, t1, 4
  j 1b
2:

  # copy .data from flash to PSRAM
  lui t0, %hi(_data_lma)
  addi t0, t0, %lo(_data_lma)
  lui t1, %hi(_data_start)
  addi t1, t1, %lo(_data_start)
  lui t2, %hi(_edata)
  addi t2, t2, %lo(_edata)
1:
  bge t1, t2, 2f
  lw t3, 0(t0)
  sw t3, 0(t1)
  addi t0, t0, 4
  addi t1, t1, 4
  j 1b
2:

  # BSS zeroing
  lui t0, %hi(_bss_start)
  addi t0, t0, %lo(_bss_start)
  lui t1, %hi(_bss_end)
  addi t1, t1, %lo(_bss_end)
1:
  bge t0, t1, 2f
  sw zero, 0(t0)
  addi t0, t0, 4
  j 1b
2:

  # absolute jump to _trm_init in PSRAM
  lui t0, %hi(_trm_init)
  addi t0, t0, %lo(_trm_init)
  jalr zero, 0(t0)

.size _start, . - _start
