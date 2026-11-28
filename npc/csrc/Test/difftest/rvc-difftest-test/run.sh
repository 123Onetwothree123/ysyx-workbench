#!/usr/bin/env bash
# rvc-difftest-test 运行器。
#
# 所有仿真统一短超时：probe/负向 60s，DiffTest 120s。日志默认写到
# /tmp/opencode/rvc-difftest，不在仓库内留下运行产物。
#
# 结果语义：
#   [PASS] 断言成立；
#   [SKIP] 当前二进制/环境不具备该用例前提（例如 RISCV_C 尚未实现），
#          或负向用例拿到了已启用 RVC 的 DUT；不会伪造 PASS；
#   [FAIL] 行为错误或前提存在但比对失败。
# 设置 STRICT=1 可把 SKIP 升级为 FAIL，供 RVC 合入后的 CI 使用。
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
TEST_RUNNER_TAIL_LINES=40
TEST_RUNNER_DIVERGENCE_LINES=80
source "$SCRIPT_DIR/../../common/test_runner.sh"

# 返回 0=支持 RVC，2=在首条 C 指令上明确 illegal，1=其他错误。
probe_c_support() {
    local xlen="$1" binary="$2" guest="$3" log="$4"
    run_npc 60 "$log" "$binary" "$guest"
    local status="$LAST_STATUS"

    if grep -Fq 'HIT GOOD TRAP' "$log"; then
        # 探针只有在 c.addi 真正把 s1 从 0 变成 1 后才会 GOOD。
        return 0
    fi
    if grep -Eq 'HIT BAD TRAP.*code = 42([, ]|$)' "$log"; then
        return 2
    fi
    if grep -Eq 'HIT BAD TRAP.*code = 43([, ]|$)' "$log"; then
        echo "[FAIL] RISCV_C=n 时 C 指令被静默跳过或错误执行（探针 code=43）" >&2
        show_log "$log"
        return 1
    fi
    echo "[FAIL] RVC 探针异常（exit=$status，既非正确执行也非 c_site illegal）" >&2
    show_log "$log"
    return 1
}

run_forward() {
    local xlen="$1" binary="$2" probe="$3" guest="$4" ref="$5" logdir="$6"
    local probe_log="$logdir/probe-rv${xlen}.log"
    local diff_log="$logdir/diff-rv${xlen}.log"

    require_file "$binary" "RV${xlen} NPC binary"
    require_file "$probe" "RV${xlen} C 探针镜像"
    require_file "$guest" "RV${xlen}C 正向镜像"
    require_file "$ref" "RV${xlen} NEMU REF"

    local probe_status=0
    probe_c_support "$xlen" "$binary" "$probe" "$probe_log" || probe_status=$?
    case "$probe_status" in
        0) ;; # 继续正向 DiffTest
        2) skip "RV${xlen} DUT 未启用 RVC：首条 C 指令按 illegal instruction 处理（探针 code=42）" ;;
        *) exit 1 ;;
    esac

    echo "[INFO] RV${xlen}C 探针通过，开始真实 NEMU REF DiffTest"
    run_npc 120 "$diff_log" "$binary" "$guest" --diff "$ref"
    local status="$LAST_STATUS"
    [[ "$status" -eq 0 ]] || {
        show_first_divergence "$diff_log"
        show_log "$diff_log"
        fail "RV${xlen}C DiffTest 运行失败（exit=$status）"
    }
    check_diff_log "$diff_log"
    echo "[PASS] RV${xlen}C DiffTest ($guest)"
}

run_negative() {
    local xlen="$1" binary="$2" probe="$3" logdir="$4"
    local probe_log="$logdir/negative-rv${xlen}.log"

    require_file "$binary" "RV${xlen} RISCV_C=n NPC binary"
    require_file "$probe" "RV${xlen} C 探针镜像"

    local probe_status=0
    probe_c_support "$xlen" "$binary" "$probe" "$probe_log" || probe_status=$?
    case "$probe_status" in
        0)
            skip "RV${xlen} DUT 已启用 RVC；负向用例需要 RISCV_C=n 的 binary"
            ;;
        2)
            grep -Fq 'HIT GOOD TRAP' "$probe_log" &&
                fail "负向用例在 illegal trap 后仍到达 GOOD TRAP"
            echo "[PASS] RV${xlen} RISCV_C=n：C 指令在 c_site 触发 illegal instruction，未静默执行"
            ;;
        *) exit 1 ;;
    esac
}

run_mismatch() {
    local xlen="$1" binary="$2" guest="$3" shim="$4" real_ref="$5" logdir="$6"
    local log="$logdir/mismatch-rv${xlen}.log"

    require_file "$binary" "RV${xlen} NPC binary"
    require_file "$guest" "RV${xlen} 分叉报告 guest"
    require_file "$shim" "故意制造分叉的 REF shim"
    require_file "$real_ref" "真实 NEMU REF"

    echo "[INFO] 用 corrupt-ref shim 注入 x5 差异，检查第一次分叉报告"
    local status=0
    if (
        export DIFFTEST_REAL_REF="$real_ref"
        timeout 120 "$binary" "$guest" --diff "$shim" --batch >"$log" 2>&1
    ); then
        status=0
    else
        status=$?
    fi
    if grep -qE -- '--batch 需要在构建时启用 CONFIG_SDB|未知参数: --batch' "$log"; then
        if (
            export DIFFTEST_REAL_REF="$real_ref"
            timeout 120 "$binary" "$guest" --diff "$shim" >"$log" 2>&1
        ); then
            status=0
        else
            status=$?
        fi
    fi

    [[ "$status" -ne 0 ]] || {
        show_log "$log"
        fail "注入 GPR 差异后 NPC 仍正常退出"
    }
    ! grep -Fq 'DiffTest: final state PASS' "$log" ||
        fail "注入 GPR 差异后仍报告 final state PASS"
    grep -Fq 'DiffTest 校验失败' "$log" ||
        {
            show_log "$log"
            fail "缺少第一次分叉报告"
        }
    grep -Fq '差异 GPR（1/32）' "$log" ||
        {
            show_first_divergence "$log"
            fail "分叉报告未指出唯一的 GPR 差异"
        }
    grep -Fq 'x5  t0' "$log" ||
        {
            show_first_divergence "$log"
            fail "分叉报告未定位到被注入的 x5/t0"
        }
    grep -Fq 'DUT 指令' "$log" ||
        {
            show_first_divergence "$log"
            fail "分叉报告缺少 DUT 指令现场"
        }
    echo "[PASS] 第一次分叉报告包含 PC/指令/GPR 差异定位"
}

if [[ $# -lt 1 ]]; then
    echo "用法: $0 <forward|negative|mismatch> ..." >&2
    exit 2
fi

mode="$1"
shift
case "$mode" in
    forward)
        [[ $# -eq 6 ]] || { echo "forward 需要 6 个参数" >&2; exit 2; }
        run_forward "$@"
        ;;
    negative)
        [[ $# -eq 4 ]] || { echo "negative 需要 4 个参数" >&2; exit 2; }
        run_negative "$@"
        ;;
    mismatch)
        [[ $# -eq 6 ]] || { echo "mismatch 需要 6 个参数" >&2; exit 2; }
        run_mismatch "$@"
        ;;
    *)
        echo "未知模式: $mode" >&2
        exit 2
        ;;
esac
