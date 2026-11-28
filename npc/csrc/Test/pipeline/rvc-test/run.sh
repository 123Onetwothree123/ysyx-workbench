#!/usr/bin/env bash
# RVC 裸机回归运行器。短超时，失败时打印日志尾部。
set -euo pipefail

mode="${1:?mode: run|difftest}"
npc_bin="${2:?npc binary}"
guest="${3:?guest image}"
xlen="${4:?xlen}"
log="${5:?log path}"
ref_so="${6:-}"
# CONFIG_SDB=y 的 DUT 在非 tty stdin 上会提前退出，需要 -b/--batch；
# Makefile 按 .config 决定是否通过环境传入（未启用 SDB 的 DUT 不认识该参数）。
batch_flag="${NPC_BATCH_FLAG:-}"
batch_args=()
if [[ -n "$batch_flag" ]]; then
    batch_args=("$batch_flag")
fi

mkdir -p "$(dirname -- "$log")"

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
TEST_RUNNER_TAIL_LINES=60
source "$SCRIPT_DIR/../../common/test_runner.sh"

[[ -x "$npc_bin" ]] || fail "NPC 可执行文件不存在: $npc_bin"
[[ -f "$guest" ]] || fail "guest 镜像不存在: $guest"

case "$mode" in
    run)
        timeout 60 "$npc_bin" "$guest" "${batch_args[@]}" >"$log" 2>&1 || \
            fail "RV${xlen}C guest 运行失败" "$log"
        grep -Fq 'HIT GOOD TRAP' "$log" ||
            fail "RV${xlen}C guest 未触发 HIT GOOD TRAP" "$log"
        ;;
    difftest)
        [[ -n "$ref_so" ]] || fail "DiffTest 缺少 REF .so"
        [[ -f "$ref_so" ]] || fail "DiffTest REF 不存在: $ref_so"
        timeout 120 "$npc_bin" "$guest" --diff "$ref_so" "${batch_args[@]}" >"$log" 2>&1 || \
            fail "RV${xlen}C DiffTest 运行失败" "$log"
        grep -Fq 'DiffTest: ON' "$log" || fail "DiffTest 未启用" "$log"
        grep -Fq 'DiffTest: final state PASS' "$log" ||
            fail "DiffTest 未完成最终状态比对" "$log"
        grep -Fq 'HIT GOOD TRAP' "$log" || fail "DiffTest guest 未 GOOD TRAP" "$log"
        ;;
    *)
        echo "未知 mode: $mode" >&2
        exit 2
        ;;
esac

echo "[PASS] $mode RV${xlen}C"
