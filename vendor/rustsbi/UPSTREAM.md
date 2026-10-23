# RustSBI 上游来源

本目录是 RustSBI 上游源码的本地副本（vendor），**不是 git submodule**。
已删除上游 `.git` 目录，保留 `LICENSE-MIT`、`LICENSE-MULAN`、`README.md`
和完整源码/文档，便于离线审计与后续升级。

| 项目 | 值 |
| --- | --- |
| 上游仓库 | <https://github.com/rustsbi/rustsbi> |
| 分支 | `main` |
| 基线 commit | `27a9c7d8056e2003b35d83af828cd3787bc55d17` |
| commit 日期 | 2026-10-22 |
| commit 标题 | `ci: boot a minimal RV32 Linux userspace on Prototyper` |
| `rustsbi` crate | 0.4.1 |
| `sbi-spec` crate | 0.0.10 |
| 许可证 | MIT 或 Mulan PSL v2（二选一） |

## NPC 使用方式

NPC 的固件 crate 位于 `npc/rustsbi-fw`，通过 Cargo path dependency 引用：

- `vendor/rustsbi/library/rustsbi`
- `vendor/rustsbi/library/sbi-spec`

上游仓库中的 `firmware/prototyper` 是官方面向多平台的完整固件模板，但它
依赖 nightly 工具链、设备树、PMP 和更多平台驱动，不适合当前 NPC 的最小
M 模式环境。NPC 采用官方文档推荐的“离散固件（discrete package）”方式，
直接使用 `rustsbi` 库并实现 NPC 自己的 Console/Timer/IPI/Fence/HSM/Reset。
详见 `npc/README_RUSTSBI.md`。

## 本地修改

除删除 `.git` 外，本目录未修改上游源码。NPC 的平台适配和 legacy SBI
兼容层都在 `npc/rustsbi-fw` 中，升级 vendor 时不会与本地补丁冲突。
