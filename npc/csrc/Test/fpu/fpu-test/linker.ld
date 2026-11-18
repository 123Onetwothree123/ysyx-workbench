OUTPUT_ARCH(riscv)
ENTRY(_start)

SECTIONS
{
  . = 0x80000000;
  .text : {
    *(.text.init)
    *(.text*)
  }
  .rodata : ALIGN(16) {
    *(.rodata*)
    *(.srodata*)
  }
  .data : ALIGN(16) {
    *(.data*)
    *(.sdata*)
  }
  __bss_start = .;
  .bss : ALIGN(16) {
    *(.bss*)
    *(.sbss*)
    *(COMMON)
  }
  __bss_end = .;
  . = ALIGN(16);
  _stack_top = 0x80010000;
}
