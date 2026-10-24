# NEMU 独立运行 OpenSBI + DTB + Linux 风格 next-stage

本文记录 NEMU 作为**功能级 Linux 参考模型**的配置、timebase 契约、
OpenSBI/DTB 启动链、真实 Linux Image 的打包方式，以及 NEMU 与 NPC
的差异和已知限制。

本文不下载、不编译、不运行 Linux kernel。仓库内验证只到：

```text
OpenSBI v1.9
...
Platform Timer Device       : aclint-mtimer @ 1000000Hz
Boot HART Base ISA          : rv64imafdc
...
SBI-NPC-PAYLOAD: entered S-mode via OpenSBI (a0=0x0, a1=0x83f00000)
SBI-NPC-PAYLOAD: FDT PASS
SBI-NPC-PAYLOAD: mtime(time CSR)=0x...
SBI-NPC-PAYLOAD: console ecall OK
SBI-NPC-PAYLOAD: PASS
```

## 1. 定位：NEMU 不是 NPC 的 RTL 等价模型

NEMU 是解释执行的功能模型，复用 NPC 的**固定设备地址契约**，但没有
NPC 的微架构和时序：

| 项目 | NEMU | NPC（RTL/direct-NPC） |
| --- | --- | --- |
| 执行方式 | 逐条解释 | 周期级 RTL/仿真 |
| cache | 无 | ICache/DCache 可配置 |
| 总线 | 直接物理内存/MMIO | AXI/Xbar/异步桥 |
| SoC64/DMA | 无 | 有对应配置 |
| hart | 单 hart | 单 hart（当前配置） |
| mtime | 宿主单调时间缩放，默认 1MHz | 每个 CPU 周期 +1，默认 450MHz |
| PMP | 16 项，严格检查 | 由 `HAS_PMP` 配置 |
| 设备时序 | 立即完成，UART 逐字符输出 | 有总线/外设时序 |
| 定时器中断 | 宿主时间驱动，非指令级确定 | 周期精确 |

因此：

- **NPC 的 450MHz DTB 不能直接给 NEMU**；
- NEMU 上通过不代表 NPC 周期/时序通过；
- NPC 上通过也不代表 NEMU 的 PMP/CSR 行为一致（见第 6 节）。

## 2. 配置

新增 `nemu/configs/riscv64-linux_defconfig`：

```text
RV64 IMAFDC + S/U + Sv39/Sv48
CONFIG_TARGET_NATIVE_ELF=y
CONFIG_DEVICE=y
CONFIG_HAS_RISCV_XV6=y
CONFIG_MBASE=0x80000000
CONFIG_MSIZE=0x04000000（64 MiB）
CONFIG_RISCV_XV6_MTIME_FREQ=1000000
CONFIG_SDB=y
```

不启用 `CONFIG_TARGET_SHARE`/DiffTest。设备只保留 CLINT/PLIC/16550，
关闭 SDL/VGA/键鼠/音频/磁盘，避免独立运行依赖。

构建（注意 shell 里的 `NEMU_HOME` 可能指向别的 worktree，必须显式
覆盖）：

```sh
cd nemu
make NEMU_HOME=$PWD ARCH=riscv64-nemu \
  AM_HOME=../abstract-machine riscv64-linux_defconfig
make NEMU_HOME=$PWD ARCH=riscv64-nemu \
  AM_HOME=../abstract-machine -j"$(nproc)"
```

输出：`nemu/build/riscv64-nemu-interpreter`。

`CONFIG_SDB` 在本树是必需项：monitor、设备轮询和 `--batch` 都依赖
SDB；关闭会在编译期明确报错，而不是生成缺少批处理能力的 NEMU。

## 3. timebase 契约（最容易出错的地方）

NEMU 的 CLINT `mtime` 不是每个 guest 指令 +1，而是：

```text
mtime = 宿主单调时间(us) * CONFIG_RISCV_XV6_MTIME_FREQ / 1,000,000
```

默认 `CONFIG_RISCV_XV6_MTIME_FREQ=1000000`，此时 `mtime` 就是宿主
微秒，与旧 xv6 行为完全一致。NEMU Linux 启动链要求三方一致：

| 位置 | 来源 | 默认值 |
| --- | --- | --- |
| NEMU CLINT `mtime` | `CONFIG_RISCV_XV6_MTIME_FREQ` | 1,000,000 |
| NEMU DTB `/cpus/timebase-frequency` | 同一配置，由 `nemu/scripts/build_linux_dtb.sh` 写入 | 1,000,000 |
| OpenSBI `NPC_MTIME_FREQ` | `build_linux_fw.sh` 显式传给 OpenSBI | 1,000,000 |

NEMU 的 `time` CSR（`0xc01/0xc81`）使用同一个 `get_mtime()`，因此
S 模式 `rdtime` 与 CLINT `mtime` 读数一致。

要改频率，必须同时改 `CONFIG_RISCV_XV6_MTIME_FREQ`，并重新用
`build_linux_dtb.sh`/`build_linux_fw.sh` 生成 DTB 和固件；脚本会检查
DTB 与 `fw.info` 的 timebase 是否一致，不一致直接失败。**不要把
NPC 的 `npc/build/dtb/npc.dtb` 或 450MHz DTB 复制给 NEMU。**

## 4. 启动链与设备地址

```text
0x80000000  OpenSBI（FW_PAYLOAD 时含测试 payload；FW_JUMP 时只放固件）
0x80200000  next-stage（测试 payload 或真实 Linux Image）
0x82000000  可选 initramfs（默认关闭）
0x83f00000  NEMU DTB（a1 指向这里）
0x84000000  RAM 结束（64 MiB）
```

设备地址与 NPC/xv6 平台一致，OpenSBI 仍复用
`vendor/opensbi/platform/npc`：

| 地址 | 设备 | NEMU 行为 |
| --- | --- | --- |
| `0x02000000` | CLINT | `mtimecmp=+0x4000`、`mtime=+0xbff8`；无 MSIP |
| `0x0c000000` | PLIC | 1024 source 数组，DTB/OpenSBI 宣告 31；S context 1，兼容 context 0 |
| `0x10000000` | 16550 | TX→stderr；batch 模式 RX←stdin；source 10 |
| `0x80000000` | RAM | 64 MiB |

OpenSBI 平台名仍显示 `npc`，因为 NEMU 复用了同一套固定地址和串口/
PLIC/CLINT 适配；区别在 timebase 由构建脚本显式覆盖为 NEMU 的 1MHz，
而不是 `CONFIG_SYNTH_FREQ` 推导的 450MHz。

## 5. 构建、运行与测试

```sh
cd nemu
# 1. NEMU DTB + OpenSBI FW_PAYLOAD + 测试 payload
./scripts/build_linux_fw.sh

# 2. 非交互运行；payload 最后在 wfi 循环，用 timeout 结束
timeout 60 ./build/riscv64-nemu-interpreter -b \
  build/linux/opensbi/fw.bin

# 3. 一键回归（含配置契约、DTB/OpenSBI timebase、FDT PASS、
#    dummy Image 打包检查）
./tests/linux-sbi-smoke.sh
```

`build_linux_fw.sh` 默认生成 `nemu/build/linux/`：

```text
dtb/nemu.dtb              NEMU 专用 DTB（1MHz、Sv39、64MiB）
dtb/nemu.dtb.info         timebase/RAM/ISA/哈希
opensbi/fw.bin            NEMU 可直接加载的 OpenSBI + payload
opensbi/fw.info           OpenSBI NPC_MTIME_FREQ、内嵌 DTB 哈希
payload/payload.bin       仓库内 SBI 测试 payload
```

单独生成/检查 DTB：

```sh
./scripts/build_linux_dtb.sh
fdtget -t u build/linux/dtb/nemu.dtb /cpus timebase-frequency   # 1000000
```

### 5.1 为什么测试 payload 用 time CSR

现有 SBI 测试 payload 默认从 S 模式直接读 CLINT `mtime`。NEMU 实现了
16 项 PMP，OpenSBI 会把 CLINT 配成 M-only。严格 PMP 下 S 模式读
`0x0200bff8` 会产生 load access fault（cause 5），这是正确行为，不是
NEMU 丢设备。

因此 `build_linux_fw.sh` 给 payload 传 `USE_TIME_CSR=1`，改读架构
`time` CSR（`rdtime`，OpenSBI 已设置 `mcounteren/scounteren`），仍能
验证 SBI TIME 扩展和 mtime 数值。NPC 侧默认 `USE_TIME_CSR=0`，
继续验证其 CLINT MMIO 路径，行为不变。

## 6. 与 OpenSBI 的 CSR/PMP 兼容性

实际启动日志确认：

- `misa`：`rv64imafdc`，含 S/U，OpenSBI 正常进入 S 模式；
- `mhartid`：0；单 hart，OpenSBI 不注册 MSWI/IPI（CLINT 无 MSIP）；
- `medeleg=0x4b109`、`mideleg=0x222`：可写，S 模式 ecall/中断委托正常；
- `mstatus.FS`：F/D 打开，OpenSBI 的 FP 初始化通过；
- `PMP Count=16`，OpenSBI 配置 PMP 后 S/U 只能访问域允许的区域；
- `menvcfg` 未实现：OpenSBI 特性探测把它当非法指令，最终报告
  `Priv Version v1.11`，走兼容路径；
- 未知 CSR 现在按非法指令陷入，而不是 `panic`。这是 OpenSBI 探测
  `menvcfg/mstateen/mhpmcounter/PMPADDR16+` 所必需的；已有 RV32/RV64
  回归不受影响。

OpenSBI 的 PMP 配置会把 CLINT/UART 等区域划成 M-only 或 S/U 可访问，
Linux 必须按设备树使用这些区域；不要假设 S 模式能直接访问 M-mode
CLINT 寄存器。

## 7. 真实 Linux Image 的打包/运行

本仓库不提供、不下载 Linux Image。用户准备好 Image 后：

```sh
cd nemu
NEMU_IMAGE_PATH=/path/to/Image \
NEMU_INITRAMFS_PATH=/path/to/initramfs.cpio   # 可选；设置后默认启用
./scripts/pack_linux_image.sh
```

输出：

```text
nemu/build/linux/pack/linux-image.bin          单一可加载 binary
nemu/build/linux/pack/linux-image.bin.info     布局/大小/哈希/timebase
```

运行：

```sh
cd nemu
./build/riscv64-nemu-interpreter -b \
  build/linux/pack/linux-image.bin
```

脚本先以 `NEMU_FW_MODE=jump` 构建 OpenSBI FW_JUMP + 1MHz DTB，再复用
`npc/scripts/pack_linux_image.sh` 做 Image header、地址、重叠和 initrd
校验。Image 必须放在 `0x80200000`（header `text_offset=0x200000` 或 0），
DTB 在 `0x83f00000`，initramfs 默认 `0x82000000`。

### 7.1 dummy Image 只验证打包

测试脚本会用 8 KiB 的 dummy Image（带合法 RISC-V Image header）跑
`pack_linux_image.sh`，检查布局、DTB 哈希和 timebase：

```sh
PACK_DUMMY=1 ./tests/linux-sbi-smoke.sh
```

**dummy Image 不会被运行，也不能冒充 Linux 启动验证。** 真实 Linux
是否能启动仍取决于用户 Image、initramfs、内核配置（Sv39、16550、
PLIC、SBI 控制台）和 PMP 行为。

## 8. 调试顺序：先 NEMU，后 NPC

建议按以下顺序定位问题：

1. **NEMU + 测试 payload**：先确保 OpenSBI banner、`FDT PASS`、
   `console ecall OK`、`PASS` 都出现。此时问题在 DTB/OpenSBI/设备
   地址，不在 Linux。
2. **NEMU + 真实 Linux Image**：观察 OpenSBI 是否跳到 `0x80200000`、
   Linux 是否打印 earlycon。NEMU 无 cache/AXI，能把内核逻辑、页表、
   SBI 调用和 PLIC/CLINT 软件编程问题与 RTL 时序问题分开。
3. **NPC + 同一 Image**：NEMU 通过后再上 NPC。此时必须换成 NPC 的
   450MHz DTB/`SYNTH_FREQ`，并预期 PMP/cache/AXI 会引入新的差异；
   不要把 NEMU 的 DTB 或 mtime 读数直接套到 NPC。

## 9. 已知限制

- 未下载/编译/运行 Linux kernel；真实 Image 启动未验证。
- NEMU 是单 hart；无 SMP、IPI、MSIP。
- 无 cache/AXI/SoC64/DMA，不能用于周期/带宽评估。
- mtime 基于宿主时钟，不是指令级确定；需要可重复指令流时应在第一个
  定时器中断前截断。
- OpenSBI 仍使用 `platform/npc` 的固定设备表；DTB 只用于传递/校验，
  不参与 OpenSBI 设备发现。
- initramfs 路径已接入 DTB 与打包脚本，但未用真实 initramfs 启动。
- `menvcfg` 等 Priv 1.12 CSR 未实现，OpenSBI 报告 v1.11；Linux 若强制
  依赖这些 CSR 需要再补 NEMU 模型。
- PLIC 模型内部支持 1024 source，但 DTB/OpenSBI/NPC 契约只宣告 31；
  不要依赖 source 32+。
