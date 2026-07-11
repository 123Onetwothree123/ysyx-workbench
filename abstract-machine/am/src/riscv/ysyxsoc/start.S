.section entry, "ax"
.globl _start
.type _start, @function

_start:
  mv s0, zero
  lui sp, %hi(_stack_pointer)
  addi sp, sp, %lo(_stack_pointer)

  # FSBL: copy .ssbl from flash to on-chip SRAM
  lui t0, %hi(_ssbl_lma)
  addi t0, t0, %lo(_ssbl_lma)
  lui t1, %hi(_ssbl_start)
  addi t1, t1, %lo(_ssbl_start)
  lui t2, %hi(_ssbl_end)
  addi t2, t2, %lo(_ssbl_end)
1:
  bge t1, t2, 2f
  lw t3, 0(t0)
  sw t3, 0(t1)
  addi t0, t0, 4
  addi t1, t1, 4
  j 1b
2:

  # FSBL: jump to SSBL in on-chip SRAM
  lui t0, %hi(ssbl_entry)
  addi t0, t0, %lo(ssbl_entry)
  jalr zero, 0(t0)

.section .ssbl, "ax"
.globl ssbl_entry
.type ssbl_entry, @function

ssbl_entry:
  # SSBL runs from SRAM, can use PC-relative la

  # copy .text from flash to PSRAM
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

  # copy .data.extra from flash to PSRAM
  la t0, _data_extra_lma
  la t1, _data_extra_start
  la t2, _data_extra_end
1:
  bge t1, t2, 2f
  lw t3, 0(t0)
  sw t3, 0(t1)
  addi t0, t0, 4
  addi t1, t1, 4
  j 1b
2:

  # copy .data from flash to PSRAM
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

  # SSBL: absolute jump to _trm_init in PSRAM
  lui t0, %hi(_trm_init)
  addi t0, t0, %lo(_trm_init)
  jalr zero, 0(t0)

.size ssbl_entry, . - ssbl_entry
