# csrc XLEN 双模式测试说明

本目录的测试构建只从 NPC Kconfig 读取 `CONFIG_XLEN`（必要时另读
`CONFIG_SOC_XLEN` / `CONFIG_HAS_MMU`），不再使用独立 ISA/ELF class
开关。公共入口是 `npc/csrc/Test/xlen.mk`：

- `NPC_XLEN`：`npc/.config` 或 `include/config/auto.conf` 中的
  `CONFIG_XLEN`，缺省回退 32；
- `XLEN_CFLAGS`：只导出 `-DCONFIG_XLEN=...`；
- `XLEN_AM_ARCH`：`riscv32e-npc` / `riscv64-npc`；
- `XLEN_GUEST_MARCH/MABI`：裸机测试的 `-march/-mabi`；
- `NPC_HAS_MMU`：用于判断能否运行高虚拟地址用例。

核心 `readelf` 也直接由 `CONFIG_XLEN` 派生 ELF32/ELF64，不再依赖
独立的 ELF class 宏。

## 审计结果：原有 32 位硬编码

| 测试 | 原问题 | 处理 |
| --- | --- | --- |
| `readelf-test` | `static_assert(!Readelf::is_elf64)`；只构造 `Elf32_*` 样本 | 按 `CONFIG_XLEN` 构造 ELF32/ELF64 样本；RV64 使用 `0x1_0000_0000` 高入口，并额外拒绝反 class 文件 |
| `ftrace-test` | 只构造 ELF32 头；未导出 XLEN | 按配置构造 ELF32/ELF64；RV64 增加 4GiB 以上 call/ret 目标检查 |
| `runtime-observability-test` | 固定断言 `2^32` 越界、`x` 只看 4B | 表达式引擎实际按 64 位保存：测试改为两种 XLEN 都要求 `2^32` 原样保留，不再按 32 位截断 |
| `difftest-abi-test` / `difftest-platform-test` | 固定 RV32 NEMU DiffTest ABI | 保留为 REF ABI 主机侧测试，不参与 NPC XLEN 判定 |
| `difftest-online-test` / `nemu-difftest-isolation-test` | 默认 `ARCH=riscv32e-npc`，RV64 下会错配 | 保留 RV32 专用 harness；RV64 下明确报错并指向 `xlen-dual-test` |
| `bootloader` / `fencei-prefetch` / `smc` | 强制 `-march=rv32i...` | 保留 RV32 专用实现，但 RV64 配置下明确报错，不再静默构建 |
| `char-test` | 写死 `riscv32-unknown-elf-*` | 工具链和 `-march/-mabi` 改由 `xlen.mk` 派生，`ARCH` 缺省跟随配置 |
| `core-fix-guest` / `timer-uptime-test` | 默认 `ARCH=riscv32e-npc` | 默认改为 `XLEN_AM_ARCH` |
| `locale-wide-test` 的 `cpp-smoke` | 固定 `rv32im/ilp32` 和 `__ISA_RISCV32__` | 按配置选择 RV32/RV64 宏与 `-march/-mabi` |
| 大量 `0x80000000` 常量 | 是 direct-NPC 复位/镜像基址 | 不是 XLEN 硬编码，保留 |

## 新增覆盖

1. `npc/csrc/Test/xlen-dual-test/`
   - SDB：`p` 读取 64 位 GPR 高半区、`p 0x100000000`、`x 1g`、
     `read64`；RV64 halt 后 `$pc` 位于 4GiB 以上；
   - mtrace：RV64 高 VA 取指后的 64 位 PC/Data、8B 宽度；RV32 对照 4B；
   - DiffTest：RV32/RV64 都用对应 NEMU REF 真正执行逐退休与最终状态
     比对；RV64 额外用只声明 132 字节状态的旧 REF 验证执行前明确拒绝。
2. `npc/csrc/Test/mtrace-test/`
   - 直接调用 `MtraceRecord`，验证 4GiB 以上 PC/Addr/Data 不被截断。
   - 说明：当前 RTL 的 MMU 集成路径把 mtrace `Addr` 记录为翻译后的
     物理地址，高 VA 由 `xlen-dual-test` 的 `p $pc` 覆盖。
3. `npc/csrc/Test/csr-ident-test/`
   - M 模式读取 `mhartid`/`mimpid`/`mvendorid`/`marchid` 与 `misa`；
   - `misa.MXL/I` 以及由 Kconfig 注入的 S/U 位断言；
   - `misa`/`mimpid`/`mhartid` 的写意图触发非法指令。
4. `npc/scripts/xlen_dual_regression.sh`
   - 生成 `mmu_default_defconfig`、`nanos_defconfig`、`rv64_mmu_defconfig`、
     `rv64_soft_defconfig`、`rv64_sv48_defconfig`，检查 `CONFIG_XLEN` /
     `CONFIG_SOC_XLEN`；
   - 构建 RV32/RV64 NEMU REF 共享库，并在两种 XLEN 下各编译一次
     `CONFIG_DIFFTEST=y` 的 direct-NPC；
   - 运行 readelf/ftrace/mtrace 单元测试与 `xlen-dual-test`；
   - 运行日志在 `/tmp/opencode/xlen-dual`，结束时恢复 `.config` 和
     `include/config`、`include/generated`，不把构建产物带入提交。

## DiffTest 失败报告测试

失败报告本身不依赖 RTL，可单独验证四类人为构造的差异：

```bash
# GPR / CSR（含 RV64 mtval 高半区）/ PC / trap 差异与旧 REF 降级
make -C npc/csrc/Test/difftest-report-test run

# NEMU 可选诊断 ABI：最后执行指令 + mtval/stval 完整 XLEN
make -C npc/csrc/Test/difftest-last-inst-abi-test run \
    REF32=/path/riscv32-nemu-interpreter-so \
    REF64=/path/riscv64-nemu-interpreter-so
```

## 运行方式

```bash
# 一键矩阵（约数分钟，含两次 NPC 构建）
npc/scripts/xlen_dual_regression.sh

# 手工 RV32（REF 需自行构建为 RV32 状态 ABI）
make -C npc nanos_defconfig
make -C npc build/ysyx_26030103_npc
make -C npc/csrc/Test/xlen-dual-test test \
    REF_SO=/path/to/riscv32-nemu-interpreter-so

# 手工 RV64（REF 使用 nemu/build/riscv64-nemu-interpreter-so）
make -C npc rv64_soft_defconfig
make -C npc build/ysyx_26030103_npc
make -C npc/csrc/Test/xlen-dual-test test \
    REF_SO=/path/to/riscv64-nemu-interpreter-so
```

`xlen-dual-test` 的 SDB/mtrace 目标要求对应 `CONFIG_SDB` /
`CONFIG_TRACE` / `CONFIG_MTRACE` 已启用，RV64 还要求
`CONFIG_HAS_MMU=y`；脚本会自动配置这些选项。

## 最近一次实际结果

`npc/scripts/xlen_dual_regression.sh`：

- RV32（`nanos_defconfig`）：readelf ELF32、ftrace ELF32、mtrace 高值、
  SDB/mtrace/DiffTest 正向全部 PASS；
- RV64（`rv64_soft_defconfig`）：readelf ELF64 高地址、ftrace ELF64 高
  call 目标、mtrace 高值、SDB 64 位 GPR/高 PC、DiffTest 正向（含 4GiB
  以上 PC、Sv39 高 VA 与 64 位 CSR）以及旧 32 位 REF 拒绝全部 PASS；
- 配置矩阵：5 个 defconfig 的 `CONFIG_XLEN` 与 `CONFIG_SOC_XLEN` 均符合
  预期。
