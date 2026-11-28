# Profile 支持族与 L0/L1/L2 认证

本文档是 Phase 3C 的说明：profile 从 3 个示例细化为 6 个支持族，并用
`npc/tools/ParamGen/qualify.py` 对 20 个 defconfig 做 L0/L1/L2 认证。

> **最重要的语义**
>
> `qualified` 认证的是 **exact effective hash**，不是 profile 名，也不是
> defconfig 名。manifest 中出现的是
> `{effective_hash, defconfig, evidence, qualified_at}`；profile 名只描述这份
> hash 属于哪个支持族。
>
> `profile-compatible` 只表示“落在 profile 策略边界内”，不表示已经跑过
> L2。`experimental` 是“合法但不在任何 profile 边界内”，**不是拒绝**。
> 配置合法性仍由 Kconfig + `kconfig_contract` 决定。

---

## 1. 支持族设计

设计目标：20 个 defconfig 是真实需求；用 **base + allow + predicate** 覆盖
成组的配置，而不是给每个 defconfig 建一个 profile。确实属于自定义 MMU 的
配置明确留在 experimental。

| Profile | base defconfig | 支持边界 | 覆盖的 defconfig |
| --- | --- | --- | --- |
| `small-rv32@2` | `rv32_c` | RV32、无 MMU、64 MiB；允许 A/C/F/D、FPU 后端、S/U 与可选 CSR、PLIC 开关 | `rv32_c` `rv32_f` `rv32_fd` `rv32_noc` |
| `rv32-mmu@1` | `nanos` | RV32 Sv32、2 级页表、4 KiB 页、PIPT；允许 M/A、cache 几何/存储、timer 路径、MDU 实现差异 | `mmu_default` `nanos` |
| `standard-rv64@2` | `rv64_c` | RV64、无 MMU、64 MiB；允许 F/D 与 FPU 后端 | `rv64_c` `rv64_fd` |
| `rv64-mmu@1` | `rv64_mmu` | RV64 标准 Sv39/Sv48、4 KiB 页、标准 PTE；允许 A/C/F/D、cache、trace/ringbuffer、timer、3/4 级页表 | `rv64_mmu` `rv64_sv48` `rv64_soft` `rv64_xv6` `rv64_nanos_gc` |
| `rv64-sbi@1` | `rv64_sbi` | RV64 + Sv39 + PMP + OpenSBI；允许 FDT/PMU 和标准地址范围；不启动 Linux/U-Boot | `rv64_sbi` `rv64_sbi_fdt` |
| `linux-rv64@2` | `rv64_linux` | RV64GC + Sv39/Sv48 + OpenSBI + Linux；允许 U-Boot、initramfs、64/128 MiB、启动参数/地址 | `rv64_linux` `rv64_linux_uboot_128m` |

设计取舍：

- **不为每个 defconfig 建 profile**：同一族的差异通过 `allow.bool`、
  `allow.enum`、`allow.int_range` 表达；硬边界通过 `predicate` 表达。
- **custom MMU 不塞进标准族**：`rv64_custom_deep`、`rv64_custom_large`、
  `rv64_custom_page` 使用 custom geometry、custom PTE、非 4 KiB 页或 5 级
  页表。强行放宽 `rv64-mmu` 的 `MMU_LEVELS/PAGE_SIZE/PTE` 会把“标准
  family”变成“任何 RV64 MMU”，所以它们保持 experimental。
- **base 不手写**：`npc/tools/ParamGen/gen_profiles.py` 从真实 typed JSON
  提取 canonical flat 中有 prompt 的主参数。改 base 必须重新生成并 review
  diff。

`npc/params/profiles/archive/` 保留了 v1 的三个示例 profile，便于对比；
分类器和 `qualify.py` 只读取 `profiles/*.json` 顶层文件，不会加载 archive。

---

## 2. 20 个 defconfig 的分类结果

数据来源：

- 分类：`npc/params/reports/defconfig-classification-summary.json` /
  `defconfig-classification.csv`
- 支持策略表：`npc/params/reports/defconfig-support-policy.json`
- 认证运行：`npc/params/reports/qualification-run.json` /
  `qualification-run.csv`

当前结果：**20 个 L0/L1 通过，17 个 L2 通过；17 qualified，3 experimental，
0 invalid**。按支持策略统计：17 个 profile-compatible（全部已认证为
qualified）、3 个 experimental、0 invalid；按认证状态统计：17 qualified、
0 个仅 profile-compatible 未认证、3 experimental、0 invalid。

| defconfig | effective hash | profile | status | 原因 |
| --- | --- | --- | --- | --- |
| `mmu_default` | `d613dfbcce4457a4…` | `rv32-mmu@1` | qualified | L0/L1/L2 通过，hash 在 manifest |
| `nanos` | `99345e5b2e3dc409…` | `rv32-mmu@1` | qualified | L0/L1/L2 通过，hash 在 manifest |
| `rv32_c` | `a2e04c43937bd7b8…` | `small-rv32@2` | qualified | L0/L1/L2 通过，hash 在 manifest |
| `rv32_f` | `e6ff4f440df764ef…` | `small-rv32@2` | qualified | L0/L1/L2 通过，hash 在 manifest |
| `rv32_fd` | `0612f3cd212beb9b…` | `small-rv32@2` | qualified | L0/L1/L2 通过，hash 在 manifest |
| `rv32_noc` | `3dbef815a9a5cf6c…` | `small-rv32@2` | qualified | L0/L1/L2 通过，hash 在 manifest |
| `rv64_c` | `5de4545daf05339f…` | `standard-rv64@2` | qualified | L0/L1/L2 通过，hash 在 manifest |
| `rv64_custom_deep` | `7b1a6230ae5c2cff…` | `rv64-mmu@1` | experimental | custom geometry/PTE，5 级页表；有意不进入标准族 |
| `rv64_custom_large` | `6940d3c71985cde8…` | `rv64-mmu@1` | experimental | custom geometry/PTE，2 MiB 页；有意不进入标准族 |
| `rv64_custom_page` | `a390ceed39705e08…` | `rv64-mmu@1` | experimental | custom geometry/PTE，1 KiB 页；有意不进入标准族 |
| `rv64_fd` | `eadc4d3a3d14a94c…` | `standard-rv64@2` | qualified | L0/L1/L2 通过，hash 在 manifest |
| `rv64_linux` | `30c8ecd6256e9d22…` | `linux-rv64@2` | qualified | L0/L1/L2 通过，hash 在 manifest |
| `rv64_linux_uboot_128m` | `3a18154993c4d9bd…` | `linux-rv64@2` | qualified | L0/L1/L2 通过，hash 在 manifest |
| `rv64_mmu` | `936695e2a7547962…` | `rv64-mmu@1` | qualified | L0/L1/L2 通过，hash 在 manifest |
| `rv64_nanos_gc` | `c26a1761447e26d5…` | `rv64-mmu@1` | qualified | L0/L1/L2 通过，hash 在 manifest |
| `rv64_sbi` | `0aae1f6b16e0a5fb…` | `rv64-sbi@1` | qualified | L0/L1/L2 通过，hash 在 manifest |
| `rv64_sbi_fdt` | `548daedba6831779…` | `rv64-sbi@1` | qualified | L0/L1/L2 通过，hash 在 manifest |
| `rv64_soft` | `7df5d04679fe19cf…` | `rv64-mmu@1` | qualified | L0/L1/L2 通过，hash 在 manifest |
| `rv64_sv48` | `e55cf5b13fc586bc…` | `rv64-mmu@1` | qualified | L0/L1/L2 通过，hash 在 manifest |
| `rv64_xv6` | `8532358f63397e50…` | `rv64-mmu@1` | qualified | L0/L1/L2 通过，hash 在 manifest |

完整 hash 在对应 manifest 和 `defconfig-classification-summary.json` 中。
表中的 `…` 只是展示截断，认证匹配使用完整 64 位十六进制 hash。

---

## 3. `qualify.py` 用法

### 3.1 基本命令

```bash
# 全量：20 个 defconfig × 6 个 profile，L0/L1 并行、L2 串行
python3 npc/tools/ParamGen/qualify.py --all --jobs 4

# 单个 defconfig + 单个 profile
python3 npc/tools/ParamGen/qualify.py rv64_linux \
  --profile linux-rv64

# 多个 defconfig
python3 npc/tools/ParamGen/qualify.py rv32_c rv32_f rv32_fd \
  --profile small-rv32

# 只看计划，不执行、不写文件
python3 npc/tools/ParamGen/qualify.py --all --dry-run

# 只跑 L0/L1，不跑 L2，不写 manifest
python3 npc/tools/ParamGen/qualify.py --all --skip-l2
```

默认 workdir 是 `/tmp/opencode/paramgen-qualify/<defconfig>/`；每个 case
独立创建 `Kconfig`/`kconfig` 符号链接和 `include/generated`，不会使用仓库的
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
- `npc/params/reports/defconfig-support-policy.json`：全量运行时生成的
  20 个 defconfig 策略表（最佳 profile、状态、原因、family 成员）。
  `--skip-l2` 不会更新该表，避免用未认证状态覆盖已认证结果。
- `npc/params/reports/defconfig-classification-summary.json`：manifest
  更新后由 `classify_defconfigs.py` 重新生成，`qualified` 数量应随之变化。

---

## 4. Manifest 与 hash 提升

### 4.1 manifest 格式

`npc/support/qualified/<profile>@<version>.json`：

```json
{
  "schema": 1,
  "profile": "linux-rv64",
  "profile_version": 2,
  "status": "qualified",
  "generated_by": "npc/tools/ParamGen/qualify.py",
  "run": {
    "git_head": "a9b055cd...",
    "git_dirty": true,
    "selection": "all-defconfigs",
    "stages": ["L0 Kconfig + contract", "L1 npc_config_gen", "L2 Chisel elaboration"]
  },
  "hashes": [
    {
      "effective_hash": "30c8ecd6...",
      "npc_config_hash": "32940dec601fd510",
      "defconfig": "rv64_linux",
      "source": "npc/configs/rv64_linux_defconfig",
      "profile": "linux-rv64",
      "profile_version": 2,
      "evidence": {
        "l0_kconfig_contract": "pass",
        "l1_npc_config_gen": "pass",
        "l2_chisel_elaboration": "pass"
      },
      "qualified_at": "2026-11-28T04:14:54+00:00"
    }
  ]
}
```

`hashes` 里是精确 hash；检查器只做 `effective_hash in manifest_hashes`。
manifest 的 `profile/profile_version` 必须与 profile 文件一致，否则分类为
`invalid`。

### 4.2 什么情况下要提升 hash/profile 版本

- 修改了 `npc/params/schema/schema.json`、生成的 Kconfig、defconfig、
  typed 生成器或 canonical 过滤规则：重新生成 typed JSON 和 profile base；
  如果 base 语义变化，提升 profile
  `version`，旧 manifest 移到 `npc/support/qualified/archive/`。
- 只修改 RTL、但配置参数不变：effective hash 不变，但 L2/L3 证据可能过期；
  应重跑 `qualify.py`，不要复用旧 L2 证据。
- 修改了 profile 的 allow/predicate：提升 profile version 并重新认证。
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
3. 确保该 defconfig 的 typed JSON 已存在（`classify_defconfigs.py` 会在
   `/tmp/opencode/paramgen-work/defconfigs/<name>/typed.json` 生成）。
4. 运行：
   ```bash
   python3 npc/tools/ParamGen/gen_profiles.py \
     --typed-dir /tmp/opencode/paramgen-work/defconfigs
   ```
5. 在 `npc/support/qualified/` 建同名 manifest 占位（`hashes: []`）。
6. 运行 `classify_defconfigs.py`，逐个检查 experimental 的原因是不是你预期
   的边界，而不是 allow/predicate 笔误。
7. 运行 `qualify.py --profile <new-id> --all` 或指定 defconfig 列表，认证
   通过后 manifest 才会写入 hash。

新增 profile 的判定标准：

- 它描述一个**支持族**，不是单个 defconfig；
- base 来自真实配置；
- allow 只列有意支持的差异；
- predicate 写清硬边界（XLEN、MMU、SBI/Linux、平台 RAM、custom MMU 等）；
- 不把“所有配置都能过”当作目标。

---

## 6. 如何标记 experimental

满足任一情况就应标 experimental，而不是改 allow 强行通过：

- 使用 custom MMU geometry / custom PTE / 非标准页大小/级数；
- 需要 profile 之外的 ISA/平台组合（例如在 Linux profile 里关闭 SBI）；
- 平台内存映射、RAM 大小、启动方式不在 family 边界内；
- 新 defconfig 还没有对应的支持族策略。

当前三个 custom MMU 配置就是明确例子：

- `rv64_custom_deep`：5 级 custom geometry/PTE；
- `rv64_custom_large`：2 MiB 页；
- `rv64_custom_page`：1 KiB 页。

它们在 `defconfig-support-policy.json` 中记录 nearest profile 为
`rv64-mmu@1`，但 `compatible=false`、`status=experimental`，原因为
“custom MMU geometry/PTE/page size”。它们仍然必须通过 Kconfig contract 和
L0/L1；本阶段没有对它们跑 L2，因为 L2 只要求覆盖 profile-compatible。

---

## 7. 复现命令

```bash
# 1. schema 是权威源；改了 schema 后生成 Kconfig 并跑 parity
python3 npc/tools/ParamGen/gen_kconfig.py
python3 npc/tools/ParamGen/parity_kconfig.py

# 2. 生成 20 个 defconfig 的 typed JSON + hash 稳定性 + 分类
python3 npc/tools/ParamGen/classify_defconfigs.py

# 3. 生成 6 个 profile（base 来自第 2 步的 typed JSON）
python3 npc/tools/ParamGen/gen_profiles.py \
  --typed-dir /tmp/opencode/paramgen-work/defconfigs

# 4. 认证：L0/L1 并行，L2 串行
python3 npc/tools/ParamGen/qualify.py --all --jobs 4

# 5. 用更新后的 manifest 刷新分类报告
python3 npc/tools/ParamGen/classify_defconfigs.py --skip-generate --no-repeat \
  --workdir /tmp/opencode/paramgen-work/defconfigs
```

如果只想复现分类而不重跑 L2：

```bash
python3 npc/tools/ParamGen/classify_defconfigs.py --skip-generate --no-repeat \
  --workdir /tmp/opencode/paramgen-work/defconfigs
```

注意：`--skip-generate` 依赖已有 typed JSON；换机器/清空 `/tmp` 后必须
先跑完整分类命令重新生成。

---

## 8. 未验证点与限制

- **没有跑 L3/L4**：没有完整 Verilator 构建，没有 VGA/Linux/SoC 长仿真；
  `qualified` 只覆盖 L0/L1/L2。
- **L2 只证明 elaboration 成功**：不证明生成的 RTL 仿真行为正确。
- **manifest 带 `qualified_at` 和 `git_head`**：hash 相同但 RTL 改动后，
  旧 L2 证据不自动失效；需要重跑认证。
- **mill 共享 `npc/out`**：L2 串行是刻意的；不要并发跑多个 `qualify.py`。
- **profile 仍是策略原型**：`status` 字段为 `prototype`，尚未接入
  Makefile/CI；experimental 仍必须通过现有全部构建/测试。
- **classification 的 `best_profile` 只是报告字段**：experimental 配置的
  nearest profile 不代表它 compatible；以 `defconfig-support-policy.json`
  的 `compatible/status` 为准。
- **hash 过滤规则版本化**：改 canonical 规则必须提升
  `HASH_DOMAIN`/`canonical_schema_version`，否则历史 hash 不可比。
