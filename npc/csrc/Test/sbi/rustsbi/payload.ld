/* 测试 payload 固定链接到 RustSBI 默认 next-stage 0x80200000。 */
OUTPUT_ARCH(riscv)
ENTRY(_start)

SECTIONS
{
  . = 0x80200000;
  .text : { KEEP(*(.text.entry)) *(.text .text.*) }
  .rodata : { *(.rodata .rodata.*) }
  .bss (NOLOAD) : { *(.bss .bss.*) *(COMMON) }
  /DISCARD/ : { *(.note .note.*) *(.comment) }
}
