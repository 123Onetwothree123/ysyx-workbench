# NEMU golden trace：xv6 第一次用户 ecall / usertrap（给 IFU/redirect 代理）

## 0. 结论（先看这里）

- NEMU 已独立启动 xv6 到 `init: starting sh` 和 `$ `，并实际执行了 `ls`
  （UART RX + PLIC source 10）。见 `boot-to-shell.log`。
- 第一次用户 `open("console", O_RDWR)` 的边界：

```text
userret  0x3ffffff0b0: 0000100f  fence.i          <-- trampoline 运行时映射
...
ecall    0x00000005a0: 00000073  ecall            <-- a7=15 (SYS_open), a0=0xe10, a1=2
uservec  0x3ffffff000 ... 0x3ffffff0ac: 000280e7 jalr t0
usertrap 0x800037e4:   fe010113  addi sp,sp,-0x20  <-- 正确提交的指令
```

- NPC 观察到的错误指令 `0x0000100f (fence.i)` 正是 **userret 入口
  `0x3ffffff0b0` 的指令字**，而不是 usertrap 的指令。`jalr t0` 位于
  `0x3ffffff0ac`，它的顺序后继就是 `0x3ffffff0b0`。因此最可疑的路径是：
  `jalr t0` 之后 IFU 已经发出/返回了顺序预取 `0x3ffffff0b0` 的响应，
  redirect 到 `0x800037e4` 后没有按 fetch tag 丢弃该旧响应，导致
  `0x0000100f` 被当作 usertrap 的第一条指令提交。
- 后续 `0x800037e4` 的 5 条 `sd` 全部依赖 `addi sp,sp,-32`；丢掉它就会
  用旧 sp 写 `TRAPFRAME+0x18`，正是之前看到的 `scause=0xf, tval=...e018`
  递归页错误。金标序列与这个解释完全一致。

## 1. 文件

| 文件 | 内容 |
| --- | --- |
| `sdb-script.txt` | 生成 SDB golden 的命令脚本 |
| `sdb-userret-usertrap.log` | SDB 原始日志（含 ANSI） |
| `sdb-userret-usertrap.clean.log` | 去 ANSI 的完整日志 |
| `itrace-userret-usertrap.log` | 只在 userret/usertrap 窗口打开的 ITRACE |
| `itrace-golden.txt` | 第一次 userret -> ecall -> uservec -> usertrap 的 PC/机器码序列 |
| `iringbuf-tail.txt` | 停在 usertrap 入口时的 IRINGBUF 末尾 62 条 |
| `boot-to-shell.log` | NEMU 独立启动到 shell 并执行 ls 的日志 |

NEMU 构建：`riscv64-xv6_defconfig`；xv6 构建：`PLATFORM=nemu`，
`-march=rv64ima_zicsr_zifencei -mabi=lp64`，kernel 加载地址 `0x80000000`，
`kernel.bin` 大小 2191360 字节。SDB 运行命令：

```sh
cd nemu
./build/riscv64-nemu-interpreter --elf ../xv6-riscv/kernel/kernel \
  ../xv6-riscv/kernel/kernel.bin < /tmp/opencode/nemu-xv6-golden/sdb-script.txt
```

## 2. 符号与地址（kernel.sym）

```text
trampoline = 0x80008000    uservec  = 0x80008000    userret = 0x800080b0
usertrap   = 0x800037e4    prepare_return = 0x80003634
trampoline 运行时虚拟地址 = 0x3ffffff000
userret    运行时虚拟地址 = 0x3ffffff0b0
sret       运行时虚拟地址 = 0x3ffffff148
TRAPFRAME = 0x3fffffe000   TRAMPOLINE = 0x3ffffff000
```

## 3. SDB 快照

### 3.1 userret 入口（0x3ffffff0b0，执行 fence.i 前）

```text
pc        = 0x3ffffff0b0
sepc      = 0x00000000000000e4
satp      = 0x8000000000083fff   (Sv39, 用户页表)
sstatus   = 0x0000000000000020   (SPIE=1, SPP=0)
sscratch  = 0
stvec     = 0x0000003ffffff000   (uservec)
a0        = 用户 satp
```

`si 1` 后 pc = 0x3ffffff0b4，确认提交的是 `fence.i`。

### 3.2 sret 前（0x3ffffff148）

```text
pc       = 0x3ffffff148
sepc     = 0x00000000000000e4
sstatus  = 0x0000000000000020
satp     = 0x8000000000083f75   (用户页表)
stvec    = 0x0000003ffffff000
sscratch = 0
```

`si 1` 执行 sret 后 pc = 0x00000000000000e4，GPR 已从 trapframe 恢复。

### 3.3 usertrap 入口（0x800037e4，执行 addi sp 前）

```text
pc       = 0x800037e4
sepc     = 0x00000000000005a0   (用户 ecall 地址)
scause   = 8                    (U-mode ecall)
stval    = 0
sstatus  = 0x0000000000000020   (SPIE=1, SPP=0)
satp     = 0x8000000000083fff   (已由 uservec 切回内核页表)
stvec    = 0x0000003ffffff000   (uservec)
sscratch = 0x0000000000000e10   (用户 a0)
a0       = 0x0000003fffffe000   (TRAPFRAME)
```

### 3.4 usertrap 保存 sepc 后：`p` / trapframe

断点 0x80003828（`sd a4,24(a5)` 之后）：

```text
p (s1)          = 0x0000000080011f00
p->pagetable    = 0x0000000083f75000
p->trapframe    = 0x0000000083f79000
p->kstack       = 0x0000003fffffe000 (trapframe +0x08 kernel_sp)
```

trapframe 物理内容（`x 32g *($s1+0x58)`）：

```text
0x83f79000: 0x8000000000083fff  kernel_satp
0x83f79008: 0x0000003fffffe000  kernel_sp
0x83f79010: 0x00000000800037e4  kernel_trap
0x83f79018: 0x00000000000005a0  epc
0x83f79020: 0x0000000000000000  kernel_hartid
0x83f79028: 0x0000000000000028  ra
0x83f79030: 0x0000000000003fb0  sp
0x83f79038: 0x0505050505050505  gp
0x83f79040: 0x0505050505050505  tp
0x83f79048: 0x0505050505050505  t0
0x83f79050: 0x0505050505050505  t1
0x83f79058: 0x0505050505050505  t2
0x83f79060: 0x0000000000003fd0  s0
0x83f79068: 0x0505050505050505  s1
0x83f79070: 0x0000000000000e10  a0
0x83f79078: 0x0000000000000002  a1
0x83f79080: 0x0505050505050505  a2
0x83f79088: 0x0505050505050505  a3
0x83f79090: 0x0505050505050505  a4
0x83f79098: 0x0505050505050505  a5
0x83f790a0: 0x0505050505050505  a6
0x83f790a8: 0x000000000000000f  a7 = SYS_open
```

`0x0505...` 是 xv6 未初始化用户栈的已知脏值，不是设备问题。

## 4. IRINGBUF 末尾（停在 usertrap 入口时）

```text
0x000000000000059c: addi a7, zero, 0xf      00 f0 08 93
0x00000000000005a0: ecall                    00 00 00 73
0x0000003ffffff000: csrw sscratch, a0        14 05 10 73
...
0x0000003ffffff0a0: sfence.vma               12 00 00 73
0x0000003ffffff0a4: csrw satp, t1            18 03 10 73
0x0000003ffffff0a8: sfence.vma               12 00 00 73
0x0000003ffffff0ac: jalr t0                  00 02 80 e7
```

执行完 `jalr t0` 后，NEMU 的下一条就是 `0x800037e4`；IRINGBUF 中
`0x3ffffff0ac` 之后没有任何 `fence.i`，说明 `fence.i` 不可能是指令流
的合法下一条。它是 userret 入口的旧字（0x3ffffff0b0）。

## 5. ftrace（第一次 usertrap 内 myproc 调用）

```text
0x80003814: call [myproc@0x80002588]
0x8000259c:   call [push_off@0x800010cc]
...
0x800025d8: ret  [myproc]
```

`p` 在 `0x80003818: mv s1,a0` 之后变为 0x80011f00；断点 0x80003828 时
`p->trapframe` 已填入用户 ecall 现场。

## 6. 给 IFU/redirect 修复代理的检查点

1. `0x3ffffff0ac: jalr t0` 的目标是 `0x800037e4`。在 EXU 提交 redirect 的
   同一拍，确认 IFU 是否仍在接收 `jalr` 的顺序后继 `0x3ffffff0b0`。
2. `0x3ffffff0b0` 的机器码是 `0x0000100f`，正是 DUT 在 usertrap 看到的
   错误字。检查 ICache `resp_valid` / fetch tag / `fetch_pc` 是否在 flush
   后仍能匹配新 PC；旧响应必须按请求 tag 丢弃，而不是按“最新 fetch tag”。
3. `jalr` 目标 `0x800037e4` 不在 ICache 中；如果 redirect 后先返回了
   outstanding 的 `0x3ffffff0b0` 行，再发起 `0x800037e4` 取指，需要保证
   前者不会写入解码队列。
4. 修复后应能连续提交：
   `0x800037e4 addi sp,sp,-32`、`0x800037e8 sd ra,24(sp)`、
   `0x800037ec sd s0,16(sp)`、`0x800037f0 sd s1,8(sp)`、
   `0x800037f4 sd s2,0(sp)`。
5. 该边界与 MMIO/UART/PLIC 无关：trapframe、页表、sret 目标均已由 NEMU
   独立验证；NEMU 在同样地址提交的是 `0xfe010113`。
