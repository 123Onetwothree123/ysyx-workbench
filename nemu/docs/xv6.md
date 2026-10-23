# NEMU 独立运行 xv6-riscv

本文记录 NEMU RV64 为跑 xv6-riscv 补齐的指令/设备、运行方式、DiffTest
接口和已知限制。golden trace 见 `docs/xv6-golden.md`，原始日志保存在
`/tmp/opencode/nemu-xv6-golden/`。

## 1. 结论

NEMU 已能独立启动 `xv6-riscv`（未修改 xv6 逻辑）：

```text
xv6 kernel is booting

init: starting sh
$ 
```

在 batch 模式下通过 stdin 注入 `ls\n`，还实际跑通了 UART RX → PLIC
source 10 → S 模式外部中断 → `uartintr()` → 文件系统读取：

```text
$ .              1 1 1024
..             1 1 1024
README         2 2 2441
...
```

复现：

```sh
nemu/tests/xv6-ref.sh
```

## 2. 补齐的 ISA

### 2.1 A 扩展

`CONFIG_RISCV_A`（默认 y）实现 RV32/RV64：

- `LR.W/LR.D`、`SC.W/SC.D`（单 hart reservation，成功返回 0，失败返回 1）
- `AMOADD/SWAP/XOR/OR/AND/MIN/MAX/MINU/MAXU` 的 `.W/.D`
- `.W` 结果按 bit31 符号扩展到 XLEN
- 非自然对齐的 AMO/LR/SC 产生 cause 6（store/AMO address misaligned）
- `misa.A` 随配置置位

xv6 实际只使用一条 `amoswap.w.aq`（`acquire()`），但完整 A 已由
`tests/rv64/a64.S` 覆盖。

## 3. 设备模型

`src/device/riscv_xv6.c`，由 `CONFIG_HAS_RISCV_XV6` 打开：

| 地址 | 设备 | 实现 |
| --- | --- | --- |
| `0x02000000` | CLINT | `mtime`（宿主 us）与 `mtimecmp`；`mtime>=mtimecmp` 置 MTIP |
| `0x0c000000` | PLIC | priority/pending/enable/threshold/claim；UART source 10 电平触发 |
| `0x10000000` | 16550 UART | THR/RBR、IER、IIR/FCR、LCR、MCR、LSR、MSR、SCR |

PLIC 同时接受标准 S 上下文地址和 NPC 简化 context 0 地址：

```text
enable    0x0c002080 / 0x0c002000
threshold 0x0c201000 / 0x0c200000
claim     0x0c201004 / 0x0c200004
```

UART TX 直接输出到 stderr；RX 在 batch 模式下从 stdin 非阻塞读取，
FIFO 非空且 IER.RX 打开时拉高 PLIC source 10。非 batch 模式不读 stdin，
避免吞掉 SDB 命令。

NEMU 物理内存仍是 `0x80000000 + 0x8000000`（128 MiB），覆盖 xv6 的
64 MiB `PHYSTOP`。kernel 以 ELF/raw binary 加载到 `0x80000000`；xv6 的
`fs.img` 已由 `.ramdisk` 段嵌入 kernel，无需额外磁盘参数。

## 4. 构建与运行

NEMU：

```sh
cd nemu
make NEMU_HOME=$PWD riscv64-xv6_defconfig
make NEMU_HOME=$PWD -j$(nproc)
```

xv6（`PLATFORM=nemu` 走通用 mhartid/PMP/完整 16550 路径，源码无需改）：

```sh
cd xv6-riscv
make -j$(nproc) PLATFORM=nemu TOOLPREFIX=riscv64-unknown-elf-
```

运行：

```sh
cd nemu
./build/riscv64-nemu-interpreter -b \
  --elf ../xv6-riscv/kernel/kernel \
  ../xv6-riscv/kernel/kernel.bin
```

`-b` 下 stdin 是 UART 输入，可 `printf 'ls\n' | ...`。交互 SDB 模式
只用于断点/单步，不转发 UART 输入。

## 5. DiffTest

REF 构建：

```sh
cd nemu
make NEMU_HOME=$PWD riscv64-ref_defconfig
make NEMU_HOME=$PWD -j$(nproc)
# build/riscv64-nemu-interpreter-so
```

ABI 与 `docs/rv64.md` 一致（264 字节 GPR+PC、32 字节 CSR 快照、
`difftest_trap`）。REF 构建没有 `CONFIG_DEVICE`，`paddr.c` 为 xv6 地址
提供最小 stub：UART LSR=0x60、CLINT mtime/mtimecmp、PLIC 读 0/写忽略；
真正的 MMIO 结果由 DUT 按现有 DiffTest 机制注入。

NPC 侧配合（本分支已改 `npc/csrc/difftest/difftest.cpp`）：

- direct-NPC 的可建模 RAM 范围改为完整的 `CONFIG_MBASE..CONFIG_MSIZE`，
  不再写死 256 KiB；否则 xv6 的页表/用户页 store 会被当作 MMIO 注入，
  REF 内存永远不更新。
- 初始化时按完整 `CONFIG_MSIZE` 同步 RAM（镜像 + 零），与 RTL 的复位
  清零语义一致，避免 REF 的 `MEM_RANDOM` 造成伪差异。

NPC 配置已加入 `npc/configs/rv64_xv6_defconfig`（RV64IMA/Sv39/ICache）。
做 DiffTest 时需要在该配置上打开 `CONFIG_DIFFTEST=y` 后重新生成 NPC。
运行：

```sh
npc/build/ysyx_26030103_npc \
  xv6-riscv/kernel/kernel --elf xv6-riscv/kernel/kernel \
  --diff nemu/build/riscv64-nemu-interpreter-so
```

定时器是宿主时间驱动的，不是指令级确定；DiffTest 时应让 DUT 通过
`difftest_trap` 注入中断，REF 不应自行产生 CLINT 中断（REF 未初始化
device，因此不会）。UART/PLIC/CLINT 的寄存器访问属于 MMIO，NPC 现有
`ReferenceModelsDataAccess()` 会走结果注入路径。

NEMU 自身也验证过 REF ABI：用临时 `CONFIG_DIFFTEST=y` 的
`riscv64-xv6` 构建 + `riscv64-nemu-interpreter-so`，从 M 模式启动到
第一次用户 `usertrap` 断点（`0x800037e4`）没有出现寄存器/CSR 差异。
日志：`/tmp/opencode/nemu-xv6-golden/nemu-self-difftest-to-usertrap.log`。

NPC 侧本 worktree 已能构建 `rv64_xv6_defconfig`（`CONFIG_DIFFTEST=y`）
并加载 NEMU REF，但主线 RTL 仍在 cycle 23 因 AXI5WidthBridge 的
64→32 地址写 B 响应问题跳到 `mtvec=0`，尚未到达 IFU/redirect 边界。
该 RTL 不在本任务修改范围；详细日志见
`/tmp/opencode/nemu-xv6-golden/NPC-DIFFTEST-ATTEMPT.md`。

## 6. Golden trace 与 IFU 问题

`tests/xv6-golden.sh` 用 SDB 断点在 `userret`、`sret`、`usertrap`
抓 `p $pc/$sepc/$satp/$sstatus/$sscratch/$stvec`、GPR、trapframe
（`x 32g *($s1+0x58)`）、`iringbuf` 和 `ftrace`。

第一次用户 `open("console")` 的关键序列：

```text
0x3ffffff0b0: 0000100f  fence.i        <-- userret 入口
...
0x000005a0:   00000073  ecall
0x3ffffff000: ... uservec ...
0x3ffffff0ac: 000280e7  jalr t0
0x800037e4:   fe010113  addi sp,sp,-0x20   <-- NEMU 正确提交
```

NPC 在 `0x800037e4` 看到的错误指令是 `0x0000100f`，即 `jalr t0` 的
顺序后继 `0x3ffffff0b0`（fence.i）。因此优先检查 IFU/ICache 在
`jalr` redirect 后是否丢弃了已发出的顺序预取响应，以及 fetch tag 是否
在 flush 后仍能匹配新 PC。详细现场与 trapframe 见
`docs/xv6-golden.md`。

## 7. 测试

```sh
nemu/tests/xv6-ref.sh          # 启动到 shell + UART RX/PLIC ls
nemu/tests/xv6-golden.sh       # 生成 SDB golden trace
nemu/tests/rv64-smoke.sh       # RV64 CSR/中断/地址/MMU/A 扩展回归
```

## 8. 已知限制

- 只有单 hart；PLIC 只有一个 enable/threshold/claim 组，不建模多上下文。
- UART RX 只在 `-b` 批处理模式转发 stdin；SDB 交互模式不转发。
- CLINT 使用宿主微秒，定时器中断时刻不确定；指令级 golden trace 应在
  第一个 timer 中断前截断，或由 DUT 注入 trap。
- NEMU REF 构建不包含设备模型，MMIO 结果依赖 DUT 注入。
- NPC 的 IFU/ICache redirect 修复不在本任务范围；本次未改动 IFU RTL。
