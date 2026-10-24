Chisel Project Template
=======================

Another version of the [Chisel template](https://github.com/ucb-bar/chisel-template) supporting mill.
mill is another Scala/Java build tool without obscure DSL like SBT. It is much faster than SBT.

Contents at a glance:

* `.gitignore` - helps Git ignore junk like generated files, build products, and temporary files.
* `build.mill` - instructs mill to build the Chisel project
* `Makefile` - rules to call mill
* `src/GCD.scala` - GCD source file
* `src/DecoupledGCD.scala` - another GCD source file
* `src/Elaborate.scala` - wrapper file to call chisel command with the GCD module
* `csrc/Test/unit/src` - Scala/Chisel 单元测试
* `csrc/Test/sbi` - OpenSBI/RustSBI 冒烟测试资产

Feel free to rename or delete files under `src/` or use them as a reference/template.

## Getting Started

First, install mill by referring to the documentation [here](https://com-lihaoyi.github.io/mill).

To run all tests in this design (recommended for test-driven development):
```bash
make test
```

To generate Verilog:
```bash
make verilog
```

## 可选 SBI 固件

NPC 支持 OpenSBI / RustSBI 两种可选的 M 模式 SBI 固件，默认关闭
（`CONFIG_SBI_ENABLE=n`），xv6 仍直接以 M 模式启动。RustSBI 的来源、
Kconfig、构建和测试见 [README_RUSTSBI.md](README_RUSTSBI.md)。
构建 RustSBI：

```bash
make rustsbi
```

需要给 Linux 传递设备树时，使用 `rv64_sbi_fdt_defconfig` 或打开
`CONFIG_SBI_FDT_ENABLE=y`：

```bash
make rv64_sbi_fdt_defconfig
make sbi
make sbi-test
```

DTB 源文件在 `npc/dts/npc.dts`，默认加载/传递地址
`SBI_FDT_ADDR=0x80f00000`；标准 Linux 布局使用 `0x83f00000`。
CPU ISA、`mmu-type` 和 timebase 由 Kconfig 自动生成，`make dtb`
可单独生成到 `build/dtb/`。Linux Image/initramfs 打包见
`make rv64_linux_defconfig && make linux-image`（不会下载/编译 Linux）。

direct-NPC RAM 默认 64 MiB，由 `CONFIG_NPC_RAM_SIZE` 统一控制。
Debian EFI 内核经 U-Boot 启动需要 128 MiB：

```bash
make rv64_linux_uboot_128m_defconfig
make -j"$(nproc)" build/ysyx_26030103_npc
NPC_UBOOT_KERNEL_PATH=/path/to/debian/install/vmlinux \
NPC_UBOOT_INITRAMFS_PATH=/path/to/busybox-initramfs.cpio.gz \
  make uboot-image
./build/ysyx_26030103_npc build/uboot/npc-uboot.bin --batch
```

布局和 NEMU 差异见 [`../UBOOT_NPC.md`](../UBOOT_NPC.md)。

## BusyBox rootfs / initramfs

rootfs 默认关闭（`CONFIG_ROOTFS_ENABLE=n`）。当前只实现 BusyBox
1.36.1，源码在 `vendor/busybox`，构建时不做网络下载。该流程只生成
rootfs/initramfs，**不下载、编译或运行 Linux kernel**。

```bash
make menuconfig          # 根文件系统 (rootfs) -> 启用 rootfs -> BusyBox
make rootfs
```

`ROOTFS_ENABLE=n` 时 `make rootfs` 会明确报错，不会静默跳过。主要
Kconfig 选项：

- `ROOTFS_BUSYBOX_TOOLCHAIN_PREFIX`：默认 `riscv64-linux-gnu-`；
- `ROOTFS_BUSYBOX_MARCH` / `ROOTFS_BUSYBOX_MABI`：默认 `rv64gc` / `lp64d`；
- `ROOTFS_BUSYBOX_STATIC`：默认 y，当前只支持静态链接；
- `ROOTFS_BUSYBOX_INIT_SCRIPT`：默认 y，生成 BusyBox sh 的 `/init`；
  关闭后使用 `/sbin/init` 和最小 `/etc/inittab`；
- `ROOTFS_BUSYBOX_APPLETS`：`/bin` 下创建的 applet 符号链接集合；
- `ROOTFS_OUT_DIR`：默认 `build/rootfs`。

构建脚本是 `scripts/build_rootfs_busybox.sh`，在
`build/rootfs/busybox/` 做 out-of-tree 构建，不修改 `vendor/busybox`。
输出：

```text
build/rootfs/busybox/busybox                  # 静态 RV64GC/lp64d BusyBox
build/rootfs/tree/                            # 最小 rootfs 树
build/rootfs/busybox-initramfs.cpio.gz        # newc + gzip
build/rootfs/busybox-initramfs.cpio.gz.info   # 大小、sha256、版本、工具链、ABI
```

脚本会用 `readelf` 审计 ELF 是 64 位 RISC-V、double-float ABI、
静态链接且没有动态解释器。检查与清理：

```bash
gzip -dc build/rootfs/busybox-initramfs.cpio.gz | cpio -it
readelf -h build/rootfs/busybox/busybox
readelf -A build/rootfs/busybox/busybox
make rootfs-clean
```

剩余限制：镜像只包含 BusyBox 和空目录，不含内核模块、网络配置或
设备节点；`/dev/console` 由 `/init` 在运行时创建。initramfs 没有
写死加载地址，由内核/引导加载器自行决定放置位置；内核仍需由用户
自行准备，并通过其 initramfs/initrd 加载机制传入该 cpio.gz。
