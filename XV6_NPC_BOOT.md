# xv6-riscv 在 NPC 上的启动状态（2026-10-23）

本文记录用主线 `c140c1cd9` 的 NPC + A 扩展实际启动 xv6-riscv 的结果、
已修复的核心 bug、临时 workaround 和剩余工作。旧的移植背景见
`XV6_PORT.md`。

## 1. 结论

- 配置：`npc/configs/rv64_xv6_defconfig`
  （RV64IMA、Sv39、ICache、CLINT、简化 PLIC、UART TX、SDB）。
- **实际到达 shell 启动点和提示符**：

```text
xv6 kernel is booting

[npc] /init loaded, entering user mode
init: starting sh
$ 
```

- `init: starting sh` 之后出现 shell 提示符 `$ `；没有交互：NPC 的 UART
  目前只有 TX，没有 RX/中断（由另一任务实现），shell 等待输入。
- 启动路径验证了：A 扩展（`amoswap.w.aq`）、Sv39 内核/用户页表、
  `satp`/`sfence.vma`/`sret`、用户态 `ecall`、ramdisk 上的
  `fsinit`/`kexec("/init")`、`init` 的 `open`/`mknod`、UART TX。
- 仍有一个核心 RTL bug（IFU/ICache redirect 后重放旧指令字，见 4.3），
  当前用 `usertrap_entry.S` 一跳垫片规避。去掉垫片会在第一次
  `usertrap` 递归 page fault，这是后续要修的核心问题。

## 2. 复现

```sh
cd npc
make rv64_xv6_defconfig
make -j"$(nproc)" build/ysyx_26030103_npc

cd ../xv6-riscv
make clean
make -j"$(nproc)" PLATFORM=npc TOOLPREFIX=riscv64-unknown-elf-
./run-npc.sh
```

`run-npc.sh` 会构建 kernel、用 `--batch` 运行 direct-NPC、捕获日志并检查
`xv6 kernel is booting`、`[npc] /init loaded, entering user mode` 和
`init: starting sh`。默认超时 300 s，可用 `TIMEOUT=...` 覆盖；
`BUILD_NPC=1` 会先重新生成 NPC RTL/二进制。

注意：`PHYSTOP` 默认被限制为 `KERNBASE + 4 MiB`。direct-NPC 的 `kinit()`
会逐页写垃圾（约 8 页/秒），64 MiB 需要约 30 分钟。需要完整内存时用
`-DPHYSTOP='(KERNBASE + 64*1024*1024)'` 重新编译 kernel。

## 3. 实际日志

### 3.1 干净启动日志（`run-npc.sh`，4 MiB，300 s timeout）

```text
现在指定了镜像文件
ELF 镜像加载完成: .../xv6-riscv/kernel/kernel，4 个程序头，镜像 2191360 字节

xv6 kernel is booting

[npc] /init loaded, entering user mode
init: starting sh
$ 

== summary ==
simulator exit: 124 (124 = timeout, expected for a non-halting guest)
PASS: xv6 kernel is booting
PASS: /init loaded, user mode entered
PASS: init: starting sh
```

### 3.2 调试期间的检查点（临时打印，已全部移除）

```text
[main] kinit done
[main] kvminit done
[main] kvminithart done
[main] procinit done
[main] trapinit done
[main] trapinithart done
[main] plicinit done
[main] plicinithart done
[main] binit done
[main] iinit done
[main] fileinit done
[main] ramdisk_init done
[main] userinit done
[sched] run pid=1
[forkret] enter first=1 pid=1
[forkret] fsinit done
[forkret] kexec a0=1
[forkret] kstack=3fffffd000 ksp=3fffffe000 ksatp=80000000000803ff ktrap=8000392c epc=e4 hart=0
[forkret] userret satp=8000000000080393
[TRAP] pc=000005a0 cause=00000008 epc=00000000 tval=000005a0
[sys] num=15 ret=-1      # open("console")
[sys] num=17 ret=0       # mknod("console")
```

这些打印在提交前已移除；它们会改变时序并放大 4.3 的重放问题。

## 4. 核心 bug

### 4.1 已修复：AXI5WidthBridge 的 64→32 地址 B 响应

`npc/src/Infra/ysyx_26030103_AXI5WidthBridge.scala`

RV64 CPU + 32 位 SoC（`AddressWidth=64`、`SOC_XLEN=32`）时
`AddressNarrows=true`、`Downsize=false`。写请求走 `wUpsize`，但
`wResp` 进入“本地生成 B”的分支，既没有等待/消费下游 RAM 的 B，也没有
驱动 `io.out.B.BREADY`。第一个 8 字节栈 store 之后下游 RAM 的 BVALID
永久悬空，第二个 store 卡死。

修复：只有 `Downsize`（数据 64→32，`wDnWaitB` 已消费下游 B）或地址越界
（`wError`）才本地生成 B；地址变窄但数据同宽时必须透传/等待下游 B。

验证：修复前 xv6 在 `0x800000a0: sd s0,16(sp)` 卡住；修复后正常执行到
`0x80001024: amoswap.w.aq`（A=n 时按非法指令停止），A=y 时继续启动。

### 4.2 已修复：AXI5Xbar 的 PLIC 写响应缺少 BVALID/BREADY

`npc/src/Infra/ysyx_26030103_AXI5Xbar.scala`

`StateWriteResponse` 的 `TargetPLIC` 分支被误复制成了请求阶段的 AW/W
驱动代码：没有 `io.in.B.BVALID := io.PLIC.B.BVALID`，也没有
`io.PLIC.B.BREADY := io.in.B.BREADY`。PLIC 的 BVALID 永远不被消费，
xv6 的 `plicinit()` 在第一次写 PLIC priority 后卡死。

修复：该分支改为完整转发 PLIC 的 BID/BVALID/BRESP 和 BREADY，并删除
同一 switch 中两份不可达的重复 `TargetPLIC`/`StateWriteDrain`/
`StateWriteResponse` 代码。

验证：`plicinit()` 和 `plicinithart()` 均通过。

### 4.3 未修复（已 workaround）：IFU/ICache 重定向后重放旧指令字

去掉 `usertrap_entry.S` 垫片后，临时 WBU 探针直接看到：

```text
[WBU] pc=0x8000392c Instruction=0x0000100f (fence.i)
      rd=0 rw=0 wen=0
```

`0x8000392c` 是 `usertrap` 的第一条 `addi sp,sp,-32`，但 WBU 实际
收到的指令字是 `fence.i`（来自 `userret` 的 `0x800090b0`）。于是
`addi sp` 被丢掉，紧接着的 `sd ra,24(sp)` 用旧 sp 写到
`TRAPFRAME+0x18`（`0x3fffffe018`，内核页表未映射），触发
`scause=0xf`（store page fault）；trap 又进入 `uservec`，形成递归：

```text
[TRAP] pc=80003930 cause=0000000f epc=00000000 tval=ffffe018
[TRAP] pc=fffff010 cause=0000000f epc=00000000 tval=ffffe028
[TRAP] pc=fffff010 cause=0000000f epc=00000000 tval=ffffe028
...
```

这是 IFU/ICache 取指响应与 fetch-tag 快照在 redirect/flush 后不匹配的
核心 bug。尝试过在 flush 后丢弃 `icache.resp_valid`（`dropStaleFetchResp`），
但没有消除问题，已回退，不纳入提交。

**当前 workaround**：`xv6-riscv/kernel/usertrap_entry.S` 在 `usertrap` 前加
一条 `nop`，让旧指令字落在无害的垫片上，再由 `j usertrap` 进入真正入口。
`prepare_return()` 的 `kernel_trap` 指向 `usertrap_entry`。该垫片使干净
构建稳定到达 `init: starting sh`；核心 RTL 修复后应删除。

### 4.4 调试打印会放大 4.3

在 `sys_open`/`fs.c`/`syscall.c` 加临时 `printk` 时，曾观察到第三次
`open("console")` 在 `begin_op()` 之后不再前进。移除所有临时打印后，
同一构建稳定到达 `init: starting sh`。因此该现象是 4.3 的时序敏感表现，
不是 ramdisk/文件系统数据错误；`fsinit`、`kexec("/init")`、`mknod`
都已实际成功。

## 5. 各子系统状态

| 子系统 | 状态 | 证据/说明 |
| --- | --- | --- |
| RV64IMA / A | 通过 | `amoswap.w.aq` 不再 illegal；自旋锁、printk 正常 |
| Sv39 / MMU | 通过 | 内核/用户页表、`satp`/`sfence.vma`/`sret`；用户态 ecall 到达 `usertrap` |
| UART TX | 通过 | 启动串、`init: starting sh` 均由 UART 输出 |
| UART RX/IRQ | 未实现 | 本任务不涉及；TX-only 运行，shell 无法输入 |
| PLIC | 寄存器编程通过 | xv6 使用 NPC 的 context 0 地址（见 `memlayout.h`）；source 10 未接线 |
| CLINT timer | 未观察到中断 | `mtimecmp` 写入未 fault；本次窗口内未看到 `scause=1/5` |
| ramdisk | 通过 | `fsinit`/`kexec("/init")`/`init` 的 open/mknod 均成功 |
| ICache | 启用 | `rv64_xv6_defconfig` 中 `CONFIG_ICACHE=y` |
| DCache | 关闭 | 为隔离 A/Sv39；未验证开启后的启动 |

## 6. 配置要点

`npc/configs/rv64_xv6_defconfig`：

- `CONFIG_XLEN=64`、`CONFIG_RISCV_M=y`、`CONFIG_RISCV_A=y`
- `CONFIG_HAS_S/U/MMU=y`、`MMU_LEVELS=3`、8B PTE、4096B 页（Sv39）
- `CONFIG_TIMER_CLINT_MTIP=y`、`CONFIG_PLIC_SOURCES=32`
- `CONFIG_ICACHE=y`、`CONFIG_DCACHE=n`
- `CONFIG_SDB=y`（`--batch` 需要）
- `CONFIG_TRACE/FTRACE/IRINGBUF=n`（ftrace 会与 UART 逐字符输出交错并显著拖慢启动）

xv6 侧：

- `memlayout.h`：NPC PLIC context 0 地址；`PHYSTOP` 默认 4 MiB 可覆盖。
- `usertrap_entry.S`：4.3 的临时垫片。
- `proc.c`：NPC-only `[npc] /init loaded` 进度行。
- `plic.c`：NPC 宏不使用 hart 参数，加 `(void)hart`。

## 7. 后续

1. 修核心 IFU/ICache redirect 后的旧指令重放（4.3），去掉
   `usertrap_entry.S`。
2. UART RX/PLIC source 10 由另一任务接入后验证交互 shell。
3. 可选：开启 DCache 验证；用 `-DPHYSTOP=...` 跑完整 64 MiB；观察
   CLINT timer 中断路径。
