# Schema 生成 Kconfig（切换后）

本文档描述当前已经生效的 Kconfig 生成结构。参数事实源是
`npc/params/schema/schema.json`；`npc/Kconfig` 只是稳定入口，真正的
symbol/menu 树由 `npc/tools/ParamGen/gen_kconfig.py` 生成。

## 1. 目录结构

```text
npc/Kconfig                          # 稳定 wrapper：mainmenu + source
npc/params/schema/schema.json        # 权威参数 schema
npc/tools/ParamGen/gen_kconfig.py    # schema -> Kconfig（只读 schema）
npc/params/generated/Kconfig         # 生成入口：只有 source
npc/params/generated/kconfig/
  part-00.Kconfig ... part-NN.Kconfig
```

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

- `npc/params/schema/schema.json` + `gen_kconfig.py` + `paramlib.py` 变化时，
  重新生成 `npc/params/generated/`；
- 生成器只写内容发生变化的文件，schema 未变时不会刷新 mtime；
- 生成失败直接退出 2，不会回退到旧 Kconfig 或继续使用旧 `.config`；
- 生成入口或某个 `part-*.Kconfig` 缺失时，配置目标会先修复再解析；
- `npc_config.json` 以生成 stamp 为依赖，schema 变化会触发重新
  `--syncconfig`，不会复用旧投影；`.generated.stamp` 是本地状态，
  `.gitignore` 已排除，不随仓库提交。

干净 checkout 不需要 `npc/params/generated/` 预先存在：第一次
`make npc-config` / `make <defconfig>` 会从已跟踪的 schema 生成。生成的
Kconfig 也作为 reviewed artifact 跟踪，便于审查生成结果；构建不依赖它
必须存在。

## 3. 权威源与迁移校验

- **schema 是权威源**：修改参数先改 `schema.json`，再生成 Kconfig。
- `gen_kconfig.py` 只读 schema，不读取旧 `npc/kconfig/Kconfig.*`。
- `gen_schema.py` 保留为迁移校验工具：它仍能解析新的 `npc/Kconfig`，
  但不再用于生成权威 schema。为避免用生成树反向覆盖权威文件，直接写
  `npc/params/schema/schema.json` 会被拒绝；请把 `--out` 指向临时目录。
- 从生成树反向解析时，`file`/`line`/`tier` 等字段会变成
  `params/generated/...` 的元数据，`and` 表达式的括号结合顺序也可能
  不同；这些不再作为权威信息。`parity_kconfig.py` 会忽略这些迁移元数据，
  比较 symbol/choice 的语义字段。

## 4. 新增参数流程

1. 编辑 `npc/params/schema/schema.json`：
   - 在 `parameters` 增加 symbol 定义（类型、prompt、help、default、
     range、depends/default 条件等）；
   - 在 `layout` 的对应 menu 中加入 `{"kind": "symbol", "name": ...}`；
   - 如果需要 choice，同时在 `choices` 和 `layout` 中登记。
2. 生成 Kconfig：

   ```bash
   python3 npc/tools/ParamGen/gen_kconfig.py
   ```

3. 运行 parity：

   ```bash
   python3 npc/tools/ParamGen/parity_kconfig.py
   ```

   要求 schema 迁移校验、符号/choice/菜单、20 个 defconfig effective
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
- 20 个 defconfig 的 effective hash 与
  `npc/params/reports/defconfig-classification.csv` 记录的基线 20/20 一致；
- `make kconfig-contract-check` 的 20 个 defconfig、self-test 和当前
  `.config` 均通过。
