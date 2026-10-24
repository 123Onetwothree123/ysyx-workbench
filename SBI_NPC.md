# OpenSBI on NPC

本文记录 ysyx-workbench 内置 OpenSBI 的来源、NPC 平台适配、Kconfig 接口、
构建/测试方法，以及与 xv6/NPC 直接启动路径的边界。

## 1. 状态与来源

- 上游：<https://github.com/riscv-software-src/opensbi>
- 版本：`v1.9`
- commit：`cbf9f6734dd85a982c63e3cb5db7ffe09da839ca`
- vendor 路径：`vendor/opensbi/`
- 许可证：BSD-2-Clause
- 纳入方式：直接复制源码并删除内部 `.git`，**不是 submodule**；
  `git add .` 会跟踪 `platform/npc/` 和所有本地适配补丁。
- 默认开关：`SBI_ENABLE=n`。xv6 仍由 NPC 直接跳到 `0x80000000`，
  不经过 SBI，现有启动路径不变。

来源与补丁清单见 `vendor/opensbi/VENDOR.md`。

## 2. 内存映射与 next-stage 布局

| 区域 | 地址 | 说明 |
| --- | --- | --- |
| SBI 固件 | `0x80000000` | NPC 复位入口，`SBI_FW_BASE` |
| 固件预留 | `0x80000000` + 2 MiB | `SBI_FW_SIZE` 默认 `0x00200000` |
| next-stage | `0x80200000` | `SBI_NEXT_ADDR`，OpenSBI 以 S 模式跳转 |
| UART | `0x10000000` | 8250 兼容，只实现 TX |
| CLINT | `0x02000000` | `mtimecmp=0x02004000`、`mtime=0x0200bff8` |
| PLIC | `0x0c000000` | 32 个 source 编号（0 保留），S context 1 |
| RAM | `0x80000000` | direct-NPC 默认 64 MiB |

后续 Linux 建议布局：

```text
0x80000000  OpenSBI fw_jump/fw_payload（2 MiB 预留）
0x80200000  Linux kernel Image（S 模式入口）
0x80f00000  DTB（SBI_FDT_ADDR，a1 指向这里）
0x84000000  RAM 结束（64 MiB）
```

启用 `SBI_FDT_ENABLE=y` 后，`0x80200000..0x80f00000` 约 13 MiB
留给内核镜像；如果 Linux Image 更大，必须同时上移 `SBI_FDT_ADDR`
或下移 `SBI_NEXT_ADDR`。

`SBI_NEXT_ADDR` 必须按 RV64 2 MiB、RV32 4 MiB 对齐；且
`SBI_FW_BASE + SBI_FW_SIZE <= SBI_NEXT_ADDR`。配置契约脚本会在
`make defconfig` 阶段检查这些约束。

## 3. Kconfig

独立文件 `npc/kconfig/Kconfig.sbi`，由 `npc/Kconfig` source，避免和
FPU/ISA 选项冲突。当前符号：

| 符号 | 默认 | 说明 |
| --- | --- | --- |
| `SBI_ENABLE` | `n` | 总开关；关闭时 `make sbi` 明确报错 |
| `SBI_IMPL_OPENSBI` | choice | 当前真实可构建实现 |
| `SBI_IMPL_RUSTSBI` | choice | 预留给 RustSBI，未提供构建脚本时明确报错 |
| `SBI_IMPL` | `opensbi`/`rustsbi`/`none` | 字符串派发键 |
| `SBI_FW_BASE` | `0x80000000` | 固件链接/加载地址 |
| `SBI_FW_SIZE` | `0x00200000` | 固件预留区上限 |
| `SBI_NEXT_ADDR` | `0x80200000` | next-stage S 模式入口 |
| `SBI_PLATFORM` | `"npc"` | OpenSBI PLATFORM 名称 |
| `SBI_BUILD_JOBS` | `0` | `0` 表示 `nproc` |
| `SBI_FDT_ENABLE` | `n` | 是否向 next-stage 传递 DTB |
| `SBI_FDT_PATH` | `"dts/npc.dts"` | DTS/DTB 源文件，相对 `npc/` 或绝对路径 |
| `SBI_FDT_ADDR` | `0x80f00000` | DTB 最终加载/传递地址（a1） |

OpenSBI 的 ISA/ABI/链接器不新增 Kconfig 项（避免和 RustSBI 接口冲突），
由 Makefile 变量/环境覆盖，默认：

```text
SBI_TOOLCHAIN_PREFIX=riscv64-unknown-elf-
SBI_ISA=rv64ima_zicsr_zifencei
SBI_ABI=lp64
SBI_LD=ld.lld
```

OpenSBI 1.9 固件是 PIE；当前 binutils 2.47 的 RISC-V `ld.bfd` 不支持
`-pie`，因此默认使用 `ld.lld`。若本机 `ld.bfd` 支持 PIE，可覆盖
`SBI_LD`。

## 4. 构建

```sh
cd npc
make rv64_sbi_defconfig
make -j"$(nproc)" build/ysyx_26030103_npc   # 首次需要重建 RTL/仿真器
make sbi                                    # 兼容别名 make opensbi
```

输出：

```text
npc/build/opensbi/fw.bin     # 可直接加载的 FW_JUMP 固件
npc/build/opensbi/fw.elf     # PIE ELF，带调试信息
npc/build/opensbi/fw.info    # 来源 commit、ISA、地址、大小、sha256
```

注意：NPC 的 `ImageLoader` 只接受 `ET_EXEC`，OpenSBI 输出的是
`ET_DYN` PIE，因此运行时加载 `fw.bin`，不要直接用 `fw.elf`。

构建脚本会检查：

- `SBI_ENABLE=y`；
- `SBI_IMPL=opensbi`，且 `scripts/build_opensbi.sh` 存在；
- `SBI_PLATFORM` 目录存在（当前只有 `npc`）；
- 地址对齐、固件不越过 next-stage；
- 工具链、`ld.lld`、源码树存在；
- 实际 `fw.bin` 不超过 `SBI_FW_SIZE`。

任一条件不满足都会以非零退出码明确报错，不会静默跳过或使用其他实现。

## 5. 设备树 DTB（SBI_FDT_ENABLE）

`SBI_FDT_ENABLE=y` 时，构建脚本先用 `scripts/build_dtb.sh` 生成 DTB，
再让 SBI 把它传给 next-stage：

- OpenSBI：`FW_FDT_PATH=<dtb>` 把 DTB 编进固件，`FW_JUMP_FDT_ADDR`
  或 `FW_PAYLOAD_FDT_ADDR` 把它复制到 `SBI_FDT_ADDR`；OpenSBI 启动
  日志的 `Domain0 Next Arg1` 就是该地址。
- RustSBI：`build.rs` 把 DTB 编进 `.rodata`，`rust_main` 在 `mret`
  前复制到 `SBI_FDT_ADDR`，并把该地址放进 `a1`。
- `SBI_FDT_ENABLE=n`：不生成/嵌入 DTB，进入 S 模式时 `a1=0`，与之前
  行为一致。

DTS 源文件 `npc/dts/npc.dts` 由 cpp 展开布局宏后交给 dtc；可用
`SBI_FDT_PATH` 指向自定义 `.dts` 或预编译 `.dtb`。默认内容：

| 节点 | 内容 |
| --- | --- |
| `/cpus` | 单 hart，`riscv,isa="rv64ima_zicsr_zifencei"`，`mmu-type="riscv,sv39"`，`timebase-frequency=10MHz` |
| `/memory@80000000` | 64 MiB |
| `/soc/clint@2000000` | CLINT，mtimecmp/mtime 兼容布局 |
| `/soc/interrupt-controller@c000000` | PLIC，`riscv,ndev=31`，S context |
| `/soc/serial@10000000` | ns16550a，IRQ 10，10 MHz 输入时钟 |
| `/chosen` | `bootargs="console=ttyS0,115200 earlycon=sbi"`，`stdout-path` 指向 UART |
| `/reserved-memory` | SBI 固件区、next-stage 入口页、DTB 保留区，均按 Kconfig 生成 |

单独生成/检查 DTB：

```sh
cd npc
make dtb
ls build/dtb/npc.dtb build/dtb/npc.dts build/dtb/npc.dtb.info
```

`build_dtb.sh` 会校验 DTB magic、8 字节对齐、RAM 范围、固件区不重叠、
next-stage 入口页不重叠，以及实际 DTB 不超过 `SBI_FDT_RESERVE_SIZE`；
生成物只写在 `npc/build/` 下，不纳入版本管理。

## 6. 测试

```sh
cd npc
make rv64_sbi_defconfig
make -j"$(nproc)" build/ysyx_26030103_npc
make sbi-test                         # 默认 SBI_FDT=n，a1=0
make sbi-test SBI_FDT_ENABLE=y        # OpenSBI + DTB
make sbi-test SBI_IMPL=rustsbi SBI_FDT_ENABLE=y   # RustSBI + DTB
```

`sbi-test` 会：

1. 用 `npc/test/opensbi_payload/` 构建最小 S 模式 payload，链接到
   `SBI_NEXT_ADDR`；
2. 以 `FW_PAYLOAD` 方式把 payload 嵌入 `build/opensbi/test/fw.bin`；
3. 用 direct-NPC 运行并检查 OpenSBI banner、S 模式入口、SBI console
   ecall 和 payload 完成标记；
4. 启用 FDT 时，payload 还会检查 `a1=SBI_FDT_ADDR`、magic
   `0xd00dfeed`、model、memory、UART（IRQ 10）、PLIC、chosen 和
   reserved-memory 节点。

实际启动日志（节选，OpenSBI + FDT）：

```text
OpenSBI v1.9
...
Domain0 Next Address        : 0x0000000080200000
Domain0 Next Arg1           : 0x0000000080f00000
Domain0 Next Mode           : S-mode
...
Boot HART MIDELEG           : 0x0000000000000222
Boot HART MEDELEG           : 0x000000000004b109
SBI-NPC-PAYLOAD: entered S-mode via OpenSBI (a0=0x0000000000000000, a1=0x0000000080f00000)
SBI-NPC-PAYLOAD: FDT magic=0x00000000d00dfeed totalsize=0x00000000000006be
SBI-NPC-PAYLOAD: FDT model=ysyx,npc
SBI-NPC-PAYLOAD: FDT memory OK: 0x0000000080000000+0x0000000004000000
SBI-NPC-PAYLOAD: FDT uart OK: 0x0000000010000000 irq=10
SBI-NPC-PAYLOAD: FDT plic OK: 0x000000000c000000+0x0000000004000000 ndev=31
SBI-NPC-PAYLOAD: FDT chosen OK: /soc/serial@10000000
SBI-NPC-PAYLOAD: FDT reserved-memory OK
SBI-NPC-PAYLOAD: FDT PASS
SBI-NPC-PAYLOAD: spec_version=0x0000000003000000 (error=0x0000000000000000)
SBI-NPC-PAYLOAD: console ecall OK
SBI-NPC-PAYLOAD: PASS
```

`SBI_FDT_ENABLE=n` 时同一 payload 输出 `a1=0x0000000000000000` 和
`FDT none (a1=0)`，用于回归旧行为。`mtime` 的值随仿真运行变化；
`spec_version=0x03000000` 表示 SBI 3.0。

## 7. 无 F/D 构建

本任务的实际固件按 **无 F/D** 构建：

```text
ISA = rv64ima_zicsr_zifencei
ABI = lp64
```

OpenSBI 的 `_start` 使用 `amoswap.w` 抢 boot lottery，因此 **A 扩展是
硬性要求**；NPC 配置必须 `RISCV_A=y`。没有 F/D 也能完整构建和启动，
`readelf -A` 显示：

```text
Tag_RISCV_arch: "rv64i2p1_m2p0_a2p1_zicsr2p0_zifencei2p0_zmmul1p0_zaamo1p0_zalrsc1p0"
```

如果后续要构建 F/D 版本（例如为 Linux 硬浮点准备），可在命令上覆盖：

```sh
make sbi SBI_ISA=rv64imafdc_zicsr_zifencei SBI_ABI=lp64d
```

但当前 NPC RTL 的 F/D 仍是 Kconfig 占位，打开会在 elaboration 明确报错；
因此 F/D 版本只能验证编译，不能在现有 NPC 上运行。无 F/D 的
`rv64ima` 版本是当前唯一可运行配置。

## 8. NPC 平台限制与 vendor 补丁

NPC 当前 RTL 的若干简化需要 OpenSBI 侧适配（未修改 CPU RTL）：

| 现象 | 适配 |
| --- | --- |
| 未实现 `mhartid` (0xf14) | 单 hart 从上一级 `a0` 取 boot hart id；`current_hartid()` 固定 0 |
| `misa` 只报告 I/A/C，未置 S/U | 平台宏声明 S/U 可用（RTL 已实现） |
| 未实现 `mvendorid`/`mimpid` | 不注册 vendor 扩展；BASE 查询返回 0 |
| CLINT 无 MSIP | 不注册 MSWI/IPI 设备；单 hart 下安全退化 |
| Xbar 不接受 64 位多拍 CLINT 写 | `has_64bit_mmio=false`，驱动拆成两个 32 位写 |
| PLIC 源编号 0..31 | OpenSBI `num_src=31` |

剩余限制：

- 单 hart；没有 IPI/HSM 多核启动能力。
- DTB 已可通过 `SBI_FDT_ENABLE` 传递，但平台仍不解析 FDT：UART/PLIC/
  CLINT 地址是编译期常量；Linux 使用 DTB 自行发现设备。默认
  `SBI_FDT_ADDR=0x80f00000` 只给内核镜像留约 13 MiB，内核更大时必须
  调整布局。
- DTS 的 `riscv,isa` 固定为 `rv64ima_zicsr_zifencei`，没有 F/D；若后续
  构建带 F/D 的 SBI/Linux，需要同步修改 DTS（当前任务不修改 FPU/C）。
- NPC `misa` 对 S-mode 仍不报告 S/U；如果后续 Linux 依赖 `misa` 判断，
  需要在 RTL 修正，而不是继续在 SBI 侧伪装。
- OpenSBI 的 system reset/suspend 没有平台实现；测试 payload 用 `wfi`
  循环，脚本以超时结束（124 属预期）。
- `NPC_ACLINT_MTIMER_FREQ` 目前按 10 MHz 约定填写；NPC 的 `mtime` 实际
  是周期计数器。若后续 Linux 对时间的精度有要求，应按仿真/综合目标
  时钟调整 `platform/npc/platform.c` 中的频率或改为可配置参数。
- xv6 不经过 SBI，仍使用 `rv64_xv6_defconfig` 直接 M 模式启动。

## 9. RustSBI 接入约定

Kconfig 已预留 `SBI_IMPL_RUSTSBI`（由主线维护），Makefile 不写死
OpenSBI。RustSBI 代理只需：

1. 不要在 `npc/kconfig/Kconfig.sbi` 新建重复的 `SBI_ENABLE`/choice；
   使用现有 `SBI_IMPL_RUSTSBI` 和 `SBI_IMPL` 字符串。
2. 新增 `npc/scripts/build_rustsbi.sh`，实现与
   `build_opensbi.sh` 相同的输入/输出契约：
   - 输入环境变量：`SBI_ENABLE`、`SBI_IMPL`、`SBI_PLATFORM`、
     `SBI_FW_BASE`、`SBI_FW_SIZE`、`SBI_NEXT_ADDR`、`SBI_BUILD_JOBS`、
     `SBI_FDT_ENABLE`、`SBI_FDT_PATH`、`SBI_FDT_ADDR`、
     `SBI_FDT_OUT_DIR`、`SBI_TOOLCHAIN_PREFIX`、`SBI_OUT_DIR`、
     `SBI_PAYLOAD_PATH`、`NPC_HOME`、`WORKBENCH_HOME`；
   - 输出 `$SBI_OUT_DIR/fw.bin` 与 `$SBI_OUT_DIR/fw.elf`；RustSBI 当前
     输出 `rustsbi-npc.bin`/`rustsbi-npc.elf`，测试脚本按同一布局拼接；
   - 输出 `$SBI_OUT_DIR/fw.bin` 与 `$SBI_OUT_DIR/fw.elf`；
   - 未安装 Rust 工具链、缺少源码、地址越界时必须非零退出并打印明确
     错误；
   - 可选支持 `SBI_PAYLOAD_PATH`（测试模式）。
3. 新增 `npc/scripts/test_rustsbi.sh`，实现与 `test_opensbi.sh` 相同的
   冒烟测试约定。
4. 不需要改 `npc/Makefile` 的 `sbi`/`sbi-test` 目标：它们按
   `SBI_IMPL` 自动分派到 `scripts/build_<impl>.sh` /
   `scripts/test_<impl>.sh`。在脚本未提供前，选择 RustSBI 会得到
   “构建脚本不存在”的明确错误，不会伪造构建。

## 10. 默认关闭回归

`SBI_ENABLE=n` 时：

- `make sbi` 返回 2，并提示先运行 `make rv64_sbi_defconfig`；
- `make rv64_xv6_defconfig` 的 `.config` 中 `CONFIG_SBI_IMPL="none"`；
- xv6/NPC 的编译、RTL 生成和启动路径不受影响；
- `PERF_CONFIG_SUMMARY` 会记录 `SBI_ENABLE=n;SBI_IMPL=none;...`，
  便于构建产物追溯。
