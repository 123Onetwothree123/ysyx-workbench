/*
 * 最小 S 模式 payload 入口。
 *
 * OpenSBI/RustSBI 以 S 模式跳到 PAYLOAD_ADDR，a0=hartid，a1=FDT 地址
 * （未启用 FDT 时为 0）。把 a0/a1 原样作为 C 函数参数保存下来，避免
 * 在调用 payload_main 前被编译器/汇编辅助代码覆盖。
 */

	.section .text.entry, "ax", %progbits
	.globl _start
_start:
	.option push
	.option norelax
	la	gp, __global_pointer$
	.option pop
	la	sp, _stack_top

	/* 清 BSS：FDT 解析器使用静态状态，不能依赖 RAM 上电值。 */
	la	t0, _bss_start
	la	t1, _bss_end
1:
	bgeu	t0, t1, 2f
	sb	zero, 0(t0)
	addi	t0, t0, 1
	j	1b
2:

	/* payload_main(fdt_address, hartid) */
	mv	t0, a0
	mv	a0, a1
	mv	a1, t0
	call	payload_main

3:
	wfi
	j	3b
