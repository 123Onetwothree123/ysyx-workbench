OUTPUT_ARCH(riscv)
ENTRY(_start)
SECTIONS
{
  . = 0x80000000;
  .text : {
    *(.text.start)
    *(.text .text.*)
  }
  .rodata : { *(.rodata .rodata.*) }
  . = ALIGN(16);
  PROVIDE(__global_pointer$ = . + 0x800);
  .data : { *(.data .data.* .sdata .sdata.*) }
  .bss : { *(.bss .bss.* .sbss .sbss.*) }
}
