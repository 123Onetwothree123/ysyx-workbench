# NPC 的 RustSBI 可选实现

本文说明 NPC 如何纳入、构建和验证 RustSBI，以及它与 OpenSBI 选项的
关系。默认配置不启用 SBI，`xv6-riscv` 仍然从 M 模式直接启动，行为不变。

## 1. 来源与版本

- 上游：<https://github.com/rustsbi/rustsbi>
- 基线：`27a9c7d8056e2003b35d83af828cd3787bc55d17`（2026-10-22）
- 库版本：`rustsbi` 0.4.1，`sbi-spec` 0.0.10
- 本地目录：`vendor/rustsbi/`（普通源码，非 submodule，无内部 `.git`）
- 许可证：MIT 或 Mulan PSL v2
- 来源说明：`vendor/rustsbi/UPSTREAM.md`

上游的完整固件模板是 `firmware/prototyper`，依赖 nightly、设备树、PMP
和较多平台驱动，不适合 NPC 当前的最小 M 模式平台。因此 NPC 按 RustSBI
官方文档的 “discrete package” 方式，在 `npc/rustsbi-fw/` 中使用
`rustsbi` 库并实现自己的平台适配；没有伪造上游固件，也没有把 Prototyper
裁剪后冒充可直接启动的固件。

## 2. 安装 RISC-V target

固件使用 **RV64IMAC** 裸机 target，但构建时关闭压缩指令（见第 5 节）：

```bash
rustup target add riscv64imac-unknown-none-elf
```

本机实际安装命令（网络较慢，使用 rsproxy 镜像）：

```bash
export RUSTUP_DIST_SERVER=https://rsproxy.cn
export RUSTUP_UPDATE_ROOT=https://rsproxy.cn/rustup
rustup target add riscv64imac-unknown-none-elf
```

构建脚本会检查 target 是否安装；缺失时直接报错并打印上面的安装命令，
不会静默跳过或退回到其他 SBI 实现。

## 3. Kconfig 接口

`npc/kconfig/Kconfig.sbi`（主线 commit `684537192`）提供：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `CONFIG_SBI_ENABLE` | `n` | SBI 总开关；默认关闭，xv6 不受影响 |
| `CONFIG_SBI_IMPL_OPENSBI` | choice 默认 | 使用 OpenSBI |
| `CONFIG_SBI_IMPL_RUSTSBI` | choice | 使用 RustSBI |
| `CONFIG_SBI_IMPL` | 自动 | 字符串：`opensbi` / `rustsbi` / `none` |
| `CONFIG_SBI_FW_BASE` | `0x80000000` | 固件 M 模式入口/加载地址 |
| `CONFIG_SBI_FW_SIZE` | `0x00200000` | 固件保留区大小（2MiB） |
| `CONFIG_SBI_NEXT_ADDR` | `0x80200000` | 跳转的 S 模式入口 |
| `CONFIG_SBI_PLATFORM` | `"npc"` | 平台名，打印到启动日志 |
| `CONFIG_SBI_BUILD_JOBS` | `0` | 构建并行度，0 表示 `nproc` |
| `CONFIG_SBI_FDT_ENABLE` | `n` | 是否把 DTB 复制到 `SBI_FDT_ADDR` 并通过 a1 传递 |
| `CONFIG_SBI_FDT_PATH` | `"dts/npc.dts"` | DTS/DTB 源文件 |
| `CONFIG_SBI_FDT_ADDR` | `0x80f00000` | DTB 最终加载/传递地址 |

OpenSBI 与 RustSBI 位于同一个 Kconfig `choice` 中，二者互斥；选择
RustSBI 时 `CONFIG_SBI_IMPL_OPENSBI` 会自动变为未选中，反之亦然。
RustSBI 复用全部 `SBI_FW_*` / `SBI_NEXT_ADDR` / `SBI_PLATFORM` 参数，
没有另建一套地址配置。

当前 RustSBI 固件还额外读取现有的 `CONFIG_XLEN`、`CONFIG_HAS_S`、
`CONFIG_HAS_PMP`：只支持 RV64 + S 模式；只有 `CONFIG_HAS_PMP=y` 时才写
PMP CSR，避免在 NPC 默认的 `HAS_PMP=n` 配置上触发 illegal instruction。

## 4. 构建

在 `npc/` 下先选择 RV64 + S 模式配置，然后：

```bash
make rv64_xv6_defconfig       # 或其他 RV64 + CONFIG_HAS_S=y 的配置
make rustsbi
```

输出：

```text
npc/build/rustsbi/rustsbi-npc.elf
npc/build/rustsbi/rustsbi-npc.bin
```

也可以直接调用脚本：

```bash
npc/scripts/build_rustsbi.sh
```

清理：

```bash
make rustsbi-clean
```

说明：

- `make rustsbi` 是显式目标，即使 `CONFIG_SBI_ENABLE=n` 也会构建，方便
  先验证固件；如果 `CONFIG_SBI_IMPL` 不是 `rustsbi`，脚本会打印提示。
- 构建脚本使用 `npc/rustsbi-fw/Cargo.lock` 和 `--locked`，避免依赖漂移。
- Cargo 的中间产物放在 `npc/build/rustsbi/target/`，不污染源码目录。
- 不会改动或覆盖 OpenSBI 的产物；`make opensbi` 与 `make rustsbi`
  可以独立存在。

## 5. 固件实现

`npc/rustsbi-fw/` 是一个 `#![no_std]` 裸机 crate：

- 入口：`.text.entry` 中的 `_start`，链接到 `CONFIG_SBI_FW_BASE`；
- 清 BSS、设置 64KiB M 模式栈，然后进入 `rust_main`；
- 初始化 16550 UART：`0x1000_0000`，8N1、FIFO 打开；
- CLINT：`0x0200_0000`，`mtimecmp=+0x4000`，`mtime=+0xbff8`；
- 设置 `medeleg=0xf5ff`（保留 S/M ecall 给 M 模式）、`mideleg=0x222`
  （SSIP/STIP/SEIP）、`mie.MTIE`；
- `mtvec` 指向自己的 M 模式陷入入口；陷入栈通过 `mscratch` 与 S 模式
  `sp` 交换，不信任 S 模式栈；
- machine timer 中断（MTIP）在 M 模式清 `mtimecmp`，再向 `mip.STIP`
  注入 S 模式定时器中断（NPC 默认 `timer_path=clint_mtip` 不会自动产生
  STIP）；
- `mret` 到 `CONFIG_SBI_NEXT_ADDR`，`a0=0`（单 hart）；
  `CONFIG_SBI_FDT_ENABLE=y` 时先把内嵌 DTB 复制到
  `CONFIG_SBI_FDT_ADDR`，再以 `a1=DTB 地址` 进入 S 模式；关闭时
  `a1=0`（保持旧行为）。

实现的 SBI 扩展：

| 扩展 | 说明 |
| --- | --- |
| Base | `rustsbi` 库；`EnvInfo` 返回 NPC 的 mvendorid/marchid，mimpid=0 |
| TIME | `set_timer` 写 CLINT `mtimecmp`，并清 `mip.STIP` |
| sPI | 单 hart，向自己写 `mip.SSIP` |
| RFNC | 本地 `fence.i` / `sfence.vma` |
| HSM | 只支持 hart 0：`STARTED`、`hart_stop` 停机；其他 hart 返回错误 |
| SRST | shutdown/cold/warm reboot 映射为 NPC 仿真停机 |
| DBCN | `Console` 读写 UART，读写缓冲区限制在 NPC RAM 窗口 |
| legacy 0x00..0x08 | 兼容层：set_timer/putchar/getchar/IPI/fence/shutdown |

`rustsbi` 0.4 默认不再分发 legacy 扩展，所以 NPC 在陷入层补了
`0x00..0x08` 的最小实现，并在 Base `probe_extension` 中报告它们可用。
这样老的 bootloader/payload 仍能使用 `console_putchar`。

### 不依赖 F/D

target 为 `riscv64imac`，不使用 F/D，ELF 是 soft-float ABI。构建日志中
可能出现一次 `unstable feature specified for -Ctarget-feature: zca`
警告：这是因为 Rust 1.92 的 RISC-V 2024 命名把压缩指令拆到 `zca`，而
NPC 默认 `CONFIG_RISCV_C=n`，必须显式 `-C target-feature=-c,-zca`。
该警告不影响构建；脚本会检查最终 ELF 的 `Tag_RISCV_arch`，若仍包含
`zca`/`c` 会明确报错。

## 6. 测试

统一入口（与 OpenSBI 共用 `npc/test/opensbi_payload/` 和 FDT 断言）：

```bash
cd npc
make sbi-test SBI_IMPL=rustsbi                  # a1=0，旧行为
make sbi-test SBI_IMPL=rustsbi SBI_FDT_ENABLE=y # DTB -> a1=0x80f00000
```

实际 NPC 日志（RustSBI + FDT，节选）：

```text
[rustsbi-npc] RustSBI 0.4.1 (vendor/rustsbi 27a9c7d)
[rustsbi-npc] platform : npc
[rustsbi-npc] firmware : 0x0000000080000000
[rustsbi-npc] uart     : 0x0000000010000000
[rustsbi-npc] clint    : 0x0000000002000000
[rustsbi-npc] next     : 0x0000000080200000
[rustsbi-npc] entering supervisor mode
[rustsbi-npc] fdt      : 0x0000000080f00000
[rustsbi-npc] fdt size : 0x00000000000006be
SBI-NPC-PAYLOAD: entered S-mode via RustSBI (a0=0x0000000000000000, a1=0x0000000080f00000)
SBI-NPC-PAYLOAD: FDT magic=0x00000000d00dfeed totalsize=0x00000000000006be
SBI-NPC-PAYLOAD: FDT model=ysyx,npc
SBI-NPC-PAYLOAD: FDT memory OK: 0x0000000080000000+0x0000000004000000
SBI-NPC-PAYLOAD: FDT uart OK: 0x0000000010000000 irq=10
SBI-NPC-PAYLOAD: FDT plic OK: 0x000000000c000000+0x0000000004000000 ndev=31
SBI-NPC-PAYLOAD: FDT chosen OK: /soc/serial@10000000
SBI-NPC-PAYLOAD: FDT reserved-memory OK
SBI-NPC-PAYLOAD: FDT PASS
SBI-NPC-PAYLOAD: PASS
```

`SBI_FDT_ENABLE=n` 时 RustSBI 打印 `fdt : none (a1=0)`，payload 同样
检查 `a1=0`。

独立测试源码在 `npc/test/rustsbi/`，payload 是一个最小 S 模式程序，验证
next-stage 跳转、legacy console、DBCN、Base、TIME、sPI：

```bash
./npc/test/rustsbi/run.sh npc     # 需要先构建 NPC 仿真器
./npc/test/rustsbi/run.sh qemu    # 可选；QEMU 需要 PMP，脚本自动打开
```

期望输出包含 `LDBPTIOK`。实际 NPC 运行日志（`rv64_xv6_defconfig`，
`CONFIG_HAS_PMP=n`，`CONFIG_RISCV_C=n`）：

```text
现在指定了镜像文件
文件加载了: npc/build/rustsbi-test/combined.bin, size = 2097549 bytes, padding = 0 bytes

[rustsbi-npc] RustSBI 0.4.1 (vendor/rustsbi 27a9c7d)
[rustsbi-npc] platform : npc
[rustsbi-npc] firmware : 0x0000000080000000
[rustsbi-npc] uart     : 0x0000000010000000
[rustsbi-npc] clint    : 0x0000000002000000
[rustsbi-npc] next     : 0x0000000080200000
[rustsbi-npc] entering supervisor mode
LDBPTIOK
收到仿真 halt 请求
HIT GOOD TRAP at pc = 0x0000000080200160, cycles = 29128
```

QEMU virt 交叉验证（脚本用 `SBI_HAS_PMP=y` 单独构建）：

```text
[rustsbi-npc] entering supervisor mode
LDBPTIOK
qemu-system-riscv64: terminating on signal 15 from pid ... (timeout)
```

QEMU 没有 NPC 的 custom halt，所以 payload 在 WFI 循环中等待外部
timeout；NPC 模式则用 `.word 0x0000000b` 正常结束并得到 GOOD TRAP。

## 7. 互斥与默认构建

- `CONFIG_SBI_ENABLE` 默认 `n`，默认 `make` / `make run` 不构建也不加载
  SBI；xv6 仍直接以 M 模式启动。
- OpenSBI 与 RustSBI 是同一个 Kconfig choice，不能同时选中。
- `make rustsbi` 只写 `npc/build/rustsbi/`，不会覆盖 OpenSBI 产物。
- 没有修改 `xv6-riscv/` 或 xv6 的启动流程。

## 8. 当前限制

1. 只支持 RV64；RV32 启动/陷入汇编尚未实现，脚本会明确拒绝。
2. 只支持单 hart；IPI 只对自己生效，HSM 只能查询/停止 hart 0。
3. 已支持内嵌 DTB 并通过 a1 传递（`SBI_FDT_ENABLE`），但 RustSBI 本身
   不解析 DTB；UART/CLINT 地址仍为编译期常量。未启用 FDT 时 `a1=0`。
4. 没有实现完整的 PMP 策略；默认 `CONFIG_HAS_PMP=n` 时不写 PMP。
   若打开 PMP，固件配置一条覆盖全部物理地址的 NAPOT 项，不是最小权限。
5. 没有 UART 中断/PLIC 初始化；console 使用轮询，S 模式外部中断需要
   payload 自己配置 PLIC。
6. `SRST`/`hart_stop` 使用 NPC 仿真的 custom-0 halt 指令结束仿真，不是
   真实硬件的复位/电源管理。
7. 未在 Linux 上验证；当前目标是先提供可构建、可启动、可回归的最小
   SBI 环境。
8. Rust 预编译 `libcore` 带 `zca` 标记；当前最终镜像检查未发现压缩指令。
   如未来工具链变化导致 core 引入压缩指令，需要 `CONFIG_RISCV_C=y` 或
   用 nightly `-Zbuild-std` 重建 core。
