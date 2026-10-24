# F/D 硬浮点短回归

镜像由 `main.S`（精确指令/异常语义）和 `test.c`（GCC 硬浮点 ABI）组成：

- `rv64.elf`：`-march=rv64imafd -mabi=lp64d`，覆盖 F 与 D。
- `rv32.elf`：`-march=rv32imaf -mabi=ilp32f`，覆盖 F。

`main.S` 覆盖：

- FLW/FSW、FLD/FSD、FMV.X.W/FMV.W.X、FMV.X.D/FMV.D.X；
- FCVT.W/WU/L/LU 与 FCVT.S/D 互转；
- FADD/FSUB/FMUL/FDIV/FSQRT、四种 FMA、FSGNJ/N/X、FMIN/FMAX、FCLASS；
- FEQ/FLT/FLE、NaN/sNaN、canonical NaN、NV/DZ/NX 标志；
- frm 动态舍入与 RNE/RTZ/RDN/RUP；
- `mstatus.FS=0` 时 FP 指令、浮点 load/store 以及 fflags/frm/fcsr
  访问都精确陷入（mepc 指向故障指令，不改变 f 寄存器）；
- trap 前后 f 寄存器与 fcsr 的上下文保存/恢复。

## 运行

```sh
make -C npc rv64_fd_defconfig      # 或 rv32_f_defconfig
make -C npc verilog                # 重新生成带 FPU 的 RTL
make -C npc build/ysyx_26030103_npc
make -C npc/csrc/Test/fpu-test run64
make -C npc/csrc/Test/fpu-test run32   # 需按 rv32_f_defconfig 重新生成 RTL
```

预期输出 `HIT GOOD TRAP at pc = ...`，失败时 `a0` 为对应的 fail 编号。

## DiffTest

主线 NEMU 已提供 RV32/RV64 F/D REF（含可选的 freg[32]+fcsr
`difftest_fp_*` ABI）。NPC 的 `--diff` 逐退休比对 GPR/PC 与
mstatus/mtvec/mepc/mcause，并在 trap 边界注入精确 cause/tval；浮点
结果本身由 guest 写回 RAM 后用整数比较自检。构建好 NEMU REF 后：

```sh
make run-diff64 REF_SO64=/path/to/riscv64-nemu-interpreter-so
make run-diff32 REF_SO32=/path/to/riscv32-nemu-interpreter-so
```

更完整的 F/D 语义与 GCC 硬浮点 C 程序回归见
`npc/csrc/Test/fpu-difftest-test`。
