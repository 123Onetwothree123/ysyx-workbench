/*
 * ysyxSoC ACT / 裸机启动 stub（C5 布局）。
 *
 * 镜像布局（C5：4 KiB MROM stub + Flash payload）：
 *   0x0000..0x0fff  本 stub，放在 MROM base（默认 0x20000000）
 *   0x0ff0           payload 字节数（32 位小端）
 *   0x1000..         payload，放在 XIP Flash base（默认 0x30000000）
 *
 * 复位后 CPU 从 MROM 执行本 stub：把 Flash 中的 payload 搬到 PSRAM
 * （默认 0x80000000），fence/fence.i 后跳转过去。MROM 只有 4 KiB，
 * 大 payload 不再放进 MROM。
 */
    .section .text
    .globl _start
_start:
    .option push
    .option norelax

    /* t0 = size 所在地址；size 是 32 位小端，RV32/RV64 都用 lw。 */
    li      t0, MROM_BASE + 0xff0
    lw      t1, 0(t0)

    /* t2 = Flash 源，t3 = PSRAM 目标。 */
    li      t2, FLASH_BASE
    li      t3, PSRAM_BASE

#if __riscv_xlen == 64
    /* 先按 8 字节复制主体，余数逐字节补齐。 */
    andi    t6, t1, 7
    srli    t1, t1, 3
    beqz    t1, 2f
1:
    ld      t5, 0(t2)
    sd      t5, 0(t3)
    addi    t2, t2, 8
    addi    t3, t3, 8
    addi    t1, t1, -1
    bnez    t1, 1b
#else
    /* RV32 按 4 字节复制主体，余数逐字节补齐。 */
    andi    t6, t1, 3
    srli    t1, t1, 2
    beqz    t1, 2f
1:
    lw      t5, 0(t2)
    sw      t5, 0(t3)
    addi    t2, t2, 4
    addi    t3, t3, 4
    addi    t1, t1, -1
    bnez    t1, 1b
#endif

2:
    beqz    t6, 3f
4:
    lb      t5, 0(t2)
    sb      t5, 0(t3)
    addi    t2, t2, 1
    addi    t3, t3, 1
    addi    t6, t6, -1
    bnez    t6, 4b

3:
    /* 等 store 排空，再同步取指。 */
    fence
    fence.i

    li      a0, 0
    li      a1, 0
    li      t0, PSRAM_BASE
    jr      t0

    .option pop
    .size _start, . - _start
