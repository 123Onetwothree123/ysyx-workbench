# Nanos-lite

Nanos-lite is the simplified version of Nanos (http://cslab.nju.edu.cn/opsystem).
It is ported to the [AM project](https://github.com/NJU-ProjectN/abstract-machine.git).
It is a two-tasking operating system with the following features
* ramdisk device drivers
* ELF program loader
* memory management with paging
* a simple file system
  * with fix number and size of files
  * without directory
  * some device files
* 9 system calls
  * open, read, write, lseek, close, gettimeofday, brk, exit, execve
* scheduler with two tasks

## RV64GC / lp64d 运行方式

Nanos-lite 的 RV64GC 目标直接复用 AM 的 `riscv64gc-npc`：

* `ARCH=riscv64gc-npc` 由 AM Makefile 拆成 `ISA=riscv64gc` 和
  `PLATFORM=npc`；
* Nanos-lite 的 `update` 目标把 `ISA=riscv64gc VME=1` 传给
  Navy-apps，因此 libc、libos 和所有用户程序都按
  `-march=rv64gc_zicsr_zifencei -mabi=lp64d` 编译，并链接到
  Sv39 用户地址空间 `0x40000000`；
* AM/Nanos 内核本身由 `abstract-machine/scripts/riscv64gc-npc.mk`
  编译为 ELF64、RVC、double-float ABI，trap 路径保存
  `f0-f31/fcsr`，用户态可以真正执行 F/D 指令。

首次构建需要先在 NPC 中选择 RV64GC + Sv39 + direct STIP 的配置
（该配置只涉及 CPU/MMU/中断，不需要也不构建 Linux）：

```bash
cd npc
make rv64_nanos_gc_defconfig
```

然后构建 ramdisk、Nanos-lite，并在 direct-NPC 上运行：

```bash
cd nanos-lite
make ARCH=riscv64gc-npc update
make ARCH=riscv64gc-npc
make ARCH=riscv64gc-npc run
```

第二、三条命令也可以合并成 `make ARCH=riscv64gc-npc run`。
`run` 会把 `nanos-lite-riscv64gc-npc.bin` 交给 direct-NPC；由于
Nanos-lite 的用户进程是无限循环，需要观察日志后手动结束（或使用
`timeout`）。

RV64GC 的 `init_proc()` 除了 PA4.4 原有的 `/bin/hello` 和
`/bin/spin`，还会装入第三个用户进程 `/bin/fp-test`。它执行
`fdiv.d/fmul.d/fmadd.d/fcvt.d.l` 等指令并打印 double 的位模式，
用于确认浮点运算确实发生在 U 模式，而不是被编译成软浮点 helper。

原有 RV32 PA4.4 目标保持不变：

```bash
cd npc
make nanos_defconfig
cd ../nanos-lite
make ARCH=riscv32-npc update
make ARCH=riscv32-npc run
```

RV32 目标只装入 `hello` + `spin`，不会引用 `fp-test`；其
`ISA=riscv32` 仍使用 `navy-apps/scripts/riscv32.mk`。
