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

- `init: starting sh` 之后出现 shell 提示符 `$ `；本次未验证交互输入，
  shell 在等待输入。
- 启动路径验证了：A 扩展（`amoswap.w.aq`）、Sv39 内核/用户页表、
  `satp`/`sfence.vma`/`sret`、用户态 `ecall`、ramdisk 上的
  `fsinit`/`kexec("/init")`、`init` 的 `open`/`mknod`、UART TX。
- 核心 IFU 重放 bug 已修复（见 4.3）：redirect/flush 后取指 MMU 会丢弃
  旧 walk 的迟到响应，ICache 不再用新 PC 取到旧物理行。xv6 已删除
  `usertrap_entry.S` 垫片，直接从 `userret` 返回用户态并再次进入
  `usertrap`。

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

### 3.1 无垫片干净启动日志（4 MiB，direct NPC）

`usertrap_entry.S` 删除后，用最终 RTL 重新构建 kernel 并运行：

```text
现在指定了镜像文件
ELF 镜像加载完成: kernel/kernel，4 个程序头，镜像 2191360 字节

xv6 kernel is booting

[npc] /init loaded, entering user mode
init: starting sh
$ 
```

仿真在 220 s 内已到达 `$ `（之后手动结束，故退出码为 SIGTERM）。
`run-npc.sh` 的 timeout 路径会显示 124，属于非停机 guest 的预期结果。

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
[forkret] kstack=3fffffd000 ksp=3fffffe000 ksatp=80000000000803ff ktrap=80003704 epc=e4 hart=0
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

### 4.3 已修复：IFU 重定向后重放旧指令字

根因不在 ICache 阵列，而在**取指 MMU 的响应与请求标签脱节**：

1. `sfence.vma`/redirect 会把 IFUMMU 和 MMU 仲裁器的在途状态清掉，但
   旧页表 walk 的响应仍可能迟到；
2. IFUMMU 的 `xwait` 收到响应时不校验它属于哪一笔请求，只把
   `paddrReg := resp.paddr`，同时用**当前** `reqVaddrReg` 打标签；
3. 于是旧 walk 的 PA（例如 `userret` 的 `0x800080b0`）被贴上 `usertrap`
   的新虚拟地址 `0x80003704`，`translationValid` 为真；
4. ICache 用旧 PA 取到 `fence.i` 指令字，而顶层快照记录的 PC 是新
   `usertrap`。`addi sp,sp,-32` 被丢掉，随后 `sd ra,24(sp)` 用旧 sp
   触发 store page fault，递归进入 `uservec`。

NEMU golden trace 与 NPC 的对比确认：在 `uservec` 的 `jalr t0` 之前
`t0=0x80003704`；修复前 NPC 第一笔 usertrap 取指实际发往
`0x800080b0`，修复后发往 `0x80003704` 并返回 `0xfe010113`
（`addi sp,sp,-32`）。

修复（`npc/src/MMU/ysyx_26030103_MMUIntegrations.scala`）：

- `MMUResponse.tval` 在所有成功/故障/TLB 命中/旁路路径上都携带原请求
  虚拟地址；
- IFUMMU（以及数据侧 LSUMMU）在 `xwait` 只接受
  `resp.tval === 当前请求 VA` 的响应，其余迟到响应直接丢弃并继续等待；
- 顶层新增断言：ICache 响应到达时必须有对应的取指 PC 快照。

定向回归：`npc/csrc/Test/rtl-issue-regression-test` 新增
`ifu_mmu_stale_resp_test`（旧响应/旧 page fault 不得贴到新 PC）与
`icache_redirect_test`（hit 在途 kill、miss refill 在途 kill、
同 cache line/不同 line 的新 PC）。

### 4.4 删除 workaround

`xv6-riscv/kernel/usertrap_entry.S`、Makefile 目标和 `prepare_return()`
中的 `usertrap_entry` 引用已全部删除；`kernel_trap` 直接指向
`usertrap`。干净构建可到达 `init: starting sh`。

## 5. 各子系统状态

| 子系统 | 状态 | 证据/说明 |
| --- | --- | --- |
| RV64IMA / A | 通过 | `amoswap.w.aq` 不再 illegal；自旋锁、printk 正常 |
| Sv39 / MMU | 通过 | 内核/用户页表、`satp`/`sfence.vma`/`sret`；用户态 ecall 到达 `usertrap` |
| UART TX | 通过 | 启动串、`init: starting sh` 均由 UART 输出 |
| UART RX/IRQ | 主线已接入 | 本任务未做交互输入验证；PLIC source 10 已接线 |
| PLIC | 寄存器编程通过 | xv6 使用 NPC 的 context 0 地址（见 `memlayout.h`） |
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
- `proc.c`：NPC-only `[npc] /init loaded` 进度行。
- `plic.c`：NPC 宏不使用 hart 参数，加 `(void)hart`。

## 7. 后续

1. UART RX/PLIC source 10 已由主线接入，可继续验证交互 shell。
2. 可选：开启 DCache 验证；用 `-DPHYSTOP=...` 跑完整 64 MiB；观察
   CLINT timer 中断路径。
