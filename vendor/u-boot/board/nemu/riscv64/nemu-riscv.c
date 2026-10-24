// SPDX-License-Identifier: GPL-2.0+
/*
 * ysyx-workbench NEMU RV64 S-mode board support.
 *
 * NEMU loads one raw binary at 0x80000000.  OpenSBI sits in the first
 * 2 MiB and jumps to U-Boot at 0x80200000.  Any EFI application, kernel
 * or initramfs that is preloaded by the NEMU image is already in RAM
 * before U-Boot starts.  The EFI loader allocates from the top of RAM
 * downwards, so without an explicit reservation it can allocate over
 * those preloaded regions and corrupt them while copying the PE image.
 *
 * 'nemu_reserve' lets the boot script tell LMB (and therefore the EFI
 * memory map) that such a region is already in use.  It is intentionally
 * board-specific: it describes how the NEMU image loader laid out RAM.
 */

#include <command.h>
#include <lmb.h>
#include <vsprintf.h>

static int do_nemu_reserve(struct cmd_tbl *cmdtp, int flag, int argc,
			   char *const argv[])
{
	phys_addr_t addr, size;
	int ret;

	if (argc != 3)
		return CMD_RET_USAGE;

	addr = hextoul(argv[1], NULL);
	size = hextoul(argv[2], NULL);
	if (!addr || !size)
		return CMD_RET_USAGE;

	ret = lmb_alloc_mem(LMB_MEM_ALLOC_ADDR, 0, &addr, size, LMB_NONE);
	if (ret) {
		printf("nemu_reserve: cannot reserve 0x%llx+0x%llx (ret=%d)\n",
		       (unsigned long long)addr, (unsigned long long)size, ret);
		return CMD_RET_FAILURE;
	}

	printf("nemu_reserve: 0x%llx-0x%llx reserved\n",
	       (unsigned long long)addr,
	       (unsigned long long)(addr + size - 1));

	return CMD_RET_SUCCESS;
}

U_BOOT_CMD(nemu_reserve, 3, 0, do_nemu_reserve,
	   "reserve RAM holding a payload preloaded by NEMU",
	   "<addr> <size>\n"
	   "    Mark a region as used before bootefi/bootm so that U-Boot's\n"
	   "    LMB and EFI allocators do not overwrite it.");
