# rvc-difftest-test

RV32C / RV64C 的裸机 DiffTest 回归，以及 `RISCV_C=n` 负向测试。

本目录只放测试资产与运行脚本；NPC/NEMU 的核心实现由对应实现代理维护。
测试不会为了让 RVC 用例“跑起来”而关闭 C，也不会在缺少 RVC 支持时伪造
PASS：探针检测到 `c_site` 明确 illegal 时，正向用例打印 `[SKIP]`；合入后
在 CI 使用 `STRICT=1` 可把 SKIP 升级为失败。

## 用例

| 镜像 | 覆盖 |
| --- | --- |
| `guest_rv64c.S` | 16/32 位混合；`C.ADDI`、`C.LW`、`C.SW`、`C.LD`、`C.SD`、`C.J`、`C.JALR`、`C.BEQZ`、`C.BNEZ`；相邻 cache line / 跨页数据；`0x800003fe` 处 32 位指令跨 16/32/64B line；`0x80001ffe` 处 32 位指令跨 4KiB 页；`ecall` trap（`mcause=11`、`mepc`、`mret`） |
| `guest_rv32c.S` | RV32C 对应子集（无 `C.LD/C.SD`，其余相同） |
| `guest_c_probe.S` | RVC 支持探针 / 负向用例。第一条 C 指令为 `c.addi s1,1`，效果可观测 |
| `corrupt_ref.cpp` | 包装真实 NEMU REF，读取 REF 状态时给 `x5/t0` 注入 +1，验证第一次分叉报告 |

退出约定沿用 `csrc/Test` 裸机镜像：`a0==0` + `.word 0x0000000b`
（custom-0 仿真 halt）为 GOOD TRAP；失败时 `a0=编号` + 同一 halt 指令。
注意标准 `ebreak` 是架构 breakpoint 异常，不能当仿真 halt 使用。

探针结果码：

- `GOOD`：C 指令被正确执行，DUT 已启用 RVC；
- `BAD code=42`：`c_site` 触发 `cause=2`（illegal instruction），说明 DUT
  的 `RISCV_C=n`。正向用例 `[SKIP]`，负向用例 `[PASS]`；
- `BAD code=43`：没有 trap 但 `s1` 没有被 `c.addi` 改变，说明 16 位指令
  被静默跳过或错误执行。正向 `[FAIL]`，负向 `[FAIL]`；
- `BAD code=41/45`：trap cause 或 `mepc` 不符合 `c_site illegal` 语义，
  属于部分实现/译码错误，`[FAIL]`。

## 运行

先构建镜像：

```bash
make -C npc/csrc/Test/rvc-difftest-test guests
```

正向 DiffTest（DUT 与 REF 必须同 XLEN，且都实现 C 扩展）：

```bash
# RV64
make -C npc/csrc/Test/rvc-difftest-test rv64 \
    BIN_RV64=/path/to/rv64-npc \
    REF_RV64=/path/to/riscv64-nemu-interpreter-so

# RV32
make -C npc/csrc/Test/rvc-difftest-test rv32 \
    BIN_RV32=/path/to/rv32-npc \
    REF_RV32=/path/to/riscv32-nemu-interpreter-so
```

负向 `RISCV_C=n`（必须显式给无 C 的 DUT）：

```bash
make -C npc/csrc/Test/rvc-difftest-test negative \
    NEG_XLEN=64 BIN_NOC=/path/to/rv64-npc-no-c

make -C npc/csrc/Test/rvc-difftest-test negative \
    NEG_XLEN=32 BIN_NOC=/path/to/rv32-npc-no-c
```

第一次分叉报告自检（任意 ABI 匹配的 DUT + 真实 REF，不要求 DUT 有 C）：

```bash
make -C npc/csrc/Test/rvc-difftest-test mismatch \
    MISMATCH_XLEN=64 \
    BIN_MISMATCH=/path/to/rv64-npc \
    REF_MISMATCH=/path/to/riscv64-nemu-interpreter-so
```

默认 `make test` 读取当前 `npc/.config` 的 XLEN，跑该 XLEN 的正向 + 负向：

```bash
make -C npc/csrc/Test/rvc-difftest-test test
make -C npc/csrc/Test/rvc-difftest-test test-all   # 两套 XLEN 资产都提供时
make -C npc/csrc/Test/rvc-difftest-test test STRICT=1
```

`BIN_RV32/BIN_RV64` 缺省为 `npc/build/ysyx_26030103_npc`；
`REF_RV32/REF_RV64` 缺省为 `nemu/build/riscv{32,64}-nemu-interpreter-so`。
文件不存在时按 `[SKIP]` 处理（`STRICT=1` 时为 FAIL）。

## 运行约束

- `--diff` 正向超时 120s，探针/负向 60s；
- 日志写到 `/tmp/opencode/rvc-difftest`，不进入仓库；
- NPC 需要 `CONFIG_DIFFTEST=y`；`--batch` 由脚本按构建是否启用 SDB 自动
  探测（未启用 SDB 的构建会自动去掉 `--batch` 重跑）；
- 正向测试要求 DUT `CONFIG_RISCV_C=y`（elaboration 环境变量
  `RISCV_C=y`），REF 也必须启用 C 译码；否则第一个 C 指令就会分叉，
  脚本会打印第一次分叉报告并 FAIL；
- 支持 ABI：REF 的 `difftest_state_size()` 必须与 DUT XLEN 一致；
  RV32 132 字节 / RV64 264 字节。

## 与实现代理的接口约定

1. NPC：
   - Kconfig 保持 `CONFIG_RISCV_C`，elaboration 读取 `RISCV_C=y`；
   - C 指令必须按 2 字节粒度退休，`debug_pc`/`debug_next_pc` 与
     DiffTest 状态中的 PC 都要保留 +2 语义；
   - `RISCV_C=n` 时 16 位编码必须精确触发 illegal instruction，不能当作
     32 位指令的一部分静默跳过。
2. NEMU REF：
   - 能译码/执行 RV32C/RV64C，并支持 `difftest_get_last_instruction`
     （可选；缺失只影响报告中的 REF 指令显示）；
   - `difftest_state_size()` 与目标 XLEN 的状态结构一致，`difftest_trap`
     能注入 `ecall`（tval=0）的 cause/epc；
   - 建议用与 DUT 相同的 `CONFIG_RISCV_C` 宏/配置构建，避免两边 ISA 不一致。

## 清理

```bash
make -C npc/csrc/Test/rvc-difftest-test clean       # 删除 build/（镜像、shim）
make -C npc/csrc/Test/rvc-difftest-test clean-logs  # 删除 /tmp/opencode/rvc-difftest
```
