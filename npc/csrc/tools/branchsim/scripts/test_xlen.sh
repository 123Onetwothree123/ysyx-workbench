#!/bin/bash
# branchsim XLEN 回归：同一源码分别以 CONFIG_XLEN=32/64 构建。
# 32 位跑低地址样例并与 64 位输出逐字节比较；64 位额外覆盖 >4GiB 的
# PC/target、BTB tag 与 RAS 返回地址（包括负的 JALR 立即数）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NPC_ROOT="$(cd "$ROOT/../../.." && pwd)"
CONF="$NPC_ROOT/tools/build/c_conf"
BUILD_ROOT="${BUILD_ROOT:-$ROOT/build/xlen-test}"
JOBS="${JOBS:-$(nproc)}"
TRACE_DIR="$ROOT/tests"

if [[ ! -x "$CONF" ]]; then
    echo "[branchsim] 构建 Kconfig 工具 ..."
    cmake -G Ninja -S "$NPC_ROOT/tools/kconfig" -B "$NPC_ROOT/tools/kconfig/build" >/dev/null
    cmake --build "$NPC_ROOT/tools/kconfig/build" --target c_conf -j "$JOBS" >/dev/null
fi

# 先验证默认配置确实来自 Kconfig 生成的 auto.conf，而不是脚本/CMake 默认值。
DEFAULT_BUILD="$BUILD_ROOT/kconfig-default"
rm -rf "$DEFAULT_BUILD"
cmake -S "$ROOT" -B "$DEFAULT_BUILD" >/dev/null
KCONFIG_VALUE="$(sed -n 's/^CONFIG_XLEN=//p' "$ROOT/.config" | tail -n1)"
test -n "$KCONFIG_VALUE"
grep -q "^CONFIG_XLEN:INTERNAL=$KCONFIG_VALUE$" "$DEFAULT_BUILD/CMakeCache.txt"

# 高地址别名用例需要 2 路 BTB：两个 PC 的低 32 位相同，必然落在同一组，
# 直接映射下即使 64 位 tag 正确也会互相驱逐，无法区分“正确冲突”与“截断别名”。
for xlen in 32 64; do
    echo "[branchsim] 构建 CONFIG_XLEN=$xlen ..."
    cmake -S "$ROOT" -B "$BUILD_ROOT/$xlen" -DCONFIG_XLEN="$xlen" \
        -DCONFIG_RUN_MODE=0 -DCONFIG_BTB_WAYS=2 -DCONFIG_JAL_BTB_WAYS=2 >/dev/null
    cmake --build "$BUILD_ROOT/$xlen" -j "$JOBS" >/dev/null
done

BIN32="$BUILD_ROOT/32/branchsim"
BIN64="$BUILD_ROOT/64/branchsim"
mkdir -p "$BUILD_ROOT/run32" "$BUILD_ROOT/run64"

echo "[branchsim] 32/64 位低地址输出一致性 ..."
(cd "$BUILD_ROOT/run32" && "$BIN32" "$TRACE_DIR/low_addr.trace") > "$BUILD_ROOT/low32.log"
(cd "$BUILD_ROOT/run64" && "$BIN64" "$TRACE_DIR/low_addr.trace") > "$BUILD_ROOT/low64.log"
diff -u "$BUILD_ROOT/low32.log" "$BUILD_ROOT/low64.log"

echo "[branchsim] 64 位 >4GiB PC/target/BTB/RAS ..."
(cd "$BUILD_ROOT/run64" && "$BIN64" "$TRACE_DIR/high_addr.trace") > "$BUILD_ROOT/high64.log"
# 16 条记录：8 条交替高/低地址 jal + 4 组高地址 call/ret。
# SplitJal+RAS 首次冷启动误预测后应全部命中：12/16 正确。
grep -Eq '^BTFN\+SplitJal\+RAS[[:space:]]+16[[:space:]]+12[[:space:]]+4' "$BUILD_ROOT/high64.log"
# 若 BTB tag 被截断为 32 位，两个交替 PC 会互相驱逐，正确数会低于 12。
grep -Eq '^BTFN\+SharedJal[[:space:]]+16[[:space:]]+12[[:space:]]+4' "$BUILD_ROOT/high64.log"

echo "[PASS] branchsim 32/64 位构建与高地址测试"
