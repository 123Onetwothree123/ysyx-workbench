OUTPUT_ARCH(riscv)
ENTRY(_start)
SECTIONS
{
  /* direct-NPC 复位地址。FPU 测试镜像只依赖这个入口和连续 RAM。 */
  . = 0x80000000;
  .text : { *(.text*) }
  .rodata : ALIGN(16) { *(.rodata*) *(.srodata*) }
  .data : ALIGN(16) { *(.data*) *(.sdata*) }
  .bss : ALIGN(16) { *(.bss*) *(.sbss*) *(COMMON) }
  /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) }
}
