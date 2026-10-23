/*
 * 最小 S 模式 payload 入口。
 *
 * OpenSBI FW_JUMP/FW_PAYLOAD 以 S 模式跳到 PAYLOAD_ADDR，a0=hartid，
 * a1=FDT 地址（本平台为 0）。payload 只使用自己的栈，不依赖 FDT。
 */

	.section .text.entry, "ax", %progbits
	.globl _start
_start:
	.option push
	.option norelax
	la	gp, __global_pointer$
	.option pop
	la	sp, _stack_top
	call	payload_main

1:
	wfi
	j	1b
