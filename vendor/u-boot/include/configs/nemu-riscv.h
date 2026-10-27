/* SPDX-License-Identifier: GPL-2.0+ */
/*
 * ysyx-workbench NEMU RV64 S-mode configuration.
 *
 * 固定布局（与 nemu/scripts/build_uboot_nemu.sh 一致）：
 *   0x80000000  OpenSBI FW_JUMP（预留 2 MiB）
 *   0x80200000  U-Boot
 *   0x80600000  可选 EFI 测试应用（如 initrddump.efi）
 *   0x80800000  可选 initramfs（BusyBox 或 Debian installer initrd）
 *   0x81000000  可选 Debian EFI vmlinux（bootefi 目标）
 *   0x83f00000  OpenSBI 传入的 DTB（由 U-Boot 重定位/保留）
 *   0x84000000  RAM 结束（64 MiB）
 */

#ifndef __NEMU_RISCV_CONFIG_H
#define __NEMU_RISCV_CONFIG_H

#include <linux/sizes.h>

#define CFG_SYS_SDRAM_BASE		0x80000000

/* NEMU DTB / OpenSBI platform/npc 的 timebase 契约：1 MHz。 */
#define RISCV_SMODE_TIMER_FREQ		1000000

/* NEMU 只提供 16550，不启用键盘/视频控制台。 */
#define CFG_STD_DEVICES_SETTINGS	"stdin=serial\0" \
					"stdout=serial\0" \
					"stderr=serial\0"

/*
 * kernel_addr_r / ramdisk_addr_r 都在 64 MiB RAM 内。真正的长度由打包
 * 脚本打印，并在 bootefi 命令中显式给出；默认环境只作为提示。
 */
#define CFG_EXTRA_ENV_SETTINGS \
	CFG_STD_DEVICES_SETTINGS \
	"kernel_addr_r=0x81000000\0" \
	"ramdisk_addr_r=0x80800000\0" \
	"efi_app_addr_r=0x80600000\0" \
	"fdt_addr_r=0x83f00000\0" \
	"initrd_high=0xffffffffffffffff\0" \
	"bootargs=console=ttyS0,115200 earlycon=sbi\0"

#endif /* __NEMU_RISCV_CONFIG_H */
