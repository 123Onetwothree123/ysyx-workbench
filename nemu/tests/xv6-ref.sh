#!/usr/bin/env bash
# NEMU RV64 独立启动 xv6-riscv 的最小回归。
#
# 覆盖：
#   - M 模式启动、mhartid/PMP、Sv39、CLINT timer
#   - 内核/用户页表、第一次用户 ecall、init/sh
#   - UART TX 与 UART RX + PLIC source 10（batch 模式把 stdin 当串口输入）
#
# 用法：
#   nemu/tests/xv6-ref.sh
#   TIMEOUT=60 BUILD_NEMU=0 nemu/tests/xv6-ref.sh
#
# 生成物放在 nemu/build/xv6-ref/，不污染源码树。
set -euo pipefail

NEMU_HOME=$(cd "$(dirname "$0")/.." && pwd)
ROOT=$(cd "$NEMU_HOME/.." && pwd)
XV6_DIR=$ROOT/xv6-riscv
CROSS=${CROSS:-riscv64-unknown-elf-}
TIMEOUT=${TIMEOUT:-40}
LOG_DIR=$NEMU_HOME/build/xv6-ref
LOG=$LOG_DIR/boot.log
NEMU=$NEMU_HOME/build/riscv64-nemu-interpreter
KERNEL=$XV6_DIR/kernel/kernel
KERNEL_BIN=$XV6_DIR/kernel/kernel.bin

mkdir -p "$LOG_DIR"

if [ "${BUILD_NEMU:-1}" = 1 ]; then
  echo "[1/3] 构建 RV64 xv6 NEMU"
  make -C "$NEMU_HOME" NEMU_HOME="$NEMU_HOME" riscv64-xv6_defconfig >/dev/null
  make -C "$NEMU_HOME" NEMU_HOME="$NEMU_HOME" -j"$(nproc)" >/dev/null
else
  echo "[1/3] 跳过 NEMU 构建"
fi

if [ "${BUILD_XV6:-1}" = 1 ]; then
  echo "[2/3] 构建 xv6（PLATFORM=nemu，通用 mhartid/PMP/16550 路径）"
  make -C "$XV6_DIR" -j"$(nproc)" PLATFORM=nemu TOOLPREFIX="$CROSS" >/dev/null
else
  echo "[2/3] 跳过 xv6 构建"
fi

[ -x "$NEMU" ] || { echo "NEMU 不存在：$NEMU" >&2; exit 1; }
[ -f "$KERNEL_BIN" ] || { echo "xv6 kernel.bin 不存在：$KERNEL_BIN" >&2; exit 1; }

echo "[3/3] 启动 xv6（timeout ${TIMEOUT}s，stdin 注入 ls）"
# Capstone 通过相对路径 tools/capstone/... 加载，必须在 nemu/ 下运行。
cd "$NEMU_HOME"
set +e
# -b 让 SDB 直接运行，stdin 才能作为 UART 输入被 guest 读取。
printf 'ls\n' | timeout "$TIMEOUT" "$NEMU" -b --elf "$KERNEL" "$KERNEL_BIN" >"$LOG" 2>&1
status=$?
set -e

# 124/143 是预期：xv6 在 shell 提示符等待输入，不会自行退出。
if [ "$status" -ne 0 ] && [ "$status" -ne 124 ] && [ "$status" -ne 143 ]; then
  echo "NEMU 异常退出，status=$status" >&2
  tail -40 "$LOG" >&2
  exit 1
fi

fail=0
for marker in "xv6 kernel is booting" "init: starting sh" '$ ' 'README'; do
  if grep -qF "$marker" "$LOG"; then
    echo "  PASS: $marker"
  else
    echo "  FAIL: 未找到 '$marker'" >&2
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "---- 日志末尾 ----" >&2
  tail -80 "$LOG" >&2
  exit 1
fi

echo "PASS: NEMU 已独立启动 xv6 到 shell，并完成一次 UART RX/PLIC ls"
echo "日志：$LOG"
