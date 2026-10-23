#!/usr/bin/env bash
# RV64/RV32 NEMU 快速回归。直接复用仓库内 NPC 的裸机测试源，不修改 npc/。
# 用法：nemu/tests/rv64-smoke.sh
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NEMU_HOME=$ROOT/nemu
TEST_BUILD=$NEMU_HOME/build/isa-tests
CROSS=${CROSS:-riscv64-unknown-elf-}
CC=${CROSS}gcc
OBJCOPY=${CROSS}objcopy
mkdir -p "$TEST_BUILD"

build_nemu() {
  local config=$1
  # 如果上一次留下 TARGET_AM 配置，Makefile 在解析阶段就会包含 AM
  # Makefile；显式给出 ARCH/AM_HOME 才能顺利切换到新 defconfig。
  make -C "$NEMU_HOME" NEMU_HOME="$NEMU_HOME" \
    ARCH=riscv64-nemu AM_HOME="$ROOT/abstract-machine" "$config" >/dev/null
  make -C "$NEMU_HOME" NEMU_HOME="$NEMU_HOME" -j"$(nproc)" >/dev/null
}

build_asm_test() {
  local name=$1
  local src=$ROOT/npc/csrc/Test/$name/main.S
  "$CC" -march=rv64ima_zicsr -mabi=lp64 -nostdlib -nostartfiles \
    -Ttext=0x80000000 -Wl,--no-relax \
    -o "$TEST_BUILD/$name.elf" "$src"
  "$OBJCOPY" -O binary "$TEST_BUILD/$name.elf" "$TEST_BUILD/$name.bin"
}

build_local_test() {
  local name=$1
  local src=$NEMU_HOME/tests/rv64/$name.S
  "$CC" -march=rv64ima_zicsr -mabi=lp64 -nostdlib -nostartfiles \
    -Ttext=0x80000000 -Wl,--no-relax \
    -o "$TEST_BUILD/$name.elf" "$src"
  "$OBJCOPY" -O binary "$TEST_BUILD/$name.elf" "$TEST_BUILD/$name.bin"
}

echo "[1/3] 构建 RV64 NEMU"
build_nemu riscv64_defconfig
NEMU64=$NEMU_HOME/build/riscv64-nemu-interpreter

echo "[2/3] RV64 内置镜像与裸机回归"
# Capstone 以相对路径 tools/capstone/... 加载，必须在 nemu/ 目录运行。
cd "$NEMU_HOME"
printf 'c\n' | "$NEMU64" >"$TEST_BUILD/builtin-rv64.log" 2>&1
grep -q "HIT GOOD TRAP" "$TEST_BUILD/builtin-rv64.log"
for test in rv64-ui-um-test rv64-address-test rv64-mmu-test rv64-sv48-test; do
  build_asm_test "$test"
  "$NEMU64" -b --elf="$TEST_BUILD/$test.elf" "$TEST_BUILD/$test.bin" \
    >"$TEST_BUILD/$test.log" 2>&1
  grep -q "HIT GOOD TRAP" "$TEST_BUILD/$test.log"
  echo "  PASS $test"
done
for test in csr64 intr64 a64; do
  build_local_test "$test"
  "$NEMU64" -b --elf="$TEST_BUILD/$test.elf" "$TEST_BUILD/$test.bin" \
    >"$TEST_BUILD/$test.log" 2>&1
  grep -q "HIT GOOD TRAP" "$TEST_BUILD/$test.log"
  echo "  PASS $test"
done

echo "[3/3] RV32 回归（内置镜像）"
build_nemu riscv32_defconfig
printf 'c\n' | "$NEMU_HOME/build/riscv32-nemu-interpreter" \
  >"$TEST_BUILD/builtin-rv32.log" 2>&1
grep -q "HIT GOOD TRAP" "$TEST_BUILD/builtin-rv32.log"
echo "  PASS riscv32 built-in"

echo "全部通过"
