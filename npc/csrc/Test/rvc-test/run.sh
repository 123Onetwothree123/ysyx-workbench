#!/usr/bin/env bash
# RVC 裸机回归运行器。短超时，失败时打印日志尾部。
set -euo pipefail

mode="${1:?mode: run|difftest}"
npc_bin="${2:?npc binary}"
guest="${3:?guest image}"
xlen="${4:?xlen}"
log="${5:?log path}"
ref_so="${6:-}"

mkdir -p "$(dirname -- "$log")"

fail() {
    local message="$1"
    echo "[FAIL] $message" >&2
    if [[ -f "$log" ]]; then
        echo "---- $log (tail) ----" >&2
        tail -n 60 "$log" >&2 || true
    fi
    exit 1
}

[[ -x "$npc_bin" ]] || fail "NPC 可执行文件不存在: $npc_bin"
[[ -f "$guest" ]] || fail "guest 镜像不存在: $guest"

case "$mode" in
    run)
        timeout 60 "$npc_bin" "$guest" >"$log" 2>&1 || \
            fail "RV${xlen}C guest 运行失败"
        grep -Fq 'HIT GOOD TRAP' "$log" ||
            fail "RV${xlen}C guest 未触发 HIT GOOD TRAP"
        ;;
    difftest)
        [[ -n "$ref_so" ]] || fail "DiffTest 缺少 REF .so"
        [[ -f "$ref_so" ]] || fail "DiffTest REF 不存在: $ref_so"
        timeout 120 "$npc_bin" "$guest" --diff "$ref_so" >"$log" 2>&1 || \
            fail "RV${xlen}C DiffTest 运行失败"
        grep -Fq 'DiffTest: ON' "$log" || fail "DiffTest 未启用"
        grep -Fq 'DiffTest: final state PASS' "$log" ||
            fail "DiffTest 未完成最终状态比对"
        grep -Fq 'HIT GOOD TRAP' "$log" || fail "DiffTest guest 未 GOOD TRAP"
        ;;
    *)
        echo "未知 mode: $mode" >&2
        exit 2
        ;;
esac

echo "[PASS] $mode RV${xlen}C"
