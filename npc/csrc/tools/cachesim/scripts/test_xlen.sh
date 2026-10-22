#!/bin/bash
# cachesim XLEN 回归：同一源码分别以 CONFIG_XLEN=32/64 构建。
# 32 位跑低地址样例并与 64 位输出逐字节比较；64 位额外覆盖 >4GiB 地址。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NPC_ROOT="$(cd "$ROOT/../../.." && pwd)"
CONF="$NPC_ROOT/tools/build/c_conf"
BUILD_ROOT="${BUILD_ROOT:-$ROOT/build/xlen-test}"
JOBS="${JOBS:-$(nproc)}"
TRACE_DIR="$ROOT/tests"

if [[ ! -x "$CONF" ]]; then
    echo "[cachesim] 构建 Kconfig 工具 ..."
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
    echo "[cachesim] 构建 CONFIG_XLEN=$xlen ..."
    cmake -S "$ROOT" -B "$BUILD_ROOT/$xlen" -DCONFIG_XLEN="$xlen" >/dev/null
    cmake --build "$BUILD_ROOT/$xlen" -j "$JOBS" >/dev/null
done

mkdir -p "$BUILD_ROOT/run"
BIN32="$BUILD_ROOT/32/cachesim"
BIN64="$BUILD_ROOT/64/cachesim"
COMMON_ARGS=(--block-size 4 --num-blocks 16 --ways 1)

echo "[cachesim] 32/64 位低地址输出一致性 ..."
"$BIN32" "${COMMON_ARGS[@]}" "$TRACE_DIR/low_addr.trace" > "$BUILD_ROOT/run/low32.log"
"$BIN64" "${COMMON_ARGS[@]}" "$TRACE_DIR/low_addr.trace" > "$BUILD_ROOT/run/low64.log"
diff -u "$BUILD_ROOT/run/low32.log" "$BUILD_ROOT/run/low64.log"

echo "[cachesim] 64 位 >4GiB 地址 ..."
"$BIN64" "${COMMON_ARGS[@]}" "$TRACE_DIR/high_addr.trace" > "$BUILD_ROOT/run/high64.log"
grep -Fq '处理了 4 条 PC 记录' "$BUILD_ROOT/run/high64.log"
grep -Fq '总访问次数：4' "$BUILD_ROOT/run/high64.log"
grep -Fq '强制缺失：2' "$BUILD_ROOT/run/high64.log"
grep -Fq '冲突缺失：2' "$BUILD_ROOT/run/high64.log"

echo "[cachesim] 32 位拒绝 >4GiB 地址而非静默截断 ..."
"$BIN32" "${COMMON_ARGS[@]}" "$TRACE_DIR/high_addr.trace" > "$BUILD_ROOT/run/high32.log"
# 4 条记录中只有 0x000000000 两条落在 32 位范围内；0x100000000 被解析器
# 判为越界而丢弃（不是截断成 0 后参与 cache 访问）。
grep -Fq '处理了 2 条 PC 记录' "$BUILD_ROOT/run/high32.log"
grep -Fq '总访问次数：2' "$BUILD_ROOT/run/high32.log"

echo "[PASS] cachesim 32/64 位构建与高地址测试"
