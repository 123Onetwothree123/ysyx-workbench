        #
        # Embed the xv6 filesystem image as the NPC ramdisk.
        #
        # fs.img is produced by mkfs/mkfs from README and the user programs.
        # The section is placed by kernel.ld after .bss, so `end` (the start
        # of the page allocator) is above it and the ramdisk is never freed.
        #
.section .ramdisk, "aw", @progbits
.balign 4096
.globl _ramdisk_start
_ramdisk_start:
.incbin "fs.img"
.globl _ramdisk_end
_ramdisk_end:
.balign 4096
