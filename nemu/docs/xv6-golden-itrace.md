# NEMU golden ITRACE: first userret -> ecall -> uservec -> usertrap
# 地址由 xv6 PLATFORM=nemu kernel.sym 确认；机器码为 NEMU 实际提交的指令。
# userret 在 trampoline 的运行时映射 0x3ffffff0b0，ecall 在 0x5a0，
# uservec 最后一条 jalr t0(0x3ffffff0ac) 的目标是 0x800037e4(usertrap)。
# 0x800037e4 的正确指令字是 fe010113 (addi sp,sp,-32)，不是 0000100f (fence.i)。

0x0000003ffffff0b0: 00 00 10 0f fence.i
0x0000003ffffff0b4: 12 00 00 73 sfence.vma
0x0000003ffffff0b8: 18 05 10 73 csrw	satp, a0
0x0000003ffffff0bc: 12 00 00 73 sfence.vma
0x0000003ffffff0c0: 02 00 05 37 lui	a0, 0x2000
0x0000003ffffff0c4: ff f5 05 1b addiw	a0, a0, -1
0x0000003ffffff0c8: 00 d5 15 13 slli	a0, a0, 0xd
0x0000003ffffff0cc: 02 85 30 83 ld	ra, 0x28(a0)
0x0000003ffffff0d0: 03 05 31 03 ld	sp, 0x30(a0)
0x0000003ffffff0d4: 03 85 31 83 ld	gp, 0x38(a0)
0x0000003ffffff0d8: 04 05 32 03 ld	tp, 0x40(a0)
0x0000003ffffff0dc: 04 85 32 83 ld	t0, 0x48(a0)
0x0000003ffffff0e0: 05 05 33 03 ld	t1, 0x50(a0)
0x0000003ffffff0e4: 05 85 33 83 ld	t2, 0x58(a0)
0x0000003ffffff0e8: 06 05 34 03 ld	s0, 0x60(a0)
0x0000003ffffff0ec: 06 85 34 83 ld	s1, 0x68(a0)
0x0000003ffffff0f0: 07 85 35 83 ld	a1, 0x78(a0)
0x0000003ffffff0f4: 08 05 36 03 ld	a2, 0x80(a0)
0x0000003ffffff0f8: 08 85 36 83 ld	a3, 0x88(a0)
0x0000003ffffff0fc: 09 05 37 03 ld	a4, 0x90(a0)
0x0000003ffffff100: 09 85 37 83 ld	a5, 0x98(a0)
0x0000003ffffff104: 0a 05 38 03 ld	a6, 0xa0(a0)
0x0000003ffffff108: 0a 85 38 83 ld	a7, 0xa8(a0)
0x0000003ffffff10c: 0b 05 39 03 ld	s2, 0xb0(a0)
0x0000003ffffff110: 0b 85 39 83 ld	s3, 0xb8(a0)
0x0000003ffffff114: 0c 05 3a 03 ld	s4, 0xc0(a0)
0x0000003ffffff118: 0c 85 3a 83 ld	s5, 0xc8(a0)
0x0000003ffffff11c: 0d 05 3b 03 ld	s6, 0xd0(a0)
0x0000003ffffff120: 0d 85 3b 83 ld	s7, 0xd8(a0)
0x0000003ffffff124: 0e 05 3c 03 ld	s8, 0xe0(a0)
0x0000003ffffff128: 0e 85 3c 83 ld	s9, 0xe8(a0)
0x0000003ffffff12c: 0f 05 3d 03 ld	s10, 0xf0(a0)
0x0000003ffffff130: 0f 85 3d 83 ld	s11, 0xf8(a0)
0x0000003ffffff134: 10 05 3e 03 ld	t3, 0x100(a0)
0x0000003ffffff138: 10 85 3e 83 ld	t4, 0x108(a0)
0x0000003ffffff13c: 11 05 3f 03 ld	t5, 0x110(a0)
0x0000003ffffff140: 11 85 3f 83 ld	t6, 0x118(a0)
0x0000003ffffff144: 07 05 35 03 ld	a0, 0x70(a0)
0x00000000000005a0: 00 00 00 73 ecall
0x0000003ffffff000: 14 05 10 73 csrw	sscratch, a0
0x0000003ffffff004: 02 00 05 37 lui	a0, 0x2000
0x0000003ffffff008: ff f5 05 1b addiw	a0, a0, -1
0x0000003ffffff00c: 00 d5 15 13 slli	a0, a0, 0xd
0x0000003ffffff010: 02 15 34 23 sd	ra, 0x28(a0)
0x0000003ffffff014: 02 25 38 23 sd	sp, 0x30(a0)
0x0000003ffffff018: 02 35 3c 23 sd	gp, 0x38(a0)
0x0000003ffffff01c: 04 45 30 23 sd	tp, 0x40(a0)
0x0000003ffffff020: 04 55 34 23 sd	t0, 0x48(a0)
0x0000003ffffff024: 04 65 38 23 sd	t1, 0x50(a0)
0x0000003ffffff028: 04 75 3c 23 sd	t2, 0x58(a0)
0x0000003ffffff02c: 06 85 30 23 sd	s0, 0x60(a0)
0x0000003ffffff030: 06 95 34 23 sd	s1, 0x68(a0)
0x0000003ffffff034: 06 b5 3c 23 sd	a1, 0x78(a0)
0x0000003ffffff038: 08 c5 30 23 sd	a2, 0x80(a0)
0x0000003ffffff03c: 08 d5 34 23 sd	a3, 0x88(a0)
0x0000003ffffff040: 08 e5 38 23 sd	a4, 0x90(a0)
0x0000003ffffff044: 08 f5 3c 23 sd	a5, 0x98(a0)
0x0000003ffffff048: 0b 05 30 23 sd	a6, 0xa0(a0)
0x0000003ffffff04c: 0b 15 34 23 sd	a7, 0xa8(a0)
0x0000003ffffff050: 0b 25 38 23 sd	s2, 0xb0(a0)
0x0000003ffffff054: 0b 35 3c 23 sd	s3, 0xb8(a0)
0x0000003ffffff058: 0d 45 30 23 sd	s4, 0xc0(a0)
0x0000003ffffff05c: 0d 55 34 23 sd	s5, 0xc8(a0)
0x0000003ffffff060: 0d 65 38 23 sd	s6, 0xd0(a0)
0x0000003ffffff064: 0d 75 3c 23 sd	s7, 0xd8(a0)
0x0000003ffffff068: 0f 85 30 23 sd	s8, 0xe0(a0)
0x0000003ffffff06c: 0f 95 34 23 sd	s9, 0xe8(a0)
0x0000003ffffff070: 0f a5 38 23 sd	s10, 0xf0(a0)
0x0000003ffffff074: 0f b5 3c 23 sd	s11, 0xf8(a0)
0x0000003ffffff078: 11 c5 30 23 sd	t3, 0x100(a0)
0x0000003ffffff07c: 11 d5 34 23 sd	t4, 0x108(a0)
0x0000003ffffff080: 11 e5 38 23 sd	t5, 0x110(a0)
0x0000003ffffff084: 11 f5 3c 23 sd	t6, 0x118(a0)
0x0000003ffffff088: 14 00 22 f3 csrr	t0, sscratch
0x0000003ffffff08c: 06 55 38 23 sd	t0, 0x70(a0)
0x0000003ffffff090: 00 85 31 03 ld	sp, 8(a0)
0x0000003ffffff094: 02 05 32 03 ld	tp, 0x20(a0)
0x0000003ffffff098: 01 05 32 83 ld	t0, 0x10(a0)
0x0000003ffffff09c: 00 05 33 03 ld	t1, 0(a0)
0x0000003ffffff0a0: 12 00 00 73 sfence.vma
0x0000003ffffff0a4: 18 03 10 73 csrw	satp, t1
0x0000003ffffff0a8: 12 00 00 73 sfence.vma
0x0000003ffffff0ac: 00 02 80 e7 jalr	t0
0x00000000800037e4: fe 01 01 13 addi	sp, sp, -0x20
0x00000000800037e8: 00 11 3c 23 sd	ra, 0x18(sp)
0x00000000800037ec: 00 81 38 23 sd	s0, 0x10(sp)
0x00000000800037f0: 00 91 34 23 sd	s1, 8(sp)
0x00000000800037f4: 01 21 30 23 sd	s2, 0(sp)
0x00000000800037f8: 02 01 04 13 addi	s0, sp, 0x20
0x00000000800037fc: 10 00 27 f3 csrr	a5, sstatus
0x0000000080003800: 10 07 f7 93 andi	a5, a5, 0x100
0x0000000080003804: 08 07 94 63 bnez	a5, 0x88
0x0000000080003808: 00 00 47 97 auipc	a5, 4
0x000000008000380c: 49 87 87 93 addi	a5, a5, 0x498
0x0000000080003810: 10 57 90 73 csrw	stvec, a5
