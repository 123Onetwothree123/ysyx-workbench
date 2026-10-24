#!/usr/bin/env bash
# RV32D/C.FP 与 RV64D/C.FP DiffTest 运行器。
#
# 每个镜像都先用目标 ISA 的 NEMU REF 比对整数/PC 状态；浮点结果由 guest
# 自己在 GOOD TRAP 前逐项检查（NPC 当前 DiffTest ABI 只覆盖 GPR/PC，
# 不在本任务中修改浮点 DiffTest ABI）。
#
# 结果语义：缺少镜像/REF 直接 FAIL（不会伪造 SKIP），因为本测试的构建
# 目标已经显式要求 RV32D/C.FP 资产存在。
set -uo pipefail

fail() {
    echo "[FAIL] $*" >&2
    exit 1
}

show_log() {
    local log="$1"
    [[ -f "$log" ]] || return 0
    echo "---- $log (tail) ----" >&2
    tail -n 80 "$log" >&2 || true
}

show_first_divergence() {
    local log="$1"
    if grep -q 'DiffTest 校验失败' "$log" 2>/dev/null; then
        echo "---- 第一次分叉报告 ----" >&2
        sed -n '/DiffTest 校验失败/,$p' "$log" | head -n 120 >&2 || true
    fi
}

require_file() {
    local path="$1" what="$2"
    [[ -f "$path" ]] || fail "缺少 $what：$path"
}

LAST_STATUS=0
BATCH_MODE="${NPC_BATCH_MODE:-auto}"
run_npc() {
    local timeout_s="$1" log="$2" binary="$3" image="$4" ref="$5"
    local status=0
    local -a args=("$binary" "$image" --diff "$ref")

    mkdir -p "$(dirname -- "$log")"
    : >"$log"

    if [[ "$BATCH_MODE" != no ]]; then
        if timeout "$timeout_s" "${args[@]}" --batch >>"$log" 2>&1; then
            status=0
        else
            status=$?
        fi
        if grep -qE -- '--batch 需要在构建时启用 CONFIG_SDB|未知参数: --batch' "$log"; then
            BATCH_MODE=no
        fi
    fi
    if [[ "$BATCH_MODE" == no ]]; then
        if timeout "$timeout_s" "${args[@]}" >>"$log" 2>&1; then
            status=0
        else
            status=$?
        fi
    fi
    LAST_STATUS=$status
}

check_log() {
    local log="$1" xlen="$2"
    grep -Fq 'DiffTest: ON' "$log" ||
        fail "RV${xlen} DiffTest 未启用（日志缺少 'DiffTest: ON'）"
    ! grep -Fq 'DiffTest 校验失败' "$log" || {
        show_first_divergence "$log"
        fail "RV${xlen} DiffTest 出现分叉"
    }
    grep -Fq 'DiffTest: final state PASS' "$log" ||
        fail "RV${xlen} DiffTest 未完成最终状态比对"
    grep -Fq 'HIT GOOD TRAP' "$log" ||
        fail "RV${xlen} guest 未触发 HIT GOOD TRAP"
    ! grep -q 'HIT BAD TRAP' "$log" ||
        fail "RV${xlen} guest 报告 HIT BAD TRAP"
}

run_one() {
    local xlen="$1" binary="$2" image="$3" ref="$4" logdir="$5"
    local log="$logdir/diff-rv${xlen}d-cfp.log"

    require_file "$binary" "RV${xlen} NPC binary"
    require_file "$image" "RV${xlen}D+C.FP guest 镜像"
    require_file "$ref" "RV${xlen} NEMU REF"

    echo "[INFO] RV${xlen}D+C.FP DiffTest: $(basename "$image") <-> $(basename "$ref")"
    run_npc 180 "$log" "$binary" "$image" "$ref"
    local status="$LAST_STATUS"
    [[ "$status" -eq 0 ]] || {
        show_first_divergence "$log"
        show_log "$log"
        fail "RV${xlen}D+C.FP DiffTest 运行失败（exit=$status）"
    }
    check_log "$log" "$xlen"
    echo "[PASS] RV${xlen}D+C.FP DiffTest（guest 自检 + NEMU 整数状态比对）"
}

LOG_DIR="${LOG_DIR:-/tmp/opencode/rv32d-cfp}"
NPC_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
BIN32="${BIN32:-$NPC_DIR/build/ysyx_26030103_npc}"
BIN64="${BIN64:-$NPC_DIR/build-rv64/ysyx_26030103_npc}"
REF32="${REF32:-$NPC_DIR/../nemu/build/riscv32-nemu-interpreter-so}"
REF64="${REF64:-$NPC_DIR/../nemu/build/riscv64-nemu-interpreter-so}"
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${BUILD_DIR:-$TEST_DIR/build}"
mkdir -p "$LOG_DIR"

mode="${1:-rv32}"
case "$mode" in
    rv32)
        run_one 32 "$BIN32" "$BUILD_DIR/guest-rv32d-cfp.bin" "$REF32" "$LOG_DIR"
        ;;
    rv64)
        run_one 64 "$BIN64" "$BUILD_DIR/guest-rv64d-cfp.bin" "$REF64" "$LOG_DIR"
        ;;
    all)
        run_one 32 "$BIN32" "$BUILD_DIR/guest-rv32d-cfp.bin" "$REF32" "$LOG_DIR"
        run_one 64 "$BIN64" "$BUILD_DIR/guest-rv64d-cfp.bin" "$REF64" "$LOG_DIR"
        ;;
    *)
        fail "用法: $0 <rv32|rv64|all>"
        ;;
esac
echo "[PASS] $mode + C.FP 全部通过"
