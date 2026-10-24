# U-Boot vendored source

本目录是 ysyx-workbench 内置的 U-Boot 源码，**不是 git submodule**，
不包含上游 `.git` 目录。`git add vendor/u-boot` 会把上游源码和下面的
NEMU 板级适配一起纳入主仓库。

## 来源

- 上游：<https://github.com/u-boot/u-boot>
- 版本：`v2026.07`（稳定版）
- tag 指向的 commit：`ece349ade2973e220f524ce59e59711cc919263f`
- 下载地址：<https://codeload.github.com/u-boot/u-boot/tar.gz/refs/tags/v2026.07>
- 下载日期：2026-10-24
- 压缩包 SHA256：`21463d6d4498768d01fa192f6027468c04480d0daa99cd546f9a75a4d9ec353c`
- 许可证：GPL-2.0+（以每个文件 SPDX 为准；例外和第三方许可证见
  `Licenses/README` 与 `Licenses/` 目录）

官方 DENX 站点也提供同一版本的 `u-boot-2026.07.tar.bz2`；本仓库使用
GitHub mirror 的 codeload 包，因为下载在本次环境中更稳定。两者源码
内容一致，但压缩包字节和 SHA256 不同，不能用 DENX 包的哈希校验本目录。

## ysyx-workbench 适配

新增文件：

```text
board/nemu/riscv64/
├── Kconfig                 # TARGET_NEMU_RISCV64 板级选项
├── MAINTAINERS
├── Makefile
└── nemu-riscv.c            # nemu_reserve 命令：为 NEMU 预装载区做 LMB 保留
configs/nemu_riscv64_smode_defconfig
include/configs/nemu-riscv.h
arch/riscv/dts/nemu-riscv64.dts
```

对上游的改动只有：

| 文件 | 改动 |
| --- | --- |
| `arch/riscv/Kconfig` | 新增 `TARGET_NEMU_RISCV64` 选项，并 source `board/nemu/riscv64/Kconfig` |

板级适配要点：

- 以 `qemu-riscv64_smode_defconfig` 的 OpenSBI -> S-mode 模型为基础，
  但使用独立的 `nemu_riscv64_smode_defconfig`，避免 QEMU 的 128 MiB
  默认环境地址越过 NEMU 的 64 MiB RAM。
- `TEXT_BASE=0x80200000`，与 `nemu/scripts/build_linux_fw.sh` 的
  `FW_JUMP` next-stage 地址一致。
- `OF_HAS_PRIOR_STAGE=y`：运行时使用 OpenSBI 通过 `a1` 传入的 DTB，
  不把 U-Boot 自己构建的 DTB 当作最终设备树。
- 只保留 ns16550、SBI timer、SBI sysreset；关闭网络、USB、视频、
  virtio、SCSI/NVMe/PCI 等 NEMU 不提供的设备。
- `CONFIG_EFI_LOADER=y`、`CONFIG_CMD_BOOTEFI=y`、
  `CONFIG_EFI_LOAD_FILE2_INITRD=y`，用于 Debian EFI PE 和 initramfs。
- `CONFIG_STACK_SIZE=0x40000`、`CONFIG_SYS_MALLOC_LEN=0x400000`：
  U-Boot 默认 16 MiB 栈在 64 MiB NEMU 上会占掉过多 RAM。
- `nemu_reserve <addr> <size>` 是新增的板级命令。NEMU 只能加载一个
  raw image，EFI 应用/内核/initrd 在 U-Boot 启动前就已经在 RAM 里；
  U-Boot 的 EFI/LMB 分配器默认不知道这些区域，复制 PE 时可能覆盖
  源数据。`nemu_reserve` 在调用 `bootefi` 前把预装载区加入 LMB 保留。

## 升级方法

1. 用新版本上游源码替换本目录，保留 `board/nemu/riscv64/`、
   `configs/nemu_riscv64_smode_defconfig`、`include/configs/nemu-riscv.h`
   和 `arch/riscv/dts/nemu-riscv64.dts`。
2. 在 `arch/riscv/Kconfig` 中重新加入 `TARGET_NEMU_RISCV64` 和
   `source "board/nemu/riscv64/Kconfig"`。
3. 更新本文件的版本、commit、下载地址和 SHA256。
4. 运行 `nemu/tests/uboot-efi-smoke.sh`，再按 `UBOOT_NEMU.md` 验证
   `bootefi` 和 EFI_LOAD_FILE2 initrd。
