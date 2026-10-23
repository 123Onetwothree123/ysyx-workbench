        #
        # Machine-mode timer interrupt handler.
        #
        # CLINT raises MTIP; we re-arm mtimecmp and convert the event into a
        # supervisor software interrupt (SSIP), which supervisor-mode xv6
        # handles as a clock tick.  This is the classic xv6 timer design and
        # avoids requiring the Sstc extension (NPC does not implement it).
        #
        # NPC's crossbar rejects multi-beat writes to CLINT, so mtimecmp is
        # updated with two 32-bit stores instead of one 64-bit sd.
        #
        # scratch layout (see start.c):
        #   [0] saved a1, [1] saved a2, [2] saved a3,
        #   [3] CLINT_MTIMECMP address, [4] interval,
        #   [5] saved a4, [6] saved a5
        #
.section .text
.globl timervec
.align 4
timervec:
        csrrw a0, mscratch, a0
        sd a1, 0(a0)
        sd a2, 8(a0)
        sd a3, 16(a0)
        sd a4, 40(a0)
        sd a5, 48(a0)

        ld a1, 24(a0)          # mtimecmp address
        lw a2, 32(a0)          # interval (fits in 32 bits)
        lwu a3, 0(a1)          # mtimecmp low
        lwu a4, 4(a1)          # mtimecmp high
        add a3, a3, a2
        sltu a5, a3, a2        # carry out of low word
        add a4, a4, a5
        sw a4, 4(a1)           # high first, then low
        sw a3, 0(a1)

        li a1, 2
        csrw sip, a1           # raise SSIP

        ld a5, 48(a0)
        ld a4, 40(a0)
        ld a3, 16(a0)
        ld a2, 8(a0)
        ld a1, 0(a0)
        csrrw a0, mscratch, a0
        mret
