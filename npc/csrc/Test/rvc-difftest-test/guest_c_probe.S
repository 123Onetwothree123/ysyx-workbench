# RVC 支持探针 / RISCV_C=n 负向用例（RV32/RV64 共用）。
#
# 第一条 C 指令是 c.addi s1, 1（编码 0x0485），效果可观测。退出语义：
#   GOOD TRAP (a0=0)  -> DUT 正确执行了 C 指令，RVC 已启用；
#   BAD   code=42     -> DUT 在 c_site 以 illegal instruction (cause=2) 拒绝，
#                        说明当前构建是 RISCV_C=n，正向用例应 SKIP；
#   BAD   code=43     -> 没有 trap 但结果不对：16 位指令被静默跳过/错误执行，
#                        这是负向用例必须捕获的严重缺陷；
#   BAD   code=41/45  -> trap 原因或 mepc 不符合“c_site illegal”语义；
#                        既不能算“C 未启用”，也不能算“C 正确执行”。
#
# 注意：探针成功路径仍可能用到其它 C 指令；如果 DUT 只实现了部分 C
# 指令，后续指令会再次 trap，mepc 不再等于 c_site，按 45 明确失败，
# 不会被误判成“未启用”。

    .section .text
    .globl _start
    .option norvc
_start:
    la      t0, trap_handler
    csrw    mtvec, t0
    li      s1, 0

    .option rvc
    .balign 2
c_site:
    .half   0x0485                  # c.addi s1, 1
    li      t0, 1
    beq     s1, t0, probe_ok
    li      a0, 43                  # 静默跳过 / 执行结果错误
    .word   0x0000000b              # custom-0 仿真 halt

    .option norvc
    .balign 4
probe_ok:
    li      a0, 0
    .word   0x0000000b              # custom-0 仿真 halt

    .option norvc
    .balign 4
trap_handler:
    csrr    t0, mcause
    li      t1, 2
    bne     t0, t1, trap_wrong_cause
    csrr    t0, mepc
    la      t1, c_site
    bne     t0, t1, trap_wrong_pc
    li      a0, 42                  # 正确语义：c_site 因 C 未启用而 illegal
    .word   0x0000000b              # custom-0 仿真 halt

trap_wrong_cause:
    li      a0, 41
    .word   0x0000000b              # custom-0 仿真 halt

trap_wrong_pc:
    li      a0, 45
    .word   0x0000000b              # custom-0 仿真 halt
