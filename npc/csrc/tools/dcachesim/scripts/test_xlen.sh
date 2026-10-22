#!/bin/bash
# dcachesim XLEN 回归：同一源码分别以 CONFIG_XLEN=32/64 构建。
# 32 位跑低地址样例并与 64 位输出逐字节比较；64 位额外覆盖 >4GiB 地址，
# 同时验证 mtrace 与简单 r/w 两种 trace 解析路径。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NPC_ROOT="$(cd "$ROOT/../../.." && pwd)"
CONF="$NPC_ROOT/tools/build/c_conf"
BUILD_ROOT="${BUILD_ROOT:-$ROOT/build/xlen-test}"
JOBS="${JOBS:-$(nproc)}"
TRACE_DIR="$ROOT/tests"

if [[ ! -x "$CONF" ]]; then
    echo "[dcachesim] 构建 Kconfig 工具 ..."
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

for xlen in 32 64; do
    echo "[dcachesim] 构建 CONFIG_XLEN=$xlen ..."
    cmake -S "$ROOT" -B "$BUILD_ROOT/$xlen" -DCONFIG_XLEN="$xlen" -DCONFIG_RUN_MODE=0 >/dev/null
    cmake --build "$BUILD_ROOT/$xlen" -j "$JOBS" >/dev/null
done

BIN32="$BUILD_ROOT/32/dcachesim"
BIN64="$BUILD_ROOT/64/dcachesim"
mkdir -p "$BUILD_ROOT/run32" "$BUILD_ROOT/run64"

echo "[dcachesim] 32/64 位低地址输出一致性 ..."
(cd "$BUILD_ROOT/run32" && "$BIN32" "$TRACE_DIR/low_addr.trace") > "$BUILD_ROOT/low32.log"
(cd "$BUILD_ROOT/run64" && "$BIN64" "$TRACE_DIR/low_addr.trace") > "$BUILD_ROOT/low64.log"
diff -u "$BUILD_ROOT/low32.log" "$BUILD_ROOT/low64.log"

echo "[dcachesim] 64 位 >4GiB 地址 ..."
(cd "$BUILD_ROOT/run64" && "$BIN64" "$TRACE_DIR/high_addr.trace") > "$BUILD_ROOT/high64.log"
# 5 次访问全部缺失：高/低地址 tag 不同且共用同一个直接映射组。
grep -Eq '^FIFO[[:space:]]+5[[:space:]]+0[[:space:]]+5' "$BUILD_ROOT/high64.log"
grep -Eq '^LRU[[:space:]]+5[[:space:]]+0[[:space:]]+5' "$BUILD_ROOT/high64.log"
grep -Eq '^Random[[:space:]]+5[[:space:]]+0[[:space:]]+5' "$BUILD_ROOT/high64.log"

echo "[dcachesim] 32 位拒绝 >4GiB 地址而非静默截断 ..."
(cd "$BUILD_ROOT/run32" && "$BIN32" "$TRACE_DIR/high_addr.trace") > "$BUILD_ROOT/high32.log"
# 5 条记录中只有 Addr=0x000000000 的两条落在 32 位范围内；其余越界地址
# 被解析器丢弃（不是截断成低 32 位后参与 cache 访问）。
grep -Eq '^FIFO[[:space:]]+2[[:space:]]+1[[:space:]]+1' "$BUILD_ROOT/high32.log"

echo "[PASS] dcachesim 32/64 位构建与高地址测试"
