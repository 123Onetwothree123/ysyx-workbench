#!/usr/bin/env bash
# RV64 Sv48 独立回归：satp.MODE=9、四级页表、1GiB 超级页取指、
# VA 0x80_0000_0000 的 4KiB 映射、未映射页与非规范地址页错误。
# 与 Sv39 分开构建，使用独立 BUILD_DIR，避免覆盖默认 Sv39 仿真器。
# 生成物位于 npc/build/rv64-sv48 与 /tmp/opencode/rv64-sv48，不进入提交。
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NPC="$ROOT/npc"
WORK=${WORK:-/tmp/opencode/rv64-sv48}
BUILD_REL=build/rv64-sv48
BIN="$NPC/$BUILD_REL/ysyx_26030103_npc"
TEST="$NPC/csrc/Test/rv64-sv48-test"

mkdir -p "$WORK"
log_step() { printf '\n===== %s =====\n' "$*"; }

log_step "1. 构建 RV64 Sv48 direct-NPC（rv64_sv48_defconfig，独立 BUILD_DIR）"
cd "$NPC"
make BUILD_DIR="$BUILD_REL" rv64_sv48_defconfig >"$WORK/npc-config.log" 2>&1
make BUILD_DIR="$BUILD_REL" -j"$(nproc)" "$BUILD_REL/ysyx_26030103_npc" \
  >"$WORK/npc-build.log" 2>&1
test -x "$BIN"

log_step "2. RV64 Sv48 裸机镜像"
make -C "$TEST" clean >/dev/null
make -C "$TEST" main.bin >"$WORK/rv64-sv48-build.log" 2>&1
timeout 120 bash -c "printf 'c\n' | '$BIN' '$TEST/main.bin'" \
  >"$WORK/rv64-sv48-run.log" 2>&1
grep -q 'HIT GOOD TRAP' "$WORK/rv64-sv48-run.log"

printf '\nPASS: RV64 Sv48 regression. 日志目录: %s\n' "$WORK"
