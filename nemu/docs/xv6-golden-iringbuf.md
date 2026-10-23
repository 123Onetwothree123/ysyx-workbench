# NEMU IRINGBUF 末尾（停在 usertrap 0x800037e4 入口）

```text
    0x0000003ffffff148: sret                     10 20 00 73
    0x00000000000000e4: addi	sp, sp, -0x10       ff 01 01 13
    0x00000000000000e8: sd	ra, 8(sp)             00 11 34 23
    0x00000000000000ec: sd	s0, 0(sp)             00 81 30 23
    0x00000000000000f0: addi	s0, sp, 0x10        01 01 04 13
    0x00000000000000f4: jal	-0xf4                f0 df f0 ef
    0x0000000000000000: addi	sp, sp, -0x20       fe 01 01 13
    0x0000000000000004: sd	ra, 0x18(sp)          00 11 3c 23
    0x0000000000000008: sd	s0, 0x10(sp)          00 81 38 23
    0x000000000000000c: sd	s1, 8(sp)             00 91 34 23
    0x0000000000000010: sd	s2, 0(sp)             01 21 30 23
    0x0000000000000014: addi	s0, sp, 0x20        02 01 04 13
    0x0000000000000018: addi	a1, zero, 2         00 20 05 93
    0x000000000000001c: auipc	a0, 1              00 00 15 17
    0x0000000000000020: addi	a0, a0, -0x20c      df 45 05 13
    0x0000000000000024: jal	0x578                57 80 00 ef
    0x000000000000059c: addi	a7, zero, 0xf       00 f0 08 93
    0x00000000000005a0: ecall                    00 00 00 73
    0x0000003ffffff000: csrw	sscratch, a0        14 05 10 73
    0x0000003ffffff004: lui	a0, 0x2000           02 00 05 37
    0x0000003ffffff008: addiw	a0, a0, -1         ff f5 05 1b
    0x0000003ffffff00c: slli	a0, a0, 0xd         00 d5 15 13
    0x0000003ffffff010: sd	ra, 0x28(a0)          02 15 34 23
    0x0000003ffffff014: sd	sp, 0x30(a0)          02 25 38 23
    0x0000003ffffff018: sd	gp, 0x38(a0)          02 35 3c 23
    0x0000003ffffff01c: sd	tp, 0x40(a0)          04 45 30 23
    0x0000003ffffff020: sd	t0, 0x48(a0)          04 55 34 23
    0x0000003ffffff024: sd	t1, 0x50(a0)          04 65 38 23
    0x0000003ffffff028: sd	t2, 0x58(a0)          04 75 3c 23
    0x0000003ffffff02c: sd	s0, 0x60(a0)          06 85 30 23
    0x0000003ffffff030: sd	s1, 0x68(a0)          06 95 34 23
    0x0000003ffffff034: sd	a1, 0x78(a0)          06 b5 3c 23
    0x0000003ffffff038: sd	a2, 0x80(a0)          08 c5 30 23
    0x0000003ffffff03c: sd	a3, 0x88(a0)          08 d5 34 23
    0x0000003ffffff040: sd	a4, 0x90(a0)          08 e5 38 23
    0x0000003ffffff044: sd	a5, 0x98(a0)          08 f5 3c 23
    0x0000003ffffff048: sd	a6, 0xa0(a0)          0b 05 30 23
    0x0000003ffffff04c: sd	a7, 0xa8(a0)          0b 15 34 23
    0x0000003ffffff050: sd	s2, 0xb0(a0)          0b 25 38 23
    0x0000003ffffff054: sd	s3, 0xb8(a0)          0b 35 3c 23
    0x0000003ffffff058: sd	s4, 0xc0(a0)          0d 45 30 23
    0x0000003ffffff05c: sd	s5, 0xc8(a0)          0d 55 34 23
    0x0000003ffffff060: sd	s6, 0xd0(a0)          0d 65 38 23
    0x0000003ffffff064: sd	s7, 0xd8(a0)          0d 75 3c 23
    0x0000003ffffff068: sd	s8, 0xe0(a0)          0f 85 30 23
    0x0000003ffffff06c: sd	s9, 0xe8(a0)          0f 95 34 23
    0x0000003ffffff070: sd	s10, 0xf0(a0)         0f a5 38 23
    0x0000003ffffff074: sd	s11, 0xf8(a0)         0f b5 3c 23
    0x0000003ffffff078: sd	t3, 0x100(a0)         11 c5 30 23
    0x0000003ffffff07c: sd	t4, 0x108(a0)         11 d5 34 23
    0x0000003ffffff080: sd	t5, 0x110(a0)         11 e5 38 23
    0x0000003ffffff084: sd	t6, 0x118(a0)         11 f5 3c 23
    0x0000003ffffff088: csrr	t0, sscratch        14 00 22 f3
    0x0000003ffffff08c: sd	t0, 0x70(a0)          06 55 38 23
    0x0000003ffffff090: ld	sp, 8(a0)             00 85 31 03
    0x0000003ffffff094: ld	tp, 0x20(a0)          02 05 32 03
    0x0000003ffffff098: ld	t0, 0x10(a0)          01 05 32 83
    0x0000003ffffff09c: ld	t1, 0(a0)             00 05 33 03
    0x0000003ffffff0a0: sfence.vma               12 00 00 73
    0x0000003ffffff0a4: csrw	satp, t1            18 03 10 73
    0x0000003ffffff0a8: sfence.vma               12 00 00 73
    0x0000003ffffff0ac: jalr	t0                  00 02 80 e7
```
