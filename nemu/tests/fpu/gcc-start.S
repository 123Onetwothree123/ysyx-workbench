# GCC 硬浮点测试的启动代码：设置栈和 FS，调用 main，最后触发 NEMU trap。
# 放在 .text.start 中，链接脚本保证它位于镜像入口 0x80000000。
    .section .text.start
    .globl _start
_start:
    .option push
    .option norelax
    la      gp, __global_pointer$
    .option pop
    li      sp, 0x87fffff0
    li      t0, 0x2000                 # mstatus.FS=Initial
    csrs    mstatus, t0
    call    main
    .word   0x0000000b
