#!/usr/bin/env bash
# RV32C/RV64C 定向回归。覆盖 16/32 位混合取指、跨页/跨内存边界、
# 非法编码、trap/CSR 低位语义、GCC -march=rv{32,64}imac 镜像，
# 以及默认未开启 RVC 时的负向检查。
#
# 用法：nemu/tests/rvc-smoke.sh
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NEMU_HOME=$ROOT/nemu
TEST_BUILD=$NEMU_HOME/build/rvc-tests
SRC=$NEMU_HOME/tests/rvc
CROSS32=${CROSS32:-riscv32-unknown-elf-}
CROSS64=${CROSS64:-riscv64-unknown-elf-}
mkdir -p "$TEST_BUILD"
# Capstone 以相对路径 tools/capstone/... 加载，必须在 nemu/ 目录运行。
cd "$NEMU_HOME"

build_nemu() {
  local config=$1
  make -C "$NEMU_HOME" NEMU_HOME="$NEMU_HOME" \
    ARCH=riscv64-nemu AM_HOME="$ROOT/abstract-machine" "$config" >/dev/null
  make -C "$NEMU_HOME" NEMU_HOME="$NEMU_HOME" -j"$(nproc)" >/dev/null
}

# build_test <cross-prefix> <arch> <abi> <name> <sources...>
build_test() {
  local cross=$1 arch=$2 abi=$3 name=$4
  shift 4
  "${cross}gcc" -march="$arch" -mabi="$abi" -Os \
    -nostdlib -nostartfiles -I "$SRC" -Ttext=0x80000000 \
    -Wl,--no-relax -o "$TEST_BUILD/$name.elf" "$@"
  "${cross}objcopy" -O binary "$TEST_BUILD/$name.elf" "$TEST_BUILD/$name.bin"
}

build_fetch_test() {
  local cross=$1 arch=$2 abi=$3 name=$4 src=$5
  "${cross}gcc" -march="$arch" -mabi="$abi" -Os \
    -nostdlib -nostartfiles -I "$SRC" -T "$SRC/rvc-fetch.ld" \
    -Wl,--no-relax -o "$TEST_BUILD/$name.elf" "$src"
  "${cross}objcopy" -O binary "$TEST_BUILD/$name.elf" "$TEST_BUILD/$name.bin"
}

# run_good <nemu> <elf> <bin> <log> [extra args...]
run_good() {
  local nemu=$1 elf=$2 bin=$3 log=$4
  shift 4
  "$nemu" -b --elf="$elf" "$@" "$bin" >"$log" 2>&1
  grep -q "HIT GOOD TRAP" "$log"
}

check_itrace_width() {
  local log=$1
  # 16 位指令的 ITRACE 行恰好有两个机器码字节，并且 capstone 识别为 c.*
  grep -Eq '^0x[0-9a-f]+: [0-9a-f]{2} [0-9a-f]{2}[[:space:]]+c\.[a-z]' "$log"
  # 同时必须存在正常的 32 位指令行（四个机器码字节）
  grep -Eq '^0x[0-9a-f]+: [0-9a-f]{2} [0-9a-f]{2} [0-9a-f]{2} [0-9a-f]{2}[[:space:]]' "$log"
}

check_compressed_image() {
  local elf=$1
  local counts
  counts=$("${CROSS_OBJDUMP}" -d "$elf" | awk '/^[[:space:]]*[0-9a-f]+:/ {print $2}' |
    awk '{ if (length($0) == 4) c++; else if (length($0) == 8) n++ }
         END { print c+0, n+0 }')
  set -- $counts
  [ "$1" -gt 0 ] && [ "$2" -gt 0 ]
}

echo "[1/5] 构建 RV64C NEMU 并运行定向测试"
build_nemu riscv64-rvc_defconfig
NEMU64=$NEMU_HOME/build/riscv64-nemu-interpreter
build_test "$CROSS64" rv64imac lp64 rv64c "$SRC/rv64c.S"
run_good "$NEMU64" "$TEST_BUILD/rv64c.elf" "$TEST_BUILD/rv64c.bin" "$TEST_BUILD/rv64c.log"
check_itrace_width "$TEST_BUILD/rv64c.log"
echo "  PASS rv64c"
build_test "$CROSS64" rv64imac lp64 illegal64 "$SRC/rvc-illegal.S"
run_good "$NEMU64" "$TEST_BUILD/illegal64.elf" "$TEST_BUILD/illegal64.bin" "$TEST_BUILD/illegal64.log"
echo "  PASS illegal64"
build_fetch_test "$CROSS64" rv64imac lp64 fetch64 "$SRC/rvc-fetch.S"
run_good "$NEMU64" "$TEST_BUILD/fetch64.elf" "$TEST_BUILD/fetch64.bin" "$TEST_BUILD/fetch64.log"
echo "  PASS fetch64"
build_test "$CROSS64" rv64imac lp64 gcc64 "$SRC/rvc-gcc-start.S" "$SRC/rvc-gcc.c"
CROSS_OBJDUMP="${CROSS64}objdump"
check_compressed_image "$TEST_BUILD/gcc64.elf"
run_good "$NEMU64" "$TEST_BUILD/gcc64.elf" "$TEST_BUILD/gcc64.bin" "$TEST_BUILD/gcc64.log"
echo "  PASS gcc64 (-march=rv64imac)"

echo "[2/5] RV64 ftrace / readelf / SDB / iringbuf"
printf 'ftrace on\nc\n' | "$NEMU64" --elf="$TEST_BUILD/rv64c.elf" "$TEST_BUILD/rv64c.bin" \
  >"$TEST_BUILD/rv64c-ftrace.log" 2>&1
grep -q "call \[func32@" "$TEST_BUILD/rv64c-ftrace.log"
grep -q "call \[cfunc@" "$TEST_BUILD/rv64c-ftrace.log"
grep -q "ret  \[cfunc\]" "$TEST_BUILD/rv64c-ftrace.log"
printf 'readelf -h\nreadelf -s\nq\n' |
  "$NEMU64" --elf="$TEST_BUILD/rv64c.elf" "$TEST_BUILD/rv64c.bin" \
  >"$TEST_BUILD/rv64c-readelf.log" 2>&1
grep -q "func32" "$TEST_BUILD/rv64c-readelf.log"
printf 'c\niringbuf\nq\n' |
  "$NEMU64" --elf="$TEST_BUILD/rv64c.elf" "$TEST_BUILD/rv64c.bin" \
  >"$TEST_BUILD/rv64c-iringbuf.log" 2>&1
grep -q "c\.addi" "$TEST_BUILD/rv64c-iringbuf.log"
printf 'si 20\nq\n' |
  "$NEMU64" --elf="$TEST_BUILD/rv64c.elf" "$TEST_BUILD/rv64c.bin" \
  >"$TEST_BUILD/rv64c-si.log" 2>&1
check_itrace_width "$TEST_BUILD/rv64c-si.log"
echo "  PASS ftrace/readelf/iringbuf/si"

echo "[3/5] 构建 RV32C NEMU 并运行定向测试"
build_nemu riscv32-rvc_defconfig
NEMU32=$NEMU_HOME/build/riscv32-nemu-interpreter
build_test "$CROSS32" rv32imac ilp32 rv32c "$SRC/rv32c.S"
run_good "$NEMU32" "$TEST_BUILD/rv32c.elf" "$TEST_BUILD/rv32c.bin" "$TEST_BUILD/rv32c.log"
check_itrace_width "$TEST_BUILD/rv32c.log"
echo "  PASS rv32c"
build_test "$CROSS32" rv32imac ilp32 illegal32 "$SRC/rvc-illegal.S"
run_good "$NEMU32" "$TEST_BUILD/illegal32.elf" "$TEST_BUILD/illegal32.bin" "$TEST_BUILD/illegal32.log"
echo "  PASS illegal32"
build_fetch_test "$CROSS32" rv32imac ilp32 fetch32 "$SRC/rvc-fetch.S"
run_good "$NEMU32" "$TEST_BUILD/fetch32.elf" "$TEST_BUILD/fetch32.bin" "$TEST_BUILD/fetch32.log"
echo "  PASS fetch32"
build_test "$CROSS32" rv32imac ilp32 gcc32 "$SRC/rvc-gcc-start.S" "$SRC/rvc-gcc.c"
CROSS_OBJDUMP="${CROSS32}objdump"
check_compressed_image "$TEST_BUILD/gcc32.elf"
run_good "$NEMU32" "$TEST_BUILD/gcc32.elf" "$TEST_BUILD/gcc32.bin" "$TEST_BUILD/gcc32.log"
echo "  PASS gcc32 (-march=rv32imac)"

echo "[4/5] 默认配置必须保持 misa.C=0 与 IALIGN=32"
build_nemu riscv64_defconfig
NEMU64_DEF=$NEMU_HOME/build/riscv64-nemu-interpreter
build_test "$CROSS64" rv64ima lp64 disabled64 "$SRC/rvc-disabled.S"
run_good "$NEMU64_DEF" "$TEST_BUILD/disabled64.elf" "$TEST_BUILD/disabled64.bin" "$TEST_BUILD/disabled64.log"
echo "  PASS rv64 default without C"
build_nemu riscv32_defconfig
NEMU32_DEF=$NEMU_HOME/build/riscv32-nemu-interpreter
build_test "$CROSS32" rv32ima ilp32 disabled32 "$SRC/rvc-disabled.S"
run_good "$NEMU32_DEF" "$TEST_BUILD/disabled32.elf" "$TEST_BUILD/disabled32.bin" "$TEST_BUILD/disabled32.log"
echo "  PASS rv32 default without C"

echo "[5/5] 现有 RV32/RV64 冒烟回归"
bash "$NEMU_HOME/tests/rv64-smoke.sh"
echo "RVC 全部测试通过"
