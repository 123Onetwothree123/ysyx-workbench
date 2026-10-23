/*
 * 跨页/跨内存边界取指测试专用链接脚本。
 *
 * 故意把几条指令放到：
 *   0x80000ffc  32 位指令恰好结束在页尾
 *   0x80001ffe  32 位指令跨越 4KiB 页边界（RVC 允许 16 位对齐）
 *   0x80002ffe  16 位指令位于页尾
 * 从而验证可变长度取指对每一半分别做地址转换/边界检查，而不是
 * 一次读取 4 字节。
 */
OUTPUT_ARCH(riscv)
ENTRY(_start)

SECTIONS
{
  . = 0x80000000;
  .text : {
    *(.text)
    *(.text.*)
  }

  . = 0x80000ffc;
  .cross32 : { *(.cross32) }

  . = 0x80001000;
  .cross32_next : { *(.cross32_next) }

  . = 0x80001ffe;
  .crosssplit : { *(.crosssplit) }

  . = 0x80002ffe;
  .cross16 : { *(.cross16) }

  . = 0x80003000;
  .cross16_next : { *(.cross16_next) }

  . = ALIGN(16);
  .data : {
    *(.data)
    *(.rodata)
    *(.rodata.*)
  }

  .bss : {
    *(.bss)
    *(COMMON)
  }
}
