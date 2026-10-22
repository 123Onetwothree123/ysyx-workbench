/* Nanos-lite 最小用户程序链接脚本（RV32E/RV64 共用）。
 * VME 开启时用户地址空间从 0x40000000 开始；Nanos 的 loader 会按
 * PT_LOAD 的 p_vaddr 映射页面，因此这里不能使用默认的 0 地址。 */
ENTRY(_start)
SECTIONS
{
  . = 0x40000000;

  .text : {
    *(.text._start)
    *(.text*)
  }

  .rodata : {
    *(.rodata*)
    *(.srodata*)
  }

  .data : {
    *(.data*)
    *(.sdata*)
  }

  .bss : {
    *(.bss*)
    *(.sbss*)
    *(COMMON)
  }
}
