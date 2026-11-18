OUTPUT_ARCH(riscv)
ENTRY(_start)
SECTIONS
{
  /* direct-NPC 复位地址。C 程序额外保留一块静态栈，避免依赖 libc/启动文件。 */
  . = 0x80000000;
  .text : { *(.text.start) *(.text*) }
  .rodata : ALIGN(16) { *(.rodata*) *(.srodata*) }
  .data : ALIGN(16) { *(.data*) *(.sdata*) }
  .bss : ALIGN(16)
  {
    _bss_start = .;
    *(.bss*) *(.sbss*) *(COMMON)
    _bss_end = .;
  }
  . = ALIGN(16);
  _stack_bottom = .;
  . += 0x4000;
  _stack_top = .;
  /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) }
}
