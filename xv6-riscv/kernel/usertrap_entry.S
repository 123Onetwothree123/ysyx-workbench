#
# Trap entry shim for the NPC port.
#
# After uservec's `jalr usertrap`, the NPC IFU/ICache redirect path can deliver
# the instruction word left over from the previous userret (observed: `fence.i`
# from 0x800090b0) together with the new PC.  The first instruction at the
# target (usertrap's `addi sp,sp,-32`) is then lost; the following `sd ra,24(sp)`
# uses the old sp (TRAPFRAME) and faults recursively.
#
# A harmless one-instruction pad absorbs the stale word; the real entry is the
# `j usertrap` at +4.  Remove this shim once the core IFU/ICache replay bug is
# fixed in RTL.  See XV6_NPC_BOOT.md for the evidence.
#
.section .text
.globl usertrap_entry
usertrap_entry:
        nop
        j usertrap
