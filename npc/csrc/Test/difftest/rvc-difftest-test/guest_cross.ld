OUTPUT_ARCH(riscv)
ENTRY(_start)
SECTIONS
{
  /* direct-NPC 复位地址。所有 guest 都是地址无关的小镜像，只依赖这个入口。 */
  . = 0x80000000;
  .text : { *(.text*) }
  /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) *(.riscv.attributes) }
}
ASSERT(cross_line_instr == 0x800003fe, "跨 cache line 指令必须位于 0x800003fe")
ASSERT(cross_page_instr == 0x80001ffe, "跨页指令必须位于 0x80001ffe")
