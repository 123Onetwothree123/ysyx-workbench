# Profile 支持族与 L0/L1/L2 认证

本文档描述当前四个硬件预设的 profile、分类结果和 L0/L1/L2 认证流程。
旧的 6 个支持族（`small-rv32`、`rv32-mmu`、`standard-rv64`、`rv64-mmu`、
`rv64-sbi`、`linux-rv64`）及其 manifest 已删除；当前 `npc/params/profiles/`
只有本节列出的四个 profile。

> **最重要的语义**
>
> `qualified` 认证的是 **exact effective hash**，不是 profile 名，也不是
> defconfig 名。manifest 中出现的是
> `{effective_hash, defconfig, evidence, qualified_at}`；profile 名只描述这份
> hash 属于哪个支持族。
>
> `profile-compatible` 表示“落在 profile 策略边界内，且 L0/L1/L2 已通过”，
> 但不声称端到端功能已验证。`experimental` 是“合法但不在任何 profile
> 边界内”，**不是拒绝**。配置合法性仍由 Kconfig + `kconfig_contract` 决定。
>
> RV32 Linux 的 manifest 带 `evidence_scope=elaboration-only`：L2 只证明
> Chisel elaboration 成功，不证明 RV32 Linux 能启动。对应 profile 的
> `status_ceiling=profile-compatible`，因此分类和认证报告不会把它写成
> `qualified`。

---

## 1. 当前支持族

| Profile | base defconfig | 硬边界 | 允许的差异 | 覆盖的 defconfig |
| --- | --- | --- | --- | --- |
| `rv32i@1` | `rv32i` | RV32I（M/A/C/F/D=n）、仅 M、无 MMU/TLB、无 SBI/Linux/U-Boot、64 MiB | PMP 可选、ICache/DCache 开关、cache 几何、storage、SoC 跟随 CPU | `rv32i` |
| `rv32imac-linux@1` | `rv32imac_linux` | RV32、M/A/C=y、F/D=n、M/S/U、标准 Sv32、PMP 16、OpenSBI+FDT、直接 Linux 启动、`SBI_NEXT_ADDR=0x80400000` | DCache、initramfs 开关、64/128 MiB RAM、`SBI_BUILD_JOBS`、cache 几何 | `rv32imac_linux` |
| `rv32gc-linux@1` | `rv32gc_linux` | RV32、IMAFDC、M/S/U、标准 Sv32、PMP 16、OpenSBI+FDT、直接 Linux 启动、`SBI_NEXT_ADDR=0x80400000` | HardFloat/Fudian、FPU 流水级、DCache（行≥8 B）、initramfs、64/128 MiB RAM | `rv32gc_linux` |
| `rv64gc-linux@1` | `rv64gc_linux` | RV64、IMAFDC、M/S/U、标准 Sv39/Sv48、PMP 16、OpenSBI+FDT、直接 Linux 启动、不走 U-Boot EFI | `MMU_LEVELS=3/4`、HardFloat/Fudian、DCache、PMU、initramfs、64/128 MiB RAM | `rv64gc_linux`、`rv64_linux`（同 hash） |

设计取舍：

- **base 不手写**：`npc/tools/ParamGen/gen_profiles.py` 从真实 typed JSON
  提取 canonical flat 中有 prompt 的主参数。改 base 必须重新生成并 review
  diff。
- **allow 只列有意支持的差异**：cache 几何、DCache、initramfs、FPU 后端、
  64/128 MiB RAM、Sv48 等；ISA、特权级、MMU 几何、SBI/Linux 启动方式是
  predicate 硬边界。
- **RV32 Linux 不伪装成已支持**：`rv32imac-linux@1` 和 `rv32gc-linux@1`
  的 `status` 是 `experimental`，`qualification.status_ceiling` 是
  `profile-compatible`。L2 通过的 exact hash 仍写入 manifest，但带
  `evidence_scope=elaboration-only` 和“无 Linux 启动证据”的 note。
- **不混入 U-Boot 路径**：`rv64_linux_uboot_128m` 是 U-Boot EFI/128 MiB
  路径，不属于 `rv64gc-linux@1`；它的 `LINUX_BOOT_ENABLE=n`，predicate
  明确要求直接 Linux 启动且 `NPC_UBOOT_ENABLE=n`。
- **微架构不在 Kconfig**：2607 B4 的路线是“多周期 → 流水线”；当前 RTL
  的流水线没有 Kconfig 开关。四个 profile 只描述 ISA/MMU/缓存/平台参数，
  不声称预设等于某种微架构实现。

`npc/params/profiles/archive/` 目录保留为空；分类器和 `qualify.py` 只读取
顶层 `profiles/*.json`，不会加载 archive。

---

## 2. 当前分类与认证结果

数据来源（2026-11-28，未 commit 的工作区）：

- 分类：`npc/params/reports/defconfig-classification-summary.json` /
  `defconfig-classification.csv`
- 认证运行：`npc/params/reports/qualification-run.json` /
  `qualification-run.csv`
- 支持策略：`npc/params/reports/defconfig-support-policy.json`（full-scope
  `qualify.py --all` 运行已重写为当前四个 profile 的策略表）

24 个 defconfig 的分类结果：**5 个 profile-compatible，19 个 experimental，
0 invalid**。其中四个新预设都落在各自 profile 内；`rv64_linux` 与
`rv64gc_linux` 的 effective hash 相同，因此也落在 `rv64gc-linux@1` 内。

| defconfig | effective hash | profile | 分类 | 说明 |
| --- | --- | --- | --- | --- |
| `rv32i` | `db537e181aaf48a7…` | `rv32i@1` | qualified | L0/L1/L2 通过；hash 在 manifest |
| `rv32imac_linux` | `b0162d92a5806524…` | `rv32imac-linux@1` | profile-compatible | L0/L1/L2 通过；manifest 仅 elaboration 证据 |
| `rv32gc_linux` | `67f5c141d32f2abe…` | `rv32gc-linux@1` | profile-compatible | L0/L1/L2 通过；manifest 仅 elaboration 证据 |
| `rv64gc_linux` | `30c8ecd6256e9d22…` | `rv64gc-linux@1` | qualified | L0/L1/L2 通过；hash 在 manifest |
| `rv64_linux` | `30c8ecd6256e9d22…` | `rv64gc-linux@1` | qualified | 与 `rv64gc_linux` 同 hash，共享同一 manifest 证据 |

其余 19 个 defconfig（`mmu_default`、`nanos`、`rv32_c`、`rv32_f`、
`rv32_fd`、`rv32_noc`、`rv64_c`、三个 `rv64_custom_*`、`rv64_fd`、
`rv64_linux_uboot_128m`、`rv64_mmu`、`rv64_nanos_gc`、`rv64_sbi`、
`rv64_sbi_fdt`、`rv64_soft`、`rv64_sv48`、`rv64_xv6`）保持
`experimental`：它们是合法配置，但不在四个预设的边界内。特别是三个
custom MMU 配置（custom geometry/PTE、2 MiB/1 KiB 页、5 级页表）不应强行
塞进标准 Sv39/Sv48 family。

四个新预设的 L0/L1/L2（定向运行）：

| 阶段 | 结果 |
| --- | --- |
| L0a `kconfig_contract --config <defconfig>` | 4/4 通过 |
| L0b/L0c `c_conf --defconfig` + `--syncconfig` | 4/4 通过 |
| L1 `npc_config_gen`（typed/effective/scala/module） | 4/4 通过 |
| L0d `kconfig_contract --config .config --typed-json` | 4/4 通过 |
| L2 `mill -i runMain ysyx_26030103_Elaborate` | 4/4 通过 |

全量 `qualify.py --all` 运行覆盖 24 个 defconfig：L0/L1 24/24 通过；
L2 只对 5 个 profile-compatible case 运行，5/5 通过（四个新预设 +
`rv64_linux`，后者与 `rv64gc_linux` 同 hash）。

L2 日志和产物在
`/tmp/opencode/paramgen-qualify-new/<defconfig>/logs/l2-elaboration.log`，
elaboration 产物在 `/tmp/opencode/paramgen-qualify-new/<defconfig>/elaborate/`。

### 2.1 构建与 smoke（不是 L3/L4 认证）

| 预设 | 构建 | guest | 结果 |
| --- | --- | --- | --- |
| `rv32i` | `make -j$(nproc) build/ysyx_26030103_npc` 通过（hash `db537e18…`） | 重新构建的 `hello-riscv32e-npc.bin`（RV32E/ilp32e，无 M/A/C） | `HIT GOOD TRAP`，pc `0x800000b4`，cycles 4029 |
| `rv32imac_linux` | `make -j$(nproc) build/ysyx_26030103_npc` 通过（hash `b0162d92…`） | `hello-riscv32-npc.bin`（RV32IMA/ilp32，无 C/F/D） | `HIT GOOD TRAP`，pc `0x800000b4`，cycles 3836 |
| `rv32gc_linux` | `make -j$(nproc) build/ysyx_26030103_npc` 通过（hash `67f5c141…`） | `hello-riscv32-npc.bin`（RV32IMA/ilp32，无 C/F/D） | `HIT GOOD TRAP`，pc `0x800000b4`，cycles 3836 |
| `rv64gc_linux` | `make -j$(nproc) build/ysyx_26030103_npc` 通过（hash `30c8ecd6…`） | `riscv-arch-test-am/build/I-add-00-riscv64-npc.bin` | `HIT GOOD TRAP`，pc `0x80008d04`，cycles 7454786 |

RV32I 没有 RV32I 专用的现成 NEMU 参考模型；smoke 使用 RV32E 裸机镜像
（RV32I 是 RV32E 的超集），没有拿 RV32IM 镜像做 DiffTest。仓库里旧的
`hello-riscv32e-npc.bin` 是改动前的 `ebreak` 版本，已用当前
`abstract-machine` 重新构建后再跑。RV32IMAC/RV32GC 的 smoke 使用
RV32IMA 软浮点镜像，只覆盖整数/CSR/异常路径，没有覆盖 F/D 指令；RV32GC
的 FPU 行为需要单独的 D 扩展测试。四个构建都只是 Verilator 构建 + 短
smoke，不是 L3/L4 认证，也不改变 L0/L1/L2 结论。

`qualification-run.json` 的全量 summary 为
`cases=24, l0_pass=24, l1_pass=24, l2_pass=5, qualified=3,
profile_compatible=2, experimental=19, failed=0, invalid=0`。`qualified=3`
对应 `rv32i`、`rv64_linux`、`rv64gc_linux`（后两者同 hash）；两个 RV32 Linux
被 `status_ceiling` 限制为 `profile-compatible`。四预设定向运行
（`--workdir /tmp/opencode/paramgen-qualify-new`）的 summary 是
`cases=4, l0_pass=4, l1_pass=4, l2_pass=4, qualified=2,
profile_compatible=2`。

---

## 3. `qualify.py` 用法

### 3.1 基本命令

```bash
# 四个预设定向认证（L0/L1 并行，L2 串行）
python3 npc/tools/ParamGen/qualify.py \
  --profile rv32i --profile rv32imac-linux \
  --profile rv32gc-linux --profile rv64gc-linux \
  --defconfig rv32i --defconfig rv32imac_linux \
  --defconfig rv32gc_linux --defconfig rv64gc_linux \
  --jobs 4

# 单个 defconfig + 单个 profile
python3 npc/tools/ParamGen/qualify.py rv64gc_linux \
  --profile rv64gc-linux

# 只看计划，不执行、不写文件
python3 npc/tools/ParamGen/qualify.py --all --dry-run

# 只跑 L0/L1，不跑 L2，不写 manifest
python3 npc/tools/ParamGen/qualify.py --all --skip-l2
```

默认 workdir 是 `/tmp/opencode/paramgen-qualify/<defconfig>/`；每个 case
独立创建 `Kconfig`/`params` 符号链接和 `include/generated`，不会使用仓库的
`npc/.config`、`npc/include/generated` 或 `npc/include/config` 作为 scratch。
运行前后会指纹比较这些文件，`qualification-run.json` 的
`repo_config_untouched` 应为 `true`。

### 3.2 三个阶段

| 阶段 | 命令 | 失败处理 |
| --- | --- | --- |
| L0a | `kconfig_contract --config <defconfig>` | 保留日志，不进入 L1 |
| L0b/L0c | `c_conf --defconfig` + `c_conf --syncconfig` | 保留日志 |
| L1 | `npc_config_gen --typed-json --effective-json --scala-defaults --output` | 保留日志 |
| L0d | `kconfig_contract --config .config --typed-json typed.json` | 保留日志 |
| L2 | `mill -i runMain ysyx_26030103_Elaborate --target-dir <case>/elaborate` | 保留日志，不写 manifest |

- **L0**：Kconfig 解析 + contract。defconfig 本身和生成后的 `.config` 都检查。
- **L1**：`npc_config_gen` 的四类输出都生成，避免只验证 typed JSON。
- **L2**：Chisel elaboration。只对 `profile-compatible` 的 case 跑。
- **不跑 L3/L4**：不做完整 Verilator 构建，不做 VGA/Linux/SoC 长仿真。

### 3.3 并发与日志

- `--jobs N` 只并行 L0/L1；每个 case 有独立 workdir。
- L2 始终串行，因为所有 mill 调用共享 `npc/out`。不要用多进程同时跑
  `mill`，否则会争用 mill 的 out 目录/daemon。
- 日志在 `<workdir>/<defconfig>/logs/`：
  `l0-defconfig-contract.log`、`l0-kconfig-defconfig.log`、
  `l0-kconfig-syncconfig.log`、`l1-npc-config-gen.log`、
  `l0-generated-contract.log`、`l2-elaboration.log`。
- 失败 case 不写入 manifest；日志保留到下一次同 case 重跑（重跑会重建该
  case 的 workdir）。

### 3.4 报告

- `npc/params/reports/qualification-run.json`：每个 case 的阶段、hash、
  分类、L2 产物数、日志路径、manifest 更新。
- `npc/params/reports/qualification-run.csv`：一行一个 case。
- `npc/params/reports/defconfig-support-policy.json`：full-scope 运行时
  生成的支持策略表；当前文件来自 24 个 defconfig × 4 个 profile 的
  `qualify.py --all` 运行。
- `npc/params/reports/defconfig-classification-summary.json`：由
  `classify_defconfigs.py` 生成；manifest 更新后重跑可看到 `qualified`
  数量变化。

---

## 4. Manifest 与 hash 提升

### 4.1 manifest 格式

`npc/support/qualified/<profile>@<version>.json`：

```json
{
  "schema": 1,
  "profile": "rv64gc-linux",
  "profile_version": 1,
  "status": "qualified",
  "evidence_scope": "l0-l2",
  "generated_by": "npc/tools/ParamGen/qualify.py",
  "run": {
    "git_head": "54bc7c20e...",
    "git_dirty": true,
    "selection": "subset",
    "stages": ["L0 Kconfig + contract", "L1 npc_config_gen", "L2 Chisel elaboration"]
  },
  "hashes": [
    {
      "effective_hash": "30c8ecd6256e9d22...",
      "npc_config_hash": "32940dec601fd510",
      "defconfig": "rv64gc_linux",
      "source": "npc/configs/rv64gc_linux_defconfig",
      "profile": "rv64gc-linux",
      "profile_version": 1,
      "evidence": {
        "l0_kconfig_contract": "pass",
        "l1_npc_config_gen": "pass",
        "l2_chisel_elaboration": "pass",
        "scope": "l0-l2"
      },
      "qualified_at": "2026-11-28T14:15:00+00:00"
    }
  ]
}
```

RV32 Linux manifest 的 `status` 是 `profile-compatible`，
`evidence_scope` 是 `elaboration-only`，每个 hash 的 `evidence.note` 明确写
“L2 Chisel elaboration only; no Linux boot evidence”。`hashes` 里仍是精确
hash；检查器只做 `effective_hash in manifest_hashes`，但分类状态会被
`qualification.status_ceiling` 限制为 `profile-compatible`。

manifest 的 `profile/profile_version` 必须与 profile 文件一致，否则分类为
`invalid`。空 manifest 不会由 `qualify.py` 伪造：只有至少一个 exact hash
通过 L2 才写文件。

### 4.2 什么情况下要提升 hash/profile 版本

- 修改了 `npc/params/schema/schema.json`、生成的 Kconfig、defconfig、
  typed 生成器或 canonical 过滤规则：重新生成 typed JSON 和 profile base；
  如果 base 语义变化，提升 profile `version`，旧 manifest 移到
  `npc/support/qualified/archive/`。
- 只修改 RTL、但配置参数不变：effective hash 不变，但 L2/L3 证据可能过期；
  应重跑 `qualify.py`，不要复用旧 L2 证据。
- 修改了 profile 的 allow/predicate/status_ceiling：提升 profile version 并
  重新认证。
- 新增 defconfig：先分类。如果落在现有 family 内，直接跑 `qualify.py`；
  如果不在，先决定是扩展 allow、增加 profile，还是标 experimental，不要
  为了让分类通过而盲目放宽 predicate。

### 4.3 如何“提升”一个已认证 hash

1. 修改 defconfig/Kconfig/profile 后，先跑 `classify_defconfigs.py` 看
   effective hash 和分类。
2. 跑 `qualify.py <defconfig> --profile <id>`，确认 L0/L1/L2 全过。
3. 检查新 manifest diff：旧 hash 不应继续被声称合格；全量运行会重建该
   profile 的 manifest。
4. 跑 `classify_defconfigs.py` 刷新报告，确认 `qualified` 数量。
5. 如果需要保留旧 hash 的历史，把旧 manifest 复制到
   `npc/support/qualified/archive/`，不要在同一 manifest 中混用不同
   profile version。

---

## 5. 如何新增 profile

1. 选一个真实 defconfig 作为 base（不要手写 base 值）。
2. 在 `npc/tools/ParamGen/gen_profiles.py` 的 `PROFILE_SPECS` 增加 spec：
   `id/version/status/description/defconfig/typed/allow/predicate/qualification`。
3. 确保该 defconfig 的 typed JSON 已存在：
   ```bash
   python3 npc/tools/ParamGen/classify_defconfigs.py --typed-only \
     --defconfig <name> --workdir /tmp/opencode/paramgen-new-presets
   ```
4. 运行：
   ```bash
   python3 npc/tools/ParamGen/gen_profiles.py \
     --typed-dir /tmp/opencode/paramgen-new-presets
   ```
5. 运行 `classify_defconfigs.py`，逐个检查 experimental 的原因是不是你预期
   的边界，而不是 allow/predicate 笔误。
6. 运行 `qualify.py --profile <new-id> --defconfig <name>`；L2 通过后
   manifest 才会写入 hash，不要手工建空 manifest 占位。

新增 profile 的判定标准：

- 它描述一个**支持族**，不是单个 defconfig；
- base 来自真实配置；
- allow 只列有意支持的差异；
- predicate 写清硬边界（XLEN、ISA、MMU、SBI/Linux、平台 RAM、custom MMU 等）；
- 不把“所有配置都能过”当作目标。

---

## 6. 如何标记 experimental

满足任一情况就应标 experimental，而不是改 allow 强行通过：

- 使用 custom MMU geometry / custom PTE / 非标准页大小/级数；
- 需要 profile 之外的 ISA/平台组合（例如在 Linux profile 里关闭 SBI）；
- 平台内存映射、RAM 大小、启动方式不在 family 边界内；
- 新 defconfig 还没有对应的支持族策略；
- 只有 L2 elaboration 证据、没有端到端启动证据（RV32 Linux 当前状态）。

当前三个 custom MMU 配置就是明确例子：

- `rv64_custom_deep`：5 级 custom geometry/PTE；
- `rv64_custom_large`：2 MiB 页；
- `rv64_custom_page`：1 KiB 页。

它们在 `defconfig-support-policy.json` 中记录 nearest profile 为
`rv64gc-linux@1`，但 `compatible=false`、`status=experimental`。它们仍然
必须通过 Kconfig contract 和 L0/L1；本阶段没有对它们跑 L2，因为 L2 只要求
覆盖 profile-compatible。

`rv64_linux_uboot_128m` 也是 experimental：它走 U-Boot EFI/128 MiB/
Sv48 路径，`LINUX_BOOT_ENABLE=n`，不属于 `rv64gc-linux@1` 的直接 Linux
启动边界。

---

## 7. 复现命令

```bash
# 1. schema 是权威源；改了 schema 后生成 Kconfig 并跑 parity
python3 npc/tools/ParamGen/gen_kconfig.py
python3 npc/tools/ParamGen/parity_kconfig.py

# 2. 生成四个新 defconfig 的 typed JSON（只生成，不分类）
python3 npc/tools/ParamGen/classify_defconfigs.py --typed-only \
  --defconfig rv32i --defconfig rv32imac_linux \
  --defconfig rv32gc_linux --defconfig rv64gc_linux \
  --workdir /tmp/opencode/paramgen-new-presets

# 3. 生成四个 profile（base 来自第 2 步的 typed JSON）
python3 npc/tools/ParamGen/gen_profiles.py \
  --typed-dir /tmp/opencode/paramgen-new-presets

# 4. 全量分类（24 个 defconfig；首次会重新生成并 repeat 校验）
python3 npc/tools/ParamGen/classify_defconfigs.py \
  --workdir /tmp/opencode/paramgen-classify-all

# 5. 四个预设定向认证：L0/L1 并行，L2 串行
python3 npc/tools/ParamGen/qualify.py \
  --profile rv32i --profile rv32imac-linux \
  --profile rv32gc-linux --profile rv64gc-linux \
  --defconfig rv32i --defconfig rv32imac_linux \
  --defconfig rv32gc_linux --defconfig rv64gc_linux \
  --jobs 4

# 6. 用更新后的 manifest 刷新分类报告
python3 npc/tools/ParamGen/classify_defconfigs.py --skip-generate --no-repeat \
  --workdir /tmp/opencode/paramgen-classify-all
```

如果只想复现分类而不重跑 L2：

```bash
python3 npc/tools/ParamGen/classify_defconfigs.py --skip-generate --no-repeat \
  --workdir /tmp/opencode/paramgen-classify-all
```

注意：`--skip-generate` 依赖已有 typed JSON；换机器/清空 `/tmp` 后必须
先跑完整分类命令重新生成。

---

## 8. 未验证点与限制

- **没有跑 L3/L4**：没有完整 Verilator 构建，没有 VGA/Linux/SoC 长仿真；
  `qualified` 只覆盖 L0/L1/L2。
- **L2 只证明 elaboration 成功**：不证明生成的 RTL 仿真行为正确。
- **RV32 Linux 端到端阻塞**：
  - 仓库没有 RV32 Linux Image/initramfs；32 位 glibc sysroot 缺 libc/atomic，
    没有可用的 RV32 用户态工具链；
  - `pack_linux_image` 要求 Image 头 `text_offset` 为 0 或 `0x400000`
    （对应 `SBI_NEXT_ADDR=0x80400000`）；很多预编译 RV32 Image 使用
    `0x200000`，会被拒绝；
  - OpenSBI 的 `SBI_ISA`/`SBI_ABI` 已能从 `CONFIG_XLEN` 派生为
    `rv32ima_zicsr_zifencei`/`ilp32`，但尚未做 RV32 实机启动验证；
  - 因此 `rv32imac-linux@1`/`rv32gc-linux@1` 只交付“配置合法 + L0/L1/L2
    通过 + exact hash 记录”，不是“RV32 Linux 已能启动”。
- **manifest 带 `qualified_at` 和 `git_head`**：hash 相同但 RTL 改动后，
  旧 L2 证据不自动失效；需要重跑认证。
- **mill 共享 `npc/out`**：L2 串行是刻意的；不要并发跑多个 `qualify.py`。
- **profile 仍是策略原型**：`status` 字段尚未接入 Makefile/CI；
  experimental 仍必须通过现有全部构建/测试。
- **classification 的 `best_profile` 只是报告字段**：experimental 配置的
  nearest profile 不代表它 compatible；以 `qualification-run.json` /
  manifest 的 `compatible/status` 为准。
- **hash 过滤规则版本化**：改 canonical 规则必须提升
  `HASH_DOMAIN`/`canonical_schema_version`，否则历史 hash 不可比。
