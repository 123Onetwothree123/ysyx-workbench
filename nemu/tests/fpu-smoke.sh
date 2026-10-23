#!/usr/bin/env bash
# NEMU RV32/RV64 F/D 扩展回归。
#
# 覆盖：
#   1. 手写 RV64D / RV32FD / RV32F 硬浮点镜像（FMA、NaN、舍入、flags、
#      NaN boxing、mstatus.FS、trap/恢复、非对齐 FLD/FSD）；
#   2. GCC -march=rv{64,32}imafd + -mabi=lp{64,32}d 源程序；
#   3. SDB 浮点寄存器/CSR、ITRACE/iringbuf/disasm、readelf 浮点 ABI；
#   4. DiffTest REF 导出与可选 freg/fcsr ABI（含旧 REF 降级）；
#   5. 默认 RISCV_F/D=off 及已有 RV32/RV64/C/A/M 冒烟回归。
#
# 用法：nemu/tests/fpu-smoke.sh
#       SKIP_REGRESSION=1 nemu/tests/fpu-smoke.sh   # 只跑 F/D 部分
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NEMU_HOME=$ROOT/nemu
SRC=$NEMU_HOME/tests/fpu
TEST_BUILD=$NEMU_HOME/build/fpu-tests
CC64=${CROSS64:-riscv64-unknown-elf-}gcc
CC32=${CROSS32:-riscv32-unknown-elf-}gcc
OBJCOPY64=${CROSS64:-riscv64-unknown-elf-}objcopy
OBJCOPY32=${CROSS32:-riscv32-unknown-elf-}objcopy
mkdir -p "$TEST_BUILD"
# Capstone 以相对路径 tools/capstone/... 加载，必须在 nemu/ 目录运行。
cd "$NEMU_HOME"

build_nemu() {
  local config=$1
  make -C "$NEMU_HOME" NEMU_HOME="$NEMU_HOME" \
    ARCH=riscv64-nemu AM_HOME="$ROOT/abstract-machine" "$config" >/dev/null
  make -C "$NEMU_HOME" NEMU_HOME="$NEMU_HOME" -j"$(nproc)" >/dev/null
}

build_asm() {
  local cc=$1 objcopy=$2 name=$3 march=$4 abi=$5 src=$6
  "$cc" -march="$march" -mabi="$abi" -nostdlib -nostartfiles \
    -Ttext=0x80000000 -Wl,--no-relax -o "$TEST_BUILD/$name.elf" "$src"
  "$objcopy" -O binary "$TEST_BUILD/$name.elf" "$TEST_BUILD/$name.bin"
}

build_c() {
  local cc=$1 objcopy=$2 name=$3 march=$4 abi=$5
  "$cc" -march="$march" -mabi="$abi" -mcmodel=medany -O2 \
    -ffreestanding -fno-builtin -fno-math-errno -fno-trapping-math \
    -nostdlib -nostartfiles -T "$SRC/fpu.ld" -Wl,--no-relax \
    -o "$TEST_BUILD/$name.elf" "$SRC/gcc-start.S" "$SRC/gcc-fp.c" \
    2>"$TEST_BUILD/$name.link.log"
  "$objcopy" -O binary "$TEST_BUILD/$name.elf" "$TEST_BUILD/$name.bin"
}

run_good() {
  local nemu=$1 name=$2
  "$nemu" -b --elf="$TEST_BUILD/$name.elf" "$TEST_BUILD/$name.bin" \
    >"$TEST_BUILD/$name.log" 2>&1
  grep -q "HIT GOOD TRAP" "$TEST_BUILD/$name.log"
}

echo "[1/6] RV64 D 指令与 GCC lp64d 源程序"
build_nemu riscv64-fd_defconfig
NEMU64=$NEMU_HOME/build/riscv64-nemu-interpreter
build_asm "$CC64" "$OBJCOPY64" rv64d rv64imafdc_zicsr lp64d "$SRC/rv64d.S"
run_good "$NEMU64" rv64d
echo "  PASS rv64d.S（F/D、FMA、NaN、rounding、flags、FS/trap）"
build_c "$CC64" "$OBJCOPY64" gcc64 rv64imafd_zicsr lp64d
run_good "$NEMU64" gcc64
echo "  PASS gcc64 (-march=rv64imafd -mabi=lp64d)"

echo "[2/6] SDB / ITRACE / iringbuf / readelf"
printf 'si 80\nq\n' | "$NEMU64" --elf="$TEST_BUILD/gcc64.elf" "$TEST_BUILD/gcc64.bin" \
  >"$TEST_BUILD/gcc64-si.log" 2>&1
grep -Eq 'fld|fadd\.d|fsqrt\.d' "$TEST_BUILD/gcc64-si.log"
printf 'c\np $ft0\np $fa3\np $fcsr\np $fflags\np $frm\ninfo r\nq\n' |
  "$NEMU64" --elf="$TEST_BUILD/gcc64.elf" "$TEST_BUILD/gcc64.bin" \
  >"$TEST_BUILD/gcc64-sdb.log" 2>&1
grep -q 'fcsr=' "$TEST_BUILD/gcc64-sdb.log"
grep -q '浮点寄存器（FLEN=64' "$TEST_BUILD/gcc64-sdb.log"
grep -q '| f31' "$TEST_BUILD/gcc64-sdb.log"
printf 'c\niringbuf\nq\n' | "$NEMU64" --elf="$TEST_BUILD/gcc64.elf" \
  "$TEST_BUILD/gcc64.bin" >"$TEST_BUILD/gcc64-iring.log" 2>&1
grep -Eq 'fadd\.d|fld' "$TEST_BUILD/gcc64-iring.log"
printf 'readelf -h\nreadelf -s\nq\n' | "$NEMU64" \
  --elf="$TEST_BUILD/gcc64.elf" "$TEST_BUILD/gcc64.bin" \
  >"$TEST_BUILD/gcc64-readelf.log" 2>&1
grep -q 'double-float' "$TEST_BUILD/gcc64-readelf.log"
grep -q 'main' "$TEST_BUILD/gcc64-readelf.log"
echo "  PASS SDB f 寄存器/fcsr、ITRACE 浮点反汇编、readelf double-float"

echo "[3/6] RV32 F-only 指令"
build_nemu riscv32-f_defconfig
NEMU32F=$NEMU_HOME/build/riscv32-nemu-interpreter
build_asm "$CC32" "$OBJCOPY32" rv32f rv32imafc_zicsr ilp32f "$SRC/rv32f.S"
run_good "$NEMU32F" rv32f
echo "  PASS rv32f.S（F-only、FMA、NaN、非法 D 编码陷入）"

echo "[4/6] RV32 D 指令与 GCC ilp32d 源程序"
build_nemu riscv32-fd_defconfig
NEMU32FD=$NEMU_HOME/build/riscv32-nemu-interpreter
build_asm "$CC32" "$OBJCOPY32" rv32d rv32imafdc_zicsr ilp32d "$SRC/rv32d.S"
run_good "$NEMU32FD" rv32d
echo "  PASS rv32d.S（RV32 FLD/FSD 拆分、F/D、FMA、NaN、rounding）"
build_c "$CC32" "$OBJCOPY32" gcc32 rv32imafd_zicsr ilp32d
run_good "$NEMU32FD" gcc32
echo "  PASS gcc32 (-march=rv32imafd -mabi=ilp32d)"

echo "[5/6] DiffTest REF 导出与可选 freg/fcsr ABI"
build_nemu riscv64-fd-ref_defconfig
REF64=$NEMU_HOME/build/riscv64-nemu-interpreter-so
cp "$REF64" "$TEST_BUILD/riscv64-fd-ref.so"
for sym in difftest_state_size difftest_fp_abi_version \
           difftest_fp_state_size difftest_fp_regcpy; do
  nm -D --defined-only "$REF64" | grep -q " $sym$"
done
build_nemu riscv32-fd-ref_defconfig
REF32=$NEMU_HOME/build/riscv32-nemu-interpreter-so
for sym in difftest_fp_abi_version difftest_fp_state_size difftest_fp_regcpy; do
  nm -D --defined-only "$REF32" | grep -q " $sym$"
done
echo "  PASS RV32/RV64 REF 导出 freg/fcsr ABI 符号"

build_nemu riscv64-fd-diff_defconfig
NEMU_DIFF=$NEMU_HOME/build/riscv64-nemu-interpreter
"$NEMU_DIFF" -b -d "$TEST_BUILD/riscv64-fd-ref.so" \
  --elf="$TEST_BUILD/gcc64.elf" "$TEST_BUILD/gcc64.bin" \
  >"$TEST_BUILD/diff64.log" 2>&1
grep -q "浮点状态 ABI: .*ON" "$TEST_BUILD/diff64.log"
grep -q "HIT GOOD TRAP" "$TEST_BUILD/diff64.log"
echo "  PASS NEMU DUT + NEMU REF 逐条比对 freg/fcsr"

# 旧 REF 不导出浮点 ABI：整数状态仍必须正常比对，并明确降级。
build_nemu riscv64-ref_defconfig
OLD_REF=$NEMU_HOME/build/riscv64-nemu-interpreter-so
cp "$OLD_REF" "$TEST_BUILD/riscv64-old-ref.so"
if nm -D --defined-only "$OLD_REF" | grep -q difftest_fp_regcpy; then
  echo "  ERROR: 无 F/D 的 REF 不应导出浮点 ABI" >&2
  exit 1
fi
printf 'c\nq\n' | "$NEMU_DIFF" -b -d "$TEST_BUILD/riscv64-old-ref.so" \
  >"$TEST_BUILD/diff-old.log" 2>&1
grep -q "未导出浮点状态 ABI" "$TEST_BUILD/diff-old.log"
grep -q "HIT GOOD TRAP" "$TEST_BUILD/diff-old.log"
echo "  PASS 旧 REF 自动降级为仅整数比对"

echo "[6/6] 默认配置与现有回归"
build_nemu riscv64_defconfig
if grep -q '^CONFIG_RISCV_F=y' "$NEMU_HOME/.config"; then
  echo "  ERROR: 默认配置不应打开 RISCV_F" >&2
  exit 1
fi
if grep -q '^CONFIG_RISCV_D=y' "$NEMU_HOME/.config"; then
  echo "  ERROR: 默认配置不应打开 RISCV_D" >&2
  exit 1
fi
if [ "${SKIP_REGRESSION:-0}" != "1" ]; then
  bash "$NEMU_HOME/tests/rvc-smoke.sh"
else
  echo "  SKIP rvc-smoke/rv64-smoke（SKIP_REGRESSION=1）"
fi

echo "F/D 全部测试通过"
