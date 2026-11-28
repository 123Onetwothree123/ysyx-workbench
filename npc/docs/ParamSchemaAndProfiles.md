# 参数 schema、effective hash 与支持 profile

本文档描述参数 schema、effective hash 与 profile/qualification。当前
`schema.json` 是参数权威 manifest，`npc/params/schema/parts/*.json` 按顶层
菜单保存实际内容；`npc/Kconfig` 只是稳定入口，Kconfig symbol/menu 树由
`npc/tools/ParamGen/gen_kconfig.py` 生成；切换结构与自动生成规则见
`npc/docs/KconfigGeneration.md`。

本工具集负责：

1. 维护/校验参数事实源的确定性 JSON schema（manifest + parts）；
2. 从 schema 生成 Kconfig，并证明切换前后语义与迁移基线的 24 个 defconfig hash 一致；
3. 给一个 effective 配置计算规范化 SHA256；
4. 用声明式 profile 把配置分类为 qualified / profile-compatible /
   experimental / invalid。

Kconfig contract 仍是配置合法性的唯一权威；profile 只表达支持策略。

> 目录：`npc/params/`、`npc/tools/ParamGen/`、`npc/support/qualified/`。
> schema 中的 `generated_snapshot: true` 记录它最初由 Kconfig 反向导出；
> 切换后 schema（manifest + parts）是权威源，新增参数应直接编辑对应
> part，而不是重新从 Kconfig 反向生成覆盖它。
>
> 当前四个硬件预设（`rv32i`、`rv32imac-linux`、`rv32gc-linux`、
> `rv64gc-linux`）的支持边界、24 个 defconfig 状态和 L0/L1/L2 认证流程见
> `npc/docs/ProfileQualification.md`。

---

## 1. 文件布局

```text
npc/params/
  schema/schema.json                 # 权威参数 manifest（schema v2）
  schema/parts/cpu-platform.json     # 顶层菜单 part（共 7 个）
  schema/parts/mode.json
  schema/parts/fpga.json
  schema/parts/soc-peripheral.json
  schema/parts/runtime-debug.json
  schema/parts/firmware-boot.json
  schema/parts/build-sim.json
  schema/schema-summary.json         # 统计摘要
  generated/Kconfig                  # 生成入口，由 npc/Kconfig source
  generated/kconfig/part-NN.Kconfig  # 按顶层菜单拆分的生成文件
  profiles/rv32i@1.json               # RV32I 最小 M 模式支持族
  profiles/rv32imac-linux@1.json      # RV32IMAC + Sv32 + OpenSBI + Linux（实验）
  profiles/rv32gc-linux@1.json        # RV32GC + Sv32 + OpenSBI + Linux（实验）
  profiles/rv64gc-linux@1.json        # RV64GC + Sv39/Sv48 + OpenSBI + Linux
  profiles/archive/                   # 历史版本（当前为空）
  reports/defconfig-classification.csv
  reports/defconfig-classification-summary.json
  reports/defconfig-support-policy.json
  reports/qualification-run.json
  reports/qualification-run.csv
  reports/kconfig-generation-parity.md
  reports/kconfig-generation-parity.json
npc/support/qualified/
  rv32i@1.json                       # 1 个 exact hash
  rv32imac-linux@1.json              # 1 个 exact hash（elaboration-only）
  rv32gc-linux@1.json                # 1 个 exact hash（elaboration-only）
  rv64gc-linux@1.json                # 1 个 exact hash
  archive/                           # 历史 manifest（当前为空）
npc/tools/ParamGen/
  param_tool.py                      # 统一 CLI
  split_schema.py                    # schema 单文件 <-> manifest/parts
  gen_schema.py                      # Kconfig -> 单文件迁移快照（迁移校验）
  gen_kconfig.py                     # 合并后的 schema -> generated Kconfig
  parity_kconfig.py                  # 符号/菜单/24 defconfig/hash parity
  gen_profiles.py                    # typed JSON -> profile base/allow
  classify_defconfigs.py             # 24 个 defconfig 的批量报告
  qualify.py                         # L0/L1/L2 认证 + manifest 更新
  selftest.py                        # hash/predicate/status 自测
  paramlib.py                        # 共享的规范化/谓词/分类逻辑
  vendor/kconfiglib.py               # vendored Kconfiglib
  vendor/LICENSE.kconfiglib
```

### Kconfiglib 来源与许可证

`schema.json`（以及拆分后的 parts）最初由
[Kconfiglib](https://github.com/ulfalizer/Kconfiglib) 解析生成。
ParamGen 把仓库内已有的
`vendor/opensbi/scripts/Kconfiglib/kconfiglib.py`（`VERSION = (14, 1, 0)`）
复制到 `npc/tools/ParamGen/vendor/kconfiglib.py`，许可证为 **ISC**，原始
许可证文本在 `npc/tools/ParamGen/vendor/LICENSE.kconfiglib`。

选择 vendor 而不是依赖系统 Python 包，是为了让 schema 生成可复现且不需要
网络；运行时只使用 Python 3 标准库 + 这一个 vendored 文件。Kconfig 生成
已接入现有 config 流程（`menuconfig`、`*_defconfig`、`npc-config`），
不需要新增手动 make 目标，也没有新增 CI。

---

## 2. JSON schema（manifest + parts）

`npc/params/schema/schema.json` 是权威 manifest，实际内容在
`npc/params/schema/parts/*.json`。直接编辑对应 part；`gen_schema.py` 只用于
迁移校验，输出**单文件**迁移快照：

```bash
python3 npc/tools/ParamGen/gen_schema.py \
  --kconfig npc/Kconfig \
  --out /tmp/opencode/schema.json \
  --summary /tmp/opencode/schema-summary.json
```

它会解析新的 `npc/Kconfig`（wrapper + 生成树），但**任何**指向
`npc/params/schema/schema.json` 的 `--out` 都会被拒绝，即使传
`--allow-schema-overwrite`。原因是反向解析会写入生成文件的
`file`/`line`/`tier` 元数据并可能改变 `and` 表达式结合顺序；单文件迁移
快照不能替换 manifest + parts 权威结构。

需要把权威 schema 重新拆分或校验时使用：

```bash
python3 npc/tools/ParamGen/split_schema.py
python3 npc/tools/ParamGen/split_schema.py --check
python3 npc/tools/ParamGen/split_schema.py --check \
  --compare /tmp/opencode/schema-before-split.json
```

默认拆分前把旧单文件备份到 `/tmp/opencode/schema-before-split.json`；
`--check` 不写文件，验证 manifest、每个 part、summary 和（可选）比较
文件。part 文件名是纯语义名；顶层顺序只由 manifest 的 `parts` 数组决定，
不按文件名排序或 glob 推断。`paramlib.load_schema()` 按 manifest 顺序把
manifest + parts 合并回旧的单文件内存结构，`parameters` 按 `name`、
`choices` 按 `id`、`menus` 按原 `order` 排序，`layout.items` 按 manifest
`parts` 顺序排列。

`schema_bundle_sha256()`（CLI：`param_tool.py schema-hash`）也严格按
manifest 的 `parts` 顺序计算：hash 覆盖 manifest 元数据和 part 内容，
part 文件名只是定位信息，所以改名不改变 hash；重排 `parts` 数组会改变
hash。构建投影必须使用这个 bundle hash，不能只 hash `schema.json`。

### 2.1 manifest 根字段

| 字段 | 含义 |
| --- | --- |
| `generated_snapshot` | 固定 `true`，提醒这是生成快照 |
| `schema_version` | 快照格式版本，当前 `2` |
| `mainmenu` | 顶层 `mainmenu`（`NPC配置菜单`） |
| `parts` | part 相对路径列表，顺序就是 `layout.items` 顺序 |
| `generator` | 生成器、Kconfiglib 版本、许可证 |
| `source` | 权威文件、稳定 Kconfig 入口、迁移 provenance 文件列表、当前生成树文件列表、visibility/tier/layout/generation 模型说明 |
| `generation_issues` | 无法忠实反向生成的字段/表达式；正常快照为空 |

### 2.1.1 part 字段

每个 part 只保存一个顶层菜单：

| 字段 | 含义 |
| --- | --- |
| `menu_prompt` | 顶层菜单 prompt，必须与 `layout.prompt` 一致 |
| `layout` | 该顶层菜单的完整 layout item（含子菜单/symbol/choice） |
| `parameters` | 该菜单下每个符号一条，按 `name` 排序 |
| `choices` | 该菜单下每个 choice 组一条，按 `id` 排序 |
| `menus` | 该菜单下的扁平菜单记录，按原 `order` 排序 |

所有参数的 `menu_path[1]`、choice 的 `menu_path[1]` 和菜单的 `path[1]`
必须等于所属 part 的 `menu_prompt`；无法归属、重复菜单、重复参数/choice
或 part 缺失都会明确报错，不会静默丢弃。

`source.visibility_model` 和 `source.tier_model` 明确写出启发式口径，避免把
猜测误当成人工结论。

### 2.2 parameter 字段

| 字段 | 含义 |
| --- | --- |
| `name` | 符号名，不含 `CONFIG_` 前缀 |
| `type` | `bool` / `int` / `hex` / `string` / `choice`；choice 选项为 `choice` |
| `kconfig_type` | Kconfig 原始类型（choice 选项通常是 `bool`） |
| `prompt` | 提示文本；无提示为 `null` |
| `prompt_exists` | 是否存在 prompt |
| `help` | help 文本；无 help 为 `null` |
| `default` | 第一条 Kconfig default 的值；无 default 为 `null` |
| `defaults` | 全部 default：`value`、`condition`、`condition_expr`、`condition_local`、`condition_local_expr` |
| `default_value` | 在“空配置 + 默认值”下解析出的值 |
| `range` | int/hex 的 range 列表：`min`、`max`、完整/局部条件表达式 |
| `range_min` / `range_max` | 第一条 range 的边界，便于快速查看 |
| `depends_on` | 完整依赖表达式原文（Kconfiglib 渲染） |
| `depends_expr` | 完整依赖表达式的结构化 JSON（`and`/`or`/`not`/`eq`/...） |
| `depends_on_local` | 相对当前 menu/choice 的局部依赖；反向生成使用 |
| `depends_local_expr` | 局部依赖的结构化 JSON |
| `prompt_condition` / `prompt_condition_local` | prompt 的完整/局部条件 |
| `selects` / `implies` | 目标符号及完整/局部条件（当前仓库为 0 条） |
| `file` / `line` | 迁移来源位置，仓库相对路径；权威 schema 中多数指向已删除的旧 Kconfig，只作 provenance，不是构建输入 |
| `order` / `file_order` | 全局定义顺序 / 同文件顺序 |
| `menu_path` | 从 mainmenu 到所在菜单的 prompt 列表 |
| `visibility` | `visible` / `hidden`（空默认配置口径） |
| `tier` | `arch` / `microarch` / `platform` / `build` / `unknown` |
| `derived` | 是否无 prompt（当前派生判定） |
| `derived_reason` | 当前为 `no_prompt` 或 `null` |
| `choice_group` | choice 选项所属组 id；普通参数为 `null` |
| `choice_option` | 是否 choice 选项 |
| `choice_default` | 是否该 choice 在空默认配置下被选中 |
| `is_menuconfig` | 源码是否使用 `menuconfig`（当前均为 `config`） |
| `node_count` | 定义位置数量；>1 时进入 `generation_issues` |

`visibility` 的口径：在**空配置 + 默认值**下，存在 prompt 且
symbol/choice 的 `visibility` 非零则为 `visible`，否则 `hidden`。因此
`hidden_with_prompt_count` 可能不为零：有 prompt 但默认模式下被依赖隐藏的
选项仍算 `hidden`。这不是“当前用户 .config 下是否可见”。

`tier` 是**启发式**：权威 schema 中保留的是迁移时写入的固定值；迁移快照
（`gen_schema.py`）的启发式先看文件名（旧 `Kconfig.cpp/sim/verilator/log/
synth/debug` → build，旧 `Kconfig.fpga/soc/sbi/linux/rootfs` → platform，
旧 `Kconfig.mmu` → arch/microarch），再按符号名前缀归类。切换到生成树后，
文件名是 `params/generated/kconfig/part-NN.Kconfig`，文件提示不再命中，
主要靠符号名前缀。它是后续人工审查的起点，不是架构结论。

`derived` 当前只表示“没有任何 prompt”。例如 `MMU_PTE_FORMAT`、`FPU_IMPL`、
`MUL_IMPL` 等隐藏字符串会被标为 derived；它们由 choice/其他参数推导。
未来如果出现“有 prompt 但已知派生”的参数，可在生成器中加入已知派生表。

### 2.3 choice 字段

每个 choice 组包含：

- `id`：命名 choice 用名字；匿名 choice 用选项名的公共前缀（如
  `FPU_IMPL`、`MUL_ENCODING`）；公共前缀重复时追加序号（如
  `MMU_PTW@2`），不使用行号，保证重生成稳定；无法形成合法前缀时用
  `choice@<file>:<line>`。
- `options`：选项符号名，排序后输出；`options_in_order` 保留 Kconfig 顺序。
- `default_option`：空默认配置下选中的选项。
- `defaults`：Kconfig default 列表及完整/局部条件。
- `prompt` / `help` / `prompt_condition*` / `visible_if*`。
- `depends_on` / `depends_on_local` 及结构化表达式、`file` / `line` /
  `order` / `file_order` / `menu_path`、`visibility`、`tier`。

### 2.4 统计

`schema-summary.json` 输出参数总数、prompt 数、visible/hidden、derived、
带 prompt 但隐藏的数量、choice 组/选项数、按 type/kconfig_type/tier/
visibility 的计数，以及 `files`（迁移 provenance 文件，可能已删除）和
`generated_kconfig_files`（当前构建实际消费的生成树）。

当前快照（生成时）：

- 868 个参数（含 227 个 choice 选项）
- 772 个有 prompt
- visible 504 / hidden 364
- derived 96
- 75 个 choice 组
- tier：arch 124 / microarch 173 / platform 293 / build 278 / unknown 0

数字会随 Kconfig 修改变化；以重新生成后的 summary 为准。

### 2.5 确定性

- 所有对象键在 `json.dumps` 时 `sort_keys=True`；
- 参数按 name、choice 按 id、文件列表按路径排序；
- 不写入时间戳、主机名、绝对路径；
- 重复运行同一 Kconfig 应得到字节一致的文件。

验证生成器确定性（迁移快照内部一致）：

```bash
python3 npc/tools/ParamGen/gen_schema.py \
  --out /tmp/opencode/schema-a.json --summary /tmp/opencode/summary-a.json
python3 npc/tools/ParamGen/gen_schema.py \
  --out /tmp/opencode/schema-b.json --summary /tmp/opencode/summary-b.json
cmp /tmp/opencode/schema-a.json /tmp/opencode/schema-b.json
```

### 2.6 从 schema 生成 Kconfig（已切换）

```bash
# 从 schema 生成；npc/Kconfig 只 source 生成入口
python3 npc/tools/ParamGen/gen_kconfig.py

# 完整 parity：schema 迁移校验、生成器确定性、符号表、24 个 defconfig hash
python3 npc/tools/ParamGen/parity_kconfig.py
```

`gen_kconfig.py` 通过 `paramlib.load_schema()` 读取 manifest 并合并
`parts/*.json`，输出：

- `npc/params/generated/Kconfig`：只有 `source`，路径相对 `npc/`；
- `npc/params/generated/kconfig/part-NN.Kconfig`：每个顶层菜单一个文件。

`mainmenu` 由稳定的 `npc/Kconfig` 持有，因为 Kconfig 只允许顶层文件声明
它；wrapper 不定义任何参数。

生成器使用 v2 的 `layout`、`parameters`、`choices`，保留 symbol 名称/类型/
prompt/help/range/default/select/imply/choice 组与菜单层级。`depends_on_local`
和 `condition_local` 是相对当前菜单/choice 的条件，避免在重建菜单时重复
堆叠父级依赖；完整依赖仍保存在 `depends_on`/`depends_expr` 中供审查。

parity 结果写入：

- `npc/params/reports/kconfig-generation-parity.md`（人读报告）；
- `npc/params/reports/kconfig-generation-parity.json`（机器可读明细）。

切换后结果：868 个符号、75 个 choice 组、92 个显式菜单与 schema 语义
一致；24 个 defconfig 的 effective hash 与记录的基线 24/24 一致，
`.config` 的 `CONFIG_*` 值也一致；生成器重复运行字节稳定，
`gen_schema.py` 仍可解析新的 `npc/Kconfig`。详细结论见报告。

普通 `#` 注释、空行、缩进、原始 `source`/`if` 写法以及文件边界不会按字节
保留（Kconfiglib 不保留这些信息）；语义结构、菜单、comment 节点、help 和
参数值会保留。无法表达的多定义/复杂表达式会记录在
`schema.json:generation_issues`，生成器不会静默丢弃。

---

## 3. canonical effective hash

### 3.1 输入

`npc_config_gen --typed-json` 产出的 typed JSON，例如
`build/generated/npc_config.typed.json`。工具也接受已生成的 canonical
effective JSON。

### 3.2 规范化对象

`param_tool.py hash` 生成如下文档：

```json
{
  "effective_hash": "<sha256>",
  "hash_domain": "npc-effective-config-v1",
  "canonical_schema_version": 1,
  "canonical": {
    "values": { "...": "过滤后的 typed 树" },
    "flat": { "...": "过滤并规范化后的 flat 键值" }
  }
}
```

`effective_hash` 只对 `canonical` 计算：

```text
sha256("npc-effective-config-v1\n" + compact_sorted_json(canonical))
```

`effective_hash`、`hash_domain`、`canonical_schema_version` 本身不参与
哈希。`compact_sorted_json` 使用 `sort_keys=True`、无空格分隔、
`ensure_ascii=False`。

### 3.3 被移除的非语义内容

**整个顶层 section 移除**：

```text
schema, config_hash, effective_hash, hash_domain,
build, boot, fpga, metadata, meta, generated_at, generated_by
```

**flat 键移除**（大小写敏感的前缀/精确名）：

```text
CC_ CXX_ KCONFIG_ SIM_ VERILATOR_ GSIM_ TRACE_ LOG_ PERF_ SYNTH_
PGO_ MARCH_ LD_ LIB_ STDLIB_ USER_CXX_ BUILD_ PROF_ X_INITIAL_
X_ASSIGN_ PDK
ASSERT COVERAGE SDB DIFFTEST WATCHPOINT ITRACE MTRACE FTRACE
IRINGBUF AUTOFLUSH SAVABLE OUTPUT_SPLIT
```

以及任何 `*_JOBS`、键名以
`PATH/DIR/DIRECTORY/FILE/TIMESTAMP/VERSION/DATE/TIME/SCHEMA/CONFIG_HASH/
EFFECTIVE_HASH/GENERATED_AT/GENERATED_BY` 结尾的键。例外：
`MMU_PTW_PATH`、`PTW_PATH`（typed 树中的 `mmu.ptw_path`）和
`TIMER_PATH` 是语义枚举（值如 `dedicated`、`clint_mtip`），保留。

**值移除**：绝对路径/相对路径/带 `/` 或 `\` 的路径样字符串、常见镜像/日志
扩展名（`.vcd/.fst/.dts/.dtb/.bin/.elf/.img/.cpio/.json/.log/.txt`）、
时间戳/日期字符串。

注意：`boot` 顶层结构化 section 被整体移除，但 flat 中仍有语义启动参数
（如 `SBI_ENABLE`、`LINUX_BOOT_ENABLE`、地址）；只移除其中的路径字段。
`fpga` 顶层 section 被整体移除，但 flat 中的 `FPGA_*` 平台参数仍可能保留，
因为它们是平台选择而不是构建元数据。

### 3.4 值规范化

- bool/tristate/choice 选项：`y/n/1/0`、`true/false` → JSON bool；
- int/hex：十进制或 `0x` 前缀 → JSON int；
- string：保留语义文本，不排序、不截断、不改写；
- 列表：保留顺序（顺序可能语义相关，如 compressor offsets）。

flat 键的类型来自 schema 的 `kconfig_type`；schema 中不存在的派生键回退到
“`y/n` → bool，数字 → int，其余字符串保留”。

### 3.5 语义边界

这个 hash 表示“有效硬件/平台参数”的规范化投影，不是整个构建环境的哈希。
编译器、优化选项、trace/log、PDK、FPGA 工具链、路径、时间戳不会改变它。
如果后续需要“构建环境 hash”，应使用独立 hash domain，而不是扩展本 hash
而不改版本。

### 3.6 使用

```bash
# 输出 hash 和 canonical JSON
python3 npc/tools/ParamGen/param_tool.py hash \
  --typed build/generated/npc_config.typed.json \
  --out /tmp/npc_config.canonical.json

# 只打印 hash
python3 npc/tools/ParamGen/param_tool.py hash \
  --typed build/generated/npc_config.typed.json
```

canonical JSON 适合直接 `diff`；键和值均已稳定排序/规范化。

---

## 4. profile 文件

### 4.1 顶层字段

```json
{
  "id": "rv64gc-linux",
  "version": 1,
  "status": "prototype",
  "description": "...",
  "base": { ... },
  "allow": { ... },
  "predicate": [ ... ],
  "qualification": { ... }
}
```

### 4.2 base

```json
"base": {
  "source": "npc/configs/rv64gc_linux_defconfig",
  "source_effective_hash": "...",
  "source_npc_config_hash": "...",
  "parameter_count": 282,
  "values": {
    "XLEN": 64,
    "RISCV_M": true,
    "...": "..."
  }
}
```

`base.values` **不是手写的**：`gen_profiles.py` 用真实 defconfig 生成 typed
JSON，再取 canonical flat 中“有 prompt 且非 derived”的主参数。因此：

- base 不包含派生参数（如 `SOC_XLEN_EFFECTIVE`、`MMU_XLEN`）；
- base 的值类型已规范化（bool/int/string）；
- `source_effective_hash` 是完整 effective 配置的 hash，可用于追溯；
- `source_npc_config_hash` 是旧 `npc_config_gen` 的 `config_hash`，仅作
  来源记录，不参与 effective hash。

### 4.3 allow

`allow` 声明允许偏离 base 的维度。当前支持四种：

```json
"allow": {
  "bool": ["RISCV_F", "RISCV_D"],
  "enum": {
    "FPU_IMPL": ["none", "hardfloat"],
    "LINUX_BOOTARGS": ["console=..."]
  },
  "int_range": {
    "NPC_RAM_SIZE": {"min": 67108864, "max": 134217728, "step": 67108864}
  },
  "list": {
    "MUL_COMPRESSOR_OUTPUT_OFFSETS": {
      "allowed_items": ["0", "1", "2"],
      "min_items": 1,
      "max_items": 8
    }
  }
}
```

语义：

- `bool`：键可以取任意 bool 值；
- `enum`：键的规范化值必须在该列表内；
- `int_range`：值是数字且在 `[min,max]` 内，可选 `step`（从 min 起）；
- `list`：逗号分隔字符串或 JSON 数组；可限制成员和长度；
- 未在 base 中出现、但被 allow 明确列出的键，可以新增；
- 如果某参数因依赖关闭而消失，但它在任一 allow 维度中被明确列出，视为
  允许的偏差（例如关闭 initramfs 后地址项消失）；
- 其他任何 base→effective 的差异都产生 violation，最终分类为
  `experimental`，而不是拒绝构建。

**比较范围**：只比较 schema 中 `prompt_exists=true` 且 `derived=false` 的
主参数。派生参数由主参数决定，仍参与 effective hash，但不单独作为
profile 维度；这避免“改 XLEN 导致一串 `*_XLEN_EFFECTIVE` 都要 allow”的
噪声。若派生值与主参数不一致，应由现有 contract/生成器检查，而不是 profile。

### 4.4 predicate

`predicate` 是子句列表；默认全部子句都必须为真。每个子句可以写成：

```json
{"id": "xlen-64", "description": "...", "expr": {"eq": {"path": "isa.xlen", "value": 64}}}
```

或直接写表达式。支持：

| 形式 | 含义 |
| --- | --- |
| `{"all": [expr, ...]}` | 全部为真；非空 |
| `{"any": [expr, ...]}` | 任一为真；非空 |
| `{"not": expr}` | 取反 |
| `{"eq": {"path": "isa.xlen", "value": 64}}` | 等于 |
| `{"ne": ...}` | 不等于 |
| `{"in": {"path": "isa.xlen", "values": [32, 64]}}` | 属于集合 |
| `{"lt"/"lte"/"gt"/"gte": {"path": "...", "value": N}}` | 数值比较 |
| `{"exists": {"path": "mmu.pte_format"}}` | 路径存在（值非 null） |
| `{"const": true}` | 常量，便于生成/调试 |

也支持 `eq`/`ne`/`in`/比较的 `[path, value]` 简写。

路径访问：

- `isa.xlen`、`mmu.pte_format`、`platform.ram_size` → typed 树；
- `flat.XLEN`、`flat.SBI_ENABLE` → 规范化 flat 键值。

缺失路径的语义：Kconfig 依赖关闭时参数会从 typed/flat 消失。对 `eq`/`ne`/
`in`，缺失值按 false/0 参与比较（所以 `eq missing false` 为真，
`eq missing true` 为假）；`lt/lte/gt/gte` 和 `exists` 对缺失值返回假。
未知操作符、结构错误、数值比较作用在非数值上，属于 profile 定义错误，
分类为 `invalid`。

### 4.5 qualification

```json
"qualification": {
  "suites": ["linux-boot", "opensbi", "sv39", "nanos-lite"],
  "manifest": "npc/support/qualified/rv64gc-linux@1.json",
  "hashes": []
}
```

`suites` 是 profile 声明需要覆盖的认证项；`manifest` 指向认证 hash 清单；
`hashes` 在 profile 内留空，实际 hash 放在 manifest。可选字段：

- `status_ceiling`：`qualified`/`profile-compatible`/`experimental`，用于
  把分类状态限制在证据允许的范围内；RV32 Linux 设为
  `profile-compatible`。
- `evidence_scope`：当前取 `l0-l2` 或 `elaboration-only`；RV32 Linux 使用
  后者，manifest 会记录“仅 L2 elaboration，无 Linux 启动证据”。

manifest 由 `npc/tools/ParamGen/qualify.py` 在 L0/L1/L2 通过后写入，格式：

```json
{
  "profile": "rv64gc-linux",
  "profile_version": 1,
  "status": "qualified",
  "evidence_scope": "l0-l2",
  "hashes": [
    {
      "effective_hash": "30c8ecd6...",
      "defconfig": "rv64gc_linux",
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

`qualified` 认证的是精确 `effective_hash`，不是 profile 名或 defconfig 名。
当前四个 profile 各记录 1 个 exact hash；RV32 Linux 的两个 manifest 的
`status` 是 `profile-compatible`、`evidence_scope` 是
`elaboration-only`。完整流程和 hash 提升规则见
`npc/docs/ProfileQualification.md`。

### 4.6 当前支持族

| Profile | 范围 | 覆盖的 defconfig |
| --- | --- | --- |
| `rv32i@1` | RV32I、仅 M、无 MMU、无 SBI/Linux | `rv32i` |
| `rv32imac-linux@1` | RV32IMAC + Sv32 + OpenSBI + Linux（实验） | `rv32imac_linux` |
| `rv32gc-linux@1` | RV32GC + Sv32 + OpenSBI + Linux（实验） | `rv32gc_linux` |
| `rv64gc-linux@1` | RV64GC + Sv39/Sv48 + OpenSBI + Linux | `rv64gc_linux`、`rv64_linux`（同 hash） |

`rv64_custom_deep` / `rv64_custom_large` / `rv64_custom_page` 使用 custom
MMU geometry/PTE 或非 4 KiB 页；`rv64_linux_uboot_128m` 走 U-Boot EFI
路径。它们都明确保持 experimental，不强行并入四个预设。

---

## 5. 分类状态

检查器输入：typed JSON / canonical effective JSON、profile JSON、可选
qualified manifest JSON。

| 状态 | 条件 |
| --- | --- |
| `qualified` | base/allow 通过 + predicate 通过 + effective hash 在 manifest 中 |
| `profile-compatible` | base/allow 通过 + predicate 通过，但 hash 不在 manifest 中 |
| `experimental` | 配置合法，但超出 profile 边界：base/allow 或 predicate 不通过 |
| `invalid` | profile/manifest 定义错误、缺字段、未知谓词、谓词求值错误、manifest profile 不匹配，或 manifest 声称合格但 profile 检查失败 |

重要：

- `experimental` **不是拒绝**。它表示“可构建、但不在该 profile 的支持边界
  内”；仍然必须通过现有 Kconfig contract、elaboration 检查和全部测试。
- `invalid` 针对 **profile/manifest 输入**，不是对配置合法性的判断。
- 检查器不做 contract 合法性判断；合法性仍由现有 Kconfig +
  `KconfigContract` 负责。
- `profile-compatible` 只表示“在支持策略边界内”，不表示已认证；认证需要
  manifest 中的证据。

### 5.1 命令行

```bash
python3 npc/tools/ParamGen/param_tool.py check \
  --config /tmp/current.typed.json \
  --profile npc/params/profiles/rv64gc-linux@1.json

# 输出 JSON
python3 npc/tools/ParamGen/param_tool.py check ... --json

# 显式指定 manifest / 忽略默认 manifest
python3 npc/tools/ParamGen/param_tool.py check ... \
  --manifest npc/support/qualified/rv64gc-linux@1.json
python3 npc/tools/ParamGen/param_tool.py check ... --no-manifest

# 认证：L0/L1 + L2，通过后写 manifest
python3 npc/tools/ParamGen/qualify.py --all --jobs 4
```

### 5.2 批量报告

```bash
python3 npc/tools/ParamGen/classify_defconfigs.py
```

该命令会：

1. 在 `/tmp/opencode/paramgen-work/defconfigs/<name>` 为每个 defconfig 建
   独立 workdir（符号链接 `Kconfig`/`kconfig`，不碰仓库的 `.config`、
   `include/generated`、`include/config`）；
2. 运行现有 `npc/tools/build/c_conf` 和 `npc/tools/build/npc_config_gen`；
3. 每个 defconfig 再跑一遍到 `<name>__repeat`，比较 effective hash 是否稳定；
4. 对每个 profile 分类，选一个“最佳 profile”（状态优先，其次谓词失败数、
   violation 数）；
5. 写 `npc/params/reports/defconfig-classification.csv` 和
   `defconfig-classification-summary.json`。

CSV 列：`defconfig,effective_hash,hash_stable,npc_config_hash,profile,status,
base_allow_violations,predicate_failures,profiles,reasons`。

`--skip-generate` 复用已有 workdir；`--no-repeat` 跳过第二遍生成（此时
`hash_stable` 仍写 true，但并未真正证明，报告会缺少重复运行证据）。

认证 run 由 `qualify.py` 完成，并额外生成
`npc/params/reports/qualification-run.json`、`qualification-run.csv` 和
`defconfig-support-policy.json`。manifest 更新后重新运行
`classify_defconfigs.py --skip-generate --no-repeat` 即可把 manifest 中
已认证的 hash 显示为 `qualified`（RV32 Linux 会被 `status_ceiling` 限制为
`profile-compatible`）。完整流程见 `npc/docs/ProfileQualification.md`。

---

## 6. 如何新增参数/profile

### 6.1 新增一个 Kconfig 参数

1. 直接编辑 `npc/params/schema/parts/<menu>.json`：增加 `parameters` 条目，
   并在 `layout` 的对应 menu 中登记 symbol/choice；命名和依赖风格保持现有
   约定。
2. 运行 `python3 npc/tools/ParamGen/split_schema.py --check`，再运行
   `python3 npc/tools/ParamGen/gen_kconfig.py` 生成 Kconfig。
3. 运行 `python3 npc/tools/ParamGen/parity_kconfig.py` 验证语义和 24 个
   defconfig hash；检查生成 diff 中的 `type/kconfig_type/default/range/
   depends_on`。
4. 如果参数有离散约束，仍应加在 `npc/tools/KconfigContract` 中；schema
   不替代 contract。
5. 如果它影响已有 profile 的 base 语义，提升对应 profile 的 `version`，
   重新生成 profile base 和 manifest；旧 manifest 不应继续宣称旧版本合格。

### 6.2 新增一个 profile

1. 选一个真实 defconfig 作为 base，不要手写 base 值。
2. 在 `gen_profiles.py` 的 `PROFILE_SPECS` 增加 spec（id/version/description/
   defconfig/typed/allow/predicate/qualification）。
3. 生成该 defconfig 的 typed JSON（`classify_defconfigs.py --typed-only` 可做）。
4. 运行 `python3 npc/tools/ParamGen/gen_profiles.py --typed-dir ...`，得到
   `npc/params/profiles/<id>@<version>.json`。
5. 用 `param_tool.py check` 和 `classify_defconfigs.py` 验证分类；
   确认 experimental 的原因是你预期的边界，而不是 profile 笔误。
6. 运行 `qualify.py --profile <id> --defconfig ...` 做 L0/L1/L2；只有通过后
   才写入 manifest hash。不要手工建空 manifest 占位，也不要手工复制 hash。
   如果只有 L2 证据、没有端到端启动证据，用 `status_ceiling`/
   `evidence_scope` 限制状态。

新增 family 的原则：它必须覆盖一组 defconfig，而不是单个配置；allow 只列
有意支持的差异；predicate 写清硬边界。当前 4 个 family 的设计和 24 个
defconfig 的状态见 `npc/docs/ProfileQualification.md`。

### 6.3 新增 allow 维度

在 profile JSON 的 `allow` 中加键即可；不需要改检查器。若需要新的约束类型
（例如字符串正则、依赖其他参数的范围），先扩展 `paramlib.validate_profile`
和 `check_boundary`，再提升 profile 版本。

---

## 7. 当前未验证点/限制

- **schema 不是人工审查结果**：`tier`、`derived` 都是启发式；`visibility`
  基于空默认配置，不代表任意 `.config` 下的菜单可见性。
- **profile 是支持策略**：24 个 defconfig 中 5 个 profile-compatible（其中
  2 个为 `qualified`，2 个 RV32 Linux 受 `status_ceiling` 限制，1 个
  `rv64_linux` 与 `rv64gc_linux` 同 hash）、19 个 experimental；这是策略
  边界 + L0/L1/L2 认证的结果，不是配置合法性结论。
- **qualified 只覆盖 L0/L1/L2**：4 个 manifest 各记录 1 个 exact effective
  hash；RV32 Linux 的两个 manifest 是 `profile-compatible` +
  `elaboration-only`，明确没有 Linux 启动证据。
- **experimental 是明确的**：3 个 custom MMU 配置（custom geometry/PTE/
  非 4 KiB 页）、`rv64_linux_uboot_128m` 以及其余不在四预设边界内的配置
  保持 experimental，没有为了分类数字放宽标准 family。
- **invalid 未在真实 defconfig 上出现**：用自测中的坏 profile/manifest 验证。
- **已接入 config 流程**：Kconfig 生成挂在现有 `menuconfig`/
  `*_defconfig`/`npc-config` 上，不新增手动 make 目标；没有新增 CI。
- **L2 只到 elaboration**：没有跑 L3 完整 Verilator 构建，也没有跑 L4
  VGA/Linux/SoC 长仿真；`qualified` 不证明仿真行为。
- **L2 串行**：mill 共享 `npc/out`，`qualify.py --jobs` 只并行 L0/L1。
- **hash 过滤规则是版本化的**：改过滤规则/规范化规则必须提升
  `HASH_DOMAIN` 或 `canonical_schema_version`，否则历史 hash 不可比。
- **profile base 会随 defconfig/Kconfig 默认值漂移**：重新生成后应 review
  diff 并提升 profile 版本。
- **表达式序列化覆盖标准 Kconfig 运算符**：当前测试 Kconfig 全部解析为
  结构化节点（无 raw）；若未来出现 Kconfiglib 无法表达的结构，会保留
  `{"op":"raw","text":...}`。
- **生成的 Kconfig 不保留原始文本格式**：普通 `#` 注释、空行、缩进、
  `source`/`if` 的原始写法、原始文件边界都不会按字节保留；语义结构、
  菜单/comment、help、prompt、默认值和依赖会重建。生成文件按顶层菜单
  拆分，schema 仍保留每个节点的 `file`/`line`/`file_order`。
- **切换已完成**：`npc/Kconfig` 只保留 `mainmenu` + source，旧
  `npc/kconfig/Kconfig.*` 已删除；`npc/params/generated/` 是构建实际使用
  的生成树。`KconfigContract`、`NPCConfigGEN` 的输入语义未改变。
  它们消费的是 `.config`/JSON，不是 Kconfig 源文件，所以不需要为生成树
  改写；直接 grep 旧 Kconfig 的测试和注释已经单独更新。`file`/`line`
  现在只是迁移 provenance，构建工具不会打开这些路径。
- **未做 contract 合法性判断**：experimental 仍必须通过现有 contract；
  分类器不替代 `kconfig_contract`。

---

## 8. 自测

```bash
python3 npc/tools/ParamGen/selftest.py
```

覆盖：

- 同一 typed JSON 重复计算 hash 一致；
- 只改 `config_hash`/`schema`/`build`/`boot`/`fpga`/路径/时间/元数据，hash 不变；
- 改 `isa.xlen`、`platform.ram_size`、`mmu.pte_format`，hash 改变；
- `y` 与 `1` 的 bool 规范化一致；
- `all/any/not/eq/ne/in/lt/lte/gt/gte/exists` 全部谓词；
- 缺失路径的 false/0 语义；
- `allow` 的 bool/enum/int_range/list 四种维度及非法值；
- profile-compatible、qualified、experimental、invalid 四种状态；
- manifest hash 与 profile 冲突时 invalid。

当前自测：38 passed, 0 failed（新增 schema provenance、稳定 Kconfig
入口、生成树与 schema 一致性检查）。

---

## 9. 与现有构建的关系

- 现有 `make ..._defconfig`、`make menuconfig`、`make npc-config` 用法不变；
  这些目标会在配置前自动从 schema 生成 Kconfig。
- `npc_config_gen` 的 typed JSON 仍是现有构建产物；ParamGen 只读取它。
- Kconfig contract 仍是合法性检查；ParamGen 只做支持策略分类。
- schema 不是白名单；profile 不阻止构建；experimental 仍要过全部检查。
- 切换后的结构与新增参数流程见 `npc/docs/KconfigGeneration.md`。
