# JSON schema → Kconfig 切换与 parity 报告

本报告由 `npc/tools/ParamGen/parity_kconfig.py` 在切换后生成。`npc/params/schema/schema.json` 是参数权威源；`npc/Kconfig` 是稳定入口，只声明 `mainmenu` 并 source `params/generated/Kconfig`。构建系统在配置流程中自动从 schema 重新生成 Kconfig 树。

## 1. 结论

**切换完成且 parity 通过。** 切换后的 `npc/Kconfig` 可由 `gen_schema.py` 解析，符号/choice/菜单语义与 schema 一致；868 个符号、75 个 choice 组、92 个菜单以及 20 个 defconfig 的 effective hash 全部与记录的基线一致。

## 2. 输入与检查方法

| 项目 | 值 |
| --- | --- |
| schema | `npc/params/schema/schema.json` |
| 生成器 | `npc/tools/ParamGen/gen_kconfig.py` |
| 生成 Kconfig | `npc/params/generated/Kconfig` |
| 稳定入口 | `npc/Kconfig`（mainmenu + source 生成入口） |
| schema 原始记录 | `npc/Kconfig` |
| 符号数 | 868 |
| choice 组数 | 75 |
| 显式 menu 数 | 92 |
| 生成 issue | 0 |

检查层次：

1. 用 `gen_schema.py` 解析切换后的 `npc/Kconfig`，比较符号/choice 语义字段；`file`/`line`/`order`/`tier` 等迁移元数据以及 `and` 结合顺序不再作为权威输入；
2. 连续两次运行 `gen_kconfig.py`，比较全部生成文件字节；
3. 用 Kconfiglib 分别解析入口 Kconfig 与生成 Kconfig（用 schema `mainmenu` 对齐路径），比较符号表、choice 组、菜单/comment 树和空配置默认值；
4. 在独立 `/tmp` workdir 中，对 20 个 defconfig 分别用当前与生成的 Kconfig 运行真实 `c_conf --defconfig` / `c_conf --syncconfig`，再由 `npc_config_gen --typed-json` 生成 typed JSON，计算 ParamGen effective hash 并比较。

## 3. Schema 字段扩展

`schema_version` 从 1 升到 2，新增：

- `help`：每个 symbol/choice 的 help 文本；
- `prompt_condition` / `prompt_condition_local`：prompt 条件及其相对当前 menu/choice 的局部形式；
- `depends_on_local` / `depends_local_expr`：相对当前 menu/choice 的局部依赖；`depends_on` / `depends_expr` 仍保留完整依赖；
- `defaults[].condition_local`、`range[].condition_local`、`selects[]/implies[]` 及各自的局部条件；
- `selects` / `implies`：目标符号与条件（当前仓库为 0 条，但格式已支持）；
- `menu_path`、`order`、`file_order`：参数所在菜单路径、全局顺序、同文件顺序；
- `layout`：完整 menu/comment/symbol/choice 树，作为生成器的唯一布局输入；
- `menus`：扁平菜单列表（path/depth/depends/visible-if/file/line）；
- `choices[].options_in_order`、`prompt_condition*`、`visible_if`、`help`、`order`、`menu_path`；
- `generation_issues`：无法忠实还原的字段/表达式清单（当前为空）。

`generated_snapshot: true` 保持不变。切换后 schema 是权威源，`gen_schema.py` 只用于迁移校验；从生成树反向解析时，`file`/`tier` 等字段会反映生成文件而不是旧的 `Kconfig.*` 文件。

## 4. 生成文件

| 文件 | 行数 | SHA256 |
| --- | ---: | --- |
| `npc/params/generated/Kconfig` | 15 | `ae77104f3ecfd31692c7a4a78d2330f3…` |
| `npc/params/generated/kconfig/part-00.Kconfig` | 1830 | `943350b54e73650123ed99d8170ac1a2…` |
| `npc/params/generated/kconfig/part-01.Kconfig` | 34 | `b17617de132a75d03d20fc5ea7a1a2c9…` |
| `npc/params/generated/kconfig/part-02.Kconfig` | 766 | `f61a8253a5040f0771d10781d8aa6bb4…` |
| `npc/params/generated/kconfig/part-03.Kconfig` | 393 | `672f9b1a77bc8981f58271f774bf2f2f…` |
| `npc/params/generated/kconfig/part-04.Kconfig` | 709 | `bd84cd8e391fd12819e1d34deaf1d4ba…` |
| `npc/params/generated/kconfig/part-05.Kconfig` | 390 | `d88dad6813842ac29131d2ff0238423b…` |
| `npc/params/generated/kconfig/part-06.Kconfig` | 641 | `e063bdc7df40db5c5efc560d28aedd01…` |

`npc/Kconfig` 只包含 `mainmenu` 和 `source "params/generated/Kconfig"`；生成入口只包含 `source`；每个顶层菜单生成一个 `params/generated/kconfig/part-NN.Kconfig`。生成器只读取 `schema.json`，不读取旧的 `npc/kconfig/Kconfig.*`。

## 5. 符号 / choice / 菜单 parity

切换后入口 Kconfig 与生成树是同一份参数定义；本节主要验证生成器重复运行和 Kconfiglib 解析稳定，20 个 defconfig hash 与 schema 迁移检查才是切换后的权威证据。

| 检查 | 结果 |
| --- | --- |
| 符号集合 | 0 missing / 0 extra / 0 field differences |
| choice 集合 | 0 missing / 0 extra / 0 field differences |
| 菜单树 | 0 differences |
| symbol default 差异 | 0 |
| symbol range 差异 | 0 |
| help 文本差异 | 0 |
| 空配置默认值差异 | 0 |

## 6. 20 个 defconfig effective hash 对比

effective hash 使用与 `npc/params/reports/defconfig-classification.csv` 相同的 ParamGen 规范化（`npc-effective-config-v1`）。schema v2 只新增反向生成元数据，没有改变 `kconfig_type`/flat 类型/过滤规则，`HASH_DOMAIN` 仍为 `npc-effective-config-v1`。`recorded` 列是重构前记录的 hash，用来确认当前基线没有漂移。

| defconfig | current effective hash | generated effective hash | match | recorded |
| --- | --- | --- | :---: | :---: |
| `mmu_default` | `4f0c85e059272e1abb5bd5e47bb6267021db354044a94e47879b386510310acf` | `4f0c85e059272e1abb5bd5e47bb6267021db354044a94e47879b386510310acf` | ✅ | ✅ |
| `nanos` | `a36377cf8ca76837e7c9f9693475ee66fe5b60df48958bb0cde2044bad06f039` | `a36377cf8ca76837e7c9f9693475ee66fe5b60df48958bb0cde2044bad06f039` | ✅ | ✅ |
| `rv32_c` | `d978e8baa610074c1e4d8f5bf9ff72e2622ea314b8f3cba6718606ade1918256` | `d978e8baa610074c1e4d8f5bf9ff72e2622ea314b8f3cba6718606ade1918256` | ✅ | ✅ |
| `rv32_f` | `cedee85baae8dbac3316542a7e2bbceb873ce6c51b965c64e897c2efb366fb72` | `cedee85baae8dbac3316542a7e2bbceb873ce6c51b965c64e897c2efb366fb72` | ✅ | ✅ |
| `rv32_fd` | `97c02c22ad4ac450250e1139affabc5f20b3a895c73e50d15b733475fc6561b0` | `97c02c22ad4ac450250e1139affabc5f20b3a895c73e50d15b733475fc6561b0` | ✅ | ✅ |
| `rv32_noc` | `88079e6aca37deb8e1644745b52211d0118bcaa470740ab322bcf250bd2bda57` | `88079e6aca37deb8e1644745b52211d0118bcaa470740ab322bcf250bd2bda57` | ✅ | ✅ |
| `rv32gc_linux` | `67f5c141d32f2abee2eadfbe1e9ecd08010dee30db4c5ffada03d635415fe55d` | `67f5c141d32f2abee2eadfbe1e9ecd08010dee30db4c5ffada03d635415fe55d` | ✅ | ✅ |
| `rv32i` | `0713ee86c0be17880a2b972e4221732e9d7da97519519ed543d5526e42f8bde6` | `0713ee86c0be17880a2b972e4221732e9d7da97519519ed543d5526e42f8bde6` | ✅ | ✅ |
| `rv32imac_linux` | `b0162d92a5806524a175c0b08edd53ef7bab912280c3cc7a6b15e2a190157c12` | `b0162d92a5806524a175c0b08edd53ef7bab912280c3cc7a6b15e2a190157c12` | ✅ | ✅ |
| `rv64_c` | `5de4545daf05339fe84807bf898ef22bb1c1361a0eac13a2829efc0c7fb5804e` | `5de4545daf05339fe84807bf898ef22bb1c1361a0eac13a2829efc0c7fb5804e` | ✅ | ✅ |
| `rv64_custom_deep` | `7b1a6230ae5c2cff4874121dd97b7d2d4b010fae7568910c142ab18bf9158e11` | `7b1a6230ae5c2cff4874121dd97b7d2d4b010fae7568910c142ab18bf9158e11` | ✅ | ✅ |
| `rv64_custom_large` | `6940d3c71985cde8f2c848f43b123b196e4b72e3c7820169d3b8e05bb5ff8c46` | `6940d3c71985cde8f2c848f43b123b196e4b72e3c7820169d3b8e05bb5ff8c46` | ✅ | ✅ |
| `rv64_custom_page` | `a390ceed39705e088f990f4817955f023d1dce54c76705bd5a0835dc99757ca4` | `a390ceed39705e088f990f4817955f023d1dce54c76705bd5a0835dc99757ca4` | ✅ | ✅ |
| `rv64_fd` | `eadc4d3a3d14a94c889afe3b11326c6c608b0e7ea86f282229d10954413e21fa` | `eadc4d3a3d14a94c889afe3b11326c6c608b0e7ea86f282229d10954413e21fa` | ✅ | ✅ |
| `rv64_linux` | `30c8ecd6256e9d222f44856cf2a1cda5747711b5281bfdfe86a508b4a618aa4d` | `30c8ecd6256e9d222f44856cf2a1cda5747711b5281bfdfe86a508b4a618aa4d` | ✅ | ✅ |
| `rv64_linux_uboot_128m` | `3a18154993c4d9bd7cd4838caafa4b5bad8db0da2e78d56a9fbab8d721b43317` | `3a18154993c4d9bd7cd4838caafa4b5bad8db0da2e78d56a9fbab8d721b43317` | ✅ | ✅ |
| `rv64_mmu` | `936695e2a7547962e9cd6780b5e43cee89028212eb81dffcec8724e11e3aa49c` | `936695e2a7547962e9cd6780b5e43cee89028212eb81dffcec8724e11e3aa49c` | ✅ | ✅ |
| `rv64_nanos_gc` | `c26a1761447e26d5acfab7df7c6ef17b58b531a406362dadadb2cd54e1b685cf` | `c26a1761447e26d5acfab7df7c6ef17b58b531a406362dadadb2cd54e1b685cf` | ✅ | ✅ |
| `rv64_sbi` | `0aae1f6b16e0a5fb9d3b86b1524b470a56b3deb8c73b7c7dec6c27109d7146e5` | `0aae1f6b16e0a5fb9d3b86b1524b470a56b3deb8c73b7c7dec6c27109d7146e5` | ✅ | ✅ |
| `rv64_sbi_fdt` | `548daedba6831779d20f207eaf582bf2e97ba3ceaedd4cf6bf5cc93969b7d15f` | `548daedba6831779d20f207eaf582bf2e97ba3ceaedd4cf6bf5cc93969b7d15f` | ✅ | ✅ |
| `rv64_soft` | `7df5d04679fe19cf7104a5f8cd136299e7c8cbada3eef007220e6b28b66b198c` | `7df5d04679fe19cf7104a5f8cd136299e7c8cbada3eef007220e6b28b66b198c` | ✅ | ✅ |
| `rv64_sv48` | `e55cf5b13fc586bc68765168a510715378eb83d20b5ca6f1b7e844272c7128b0` | `e55cf5b13fc586bc68765168a510715378eb83d20b5ca6f1b7e844272c7128b0` | ✅ | ✅ |
| `rv64_xv6` | `8532358f63397e50dd2712f40f57982b0bff5863e156904babff6a70638b8712` | `8532358f63397e50dd2712f40f57982b0bff5863e156904babff6a70638b8712` | ✅ | ✅ |
| `rv64gc_linux` | `30c8ecd6256e9d222f44856cf2a1cda5747711b5281bfdfe86a508b4a618aa4d` | `30c8ecd6256e9d222f44856cf2a1cda5747711b5281bfdfe86a508b4a618aa4d` | ✅ | ✅ |

全部 20 个 defconfig 的 current / generated effective hash 一致；`.config` 的 `CONFIG_*` 值也一致。

## 7. 无法生成 / 需要人工维护的字段

- 普通 `#` 注释、空行、缩进和原始 `source`/`if` 写法不会按字节保留；Kconfiglib 不保留这些语法信息。菜单、comment、choice、依赖和顺序已由 `layout` 重建。
- 原始文件边界被替换为按顶层菜单拆分的 `kconfig/part-NN.Kconfig`；每个参数仍在 schema 中保留 `file`/`line`/`file_order`。
- 表达式文本会被规范化（例如括号位置、`=` 两侧空格）；parity 以表达式语义和默认值为准，不要求原始文本逐字一致。
- `select`/`imply` 格式已支持，当前仓库为 0 条；若未来出现复杂条件，schema 会记录局部条件并由生成器输出。
- menu `visible if` 已支持；当前菜单的 `visible_if` 全为 `y`。
- 多定义位置（同一 symbol 多个 `config` 块）会被 `generation_issues`标记；当前为 0。
- 如果 prompt/字符串常量包含换行、`$` 等 Kconfig 会展开/无法单行表达的字符，生成器会显式报错而不是静默改写；当前为 0。
- 已新增 `npc/params/generated/.gitignore` 与 `npc/params/reports/.gitignore`，使生成 Kconfig 与 parity 报告可被版本控制；没有修改顶层 `npc/.gitignore`。

## 8. 确定性

| 检查 | 结果 |
| --- | --- |
| `gen_schema.py` 可解析切换后 `npc/Kconfig` | ✅ |
| 反向解析的 schema 语义一致（差异 0 项） | ✅ |
| `npc/Kconfig` mainmenu 与 schema 一致 | ✅ |
| gen_kconfig 两次运行字节一致 | ✅ |
| 仓库 generated/ 与最新生成结果一致 | ✅ |

## 8.1 旧 Kconfig 路径耦合审计

所有 tracked 构建/源码文件都已改为消费 `.config`、`auto.conf`、typed/effective JSON 或 `params/generated/`；旧 `npc/kconfig/Kconfig.*` 只出现在文档、迁移工具、schema provenance 和历史报告中。

## 9. 复现

```bash
# 1. 从 schema 生成 Kconfig（schema 是权威源）
python3 npc/tools/ParamGen/gen_kconfig.py

# 2. 完整 parity（schema 迁移校验/符号/菜单/20 defconfig/hash/确定性）
python3 npc/tools/ParamGen/parity_kconfig.py
```

