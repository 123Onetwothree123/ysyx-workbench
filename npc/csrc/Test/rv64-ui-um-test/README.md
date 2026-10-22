# RV64UI/RV64UM 本地定向小子集

本目录是仓库内的本地裸机回归，不是外部 `riscv-tests` /
`riscv-arch-test` 的移植或替代：

- 当前仓库中的 `riscv-tests`、`riscv-tests-am`、`riscv-arch-test-am`
  是未初始化的空子模块，没有可直接接入的 RV64 用例或构建入口。
- `main.S` 只覆盖最终收尾需要的最小 RV64UI/RV64UM 指令语义：
  64 位加减/移位（含 `shamt[5]`）、`*W` 指令的 32 位运算与符号扩展、
  有/无符号比较、`LUI/AUIPC/JAL/JALR`、条件分支、`LB/LH/LW/LD/LBU/LHU/LWU`、
  `SB/SH/SW/SD`，以及 `MUL/MULH/MULHSU/MULHU/DIV/DIVU/REM/REMU`
  和对应的 `*W` 指令。
- 运行方式：

  ```bash
  make -C npc/csrc/Test/rv64-ui-um-test run
  ```

  默认使用 `npc/build/ysyx_26030103_npc`；`rv64_soft_regression.sh`
  会先构建 `rv64_soft_defconfig`，再把本镜像作为独立步骤运行。

外部 arch-test 的完整覆盖仍未接入，报告时不得把本测试计为外部
arch-test 结果。
