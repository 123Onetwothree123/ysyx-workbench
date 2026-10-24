# U-Boot S-mode on NEMU

本文记录 ysyx-workbench 的 U-Boot S-mode 启动路径、NEMU 板级适配、
EFI loader/initramfs 传递验证，以及 Debian riscv64 EFI 内核在 64 MiB
RAM 下的阻塞结论。

本轮**没有下载/编译 Linux，也没有构建 rootfs**。Debian
`install/vmlinux` 和 BusyBox initramfs 都是调用者提供的现成文件。
64 MiB 默认布局下 Debian 内核在 U-Boot 的 PE loader 中因内存不足
失败，没有进入内核；随后用单独的 128 MiB NEMU 评估配置验证了
EFI -> Debian 6.12.107 -> earlycon/console 路径（见第 5.4 节）。

## 1. 结论

| 项目 | 状态 |
| --- | --- |
| OpenSBI -> U-Boot S-mode | **通过**，U-Boot 2026.07 banner 和 `=>` prompt 正常 |
| NEMU 64 MiB RAM / 16550 / timer | **通过**，`bdinfo`/`coninfo`/`mtest`/`time` 均正常 |
| EFI loader / `bootefi` | **通过**，`bootefi hello` 输出 `Hello, world!` |
| EFI_LOAD_FILE2 initrd 传递 | **通过**，`initrddump.efi` 读到的长度和 CRC32 与 BusyBox initramfs 完全一致 |
| Debian EFI `install/vmlinux` | **64 MiB 阻塞**；**128 MiB 通过**，打印 Linux 6.12.107 + earlycon/console |
| Debian installer initrd (49 MB) | **不可行**，单 initrd 已接近 64 MiB RAM 总量 |
| NPC RTL | **未验证**，本轮只做 NEMU；NPC 的 450 MHz DTB/timebase 不同 |

U-Boot 版本、来源和 SHA256 见 `vendor/u-boot/VENDOR.md`：

- U-Boot `v2026.07`
- commit `ece349ade2973e220f524ce59e59711cc919263f`
- 源码作为普通文件放在 `vendor/u-boot/`，不是 git submodule

## 2. 固定内存布局

NEMU 只能通过 `-b` 加载一个 raw binary。`build_uboot_nemu.sh` 把
OpenSBI、U-Boot 和可选 payload 按固定地址拼成一个镜像：

| 地址 | 内容 | 说明 |
| --- | --- | --- |
| `0x80000000` | OpenSBI FW_JUMP | 预留 2 MiB，实际约 261 KiB |
| `0x80200000` | U-Boot | `CONFIG_TEXT_BASE`，也是 FW_JUMP next-stage |
| `0x80600000` | 可选 EFI 应用 | 默认放 U-Boot 自带的 `initrddump.efi` |
| `0x80800000` | 可选 initramfs | BusyBox 或 Debian installer initrd |
| `0x81000000` | 可选 EFI 内核 | Debian `install/vmlinux`（EFI PE） |
| `0x83f00000` | DTB | OpenSBI 复制并通过 `a1` 传入 |
| `0x84000000` | RAM 结束 | 64 MiB |

U-Boot 自己会重定位到 RAM 顶部，并在 LMB 中保留
`gd->start_addr_sp - CONFIG_STACK_SIZE` 以上的区域。板级配置把
`CONFIG_STACK_SIZE` 从默认 16 MiB 降到 256 KiB、`CONFIG_SYS_MALLOC_LEN`
降到 4 MiB，否则 64 MiB NEMU 会被 U-Boot 自己占掉过多空间。

U-Boot 重定位不会覆盖 DTB：实测 `firmware fdt=0x83f00000`，
`fdt_blob=0x83b7e6e0`（重定位后的工作 FDT），`relocaddr=0x83f9f000`。
U-Boot 的 malloc/FDT/栈都在 RAM 顶部，DTB 原始位置在
`0x83f00000`，两者不重叠。

## 3. 构建与运行

先构建 NEMU 的 riscv64-linux 配置：

```sh
cd nemu
make NEMU_HOME=$PWD ARCH=riscv64-nemu \
  AM_HOME=../abstract-machine riscv64-linux_defconfig
make NEMU_HOME=$PWD ARCH=riscv64-nemu \
  AM_HOME=../abstract-machine -j"$(nproc)"
```

构建 U-Boot + OpenSBI + 镜像：

```sh
# 只验证 U-Boot 启动
./nemu/scripts/build_uboot_nemu.sh

# 附带 BusyBox initramfs；脚本会自动用 initrddump.efi 验证
# EFI_LOAD_FILE2，不会执行 Linux
NEMU_UBOOT_INITRD=/path/to/busybox-initramfs.cpio.gz \
  ./nemu/scripts/build_uboot_nemu.sh

# 再附带 Debian EFI vmlinux（本轮实测会 OOM，见第 5 节）
NEMU_UBOOT_KERNEL=/path/to/debian/install/vmlinux \
NEMU_UBOOT_INITRD=/path/to/busybox-initramfs.cpio.gz \
  ./nemu/scripts/build_uboot_nemu.sh
```

输出：

```text
nemu/build/uboot/nemu-uboot.bin
nemu/build/uboot/nemu-uboot.bin.info
nemu/build/uboot/uboot-build/u-boot.bin
nemu/build/uboot/linux/opensbi/fw.bin
```

运行：

```sh
cd nemu
./build/riscv64-nemu-interpreter -b build/uboot/nemu-uboot.bin
```

`build_uboot_nemu.sh` 会检查 U-Boot 配置里
`CONFIG_EFI_LOADER`、`CONFIG_CMD_BOOTEFI`、
`CONFIG_EFI_LOAD_FILE2_INITRD`、`CONFIG_OF_HAS_PRIOR_STAGE` 和
`CONFIG_TEXT_BASE=0x80200000` 都存在；缺一项直接失败，避免悄悄
生成一个不能跑 EFI 的 U-Boot。

## 4. 已实测的 NEMU 日志

### 4.1 U-Boot S-mode 启动

```text
U-Boot 2026.07 (Oct 24 2026 - 16:34:16 +0800)

CPU:   riscv
Model: ysyx,npc
DRAM:  64 MiB
Core:  11 devices, 7 uclasses, devicetree: board
Loading Environment from nowhere... OK
In:    serial@10000000
Out:   serial@10000000
Err:   serial@10000000

NEMU U-Boot S-mode ready
Use nemu_reserve + bootefi, see UBOOT_NEMU.md
=>
```

`Model: ysyx,npc`、`DRAM: 64 MiB` 说明 U-Boot 使用的是 OpenSBI 传入
的 NEMU DTB，而不是 U-Boot 自带的开发 DTS。

### 4.2 UART / timer / memory

```text
=> bdinfo
DRAM bank   = 0x0000000000000000
-> start    = 0x0000000080000000
-> size     = 0x0000000004000000
baudrate    = 115200 bps
relocaddr   = 0x0000000083f9f000
serial addr = 0x0000000010000000
 clock      = 0x0000000000989680
firmware fdt= 0x0000000083f00000

=> coninfo
List of available devices
|-- serial@10000000 (IO)
|   |-- stdin
|   |-- stdout
|   |-- stderr

=> mtest 0x80400000 0x8040ffff 0 1
Tested 1 iteration(s) with 0 errors.

=> time sleep 1
time: 1.000 seconds
```

- UART：`serial@10000000`，115200 bps，输入/输出/错误都绑定到它。
- timer：`time sleep 1` 使用 SBI timer/RISC-V `time` CSR，实测 1.000 s。
- memory：64 MiB bank，`mtest` 覆盖 64 KiB 无错误。

### 4.3 EFI loader 最小应用

```text
=> bootefi hello
Booting <NULL>
Hello, world!
Running on UEFI 2.11
Firmware vendor: Das U-Boot
Firmware revision: 20260700
Have device tree
Load options: console=ttyS0,115200 earlycon=sbi
```

`bootefi` 和 EFI 系统表可用；U-Boot 把 DT 作为 EFI configuration
table 传给应用，并把 `bootargs` 作为 load options。

### 4.4 EFI_LOAD_FILE2 initrd 传递

使用 U-Boot 自带的 `initrddump.efi`（`lib/efi_loader/initrddump.c`）
读取 initramfs。它只调用 EFI_LOAD_FILE2，不执行 Linux：

```text
=> nemu_reserve 0x80600000 0x50b1
nemu_reserve: 0x80600000-0x806050b0 reserved
=> nemu_reserve 0x80800000 0x1204cc
nemu_reserve: 0x80800000-0x809204cb reserved
=> bootefi 0x80600000:0x50b1 0x80800000:0x1204cc
Booting <NULL>
INITRD Dump
===========
=> load
length: 0x1204cc
crc32: 0x751f116c
=> exit
```

BusyBox initramfs 的实际值：

```text
bytes  = 0x1204cc (1,180,876)
crc32  = 0x751f116c
```

两者完全一致，说明 U-Boot 的 `bootefi <image>[:size] <initrd>[:size]`
路径确实把 initrd 通过 EFI_LOAD_FILE2 交给了 EFI 应用。

Linux EFI stub 使用同一机制：U-Boot 在
`lib/efi_loader/efi_load_initrd.c` 中注册 Linux 专用的
`EFI_INITRD_MEDIA_GUID` 设备路径和 `EFI_LOAD_FILE2_PROTOCOL`；
Linux 5.7+ 的 EFI stub 用 `LoadFile` 查询长度、分配页、读取整个
initrd，然后以内存中的 initrd 启动内核。U-Boot 不会把 initrd 写进
DT 的 `linux,initrd-start/end`，而是走 EFI_LOAD_FILE2；这避免了大
initrd 在 DTB 里做 32/64 位属性兼容。

`nemu_reserve` 是必须的：NEMU 在 U-Boot 启动前就把 EFI 应用/内核/
initrd 放在固定地址，而 U-Boot 的 EFI 分配器默认从 RAM 顶部向下
分配。不保留这些区域时，`efi_load_pe()` 分配目标页可能覆盖源 PE
或 initrd。`nemu_reserve` 调用
`lmb_alloc_mem(LMB_MEM_ALLOC_ADDR, ...)`，在 EFI memory map 建立前
把预装载区标记为已用。

## 5. Debian EFI vmlinux：64 MiB 下的阻塞

### 5.1 输入

```text
/tmp/opencode/debian-iso/install/vmlinux
  size       = 31,165,952 (0x1db8e00)
  format     = MZ/PE32+ EFI, RISC-V machine 0x5064
  sections   = 2 (.text, .data)
  ImageBase  = 0
  SizeOfImage= 0x1e6b000
  virt_size  = 0x1e6b000 (31,895,552)
```

### 5.2 实测命令和结果

```text
=> nemu_reserve 0x80800000 0x1204cc
nemu_reserve: 0x80800000-0x809204cb reserved
=> nemu_reserve 0x81000000 0x1db8e00
nemu_reserve: 0x81000000-0x82db8dff reserved
=> bootefi 0x81000000:0x1db8e00 0x80800000:0x1204cc
Booting <NULL>
Out of memory
Loading image failed
```

失败点是 U-Boot 的 `lib/efi_loader/efi_image_loader.c:efi_load_pe()`：

```c
efi_reloc = efi_alloc_aligned_pages(virt_size,
                                    loaded_image_info->image_code_type,
                                    opt->SectionAlignment);
if (!efi_reloc) {
        log_err("Out of memory\n");
        ret = EFI_OUT_OF_RESOURCES;
        goto err;
}
...
memcpy(efi_reloc + sec->VirtualAddress,
       efi + sec->PointerToRawData,
       copy_size);
```

它**总是**为 PE 的 `virt_size` 新分配一份内存，再把源 PE 的 section
复制过去；没有 in-place 执行的选项。因此 64 MiB 下需要同时容纳：

| 区域 | 大小 |
| --- | --- |
| OpenSBI 预留 | 2 MiB |
| U-Boot + malloc + 栈 + FDT（实测 LMB reserved） | 约 4.75 MiB |
| Debian vmlinux 源文件 | 29.7 MiB |
| BusyBox initramfs | 1.13 MiB |
| **剩余给 EFI PE 目标页** | **约 26.4 MiB** |
| `efi_load_pe()` 需要 | **30.4 MiB** |

缺口约 4 MiB，所以 `efi_alloc_aligned_pages()` 直接失败。

即使把 U-Boot 自身占用压到约 1 MiB，剩余也只有约 31.4 MiB，仅比
PE 目标页多约 1 MiB；Linux EFI stub 还需要再分配约 1.13 MiB 的
initrd buffer，之后内核还要页表、memblock、slab 等内存。这个余量
不足以可靠启动，不能据此声称 Debian 内核可启动。

Debian installer 的 `initrd.gz` 是 49,382,269 字节，单它就已经
超过 64 MiB RAM 扣除固件/U-Boot 后的可用空间；即使 U-Boot 能加载
vmlinux，也无法同时放入 installer initrd。

### 5.3 不是缺失 UEFI 协议

失败信息是 `Out of memory`，不是 `EFI_UNSUPPORTED` 或找不到协议。
同一 U-Boot 已经能：

- `bootefi hello`：EFI 2.11 应用正常运行；
- `initrddump.efi`：EFI_LOAD_FILE2 返回完整的 0x1204cc 字节，CRC32
  匹配；
- `bootefi` 接受 `image:size initrd:size` 参数，并建立 EFI memory map。

所以阻塞点是 64 MiB RAM 与 U-Boot PE loader 的“双份内核”模型，
不是 UEFI 服务缺失。

### 5.4 128 MiB 评估配置：Debian 内核真正开始执行

为了区分“U-Boot EFI 路径错误”和“64 MiB 物理内存不足”，新增了
`nemu/configs/riscv64-linux-128m_defconfig`，只把 `CONFIG_MSIZE` 从
`0x04000000` 改为 `0x08000000`，设备/timebase 契约不变。用该配置
重新生成 DTB（memory 128 MiB，DTB 在 `0x87f00000`）和 OpenSBI：

```sh
cd nemu
make NEMU_HOME=$PWD ARCH=riscv64-nemu \
  AM_HOME=../abstract-machine riscv64-linux-128m_defconfig
make NEMU_HOME=$PWD ARCH=riscv64-nemu AM_HOME=../abstract-machine -j"$(nproc)"

NEMU_UBOOT_CONFIG_FILE=$PWD/.config \
NEMU_UBOOT_INITRD=/path/to/busybox-initramfs.cpio.gz \
NEMU_UBOOT_KERNEL=/path/to/debian/install/vmlinux \
  ./scripts/build_uboot_nemu.sh
```

U-Boot `bdinfo` 显示 `DRAM: 128 MiB`，LMB 中 U-Boot 只占
`0x87b3e6d0-0x87ffffff`，与 `0x80800000` 的 initrd、
`0x81000000` 的 kernel 源文件不重叠。`bootefi` 后 EFI stub 和
Debian 内核实际输出：

```text
=> setenv bootargs console=ttyS0,115200 earlycon=sbi no5lvl
=> bootefi 0x81000000:0x1db8e00 0x80800000:0x1204cc
Booting <NULL>
EFI stub: Booting Linux Kernel...
EFI stub: Loaded initrd from LINUX_EFI_INITRD_MEDIA_GUID device path
EFI stub: Using DTB from configuration table
EFI stub: Exiting boot services...
[    0.000000] Linux version 6.12.107+deb13-riscv64 (debian-kernel@lists.debian.org) ...
[    0.000000] earlycon: sbi0 at I/O port 0x0 (options '')
[    0.000000] printk: legacy bootconsole [sbi0] enabled
[    0.000000] Kernel command line: console=ttyS0,115200 earlycon=sbi no5lvl
...
[    4.769067] Memory: 54224K/131072K available
[    4.974113] devtmpfs: initialized
[    6.756295] DMA: preallocated 128 KiB GFP_KERNEL pool for atomic allocations
[    6.939176] thermal_sys: Registered thermal governor 'fair_share'
```

这说明 U-Boot 的 `bootefi`、EFI_LOAD_FILE2 initrd、EFI configuration
table DTB 和 Debian EFI stub 都是连通的；64 MiB 的失败只是物理 RAM
不足。

`no5lvl` 是必需的：Debian 6.12 默认选择 Sv57（`satp.MODE=10`），
而本树 NEMU 只实现 Bare/Sv39/Sv48，会明确 panic：

```text
不支持的 RV64 satp.MODE=10（仅支持 Bare/Sv39/Sv48）
```

传 `no5lvl` 后内核降级到 Sv48，NEMU 可以继续启动。这不是 U-Boot
的问题，也不影响 NPC（NPC 的 RTL 是否支持 Sv57 需要单独评估）。

### 5.5 `CONFIG_SYS_MALLOC_LEN` 不是根因

U-Boot 的 EFI page allocator 走
`efi_allocate_pages() -> lmb_alloc_mem()`，不是 U-Boot 的
`malloc()`；`CONFIG_SYS_MALLOC_LEN` 只决定 U-Boot 自身堆的大小，
并且这段堆位于 RAM 顶部、会被 LMB 一并保留。把它从 4 MiB 增大到
32 MiB 后，128 MiB 下 U-Boot 保留区扩大到
`0x85f3e6d0-0x87ffffff`，虽然仍不与 kernel/initrd 重叠，但 EFI
stub 随后报：

```text
EFI stub: ERROR: Failed to relocate kernel
```

4 MiB 配置反而能正常进入 Linux。因此本板保留
`CONFIG_SYS_MALLOC_LEN=0x400000`；64 MiB 下增大它只会进一步压缩
EFI 可用空间。

### 5.6 最小内存要求

按本布局的硬性组成：

```text
OpenSBI 预留                 2 MiB
U-Boot（text+malloc+stack） ~1-5 MiB
Debian vmlinux 源文件       29.7 MiB
BusyBox initramfs            1.1 MiB
efi_load_pe() 目标页        30.4 MiB
EFI pool/initrd buffer      ~1-3 MiB
```

仅 loader 阶段就需要约 66-71 MiB，64 MiB 无解。加上 Linux 自身
页表/slab/initramfs 运行空间，建议至少 96 MiB，**实测 128 MiB 可用**。
`nemu/configs/riscv64-linux-128m_defconfig` 是当前验证过的评估配置；
默认 `riscv64-linux_defconfig` 仍是 64 MiB，NPC 仍是 64 MiB/450 MHz
契约。

## 6. 替代方案

1. **把 NEMU RAM 提高到 128 MiB**（已实现并验证）。
   使用 `nemu/configs/riscv64-linux-128m_defconfig`，重新生成
   DTB/OpenSBI；U-Boot 布局不变，`0x81000000` 的 kernel 和
   `0x80800000` 的 initrd 都有足够空间。默认 64 MiB 配置不变。
2. **Debian raw `Image` + `booti`**：如果拿到未加 EFI PE 头的
   RISC-V `Image`，U-Boot `booti` 只需一份内核，64 MiB 更宽裕；但
   本轮用户明确要求走 EFI 兼容路径，且现有文件是 EFI PE，不能用
   `booti`。
3. **OpenSBI -> Linux 直接启动**：用现有
   `nemu/scripts/pack_linux_image.sh` 把 raw Image 放 `0x80200000`，
   不经过 U-Boot，也没有 EFI PE 的第二次复制。这是 64 MiB 下最省
   内存的路径，但放弃 U-Boot 的 EFI 兼容能力。
4. **U-Boot 只作为 EFI 应用/加载器**：本轮已验证最小 EFI 应用和
   EFI_LOAD_FILE2 initrd；如果目标只是验证 U-Boot EFI 栈，这已经
   足够，不需要启动 Debian 内核。

## 7. 边界与已知限制

- 本轮只验证 NEMU；NPC RTL 未运行 U-Boot。NPC 的 timebase 是
  450 MHz、DTB/内存布局不同，不能直接复用 NEMU 的 1 MHz DTB。
- NEMU 单 hart；U-Boot 不启用 SMP/IPI 相关路径。
- U-Boot 不启用 virtio/网络/磁盘/PCI/USB/视频；没有持久环境
  （`CONFIG_ENV_IS_NOWHERE`）。所有 payload 由 NEMU 镜像预装载。
- `nemu_reserve` 是板级命令，只解决“预装载区不被 EFI 分配器覆盖”；
  它不会让 64 MiB 容纳两份 30 MiB 内核。
- 64 MiB 默认布局下 Debian 内核未进入；128 MiB 评估配置下已执行到
  earlycon/console（Linux 6.12.107、内存初始化、devtmpfs），但没有
  等到完整 initramfs shell——NEMU 解释执行较慢，验证到内核启动即停止。
- Debian installer initrd 未尝试；49 MB 大小在 64 MiB 下没有可行布局。

## 8. 相关文件

```text
vendor/u-boot/VENDOR.md                          U-Boot 来源/版本/哈希/许可证
vendor/u-boot/board/nemu/riscv64/                NEMU 板级适配 + nemu_reserve
vendor/u-boot/configs/nemu_riscv64_smode_defconfig
vendor/u-boot/include/configs/nemu-riscv.h
vendor/u-boot/arch/riscv/dts/nemu-riscv64.dts
vendor/u-boot/arch/riscv/Kconfig                 TARGET_NEMU_RISCV64
nemu/scripts/build_uboot_nemu.sh                 构建/打包脚本
nemu/tests/uboot-efi-smoke.sh                    冒烟测试
nemu/configs/riscv64-linux-128m_defconfig        Debian EFI 评估用 128 MiB 配置
```
