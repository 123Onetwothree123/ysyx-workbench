// Nanos-lite 最小用户程序：write(1, msg, len) 后 exit(0)。
// RV64 用 a7 传系统调用号；RV32E 只有 x0-x15，因此用 a5。
// 该文件由脚本用 riscv64-unknown-elf-gcc 分别构建 ELF64/ELF32。
    .section .text
    .globl _start
_start:
    li      a0, 1              // fd = stdout
    la      a1, msg
    la      t0, msg_end
    la      t1, msg
    sub     a2, t0, t1
#if __riscv_xlen == 64
    li      a7, 4              // SYS_write
#elif defined(__riscv_e)
    li      a5, 4
#else
    li      a7, 4
#endif
    ecall

    li      a0, 0
#if __riscv_xlen == 64
    li      a7, 0              // SYS_exit
#elif defined(__riscv_e)
    li      a5, 0
#else
    li      a7, 0
#endif
    ecall
1:
    j       1b

    .section .rodata
msg:
    .ascii  "user: hello from Nanos-lite ELF user program\n"
msg_end:
