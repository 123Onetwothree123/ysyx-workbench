# ParamGen

参数 schema、Kconfig 生成、canonical effective hash、declarative
profile/predicate 检查。`npc/params/schema/schema.json` 是参数权威源，
`npc/Kconfig` 只 source 生成树；现有 config 流程会自动运行生成器。

完整设计见 `npc/docs/KconfigGeneration.md` 和
`npc/docs/ParamSchemaAndProfiles.md`。

## 快速使用

```bash
# 1. 修改 schema 后生成 Kconfig（构建流程也会自动做）
python3 npc/tools/ParamGen/gen_kconfig.py

# 2. 完整 parity：schema 迁移校验、符号/choice/菜单、
#    20 个 defconfig effective hash、生成器确定性
python3 npc/tools/ParamGen/parity_kconfig.py

# 3. 迁移校验：从切换后的 npc/Kconfig 反向解析到临时目录
python3 npc/tools/ParamGen/gen_schema.py \
  --out /tmp/opencode/schema.json \
  --summary /tmp/opencode/schema-summary.json

# 4. 为所有 defconfig 在 /tmp 隔离目录生成 typed JSON 并分类
python3 npc/tools/ParamGen/classify_defconfigs.py

# 5. 单个配置的 effective hash（同时输出 canonical JSON）
python3 npc/tools/ParamGen/param_tool.py hash \
  --typed build/generated/npc_config.typed.json \
  --out /tmp/npc_config.canonical.json

# 6. 分类
python3 npc/tools/ParamGen/param_tool.py check \
  --config build/generated/npc_config.typed.json \
  --profile npc/params/profiles/standard-rv64@2.json

# 7. 认证：L0/L1 并行、L2 串行；通过后写 npc/support/qualified/*.json
python3 npc/tools/ParamGen/qualify.py --all --jobs 4

# 8. 自测
python3 npc/tools/ParamGen/selftest.py
```

完整支持族和认证流程见 `npc/docs/ProfileQualification.md`。

工具只依赖 Python 3 标准库，以及 `vendor/kconfiglib.py`（仅 schema 生成使用）。

## kconfiglib

`vendor/kconfiglib.py` 复制自仓库内已有文件
`vendor/opensbi/scripts/Kconfiglib/kconfiglib.py`（Kconfiglib 14.1.0，
ISC 许可证）。许可证全文在 `vendor/LICENSE.kconfiglib`。

不依赖系统安装的 `kconfiglib`/pip 包；schema 生成离线可复现。

## 目录

- `param_tool.py`：统一 CLI（`schema` / `hash` / `check` / `selftest`）
- `gen_schema.py`：`npc/Kconfig` → schema 迁移快照（只用于校验；默认拒绝
  覆盖权威 `schema.json`）
- `gen_kconfig.py`：`schema.json` → `npc/params/generated/Kconfig` +
  `kconfig/part-NN.Kconfig`（`source` 路径相对 `npc/`）
- `parity_kconfig.py`：schema 迁移语义对比、符号/choice/菜单对比、20 个
  defconfig 的 `c_conf --syncconfig` + effective hash 对比、确定性检查，写
  `reports/kconfig-generation-parity.{md,json}`
- `gen_profiles.py`：真实 defconfig typed JSON → profile base/allow
- `classify_defconfigs.py`：20 个 defconfig 的 hash 稳定性 + profile 分类报告
- `qualify.py`：L0/L1/L2 认证，按 exact effective hash 更新
  `npc/support/qualified/*.json`
- `selftest.py`：hash/predicate/status 自测
- `paramlib.py`：共享实现

## 输入/输出约定

- typed JSON：现有 `npc_config_gen --typed-json` 输出；
- canonical effective JSON：`param_tool.py hash --out` 输出；
- profile：`npc/params/profiles/<id>@<version>.json`；
- qualified manifest：`npc/support/qualified/<id>@<version>.json`。

分类状态只表达支持策略（qualified/profile-compatible/experimental/invalid），
不表达配置合法性。合法性仍由现有 Kconfig contract 负责。
