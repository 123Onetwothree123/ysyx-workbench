#!/usr/bin/env bash
# common/test_runner.sh
#
# 测试 run.sh 的公共函数：失败/跳过、日志尾部、第一次分叉报告、资产检查，
# 以及自动适配 CONFIG_SDB=y/n 的 run_npc。
#
# 用法（脚本位于 npc/csrc/Test/<category>/<name>/run.sh）：
#   SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
#   TEST_RUNNER_TAIL_LINES=40
#   source "$SCRIPT_DIR/../../common/test_runner.sh"
#
# 调用者负责设置 set -uo pipefail / set -euo pipefail；本文件不改变 shell
# 选项。可调变量：
#   TEST_RUNNER_TAIL_LINES          show_log 默认 tail 行数（60）
#   TEST_RUNNER_DIVERGENCE_LINES    show_first_divergence 默认 head 行数（100）
#   TEST_RUNNER_FAIL_TAIL_LINES     fail 打印附带日志的 tail 行数（30）
#   NPC_BATCH_MODE                  auto（默认）| no

: "${TEST_RUNNER_TAIL_LINES:=60}"
: "${TEST_RUNNER_DIVERGENCE_LINES:=100}"
: "${TEST_RUNNER_FAIL_TAIL_LINES:=30}"

fail() {
    local message="$1"
    shift
    echo "[FAIL] $message" >&2
    local file
    for file in "$@"; do
        [[ -f "$file" ]] || continue
        echo "---- $file (tail) ----" >&2
        tail -n "$TEST_RUNNER_FAIL_TAIL_LINES" "$file" >&2 || true
    done
    exit 1
}

skip() {
    if [[ "${STRICT:-0}" == 1 ]]; then
        fail "STRICT=1: $*"
    fi
    echo "[SKIP] $*"
    exit 0
}

show_log() {
    local log="${1:?show_log 需要日志路径}"
    local lines="${2:-$TEST_RUNNER_TAIL_LINES}"
    [[ -f "$log" ]] || return 0
    echo "---- $log (tail) ----" >&2
    tail -n "$lines" "$log" >&2 || true
}

show_first_divergence() {
    local log="${1:?show_first_divergence 需要日志路径}"
    local lines="${2:-$TEST_RUNNER_DIVERGENCE_LINES}"
    [[ -f "$log" ]] || return 0
    if grep -q 'DiffTest 校验失败' "$log" 2>/dev/null; then
        echo "---- 第一次分叉报告 ----" >&2
        sed -n '/DiffTest 校验失败/,$p' "$log" | head -n "$lines" >&2 || true
    fi
}

# 缺少资产时的默认语义是 SKIP；需要直接 FAIL 的测试用 require_file_or_fail。
require_file() {
    local path="$1" what="${2:-文件}"
    [[ -f "$path" ]] || skip "缺少 $what：$path"
}

require_file_or_fail() {
    local path="$1" what="${2:-文件}"
    [[ -f "$path" ]] || fail "缺少 $what：$path"
}

# 运行 NPC，自动适配启用/未启用 SDB 的构建：
#   - 启用 SDB 时必须 --batch，否则非交互 stdin 会提前退出；
#   - 未启用 SDB 的构建不认识 --batch，检测到后自动去掉重跑。
# 结果写入 LAST_STATUS，函数本身总是返回 0，便于调用者统一检查。
LAST_STATUS=0
BATCH_MODE="${NPC_BATCH_MODE:-auto}"
run_npc() {
    local timeout_s="$1" log="$2" binary="$3" image="$4"
    shift 4
    local status=0
    local -a args=("$binary" "$image" "$@")

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

# 不做 --batch 探测，由调用者显式传入参数（如 -b/--batch）。
# 结果写入 LAST_STATUS。
run_npc_once() {
    local timeout_s="$1" log="$2"
    shift 2
    local status=0

    mkdir -p "$(dirname -- "$log")"
    : >"$log"

    if timeout "$timeout_s" "$@" >>"$log" 2>&1; then
        status=0
    else
        status=$?
    fi
    LAST_STATUS=$status
}

# DiffTest 日志的公共断言。require_fp=1 时额外要求浮点状态 ABI 真正启用。
check_diff_log() {
    local log="$1"
    local require_fp="${2:-0}"

    grep -Fq 'DiffTest: ON' "$log" ||
        fail "DiffTest 未启用（日志缺少 'DiffTest: ON'）"
    ! grep -Fq 'DiffTest 校验失败' "$log" ||
        {
            show_first_divergence "$log"
            fail "DiffTest 出现分叉；上方为第一次分叉报告"
        }
    grep -Fq 'DiffTest: final state PASS' "$log" ||
        fail "DiffTest 未完成最终状态比对"
    if [[ "$require_fp" == 1 ]]; then
        grep -Fq 'DiffTest: 浮点状态 ABI ON' "$log" ||
            fail "REF/DUT 未启用浮点状态 ABI，本轮没有逐 f 寄存器比较"
    fi
    grep -Fq 'HIT GOOD TRAP' "$log" ||
        fail "guest 未触发 HIT GOOD TRAP"
}
