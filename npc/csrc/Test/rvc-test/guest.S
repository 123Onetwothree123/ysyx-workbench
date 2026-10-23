# RV32C/RV64C 裸机回归 guest。
#
# 覆盖：
#   * 16/32 位指令混合执行，PC 以 2 字节步进；
#   * C.ADDI/LI/MV/ADD/SUB/XOR/OR/AND/SLLI/SRLI/SRAI/ANDI/LUI；
#   * C.LW/SW、C.LD/SD（RV64）、C.LWSP/SWSP、C.LDSP/SDSP（RV64）、
#     C.ADDI4SPN/C.ADDI16SP；
#   * C.J/C.JAL(RV32)/C.JR/C.JALR/C.BEQZ/C.BNEZ；
#   * RV32 的 C.JAL 与 RV64 的 C.ADDIW 差异；
#   * 32 位指令跨 cache line（0x...ffe）和跨 4KiB 页（0x...1ffe）；
#   * 压缩非法编码、C.EBREAK 的 mepc 精确值与 mtval；
#   * 2 字节对齐的 trap 返回（mret/sret）。
#
# s0 保存错误码；通过时 a0=0，自定义 halt 指令退出。
    .section .text
    .globl _start
_start:
    li      sp, 0x80008000
    la      t0, trap_handler
    csrw    mtvec, t0
    li      s0, 0

    # ---- misa.C 与 MXL ----
    csrr    t0, misa
    andi    t1, t0, 4
    beqz    t1, fail_misa_c
.if __riscv_xlen == 64
    srli    t1, t0, 62
    li      t2, 2
.else
    srli    t1, t0, 30
    li      t2, 1
.endif
    bne     t1, t2, fail_misa_mxl

    # ---- 基本压缩算术/逻辑 ----
    li      a0, 1
    c.addi  a0, 2               # 3
    c.li    a1, 5
    c.add   a0, a1              # 8
    c.mv    a2, a0              # 8
    c.sub   a2, a1              # 3
    c.slli  a2, 3               # 24
    c.srli  a2, 1               # 12
    c.srai  a2, 2               # 3
    c.andi  a2, 7               # 3
    c.xor   a2, a1              # 6
    c.or    a2, a1              # 7
    c.and   a2, a1              # 5
    li      t0, 5
    bne     a2, t0, fail_arith

    c.li    t0, -1
    c.addi  t0, 1
    bnez    t0, fail_arith

    c.lui   a3, 1               # 0x1000
    c.addi  a3, 1               # 0x1001
    li      t0, 0x1001
    bne     a3, t0, fail_lui

    # ---- SP 相对寻址与压缩访存 ----
    c.addi16sp sp, -32
    c.addi4spn a4, sp, 16
    addi    a4, a4, 0           # a4 = sp-16
    li      a2, 0x12345678
    c.sw    a2, 0(a4)
    c.lw    a5, 0(a4)
    bne     a2, a5, fail_lwsw

    li      t0, 0x0badf00d
    c.swsp  t0, 0(sp)
    c.lwsp  t1, 0(sp)
    bne     t0, t1, fail_lwsp

.if __riscv_xlen == 64
    li      a2, 0x1122334455667788
    c.sd    a2, 0(a4)
    c.ld    a5, 0(a4)
    bne     a2, a5, fail_ldsd
    c.sdsp  a2, 0(sp)
    c.ldsp  a5, 0(sp)
    bne     a2, a5, fail_ldsp

    # RV64 专属：C.ADDIW 符号扩展低 32 位结果。
    c.li    a0, 5
    c.addiw a0, 3
    li      t0, 8
    bne     a0, t0, fail_addiw
    c.li    a0, -1
    c.addiw a0, 0
    li      t0, -1
    bne     a0, t0, fail_addiw
.else
    # RV32 专属：C.JAL 链接 x1，C.JR 返回。返回地址是 c.jal 的下一条
    # 指令，因此这里放一个跳到 after_cjal 的正常路径，而不是 fail。
    li      t3, 0
    c.jal   rv32_jal_target
    j       after_cjal
rv32_jal_target:
    li      t3, 1
    c.jr    ra
after_cjal:
    li      t0, 1
    bne     t3, t0, fail_cjal
.endif

    # ---- 条件分支与 JALR ----
    c.li    a0, 1
    c.beqz  a0, fail_branch
    c.bnez  a0, 1f
    j       fail_branch
1:
    c.li    a0, 0
    c.beqz  a0, 2f
    j       fail_branch
2:
    la      t0, jalr_target
    c.jalr  t0
    j       after_jalr
jalr_target:
    c.jr    ra
after_jalr:

    # 分支目标落在 2 字节边界（3: 位于一条 C.NOP 之后）。
    c.li    a0, 1
    c.bnez  a0, 3f
    j       fail_branch
    .half   0x0001
3:
    c.li    a0, 0

    # ---- 压缩非法编码 / EBREAK 的 mepc、mtval ----
    li      s2, 2               # mcause = illegal instruction
    la      s4, illegal_zero
    li      s3, 0               # 全零压缩编码的 mtval = 0
    li      s5, 2
    li      s1, 0
illegal_zero:
    .half   0x0000
    li      t0, 1
    bne     s1, t0, fail_trap

    li      s2, 2
    la      s4, illegal_addi4spn
    li      s3, 0x0004          # C.ADDI4SPN x9, sp, 0 的原始编码
    li      s5, 2
    li      s1, 0
illegal_addi4spn:
    .half   0x0004
    li      t0, 1
    bne     s1, t0, fail_trap

    li      s2, 3               # EBREAK
    la      s4, ebreak_site
    la      s3, ebreak_site     # 32 位指令语义下 tval 回退到 PC
    li      s5, 2
    li      s1, 0
ebreak_site:
    .half   0x9002
    li      t0, 1
    bne     s1, t0, fail_trap

.if __riscv_xlen == 64
    # RV64：C.ADDIW rd=0 是保留编码（RV32 同一编码是 C.JAL）。
    li      s2, 2
    la      s4, illegal_addiw0
    li      s3, 0x2001
    li      s5, 2
    li      s1, 0
illegal_addiw0:
    .half   0x2001
    li      t0, 1
    bne     s1, t0, fail_trap
.else
    # RV32：C.LD 编码在无 D 扩展时非法。
    li      s2, 2
    la      s4, illegal_ld32
    li      s3, 0x6000
    li      s5, 2
    li      s1, 0
illegal_ld32:
    .half   0x6000
    li      t0, 1
    bne     s1, t0, fail_trap
.endif

    # ---- 32 位指令跨 cache line / 跨 4KiB 页 ----
    li      t3, 0
    la      t0, cross_page
    c.jalr  t0
    li      t0, 1
    bne     t3, t0, fail_cross_page

    li      t3, 0
    la      t0, cross_line
    c.jalr  t0
    li      t0, 1
    bne     t3, t0, fail_cross_line

    # ---- S 模式 sepc/stval：委托非法压缩指令 ----
    # 只委托 cause=2；S 模式入口用 stvec，返回用 sret。s_illegal_site
    # 刻意放在 4 字节对齐后 +2，验证 sepc 保留 bit1。
    li      t0, 4               # medeleg bit2 = illegal instruction
    csrw    medeleg, t0
    la      t0, s_trap_handler
    csrw    stvec, t0
    la      t0, s_entry
    csrw    mepc, t0
    li      t0, 0x800           # mstatus.MPP=S
    csrw    mstatus, t0
    mret

s_entry:
    li      s1, 0
    li      s2, 2
    li      s3, 0               # 全零压缩编码的 stval = 0
    li      s5, 2
    j       1f
    .balign 4
    .half   0x0001              # C.NOP，使下一条非法指令位于 4n+2
1:
    la      s4, s_illegal_site
s_illegal_site:
    .half   0x0000
    li      t0, 1
    bne     s1, t0, fail_sepc

    # 全部通过。
    li      a0, 0
    j       test_done

s_trap_handler:
    csrr    t0, scause
    bne     t0, s2, s_trap_fail
    csrr    t0, sepc
    andi    t1, t0, 1           # IALIGN=16：bit0=0
    bnez    t1, s_trap_fail
    andi    t1, t0, 2           # 本用例故意让 sepc bit1=1
    beqz    t1, s_trap_fail
    bne     t0, s4, s_trap_fail
    csrr    t0, stval
    bne     t0, s3, s_trap_fail
    csrr    t0, sepc
    add     t0, t0, s5
    csrw    sepc, t0
    li      s1, 1
    sret

s_trap_fail:
    li      s0, 21
    j       test_done

# ---- trap handler：校验 cause/mepc/mtval，按 s5 前进后返回 ----
trap_handler:
    csrr    t0, mcause
    bne     t0, s2, trap_fail
    csrr    t0, mepc
    andi    t1, t0, 1           # IALIGN=16：mepc 只保证 bit0=0
    bnez    t1, trap_fail
    bne     t0, s4, trap_fail
    csrr    t0, mtval
    bne     t0, s3, trap_fail
    csrr    t0, mepc
    add     t0, t0, s5
    csrw    mepc, t0
    li      s1, 1               # 标记 trap 已处理
    mret

trap_fail:
    li      s0, 90
    j       test_done

# ---- 错误码 ----
fail_misa_c:        li s0, 1
                    j test_done
fail_misa_mxl:      li s0, 2
                    j test_done
fail_arith:         li s0, 3
                    j test_done
fail_lui:           li s0, 4
                    j test_done
fail_lwsw:          li s0, 5
                    j test_done
fail_lwsp:          li s0, 6
                    j test_done
fail_ldsd:          li s0, 7
                    j test_done
fail_ldsp:          li s0, 8
                    j test_done
fail_addiw:         li s0, 9
                    j test_done
fail_cjal:          li s0, 10
                    j test_done
fail_branch:        li s0, 11
                    j test_done
fail_trap:          li s0, 12
                    j test_done
fail_cross_page:    li s0, 13
                    j test_done
fail_cross_line:    li s0, 14
                    j test_done
fail_sepc:          li s0, 20
                    j test_done

test_done:
    mv      a0, s0
    .word   0x0000000b

# 跨页：32 位 addi t3,t3,1 位于 0x80000ffe，横跨 4KiB 页边界。
    .org    0xffe
cross_page:
    .word   0x001e0e13          # addi t3, t3, 1（32 位，跨页）
    c.jr    ra

# 跨行：32 位 addi t3,t3,1 位于 0x80001ffe，跨 16B cache line。
    .org    0x1ffe
cross_line:
    .word   0x001e0e13          # addi t3, t3, 1（32 位，跨行）
    c.jr    ra
