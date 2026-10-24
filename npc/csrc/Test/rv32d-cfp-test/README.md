# RV32D + C.FP 回归

本目录补齐 RV32D 的两个硬件缺口：

1. **RV32 的 8 字节 FLD/FSD 访存**：XLEN=32、FLEN=64 时，LSU 仍以
   32 位 AXI 数据 beat 工作，但把 8 字节访问拆成两拍窄传输；DCache
   对外提供 64 位 load/store 数据，写缓冲保存高 32 位，访问故障按
   第二拍响应精确提交。
2. **压缩浮点访存指令**：RV32 的 C.FLD/C.FSD/C.FLW/C.FSW 及四种 SP
   形式按 F/D 开关和 XLEN 展开；RV64 同编码位的 C.LD/C.SD 保持整数
   语义，F/D 关闭时浮点压缩编码仍是非法指令。

## 文件

| 文件 | 说明 |
| --- | --- |
| `guest_rv32d_cfp.S` | `gcc -march=rv32gc -mabi=ilp32d` 裸机镜像：C.FLD/C.FSD/C.FLW/C.FSW 与全部 SP 形式、最大立即数偏移、FLW NaN-box、D 算术、fcsr/fflags、mstatus.FS、FS=Off illegal、FLD 非对齐 trap |
| `guest_rv64d_cfp.S` | `gcc -march=rv64gc -mabi=lp64d` 裸机镜像：C.FLD/C.FSD 与 SP 形式、RV64 同编码位 C.LD/C.SD、D 算术、FS 与 FLD 非对齐 trap |
| `audit_cfp.py` | 直接解析 ELF 中的 16 位 RVC 编码，统计 C.FLD/C.FSD/C.FLW/C.FSW 及 SP 形式；不依赖反汇编器助记符 |
| `run.sh` | 用对应 XLEN 的 NEMU REF 跑 `--diff`，要求 `DiffTest: final state PASS` 且 guest `HIT GOOD TRAP` |

## 构建与运行

```bash
# RV32D defconfig 与 direct-NPC
make -C npc rv32_fd_defconfig
make -C npc verilog
make -C npc build/ysyx_26030103_npc

# 用本仓库 NEMU 构建 RV32D / RV64D REF
make -C npc/csrc/Test/rv32d-cfp-test ref32 ref64

# RV32D+C.FP DiffTest（默认 test 目标）
make -C npc/csrc/Test/rv32d-cfp-test test

# RV64 C.FP 需要单独的 RV64 DUT，例如：
make -C npc BUILD_DIR=build-rv64 rv64_fd_defconfig
make -C npc BUILD_DIR=build-rv64 verilog
make -C npc BUILD_DIR=build-rv64 build-rv64/ysyx_26030103_npc
make -C npc/csrc/Test/rv32d-cfp-test test-rv64
```

日志默认写到 `/tmp/opencode/rv32d-cfp`；`make clean` 只删除本目录的
`build/`，不删除 NEMU 配置或日志。

## 当前 DiffTest 范围

NPC 现有 DiffTest ABI 只比较 GPR/PC/CSR；浮点结果、fcsr/fflags、
mstatus.FS 和内存内容由 guest 自己在 `HIT GOOD TRAP` 前逐项检查。
本任务没有修改 FPR DiffTest ABI，避免与并行任务冲突。
