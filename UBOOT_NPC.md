# U-Boot EFI / Debian 内核 on NPC

本文记录 NPC（ysyx-workbench RTL 仿真）上的 U-Boot S-mode EFI 启动路径、
128 MiB RAM 配置、与 NEMU 的差异，以及 Debian riscv64 EFI 内核的实际
运行结果。

本轮**没有下载/编译 Linux，也没有构建 rootfs**。Debian
`install/vmlinux`（EFI PE，31,165,952 字节）和 BusyBox initramfs 都是
调用者提供的现成文件。

## 1. 结论

| 项目 | 状态 |
| --- | --- |
| NPC RAM 参数化 | **通过**，默认 64 MiB，`rv64_linux_uboot_128m_defconfig` 为 128 MiB |
| OpenSBI -> U-Boot S-mode | **通过**，U-Boot 2026.07 banner / prompt 正常 |
| U-Boot DRAM / DTB | **通过**，`DRAM: 128 MiB`，DTB `/memory` 为 128 MiB |
| U-Boot console / mtest | **通过**，`bdinfo` / `coninfo` / `mtest` 正常 |
| EFI `bootefi` + Debian vmlinux | **通过**，越过 `EFI stub: Exiting boot services...` |
| Linux earlycon / `Linux version` | **通过**，Linux 6.12.107 打印版本、SBI 3.0、earlycon、EFI 信息 |
| 用户态 `/init` marker | 本轮未等到；earlycon 已满足阶段验收 |

## 2. 固定布局

`npc/configs/rv64_linux_uboot_128m_defconfig`：

| 地址 | 内容 | 说明 |
| --- | --- | --- |
| `0x80000000` | OpenSBI FW_JUMP | 预留 2 MiB，实际约 261 KiB |
| `0x80200000` | U-Boot | `CONFIG_TEXT_BASE` / `SBI_NEXT_ADDR` |
| `0x80600000` | 可选 EFI 测试应用 | 默认 U-Boot `initrddump.efi` |
| `0x80800000` | 可选 initramfs | BusyBox（EFI_LOAD_FILE2） |
| `0x81000000` | 可选 EFI 内核 | Debian `install/vmlinux`（EFI PE） |
| `0x87f00000` | DTB | `SBI_FDT_ADDR`，OpenSBI 复制并通过 `a1` 传入 |
| `0x88000000` | RAM 结束 | 128 MiB |

默认 `rv64_linux_defconfig` 仍是 64 MiB，DTB 在 `0x83f00000`，
RAM 结束于 `0x84000000`。RAM 大小由 `CONFIG_NPC_RAM_SIZE` 统一决定，
并同时传入：

- `riscv32e_npc_AXIRAM` 深度和 `RamLast`；
- CPU PMA 的 RAM 区间；
- C++ `CONFIG_MSIZE` / `ImageLoader` 容量 / `InitializeMemory`；
- `dts/npc.dts` 的 `/memory` 和 reserved-memory 边界；
- OpenSBI/DTB/U-Boot 打包脚本的地址校验。

U-Boot 会重定位到 RAM 顶部，因此 OpenSBI 最初放在 `0x87f00000` 的
DTB 会被覆盖；U-Boot 在重定位前把工作 FDT 复制到 `fdt_blob`
（实测 `0x87b7e6c0`），Linux 使用这份工作 FDT。`fdt addr 0x87f00000`
在 U-Boot prompt 下会报 `FDT_ERR_BADMAGIC`，应使用
`fdt addr ${fdtcontroladdr}`。

## 3. 与 NEMU 的差异

| 项目 | NEMU | NPC |
| --- | --- | --- |
| timebase | 1 MHz | **450 MHz**（`CONFIG_SYNTH_FREQ=450`，mtime 每周期 +1） |
| U-Boot bootdelay | 2 s（2M cycles） | 构建时覆盖为 **-1**，直接进 prompt；否则要等 900M cycles |
| 终端尺寸查询 | 无阻塞/快速返回 | EFI console 发送 `ESC[6n`，驱动必须回 `ESC[24;80R` |
| RAM | `CONFIG_MSIZE` | `CONFIG_NPC_RAM_SIZE`（默认 64 MiB，U-Boot 配置 128 MiB） |
| DTB 生成 | `nemu/dts/nemu.dts`，1 MHz | `npc/dts/npc.dts`，450 MHz |
| U-Boot 板级 | `board/nemu/riscv64` | 复用同一板级（S-mode UART/CLINT/PLIC 地址与 SBI 接口相同） |

U-Boot 的 `board/nemu/riscv64` 在当前场景下没有 NPC 专属 MMIO：两者
都使用 `serial@10000000`、CLINT `0x02000000`、PLIC `0x0c000000`，并且
都通过 OpenSBI `platform/npc` 提供 SBI timer/console。因此 NPC 侧只
新增构建/打包适配，不改 NEMU 的调试代码。

## 4. 构建

```sh
cd npc
make rv64_linux_uboot_128m_defconfig
make -j"$(nproc)" build/ysyx_26030103_npc

# 只验证 U-Boot：
make uboot-image

# 附带 BusyBox initramfs + Debian EFI 内核：
NPC_UBOOT_KERNEL_PATH=/path/to/debian/install/vmlinux \
NPC_UBOOT_INITRAMFS_PATH=/path/to/busybox-initramfs.cpio.gz \
  make uboot-image
```

输出：

```text
npc/build/uboot/npc-uboot.bin
npc/build/uboot/npc-uboot.bin.info
npc/build/uboot/uboot-build/u-boot.bin
npc/build/uboot/opensbi/fw.bin
npc/build/uboot/dtb/npc.dtb
```

运行：

```sh
cd npc
./build/ysyx_26030103_npc build/uboot/npc-uboot.bin --batch
```

`--batch` 会让 SDB 直接执行 `c`，同时把 stdin 转发给模拟 16550。
U-Boot prompt 下可直接输入命令。由于 NPC 的 timebase 是 450 MHz，
U-Boot 构建脚本把 `.config` 的 `CONFIG_BOOTDELAY` 改为 `-1`；这只是
构建产物覆盖，不修改 `vendor/u-boot` 的 defconfig。

## 5. U-Boot 日志（128 MiB）

```text
OpenSBI v1.9
...
Platform Timer Device       : aclint-mtimer @ 450000000Hz
Firmware Base               : 0x80000000
Domain0 Next Address        : 0x0000000080200000
Domain0 Next Arg1           : 0x0000000087f00000

U-Boot 2026.07 (Oct 24 2026 - 17:55:37 +0800)

CPU:   riscv
Model: ysyx,npc
DRAM:  128 MiB
Core:  11 devices, 7 uclasses, devicetree: board
Loading Environment from nowhere... OK
In:    serial@10000000
Out:   serial@10000000
Err:   serial@10000000
=> bdinfo
DRAM bank   = 0x0000000000000000
-> start    = 0x0000000080000000
-> size     = 0x0000000008000000
relocaddr   = 0x0000000087f9f000
fdt_blob    = 0x0000000087b7e6c0
 memory[0]   [0x80000000-0x87ffffff], 0x8000000 bytes, flags: none
 reserved[3] [0x87b3e6b0-0x87ffffff], 0x4c1950 bytes, flags: no-overwrite
firmware fdt= 0x0000000087f00000

=> coninfo
|-- serial@10000000 (IO)
|   |-- stdin
|   |-- stdout
|   |-- stderr

=> mtest 0x80400000 0x8040ffff 0 1
Tested 1 iteration(s) with 0 errors.

=> fdt addr ${fdtcontroladdr}
Working FDT set to 87b7e6c0
=> fdt print /memory
memory@80000000 {
	device_type = "memory";
	reg = <0x00000000 0x80000000 0x00000000 0x08000000>;
};
```

## 6. Access Fault 根因与修复

首次在 NPC 上运行 Debian EFI 内核时，`EFI stub: Exiting boot services...`
之后立即出现：

```text
Access Fault [RESP=0] at PC=0x000000008460688a
trap_tval = 0x000000008460688a
```

PC 位于 128 MiB RAM 内，且 U-Boot prompt 下用 `go` 执行
`0x84600000` 的探针代码正常，排除 RAM/PMA/AXI 基本读写问题。最终定位
到三个独立缺陷：

1. **RV64 PMP 配置字布局错误**。`pmpcfg0`/`pmpcfg2` 在 RV64 是
   64 位 CSR，每个容纳 8 个 8 位配置项；原实现把 16 项拆成 4 个
   32 位寄存器（`0x3a0..0x3a3`），OpenSBI 写入 `pmpcfg0` 的高 32 位
   （entry 4..7）被丢弃。域的全内存 NAPOT entry 正好落在高位，
   丢失后 S 模式取指无处匹配。CSR 已改为 RV64 64 位布局。
2. **PMP 取指检查用了虚拟地址**。MMU 打开后必须检查翻译后的物理
   地址；原实现把 `ifu.FetchAddr`（VA）送入 PMP。高虚拟地址永远
   不匹配低物理地址的 NAPOT 项。现在 MMU 分支使用 IMMU 的
   `fetchPaddr`，Bare 分支仍用原地址。
3. **direct-NPC PMA 缺少 PLIC**。PLIC 在 CPU 内部并接到 Xbar，但
   `PhysicalMemoryMap.NPC` 只列出 CLINT/UART/RAM。U-Boot/Linux 在
   S 模式访问 PLIC enable/threshold/claim 会被 PMA 拒绝（Store/AMO
   access fault），外部中断也无法清除。已加入
   `0x0c000000 + 0x04000000` 的 PLIC 窗口，与 DTB 一致。

另外，128 MiB 配置必须使用 `CONFIG_MMU_LEVELS=4`（Sv48）。
Debian 6.12 在 `no5lvl` 下选择 Sv48；若 RTL 仍是 Sv39，NPC CSR 会把
`satp.MODE=9` 按 WARL 收敛到 Bare，内核在 `csrw satp` 后的下一条
取指就会 Access Fault。

## 7. Linux 实际启动日志（128 MiB）

```text
OpenSBI v1.9
Platform Timer Device       : aclint-mtimer @ 450000000Hz
Domain0 Next Address        : 0x0000000080200000
Domain0 Next Arg1           : 0x0000000087f00000

U-Boot 2026.07 (Oct 24 2026 - 18:16:44 +0800)
CPU:   riscv
Model: ysyx,npc
DRAM:  128 MiB
...
=> bootefi 0x81000000:0x1db8e00 0x80800000:0x120548
Booting <NULL>
EFI stub: Booting Linux Kernel...
EFI stub: Loaded initrd from LINUX_EFI_INITRD_MEDIA_GUID device path
EFI stub: Using DTB from configuration table
EFI stub: Exiting boot services...
[    0.000000] Linux version 6.12.107+deb13-riscv64 (debian-kernel@lists.debian.org) (riscv64-linux-gnu-gcc-14 (Debian 14.2.0-19) 14.2.0, GNU ld (GNU Binutils for Debian) 2.44) #1 SMP Debian 6.12.107-1 (2026-08-29)
[    0.000000] Machine model: ysyx,npc
[    0.000000] SBI specification v3.0 detected
[    0.000000] SBI implementation ID=0x1 Version=0x10009
[    0.000000] SBI TIME extension detected
[    0.000000] SBI IPI extension detected
[    0.000000] SBI RFENCE extension detected
[    0.000000] SBI DBCN extension detected
[    0.000000] earlycon: sbi0 at I/O port 0x0 (options '')
[    0.000000] printk: legacy bootconsole [sbi0] enabled
[    0.000000] Disabled 5-level paging
[    0.000000] efi: EFI v2.11 by Das U-Boot
[    0.000000] efi: RTPROP=0x87b17040 SMBIOS 3.0=0x87b82000 INITRD=0x879e3040 MEMRESERVE=0x879e2040
[    0.000000] secureboot: Secure boot disabled
```

`Disabled 5-level paging` 证实 kernel 使用了 Sv48 页表，而不是
被 WARL 收敛到 Bare。

## 8. 已知限制

- NPC 是 RTL 仿真，加载/复制 31 MB EFI PE 需要大量仿真周期；不能用
  NEMU 的墙钟时间估算。
- U-Boot EFI console 会阻塞等待终端尺寸查询应答；自动化驱动必须
  回应 `ESC[6n`。这是 U-Boot `lib/efi_loader/efi_console.c` 的行为，
  不是 NPC 独有。
- Debian 6.12 默认选择 Sv57；必须传 `no5lvl`。`no5lvl` 之后内核选择
  **Sv48**（不是 Sv39），因此 `rv64_linux_uboot_128m_defconfig` 必须
  使用 `CONFIG_MMU_LEVELS=4`（DTB `mmu-type=riscv,sv48`）。如果仍用
  3 级 Sv39，NPC CSR 会把 `satp.MODE=9` 按 WARL 收敛到 Bare，内核在
  `csrw satp` 后的下一条取指就会 `Access Fault`。
- U-Boot 只启用串口；没有 virtio/网络/磁盘/PCI/USB/视频，也没有持久
  环境。
- 本次复用 NEMU U-Boot 板级；若以后 NPC 增加独立板级/设备，需要把
  `build_uboot_npc.sh` 的 `NPC_UBOOT_DEFCONFIG` 指到新配置。
- 本轮验收停在 earlycon；尚未等到 `Run /init`。按用户要求未运行
  fastfetch，也没有应用 8 KiB cache/10 GHz/LTO 等性能配置。
- 直接运行 full Debian 需要数十分钟到数小时；调试时不要把 SBI ecall
  （cause 9）误判为外部中断风暴。
