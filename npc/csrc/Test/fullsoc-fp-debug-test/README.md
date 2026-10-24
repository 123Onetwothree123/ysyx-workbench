# fullsoc-fp-debug-test

最小 full-SoC 浮点/trap 调试端口回归。它不跑完整 FPU guest，只验证
`ysyxSoCFull` 是否把 NPC 的调试 ABI 真正接到顶层：

- `probe`：用 main.S 直接驱动 `VysyxSoCFull`，halt 后从顶层端口回读
  `debug_fpr_rdata`（f2=1.5、f3=3.0）、`debug_fcsr=0x40`、
  `debug_trap_tval=0x30000001`，并检查 FP 写口确实命中 f2/f3；
- `diff`：用完整的 full-SoC NPC 仿真器和 NEMU REF 跑同一个 guest。
  guest 在 trap handler 中强制断言 `mcause=4`、`mtval=0x30000001`，
  DiffTest 必须打印 `DiffTest: 浮点状态 ABI ON`、不得打印 tval 降级
  告警，最后到达 `HIT GOOD TRAP`。

## 运行

端口级探针（自动生成 NPC RTL 和 ysyxSoCFull Verilog）：

```bash
make -C npc/csrc/Test/fullsoc-fp-debug-test probe
```

full-SoC DiffTest（先构建 `rv32_f_defconfig` 的 full-SoC 仿真器）：

```bash
make -C npc rv32_f_defconfig
make -C npc SIM_TOP=soc build/ysyx_26030103
make -C npc/csrc/Test/fullsoc-fp-debug-test diff \
  REF=/path/to/riscv32-nemu-interpreter-so
```

## 覆盖边界

本测试只覆盖端口连通性、FPR/fcsr 逐退休比对和一次非零 tval 注入，
不重复 `fpu-difftest-test` 的完整 F/D 算术、舍入、NaN、FS trap 语义。
完整 FPU guest 在 full-SoC 上仍需单独运行，属于剩余验证项。
