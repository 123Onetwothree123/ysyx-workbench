# Schema 生成 Kconfig（切换后）

本文档描述当前已经生效的 Kconfig 生成结构。参数事实源是
`npc/params/schema/schema.json`（manifest）加
`npc/params/schema/parts/*.json`（每个顶层菜单一个 part）；`npc/Kconfig`
只是稳定入口，真正的 symbol/menu 树由
`npc/tools/ParamGen/gen_kconfig.py` 生成。

## 1. 目录结构

```text
npc/Kconfig                          # 稳定 wrapper：mainmenu + source
npc/params/schema/schema.json        # 权威 schema manifest
npc/params/schema/parts/
  cpu-platform.json
  mode.json
  fpga.json
  soc-peripheral.json
  runtime-debug.json
  firmware-boot.json
  build-sim.json
npc/params/schema/schema-summary.json
npc/tools/ParamGen/paramlib.py       # manifest + parts 合并 loader
npc/tools/ParamGen/split_schema.py   # 单文件 <-> manifest/parts 拆分工具
npc/tools/ParamGen/gen_kconfig.py    # 合并后的 schema -> Kconfig
npc/params/generated/Kconfig         # 生成入口：只有 source
npc/params/generated/kconfig/
  part-00.Kconfig ... part-NN.Kconfig
```

`schema.json` 只保存 `schema_version`、`generated_snapshot`、`mainmenu`、
`parts` 顺序、`generator`、`source` 和 `generation_issues`。每个 part 保存
自己的 `menu_prompt`、顶层 `layout` 菜单项，以及该菜单下的 `parameters`、
`choices` 和 `menus`。part 文件名是纯语义名；顶层顺序只由 manifest 的
`parts` 数组决定，不按文件名排序或 glob 推断。`load_schema()` 按 manifest
的 `parts` 顺序合并 layout，把 parameters 按 `name`、choices 按 `id`、
menus 按原 `order` 排序，恢复出与拆分前完全相同的内存 schema。

`npc/Kconfig` 的内容是：

```kconfig
mainmenu "NPC配置菜单"

source "params/generated/Kconfig"
```

`mainmenu` 留在 wrapper 是因为 Kconfig 语法只允许顶层文件声明它；把
`mainmenu` 放进被 source 的文件会让 `c_conf` 报 `invalid statement`。
除标题外，wrapper 不定义任何 symbol/choice/menu。

生成入口中的 source 路径相对 `npc/`（srctree）：

```kconfig
source "params/generated/kconfig/part-00.Kconfig"
```

`c_conf` 解析 `source` 时按工作目录（`npc/`）解析，Kconfiglib 也以
`srctree=npc` 解析，因此同一份生成文件同时适用于配置工具和迁移校验。

## 2. 自动生成

不需要新的手动 make 目标。现有配置流程会在调用 `c_conf`/`c_mconf` 之前
自动运行生成器：

- `make menuconfig`
- `make <name>_defconfig`
- `make profile-defconfig PROFILE=...`
- `make savedefconfig`
- `make npc-config`

实现位于 `npc/scripts/config.mk`：

- `npc/params/schema/schema.json`、`npc/params/schema/parts/*.json` +
  `gen_kconfig.py` + `paramlib.py` 变化时，重新生成
  `npc/params/generated/`；part 新增/删除也会通过目录依赖触发；
- 生成器只写内容发生变化的文件，schema 未变时不会刷新 mtime；
- 生成失败直接退出 2，不会回退到旧 Kconfig 或继续使用旧 `.config`；
- 生成入口或某个 `part-*.Kconfig` 缺失时，配置目标会先修复再解析；
- 每次配置流程还会运行 `gen_kconfig.py --check`：生成树与 schema 字节
  不一致、provenance 元数据指向失效路径、或 `npc/Kconfig` 不再 source
  生成入口时，自动重建/明确失败；
- `npc_config.json` 以生成 stamp 为依赖，schema 变化会触发重新
  `--syncconfig`，不会复用旧投影；`.generated.stamp` 是本地状态，
  `.gitignore` 已排除，不随仓库提交。

干净 checkout 不需要 `npc/params/generated/` 预先存在：第一次
`make npc-config` / `make <defconfig>` 会从已跟踪的 schema 生成。生成的
Kconfig 也作为 reviewed artifact 跟踪，便于审查生成结果；构建不依赖它
必须存在。

## 3. 权威源与迁移校验

- **schema 是权威源**：参数事实源是 manifest + `parts/*.json`；修改参数先改
  对应 part，再生成 Kconfig。
- `gen_kconfig.py` 通过 `paramlib.load_schema()` 读取并合并 manifest/parts，
  不读取旧 `npc/kconfig/Kconfig.*`。
- `gen_schema.py` 保留为迁移校验工具：它仍能解析新的 `npc/Kconfig`，
  但不再用于生成权威 schema。它只输出单文件迁移快照，且**任何**指向
  `npc/params/schema/schema.json` 的 `--out` 都会被拒绝（包括
  `--allow-schema-overwrite`）；请把 `--out` 指向临时目录。
- 拆分/重新拆分权威 schema 使用：

  ```bash
  python3 npc/tools/ParamGen/split_schema.py
  python3 npc/tools/ParamGen/split_schema.py --check
  ```

  默认拆分前会把原单文件备份到 `/tmp/opencode/schema-before-split.json`；
  `--check` 验证 manifest、全部 parts 和 `schema-summary.json` 与合并结果
  一致，不写文件。重复运行字节一致。
- `schema_bundle_sha256()` / `param_tool.py schema-hash` 按 manifest 的
  `parts` 顺序对 manifest 元数据和 parts 内容计算稳定哈希；part 改名不会
  改变哈希，重排 `parts` 数组会改变哈希。构建投影用它，不能只 hash
  manifest。
- 从生成树反向解析时，`file`/`line`/`tier` 等字段会变成
  `params/generated/...` 的元数据，`and` 表达式的括号结合顺序也可能
  不同；这些不再作为权威信息。`parity_kconfig.py` 会先合并 manifest/parts，
  再忽略这些迁移元数据，比较 symbol/choice 的语义字段。

## 3.1 旧代码兼容边界

切换删除的是“手写 Kconfig 源树”，不是 `.config`/`auto.conf`/JSON 这套
配置输出。因此旧代码要分两类看：

| 旧代码 | 实际输入 | 是否需要为 schema 切换改写 |
| --- | --- | --- |
| `c_conf` / `c_mconf` | `npc/Kconfig` 入口 + 生成树文本 | 不需要；它是通用 Kconfig 解析器，生成树仍使用同一语法子集 |
| `npc_config_gen` / `NPCConfigGEN` | `include/generated/npc_config.json` / typed JSON | 不需要；不打开 Kconfig 源文件 |
| `kconfig_contract` / `KconfigContract` | `.config` + typed JSON | 不需要；symbol 名、类型、默认值、choice 均未变 |
| `BuildTools`、平台脚本 | `auto.conf`、JSON | 不需要 |
| RTL / Scala / C++ | typed JSON、配置模块 | 不需要；消费的是投影值 |
| `csrc/Test/**` | `auto.conf`、`CONFIG_*`、typed JSON | 原则上不需要；只有直接 grep 旧 Kconfig 文件的检查需要改 |

已经改掉的源文件路径耦合：

- `runtime-observability-test` 从 grep `npc/kconfig/Kconfig.soc` 改为
  grep `npc/params/generated/kconfig/*.Kconfig`；
- `ysyxSoC/src/Top.scala`、`npc/fpga/README.md` 等注释改为 schema /
  生成路径。

`schema.json`/parts 仍保留 `file`/`line` 作为迁移来源记录，但它们是**历史
provenance，不是构建输入**：

- `source.kconfig_files` 列出生成该快照时解析过的文件；
- `source.legacy_kconfig_files` 列出切换后已删除、只能从 git 历史恢复的
  文件；
- `source.generated_kconfig_files` 列出当前构建实际消费的生成树；
- `gen_kconfig.py --check` 会拒绝“旧文件被重新创建”或“参数 provenance
  指向既不存在、也未登记为 legacy 的路径”。

`gen_schema.py` 也属于旧方向代码，但只保留为迁移校验工具：它可以从当前
生成树反向解析到临时目录，不能覆盖权威 manifest。正常新增参数只改
`npc/params/schema/parts/*.json`，不再从 Kconfig 反向生成。

## 4. 新增参数流程

1. 编辑 `npc/params/schema/parts/<menu>.json`：
   - 在 `parameters` 增加 symbol 定义（类型、prompt、help、default、
     range、depends/default 条件等）；
   - 在 `layout` 的对应 menu 中加入 `{"kind": "symbol", "name": ...}`；
   - 如果需要 choice，同时在 `choices` 和 `layout` 中登记。
   - 若参数需要换顶层菜单，必须同步修改 `menu_path`、目标 part 和 layout；
     无法归属任何顶层菜单时 `split_schema.py --check` 会报错。
2. 校验拆分并生成 Kconfig：

   ```bash
   python3 npc/tools/ParamGen/split_schema.py --check
   python3 npc/tools/ParamGen/gen_kconfig.py
   ```

3. 运行 parity：

   ```bash
   python3 npc/tools/ParamGen/parity_kconfig.py
   ```

   要求 schema 迁移校验、符号/choice/菜单、24 个 defconfig effective
   hash 全部通过。
4. 如参数有离散约束，继续在 `npc/tools/KconfigContract` 中补检查；
   profile/manifest 需要时按 `npc/docs/ParamSchemaAndProfiles.md` 更新。

不要直接编辑 `npc/params/generated/` 下的文件；下一次配置流程会覆盖。

## 5. 旧 Kconfig 迁移状态

原 `npc/kconfig/Kconfig.*`（hardware/fpga/soc/debug/log/sbi/linux/rootfs/
cpp/sim/verilator/synth/mmu）已删除。删除前确认：

- 构建系统只引用 `npc/Kconfig`；
- `runtime-observability-test` 改为检查
  `npc/params/generated/kconfig/*.Kconfig`；
- 文档/注释中的旧路径已改为 schema 或生成路径。

历史内容仍可从 git 历史恢复；当前仓库不再保留第二份 Kconfig 源文件。
`npc/kconfig/` 目录不再参与构建。

## 6. Parity 记录

`npc/params/reports/kconfig-generation-parity.{md,json}` 记录切换后的结果：

- 868 个 symbol、75 个 choice、92 个 menu 与 schema 语义一致；
- 8 个生成文件（入口 + 7 个 part）与 `gen_kconfig.py` 最新输出字节一致；
- 24 个 defconfig 的 effective hash 与
  `npc/params/reports/defconfig-classification.csv` 记录的基线 24/24 一致；
- `make kconfig-contract-check` 的 24 个 defconfig、self-test 和当前
  `.config` 均通过。
