# OpenSBI vendored source

本目录是 ysyx-workbench 内置的 OpenSBI 源码，**不是 git submodule**，
也不包含上游 `.git` 目录。`git add .` 会把 NPC 平台适配和下述补丁一起
纳入主仓库。

## 来源

- 上游：<https://github.com/riscv-software-src/opensbi>
- 版本：`v1.9`
- commit：`cbf9f6734dd85a982c63e3cb5db7ffe09da839ca`
- 下载日期：2026-10-23
- 许可证：BSD-2-Clause（见 `LICENSE`）

## ysyx-workbench 适配

新增平台目录：

```text
platform/npc/
├── Kconfig                 # 选择 PLIC/UART8250/MTIMER，不选 MSWI
├── configs/defconfig
├── objects.mk              # 默认 rv64ima/lp64，允许命令行覆盖
└── platform.c              # UART/CLINT/PLIC 固定地址；单 hart；接受 FDT
```

对上游核心的最小补丁（均由 `platform/npc/objects.mk` 中的宏控制，
不影响其他平台）：

| 文件 | 宏 | 原因 |
| --- | --- | --- |
| `firmware/fw_base.S` | `FW_HARTID_FROM_BOOT_ARG` | 历史上 NPC 未实现 `mhartid`；现 RTL 已实现并固定读 0，该宏仅保留以兼容旧 RTL |
| `include/sbi/riscv_asm.h` | `FW_HARTID_FROM_BOOT_ARG` | `current_hartid()` 固定为 0（单 hart） |
| `lib/sbi/riscv_asm.c` | `FW_ASSUME_SU_MODE` | 历史上 `misa` 未置 S/U；现 RTL 按 HAS_S/HAS_U 真实置位，该宏仅用于旧 RTL |
| `lib/sbi/sbi_ecall_vendor.c` | `FW_NO_MVENDORID` | 平台不注册 vendor 扩展；RTL 已实现 `mvendorid`/`marchid`/`mimpid` |
| `lib/sbi/sbi_ecall_base.c` | `FW_NO_MVENDORID` | BASE 扩展按平台宏固定返回 0；不再用于规避缺失 CSR |

平台侧还做了两项硬件适配（未改 RTL）：

- CLINT 使用标准布局 `mtimecmp=0x02004000`、`mtime=0x0200bff8`，并关闭
  `has_64bit_mmio`，因为 NPC Xbar 不接受 64 位多拍 CLINT 写。
- NPC CLINT 没有 MSIP，因此不注册 MSWI/IPI 设备；单 hart 下 OpenSBI
  的 IPI 框架会安全退化。
- `platform.c` 的 `fw_platform_init` 原样接受上一级或 `FW_FDT_PATH`
  传入的 FDT；设备仍按固定地址初始化，DTB 由通用固件复制到
  `FW_JUMP_FDT_ADDR`/`FW_PAYLOAD_FDT_ADDR` 并作为 a1 传给 Linux。

## 升级方法

1. 用新版本上游源码替换本目录（保留本文件与 `platform/npc/`）。
2. 重新应用上表中的宏和补丁；也可先尝试直接构建，按编译/启动错误定位。
3. 更新本文件的版本、commit 和 `npc/scripts/build_opensbi.sh` 中的
   `OPENSBI_VERSION` / `OPENSBI_COMMIT` 常量。
4. 运行 `cd npc && make rv64_sbi_defconfig && make sbi-test`。
