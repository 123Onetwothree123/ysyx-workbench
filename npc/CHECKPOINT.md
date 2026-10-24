# NPC 检查点与 LightSSS 实验

本文件记录在 direct-NPC 上实际测试过的两种调试加速手段。它们默认不
影响正常构建；只有显式打开宏/环境变量时才生效。

## 1. Verilator `--savable` 保存/恢复

构建：

```sh
cd npc
make VERILATOR_SAVABLE=y BUILD_DIR=build-cp \
  -j"$(nproc)" build-cp/ysyx_26030103_npc
```

也可以按 UART 输出 marker 精确定位（推荐用户态检查点）：

```sh
NPC_CHECKPOINT_MARKER=NPC_USER_READY \
NPC_CHECKPOINT_FILE=/home/abc/ysyx-workbench/npc/checkpoints/npc-user/cp-user.bin \
  build-cp/ysyx_26030103_npc ... --batch
```

marker 由 AXIRAM 的 UART TX 字节流在 C++ 侧匹配；保存后默认退出。
用户态 fastfetch initramfs 在打印 `NPC_USER_READY` 后阻塞等待串口输入，
因此检查点正好落在 fastfetch 尚未执行的位置。

在指定周期保存（保存后默认退出）：

```sh
NPC_CHECKPOINT_CYCLE=350000000 \
NPC_CHECKPOINT_FILE=/tmp/opencode/cp-linux.bin \
  build-cp/ysyx_26030103_npc build/uboot/npc-uboot.bin --batch
```

从检查点恢复：

```sh
build-cp/ysyx_26030103_npc build/uboot/npc-uboot.bin \
  --restore /tmp/opencode/cp-linux.bin --batch
```

注意：

- 只序列化 Verilator RTL 模型（AXIRAM/UART/PLIC/CLINT/CPU 寄存器）；
  `FlashMemory`/SDB shadow 等 host 状态不保存。恢复时仍可传镜像参数，
  但会跳过 `InitializeMemory` 和 `reset`。
- 恢复后 host `cycle` 从 0 重新计数；不要再设置 `NPC_CHECKPOINT_CYCLE`
  指向同一周期，否则会再次保存。
- 该构建需要 `--savable`，正常默认构建不包含额外序列化代码。

## 2. fork/COW 快照（LightSSS 最小版）

用环境变量在指定 host cycle fork：

```sh
NPC_FORK_AT=10000000 \
NPC_FORK_PIDFILE=/tmp/opencode/lightsss.pid \
  build-cp/ysyx_26030103_npc build/uboot/npc-uboot.bin --batch
```

- 父进程打印 fork 开销并退出。
- 子进程继承完整 RTL/RAM 状态，`raise(SIGSTOP)` 等待调试器。
- 调试器 `kill -CONT <pid>` 后，子进程从快照点继续；父进程退出后
  它是唯一仿真实例。
- 该路径不要求 `--savable`，普通 direct-NPC 构建即可使用。

## 3. 实测数据（2026-10-24，128 MiB 配置）

| 项目 | 结果 |
| --- | --- |
| 基线 U-Boot prompt（16B ICache/32 sets，DCache=n，默认 CFLAGS/频率） | 113.1 s |
| 仅 perf CFLAGS + device freq=10000（小 cache） | 113.3 s |
| 小 cache + DCache=y | 111.7 s |
| 16 KiB ICache、DCache=n | 205.0 s |
| 16 KiB I+D Cache（`npc-perf`） | 264.4 s |
| Verilator 检查点保存（cycle=350M，Linux earlycon 之后） | 131 ms，129 MiB |
| 从检查点恢复到下一条 Linux 输出 | 约 2.05 s（含进程启动） |
| fork 开销（cycle=10M） | 2.6 ms |
| fork 子进程 RSS（10M 快照） | 229 MiB |
| fork 后恢复至 U-Boot prompt | 56.2 s |
| Linux 检查点 fork 子进程 RSS | 228 MiB |
| Linux 检查点 fork 后恢复至 `Disabled 5-level paging` | 1.7 s |

结论：Verilator 检查点和 fork/COW 都能完整恢复 NPC 的 RTL/RAM 状态，
适合跳过 U-Boot+EFI+内核早期的重复启动。它们改善的是**重复调试**，
不会缩短第一次完整启动。大 cache 配置反而明显拖慢 RTL 仿真。
