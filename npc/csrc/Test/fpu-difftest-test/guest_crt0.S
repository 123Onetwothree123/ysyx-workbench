# RV64D C 语言启动代码。
#
# 使用 GCC 的硬浮点 ABI（-march=rv64imafd -mabi=lp64d），不链接 libc/
# libgcc。启动时置 mstatus.FS、清 fcsr，再调用 fpu_c_main。
.section .text.start, "ax", @progbits
.globl _start
_start:
    .option push
    .option norelax
    la sp, _stack_top
    .option pop
    li t0, 0x6000
    csrs mstatus, t0
    csrw fcsr, zero
    call fpu_c_main

    # a0 为 fpu_c_main 的返回码，沿用 csrc/Test 的 custom-0 halt 约定。
    .word 0x0000000b
