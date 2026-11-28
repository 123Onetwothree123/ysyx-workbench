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

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
TEST_RUNNER_TAIL_LINES=80
TEST_RUNNER_DIVERGENCE_LINES=120
source "$SCRIPT_DIR/../../common/test_runner.sh"

check_log() {
    local log="$1" xlen="$2"
    check_diff_log "$log" 0
    ! grep -q 'HIT BAD TRAP' "$log" ||
        fail "RV${xlen} guest 报告 HIT BAD TRAP"
}

run_one() {
    local xlen="$1" binary="$2" image="$3" ref="$4" logdir="$5"
    local log="$logdir/diff-rv${xlen}d-cfp.log"

    require_file_or_fail "$binary" "RV${xlen} NPC binary"
    require_file_or_fail "$image" "RV${xlen}D+C.FP guest 镜像"
    require_file_or_fail "$ref" "RV${xlen} NEMU REF"

    echo "[INFO] RV${xlen}D+C.FP DiffTest: $(basename "$image") <-> $(basename "$ref")"
    run_npc 180 "$log" "$binary" "$image" --diff "$ref"
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
NPC_DIR="$(cd "$(dirname "$0")/../../../.." && pwd)"
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
