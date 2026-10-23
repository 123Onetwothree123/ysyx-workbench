# xv6-riscv → NPC 移植记录

## 1. 结论

- 源码：MIT `xv6-riscv`，commit `f780d7850765d74cbdcd1fff4b822d8829f7c9bd`
  （2026-10-15，“build with -fno-delete-null-pointer-checks”）。
- 已用 `riscv64-unknown-elf-gcc` 以 `-march=rv64ima_zicsr_zifencei -mabi=lp64`
  构建 kernel、user programs、`fs.img` 和 `kernel.bin`。
- 已移除 virtio，`fs.img` 作为 ramdisk 链接进 kernel，由 `kernel/ramdisk.c`
  提供同步块读写。
- 已在 QEMU 参考平台完整启动到 shell，并验证了 ramdisk 的读、写、回读。
- 在当前 NPC 上实际运行到第一条失败指令：
  `0x80001024: amoswap.w.aq a5,a4,(s1)`（`acquire()`，`kernel/spinlock.c:37`），
  `cause=2`（illegal instruction）。这就是当前唯一的 xv6 启动阻塞项：NPC
  尚未实现 A 扩展。没有伪造“已跑通”的结果。

## 2. 源码获取

```sh
git clone https://github.com/mit-pdos/xv6-riscv.git xv6-riscv
cd xv6-riscv
git checkout f780d7850765d74cbdcd1fff4b822d8829f7c9bd
```

源码以普通目录形式提交在 `xv6-riscv/`，已删除其 `.git`。上游为 MIT
License（见 `xv6-riscv/LICENSE`）。

## 3. 构建

工具链：

```text
riscv64-unknown-elf-gcc (g6afcc4f6d) 16.1.0
```

NPC 构建（默认 `PLATFORM=npc`）：

```sh
cd xv6-riscv
make clean
make -j"$(nproc)" PLATFORM=npc TOOLPREFIX=riscv64-unknown-elf-
make check-isa TOOLPREFIX=riscv64-unknown-elf-
```

关键编译参数在 `xv6-riscv/Makefile`：

```make
ARCH ?= rv64ima_zicsr_zifencei
ABI  ?= lp64
CFLAGS += -march=$(ARCH) -mabi=$(ABI)
```

`check-isa` 的结果：

```text
Class:  ELF64
Machine: RISC-V
Flags:  0x0        # 没有 RVC
== compressed mnemonics (must be empty) ==
（空）
== A-extension instructions ==
80001024: 0ce4a7af  amoswap.w.aq  a5,a4,(s1)
```

也就是说：

- 没有压缩指令（ELF flags 无 RVC，objdump 无 `c.*`）；
- 整个 kernel 只出现一条 A 扩展指令：`amoswap.w.aq`；
- 没有 `lr.w/sc.w`；kernel 和 user 程序都没有其它 AMO。

当前构建产物（clean 前）：

| 文件 | 大小 | SHA-256 |
| --- | ---: | --- |
| `kernel/kernel` | 2.3 MiB | `422a2d2e414652f51360da2ec4d74abff91d561856c7ffe2bcc5f9451e3d2cdb` |
| `kernel/kernel.bin` | 2.1 MiB | `4f2cdac67e9ca5904d62b6197527db70c9ac8ac4f055f877d5144d0b5e58064a` |
| `fs.img` | 2,048,000 B | `90ca005c70047f0015f98fb8c68a01f8cefe90a389edbf5190e46163364aae44` |

`fs.img` 是 2000 个 1024 字节块（`FSSIZE=2000`、`BSIZE=1024`）。

## 4. 内存布局

NPC direct-simulation 平台（与 `npc/src/Common/ysyx_26030103_PMA.scala`、
`npc/src/riscv32e_npc.scala` 对照）：

| 区域 | 物理地址 | 大小/说明 |
| --- | --- | --- |
| RAM | `0x80000000` | 64 MiB，`0x80000000..0x84000000`，可缓存、可执行 |
| CLINT | `0x02000000` | 64 KiB，仅 mtime/mtimecmp |
| PLIC | `0x0c000000` | 由 NPC xbar 独立译码；不在 PMA 列表中 |
| UART | `0x10000000` | NPC 只实现 4 字节 TX 寄存器 |

xv6 侧：

| 符号/区域 | 地址 |
| --- | --- |
| `_entry` / kernel load | `0x80000000` |
| `etext` | `0x80009000` |
| `_ramdisk_start` | `0x80023000` |
| `_ramdisk_end` / `end` | `0x80217000` |
| page allocator | `end .. PHYSTOP` |
| `PHYSTOP` | `0x84000000`（`KERNBASE + 64 MiB`） |
| `TRAMPOLINE` | `MAXVA - PGSIZE` |
| `TRAPFRAME` | `TRAMPOLINE - PGSIZE` |

`.ramdisk` 的地址会随 kernel 大小变化，运行时必须使用
`_ramdisk_start`/`_ramdisk_end`，不要硬编码 `0x80023000`。

## 5. 平台差异审计与移植改动

### 5.1 ISA / 压缩指令

- 原 Makefile 写死 `-march=rv64gc`，会生成 C 扩展和 F/D 指令。
- 已改为 `rv64ima_zicsr_zifencei` + `lp64`，`.S` 编译规则也使用同一组
  `$(CFLAGS)`。
- 结果：ELF flags 为 0，无 `c.*`；无 F/D。
- `mkfs` 是宿主工具，仍用宿主 `gcc`，不参与目标 ISA。

### 5.2 `mhartid` / 单 hart

NPC 没有实现 `mhartid`（CSR `0xf14`，`ysyx_26030103_CSR.scala` 中没有
`CSR_MHARTID`）。原 `entry.S` 和 `start.c` 会读它，导致启动第一条 CSR
就非法指令。

- `entry.S`：NPC 路径直接用 `stack0 + 4096`，不读 `mhartid`。
- `start.c`：`tp = 0`，`timerinit()` 也固定 hart 0。
- `NCPU` 仍为 8，但 NPC 实际只使用 hart 0；该移植不宣称 SMP。

### 5.3 PMP

NPC 配置 `CONFIG_HAS_PMP=n`，写 `pmpaddr0/pmpcfg0` 会触发非法指令。

- `start.c` 在 `XV6_PLATFORM_NPC` 下跳过 PMP 配置。
- QEMU 参考构建（`PLATFORM=qemu`）保留原 PMP 配置。

### 5.4 定时器：Sstc → CLINT M-mode timer

上游 2026 版 xv6 使用 Sstc：

- `start.c` 写 `menvcfg.STCE`，写 `stimecmp`（CSR `0x14d`）；
- `clockintr()` 重新写 `stimecmp`。

NPC：

- 没有 `menvcfg`、`stimecmp`；
- 有标准 CLINT（`mtime=0x0200bff8`，`mtimecmp=0x02004000`）；
- 定时器路径配置为 `clint_mtip`。

移植采用经典 xv6 timer 方案：

- 新增 `kernel/timer.S`：M-mode `timervec` 重新装载 `mtimecmp`，然后置
  `sip.SSIP`，把事件转成 S-mode 软件中断；
- `start.c` 设置 `mscratch`、`mtvec`、`mie.MTIE`；
- `mideleg` 只委托 `SEIE|STIE|SSIE`，**不委托 MTIP(bit 7)**；
- `trap.c` 的 `devintr()` 同时接受 `scause=1`（SSIP）和 `scause=5`
  （STIP，兼容 QEMU/其它平台），调用 `clockintr()`；
- `clockintr()` 清 `sip`，不再写 `stimecmp`。

NPC xbar 对 CLINT 的写有限制：`WriteCannotForward` 在
`AWLEN != 0` 时拒绝多拍写。因此 `mtimecmp` 不能用 64 位 `sd`，必须拆成
两个 32 位 `sw`。`start.c` 和 `timer.S` 都按低/高 32 位写入；
`mtime` 也用两个 32 位读拼接。

### 5.5 MMU / Sv39 / A/D 位

NPC RV64 MMU 配置：

- `CONFIG_HAS_MMU=y`
- Sv39（`MMU_LEVELS=3`，8 字节 PTE）
- `MMU_AD_MODE="fault"`，没有 `menvcfg.ADUE`

上游 xv6 依赖硬件更新 A/D（`menvcfg.ADUE`）。NPC 不会更新，因此
`vm.c:mappages()` 现在给每个叶子 PTE 直接置 `PTE_A|PTE_D`：

```c
*pte = PA2PTE(pa) | perm | PTE_V | PTE_A | PTE_D;
```

这是软件管理 A/D 的保守做法，语义合法；代价是失去 accessed/dirty
统计，对本移植不重要。

`satp`、`sfence.vma`、`mret`、`sret` 在 NPC 中均有实现；路径审计结果：

- `mret`：`start.c` 从 M 进入 S；
- `sret`：`trampoline.S` 从 S 返回 U；
- `satp`：`kvminithart()` 写 Sv39 `satp`；
- `sfence.vma`：`kvminithart()` 和 `sfence_vma()` 使用；
- 这几条在 QEMU 参考启动中全部实际执行通过。NPC 上由于 A 扩展阻塞，
  尚未执行到打开分页之后的代码。

### 5.6 UART

NPC 的 UART 模型（`npc/src/Common/ysyx_26030103_UART16550.scala`，
由 `riscv32e_npc_AXIRAM` 例化）实现了最小 16550 子集：

- 地址 `0x10000000` 起 8 个字节寄存器：THR/RBR、IER、IIR/FCR、
  LCR、MCR、LSR、MSR、SCR；
- TX：写 THR 仍直接 `printf` 到仿真 stdout，不产生 TX 中断；
- RX：64 字节 FIFO，读 RBR 弹出；LSR.DataReady 反映 FIFO 非空；
  IER.RX 使能且 FIFO 非空时 `uart_irq` 保持高电平；
- direct-NPC 顶层新增 `uart_rx_valid/uart_rx_data/uart_rx_ready` 端口，
  C++ 在 SDB `c`/非 SDB 主循环中把 stdin 非阻塞转发进来；
- UART PMA 区域改为可读，空 FIFO 读 RBR 返回 0，不再 DECERR。

NPC 路径的 `uart.c` 现在也打开 IER.RX，并在 `uartintr()` 中读 ISR、
轮询 LSR/RBR 排空输入。`uartputc_sync()`/`uartwrite()` 仍直接写 THR，
避免依赖 TX 中断。QEMU 参考构建保留完整 16550 路径。

### 5.7 PLIC / CLINT

- `memlayout.h` 保留标准 PLIC 地址 `0x0c000000`。
- NPC xbar 的 `decode()` 在检查 PMA 列表之前先匹配 PLIC 窗口，所以即使
  PLIC 不在 PMA 列表中，xv6 的 `plicinit()`/`plicinithart()` 写入仍可到达
  PLIC 从设备。
- PLIC 的 bit/寄存器下标就是 source ID（bit 0 保留）：
  source 1 = CLINT timer，source 10 = UART。direct-NPC 顶层把
  AXIRAM 的 `uart_irq` 接到 `io_interrupt`，再由 PLIC 交给 CPU。
- PLIC 同时支持标准 RISC-V 上下文地址（S 上下文 enable `0x2080`、
  threshold `0x201000`、claim/complete `0x201004`）和早期测试使用的
  M 上下文地址（`0x200000/0x200004`）。xv6 的 `plic.c` 无需修改。
- xbar 的 PLIC 写响应曾因 firtool 把最后一个 `elsewhen` 分支条件同时
  并入取反项与正向项，生成恒假的 BREADY；现已改为独立的组合 BREADY/
  响应逻辑，PLIC 写能够完成 B 握手（见 `uart-irq-test`）。
- CLINT 只实现 `mtime/mtimecmp`，没有 `msip`。xv6 不使用 `msip`。

### 5.8 其它 CSR / 指令

| 项目 | NPC | 移植处理 |
| --- | --- | --- |
| `mhartid` | 未实现 | 单 hart，固定 0 |
| `menvcfg` | 未实现 | 不使用 |
| `stimecmp` | 未实现 | 改用 CLINT M-mode timer |
| `time` (0xc01) | 未实现 | 不再使用；计时用 `ticks` |
| `mcounteren` | 已实现 | 保留设置 |
| PMP CSRs | 未实现（`HAS_PMP=n`） | NPC 下跳过 |
| `satp/sfence.vma` | 已实现 | 保留 |
| A 扩展 | 未实现（`CONFIG_RISCV_A=n`） | 见第 7 节 |

## 6. ramdisk 方案

### 6.1 为什么不用 virtio

NPC 没有 virtio MMIO 设备，也没有 `0x10001000` 外设。原 `virtio_disk.c`
会在 `virtio_disk_init()` 读 magic 时得到 DECERR。移植直接删除
`kernel/virtio_disk.c`、`kernel/virtio.h`，不注册 VIRTIO IRQ。

### 6.2 链接方式

- `kernel/ramdisk_img.S`：

```asm
.section .ramdisk, "aw", @progbits
.balign 4096
.globl _ramdisk_start
_ramdisk_start:
.incbin "fs.img"
.globl _ramdisk_end
_ramdisk_end:
.balign 4096
```

- `kernel/kernel.ld` 在 `.bss` 之后放置 `.ramdisk`，然后才定义 `end`：

```ld
. = ALIGN(0x1000);
.ramdisk : {
  _ramdisk_start = .;
  *(.ramdisk)
  _ramdisk_end = .;
}
PROVIDE(end = .);
```

这样 `kinit()` 的 `freerange(end, PHYSTOP)` 从 ramdisk 之后开始，
`kfree()` 的 `pa < end` 检查也会拒绝 ramdisk 页，ramdisk 不会被页分配器
覆盖。

`kernel/kernel` 的 ELF 程序头中可以看到独立的 RW `LOAD` 段：

```text
LOAD 0x80023000 FileSiz 0x1f4000 MemSiz 0x1f4000 RW
```

`kernel.bin` 同样包含这段数据；NPC 只加载一个 ELF 到 `0x80000000`，
不需要额外的磁盘参数。

### 6.3 后端

`kernel/ramdisk.c`：

- `ramdisk_init()`：检查 `_ramdisk_end - _ramdisk_start >= FSSIZE*BSIZE`；
- `ramdisk_rw(b, write)`：按 `blockno * BSIZE` 做 `memmove`；
- 用一个 spinlock 保护 backing store；
- 同步完成，`b->disk = 0`；
- 写只修改 RAM 中的嵌入副本，宿主 `fs.img` 不会被改；重启即恢复初始内容。

`bio.c` 的 `bread()`/`bwrite()` 现在调用 `ramdisk_rw()`，不再有
virtio 请求队列、中断和睡眠等待。

## 7. 验证结果

### 7.1 QEMU 参考启动（验证 kernel/ramdisk/文件系统）

QEMU 只作为参考平台，不参与 NPC 构建：

```sh
make clean
make -j"$(nproc)" PLATFORM=qemu TOOLPREFIX=riscv64-unknown-elf-
timeout 18 qemu-system-riscv64 -machine virt -bios none \
  -kernel kernel/kernel -m 64M -smp 1 -nographic
```

实际输出（节选）：

```text
xv6 kernel is booting

init: starting sh
$ ls
.              1 1 1024
..             1 1 1024
README         2 2 2441
cat            2 3 37280
...
sh             2 13 59624
...
$ echo hello-ramdisk > note
$ cat note
hello-ramdisk
$ ls
...
note           2 24 14
```

这验证了：

- Sv39 分页和 `satp/sfence.vma/mret/sret`；
- CLINT M-mode timer + SSIP 路径；
- UART 输出/输入和 PLIC UART 中断（QEMU 版）；
- `fs.img` 的读、写、回读（ramdisk 后端）；
- 用户态 `exec`、shell、文件系统。

QEMU `-d int` 日志也能直接看到定时器和外部中断路径：

```text
riscv_cpu_do_interrupt: ... cause:...0007 ... desc=m_timer
riscv_cpu_do_interrupt: ... cause:...0001 ... desc=s_software
riscv_cpu_do_interrupt: ... cause:...0009 ... desc=s_external
```

即 CLINT 的 M-mode timer 中断被 `timervec` 转成 S-mode 软件中断，
UART 输入则通过 PLIC 产生 S-mode 外部中断。

### 7.2 当前 NPC 的实际结果

使用主线预编译 direct-NPC：

```sh
/home/abc/ysyx-workbench/npc/build/ysyx_26030103_npc \
  xv6-riscv/kernel/kernel --elf xv6-riscv/kernel/kernel
# SDB:
ftrace off
si 50000
```

实际日志：

```text
现在指定了镜像文件
ELF 镜像加载完成: kernel/kernel，4 个程序头，镜像 2191360 字节
(npc) ftrace off
ftrace: 已关闭
(npc) si 50000
遇到异常，停止单步（pc=0x80001024, cause=0x00000002, target=0x0）
```

定位：

```text
$ riscv64-unknown-elf-addr2line -e kernel/kernel -f 0x80001024
acquire
./kernel/spinlock.c:37

$ riscv64-unknown-elf-objdump -d ... 
80001024: 0ce4a7af  amoswap.w.aq  a5,a4,(s1)
```

`cause=2` 是 illegal instruction。第一条 `printk()` 会先 `acquire(&pr.lock)`，
所以 UART 还没有输出任何字符就停在这里。这个失败点是确定性的，不是网络、
镜像加载或设备问题。

### 7.3 补充诊断（非提交配置，不作为“已跑通”）

为了确认 A 扩展之外没有更早的平台阻塞，曾临时把 `acquire()` 改成
单 hart 的非原子测试锁（仅本地诊断，提交源码中已恢复为
`__atomic_exchange_n`），NPC 实际输出到：

```text
xv6 kernel is booting

```

随后停留在 `kinit()` 的 `memset`/`kfree` 循环（Verilator 下逐页清零
64 MiB 很慢），在测试窗口内没有继续到 `init: starting sh`。该诊断说明
UART、CLINT、`mret`、启动路径已经没有更早的硬故障；但它不是正确的
SMP 锁，**不能**作为 xv6 已在 NPC 上跑通的证据。

## 8. NPC 待支持项

### 8.1 必须：A 扩展

当前 kernel 唯一需要的 A 指令是：

```text
amoswap.w.aq  a5, a4, (s1)     # acquire(), 0x80001024
```

没有 `lr.w/sc.w`。因此最低限度需要 NPC 支持 `AMOSWAP.W`（带 aq/rl
语义；单 hart 下 aq 可先按 fence 实现），然后 xv6 才能通过第一个
`printk`。若希望完整支持任意编译器原子模式，仍建议实现完整 A
（AMOADD/SWAP/AND/OR/XOR/MAX/MIN 及 LR/SC）。

`fence`/`fence.i` 已由 NPC 支持；`__atomic_store_n(..., RELEASE)` 编译为
`fence rw,w` + `sw`，不需要 A。

### 8.2 交互式 shell：UART RX + IRQ（已实现）

direct-NPC 现在具备：

- 16550 最小 RX 寄存器组（RBR/LSR/IIR/IER/FCR）；
- 仿真 stdin/测试平台可注入接收字节，FIFO 满时停止读取而非阻塞；
- UART 中断接 PLIC source 10，PLIC 标准 S 上下文寄存器地址可用；
- `npc/csrc/Test/uart-irq-test/` 裸机回归：配置 PLIC/UART 后注入 `'Z'`，
  S 模式外部中断 claim 到 10、读到 LSR.DataReady 与 RBR，再 complete
  并 GOOD halt。

运行方式：

```sh
make -C npc/csrc/Test/uart-irq-test test
```

注意：本环境下 RV64 direct-NPC 存在与 UART 无关的“store 后紧接 load
卡死”问题，未修改的 c140c1cd9 基线也能复现，因此 RV64 xv6 的实际
shell 输入尚未完成端到端验证。上述 UART/PLIC 回归使用 RV32；UART 和
PLIC 的寄存器/中断路径与 XLEN 无关。

### 8.3 非阻塞的差异

- 无 `mhartid`：已按单 hart 处理；
- 无 PMP：已跳过；
- 无 `menvcfg/stimecmp`：已改用 CLINT M-mode timer；
- 无 `time` CSR：已不使用；
- CLINT 多拍写限制：已改为两个 32 位写；
- MMU 无硬件 A/D 更新：已在 PTE 中软件置 A/D；
- `LOAD segment with RWX permissions` 链接警告来自 xv6 原始单一
  text/data 段布局，不影响运行。

## 9. 运行命令

NPC（推荐先用预编译、支持 `--elf` 的 direct-NPC）：

```sh
cd xv6-riscv
make PLATFORM=npc TOOLPREFIX=riscv64-unknown-elf-
/home/abc/ysyx-workbench/npc/build/ysyx_26030103_npc \
  kernel/kernel --elf kernel/kernel
```

SDB 中：

```text
ftrace off
c
```

或使用 Makefile 目标：

```sh
make run-npc NPC=/path/to/ysyx_26030103_npc
```

`run-npc-batch` 依赖 NPC 支持 `--batch`；当前主线预编译版本不支持该参数，
可改用 `run-npc` 后输入 `c`。

QEMU 参考：

```sh
make clean
make -j"$(nproc)" PLATFORM=qemu TOOLPREFIX=riscv64-unknown-elf-
qemu-system-riscv64 -machine virt -bios none \
  -kernel kernel/kernel -m 64M -smp 1 -nographic
```

注意：切换 `PLATFORM` 后必须 `make clean`，因为 `XV6_PLATFORM_NPC`
会影响 UART/PMP 代码的编译。

## 10. 文件清单

新增/修改的关键文件：

```text
xv6-riscv/Makefile
xv6-riscv/kernel/memlayout.h
xv6-riscv/kernel/kernel.ld
xv6-riscv/kernel/entry.S
xv6-riscv/kernel/start.c
xv6-riscv/kernel/timer.S          (new)
xv6-riscv/kernel/trap.c
xv6-riscv/kernel/riscv.h
xv6-riscv/kernel/vm.c
xv6-riscv/kernel/uart.c
xv6-riscv/kernel/plic.c
xv6-riscv/kernel/main.c
xv6-riscv/kernel/defs.h
xv6-riscv/kernel/bio.c
xv6-riscv/kernel/ramdisk.c        (new)
xv6-riscv/kernel/ramdisk_img.S    (new)
xv6-riscv/kernel/virtio_disk.c    (removed)
xv6-riscv/kernel/virtio.h         (removed)
```

相对上游的完整补丁见仓库根目录的 `XV6_PORT.patch`。
