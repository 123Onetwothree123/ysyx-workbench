# fpu-difftest-test

RV32F / RV64D 的 `csrc/Test` 风格裸机语义测试、NPC `--diff` 回归和
`RISCV_F/D` 配置负向测试。

本目录只新增测试资产与运行脚本，**不修改 NPC/NEMU 核心 RTL 或参考模型**。
F/D 尚未实现时，正向用例明确打印 `[SKIP]`，不会用软浮点、空操作或
“只比较整数寄存器”伪造 PASS；实现合入后使用 `STRICT=1` 把 SKIP 升级
为 FAIL。

## 用例

| 文件 | 覆盖 |
| --- | --- |
| `guest_rv64d.S` | RV64D 全量裸机：FLW/FSW/FLD/FSD、FADD/FSUB/FMUL/FDIV/FSQRT、FMADD/FMSUB/FNMSUB/FNMADD、FMIN/FMAX（±0、qNaN、sNaN）、FSGNJ/FSGNJN/FSGNJX、FEQ/FLT/FLE、FCVT.W/WU/L/LU、FCVT.S.D/D.S（溢出、下溢）、FMV.X.D/FMV.D.X、FCLASS、NaN 规范化、五种舍入模式、动态 `frm`、sticky flags、`mstatus.FS`、小端 FLEN=64 检查 |
| `guest_rv32f.S` | RV32F 对应子集：FLW/FSW、FADD/FSUB/FMUL/FDIV/FSQRT、FMA、FMIN/FMAX、FSGNJ、比较、FCVT.W/WU、FMV.X.W/FMV.W.X、NaN、舍入、flags、FS；不包含 D 指令 |
| `guest_f_trap.S` | RV32F/RV64D 共用：`mstatus.FS=0` 时 FADD 与 FLW 必须 illegal；检查 `mcause=2`、`mepc` 精确指向故障指令、`MPIE/MPP`；trap handler 中置 FS=3 后可访问 f8/fcsr 和继续执行浮点；`mret` 后 FS 保持 Dirty |
| `guest_fpu_probe.S` | F/D 支持探针。区分 RISCV_F=n（code 21）与 F=y/D=n（code 22）；计算错误 code 30/31、FS 未变 Dirty code 23、D 有而 F 无 code 25、错误的 trap 类型 code 40 |
| `guest_rv64d_c.c` | GCC `-march=rv64imafd -mabi=lp64d` 硬浮点 C 程序；覆盖 double/float 算术、比较、FCVT、内联 FMADD/FCLASS、fcsr flags、qNaN |
| `guest_crt0.S` / `guest_c.ld` | C 程序的裸机 `_start`、静态栈和段布局 |
| `ref_probe.cpp` | 直接 dlopen NEMU REF，注入 FADD.S/FADD.D，判断 REF 是否已实现 F/D；避免把 REF 缺失误报为 DUT 分叉 |
| `corrupt_ref.cpp` | 包装真实 REF，读取状态时给 `x5/t0` 注入 +1，验证第一次分叉报告 |
| `audit.sh` | 检查 C 镜像为 lp64d 硬浮点、反汇编含 F/D 指令、没有 `__adddf3` 等 libgcc 软浮点符号；同时审计 RV64D/RV32F 汇编镜像 |

退出约定沿用 `csrc/Test` 裸机镜像：`a0==0` + `.word 0x0000000b`
（custom-0 仿真 halt）为 GOOD；失败时 `a0=编号` + 同一 halt 指令。
每个 `failN` 使用独立编号，DiffTest 第一次分叉或 BAD TRAP code 可直接
定位测试点。

## 运行

构建镜像和审计（不需要 NPC/REF）：

```bash
make -C npc/csrc/Test/fpu-difftest-test guests audit
```

RV64D 正向 DiffTest（DUT 与 REF 必须同 XLEN，且都启用 F/D）：

```bash
make -C npc/csrc/Test/fpu-difftest-test rv64 \
    BIN_RV64=/path/to/rv64-npc \
    REF_RV64=/path/to/riscv64-nemu-interpreter-so
```

RV32F 正向 DiffTest：

```bash
make -C npc/csrc/Test/fpu-difftest-test rv32 \
    BIN_RV32=/path/to/rv32-npc \
    REF_RV32=/path/to/riscv32-nemu-interpreter-so
```

负向 `RISCV_F=n` / `RISCV_D=n`（必须显式给禁用 F 或 D 的 DUT）：

```bash
# F 和 D 都关闭：F/D 指令都必须 illegal
make -C npc/csrc/Test/fpu-difftest-test negative \
    NEG_XLEN=64 BIN_NEG=/path/to/rv64-npc-no-f

# 只有 F、D 关闭：F 正常，D 指令 illegal
make -C npc/csrc/Test/fpu-difftest-test negative \
    NEG_XLEN=64 BIN_NEG=/path/to/rv64-npc-f-only
```

第一次分叉报告自检：

```bash
make -C npc/csrc/Test/fpu-difftest-test mismatch \
    MISMATCH_XLEN=64 \
    BIN_MISMATCH=/path/to/rv64-npc \
    REF_MISMATCH=/path/to/riscv64-nemu-interpreter-so
```

配置错误检查（`RISCV_D=y` 无 F、F+D 且 FLEN=32 必须明确失败）：

```bash
make -C npc/csrc/Test/fpu-difftest-test config-errors \
    NPC_DIR=/path/to/npc
```

`config-errors` 会把 `NPC_DIR` 复制到 `/tmp/opencode/fpu-difftest/config.*`，
在副本里生成 `rv64_c_defconfig` 并调用 `make verilog`，不会污染仓库。
默认 `CONFIG_CHECK=auto`；`CONFIG_CHECK=0` 跳过，`CONFIG_CHECK=1` 强制执行。
当前 Makefile 已把后端/位宽变量命名为 `FPU_IMPL`、`FPU_FLEN`；本测试同时
接受任务描述里的 `FLEN=32` 名称，便于后续实现对齐。

默认 `make test` 读取当前 `npc/.config` 的 XLEN，跑该 XLEN 的正向 +
负向 + 配置错误；没有 `.config` 时 `xlen.mk` 回退为 RV32，建议显式执行
`rv64`/`rv32`：

```bash
make -C npc/csrc/Test/fpu-difftest-test test
make -C npc/csrc/Test/fpu-difftest-test test-all   # 两套 XLEN 资产都提供时
make -C npc/csrc/Test/fpu-difftest-test test STRICT=1
```

`BIN_RV32/BIN_RV64` 缺省为 `npc/build/ysyx_26030103_npc`；
`REF_RV32/REF_RV64` 缺省为 `nemu/build/riscv{32,64}-nemu-interpreter-so`。
文件不存在时按 `[SKIP]` 处理（`STRICT=1` 时为 FAIL）。

## 结果语义

- `[PASS]`：断言成立；
- `[SKIP]`：前提不具备（DUT/REF 尚未实现 F/D、缺少对应 XLEN 资产、
  或负向用例拿到已启用 F/D 的 DUT）；不会伪造 PASS；
- `[FAIL]`：行为错误，或前提具备但 DiffTest 第一条分叉、浮点结果错误、
  trap 语义错误。

正向流程：

1. 在 DUT 上跑 `guest_fpu_probe.S`。F/D 指令明确 illegal 时 SKIP；
   计算结果错误、FS 不生效或 trap 类型错误时 FAIL。
2. 用 `ref_probe.cpp` 检查 NEMU REF 是否支持 F/D。REF 未实现时 SKIP。
3. 依次对正向语义镜像、FS/trap 镜像和 GCC C 镜像执行 `--diff`：
   - 要求日志包含 `DiffTest: ON`；
   - 出现 `DiffTest 校验失败` 时打印第一次分叉报告并 FAIL；
   - 要求 `DiffTest: final state PASS` 且 guest 到达 `HIT GOOD TRAP`。

所有浮点期望值最终都转换成整数寄存器比较；即使 REF 暂未比较 FPR，
DUT 的浮点结果错误也会通过 GPR/PC 暴露。实现 DiffTest FPR 快照后，
还会额外比较 f0–f31/fcsr。

## 设备/MMIO 与超时

- 所有 guest 只访问 `0x80000000` 起始的连续 RAM，不读 RTC/mtime、
  不访问 UART/PLIC 等 MMIO；因此不需要为 FPU 测试新增非确定性规则。
- 现有 `DifftestStep` 已对 MMIO 和 `mcycle` 在精确退休边界注入 DUT
  结果；脚本不要求 `injected` 计数为 0，也不会把正常的注入提示当失败。
- 探针 60s，DiffTest 120s，配置检查 300s（另有 180s defconfig 超时）。
- 日志写到 `/tmp/opencode/fpu-difftest`，不进入仓库。

## 与实现代理的接口约定

1. NPC：
   - Kconfig/elaboration 读取 `RISCV_F`、`RISCV_D`、`FPU_FLEN`、
     `FPU_IMPL`；`RISCV_D=y` 时 `RISCV_F` 必须为 y，`FPU_FLEN` 必须为 64；
   - 实现 f0–f31、fcsr（fflags/frm）、`mstatus.FS`；
   - `RISCV_F=n` 时 F/D 编码必须精确触发 `cause=2`，不能被当作整数
     指令或静默 NOP；FS=0 时 F 指令和浮点 load/store 同样 illegal；
   - trap 必须精确写 `mepc`，并保持 `MPIE/MPP`；handler 中可用 CSR 打开
     FS 后继续使用浮点寄存器；
   - DiffTest 状态 ABI：现有 `GPR[32] + PC` 前缀保持不变；FP 状态建议
     使用 NEMU 已约定的可选符号
     `difftest_fp_abi_version` / `difftest_fp_state_size` /
     `difftest_fp_regcpy`，或追加到状态尾部并在启动时协商 size；
   - 失败报告继续复用 `DifftestReport`，包含 PC、DUT 指令、GPR 差异。
2. NEMU REF：
   - F/D 译码、f0–f31、fcsr、FS 门控、NaN 规范化和完整舍入/flags；
   - `difftest_state_size()`/`difftest_csr_state_size()` 与 DUT XLEN
     匹配；`mstatus` CSR 快照包含 FS/SD 语义；
   - 建议导出上述可选 FP 状态 ABI，使 DiffTest 能逐浮点寄存器比对；
   - `difftest_fp_regcpy` 的 `freg` 使用固定 64 位槽位，RV32F 高 32 位为 0。
3. 配置错误：
   - `RISCV_D=y` 且 `RISCV_F=n` 必须在 elaboration 阶段明确拒绝；
   - `RISCV_F=y RISCV_D=y FPU_FLEN=32` 必须明确拒绝；
   - 错误信息应包含 `RISCV_D`/`RISCV_F`/`FPU_FLEN` 等关键字，便于测试
     区分真实规则与“占位未实现”错误。

## 当前状态（HardFloat 后端接入后）

- 镜像构建和 ELF 审计：PASS；
- RV64D 正向 DiffTest：PASS（`guest-rv64d`、`guest-f-trap-rv64d`、
  GCC `-march=rv64imafd -mabi=lp64d` 的 `guest-rv64d-c` 全部
  `HIT GOOD TRAP`，且逐退休 GPR/PC/CSR 比对无分叉）；
- RV32F 正向 DiffTest：PASS（`guest-rv32f`、`guest-f-trap-rv32f`）；
- F/D 探针：PASS（F/D 均可执行，首条浮点指令后 `mstatus.FS` 进入
  Dirty）；
- `RISCV_F=n` 负向与配置错误检查：本分支未改动相关规则，沿用主线
  测试；本报告只记录本次实际运行的正向用例；
- DUT 的 `--diff` 目前比较 GPR/PC/CSR，NEMU REF 已导出可选的
  `difftest_fp_*` ABI，但 NPC 尚未接入逐 f 寄存器快照；浮点结果由
  guest 写回 RAM 后以整数比较和 C 程序断言覆盖。

测试资产同时修正了三处自身错误：probe 的 8 字节 scratch 对齐、
`fsflags` 是 CSRRW 而不是 OR、以及 `fcvt.lu.d` 测试常量笔误。

## 清理

```bash
make -C npc/csrc/Test/fpu-difftest-test clean       # 删除 build/
make -C npc/csrc/Test/fpu-difftest-test clean-logs  # 删除 /tmp/opencode/fpu-difftest
```
