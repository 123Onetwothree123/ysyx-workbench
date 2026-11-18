#!/usr/bin/env bash
# fpu-difftest-test 运行器。
#
# 正向/负向仿真统一短超时：探针 60s，DiffTest 120s；配置错误检查 300s。
# 日志默认写到 /tmp/opencode/fpu-difftest，不进入仓库。
#
# 结果语义：
#   [PASS] 断言成立；
#   [SKIP] 前提尚不具备（F/D 尚未实现、缺少二进制、REF 没有 F/D、
#          或负向用例拿到了已启用 F/D 的 DUT），绝不伪造 PASS；
#   [FAIL] 行为错误，或前提具备但比对失败。
# STRICT=1 把 SKIP 升级为 FAIL，供 F/D 合入后的 CI 使用。
set -uo pipefail

fail() {
    echo "[FAIL] $*" >&2
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
    local log="$1"
    if [[ -f "$log" ]]; then
        echo "---- $log (tail) ----" >&2
        tail -n 60 "$log" >&2 || true
    fi
}

show_first_divergence() {
    local log="$1"
    if grep -q 'DiffTest 校验失败' "$log" 2>/dev/null; then
        echo "---- 第一次分叉报告 ----" >&2
        sed -n '/DiffTest 校验失败/,$p' "$log" | head -n 100 >&2 || true
    fi
}

require_file() {
    local path="$1" what="$2"
    [[ -f "$path" ]] || skip "缺少 $what：$path"
}

# 运行 NPC。自动适配启用/未启用 SDB 的构建。
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

# 解释 F/D 探针的返回码。成功返回 0，否则打印原因并返回 1，
# 或返回 2 表示“扩展未启用”（正向应 SKIP，负向应 PASS）。
LAST_PROBE_KIND=""
probe_fpu() {
    local xlen="$1" binary="$2" probe="$3" log="$4"
    run_npc 60 "$log" "$binary" "$probe"
    local status="$LAST_STATUS"

    if grep -Fq 'HIT GOOD TRAP' "$log"; then
        LAST_PROBE_KIND=ok
        return 0
    fi
    if grep -Eq 'HIT BAD TRAP.*code = 21([, ]|$)' "$log"; then
        LAST_PROBE_KIND=no_f
        return 2
    fi
    if grep -Eq 'HIT BAD TRAP.*code = 22([, ]|$)' "$log"; then
        LAST_PROBE_KIND=no_d
        return 2
    fi
    if grep -Eq 'HIT BAD TRAP.*code = 23([, ]|$)' "$log"; then
        echo "[FAIL] FPU 可执行但 mstatus.FS 未进入 Dirty(3)，实现不完整" >&2
        show_log "$log"
        return 1
    fi
    if grep -Eq 'HIT BAD TRAP.*code = 25([, ]|$)' "$log"; then
        echo "[FAIL] D 可执行而 F 不可执行，F/D 配置或译码不一致" >&2
        show_log "$log"
        return 1
    fi
    if grep -Eq 'HIT BAD TRAP.*code = (30|31)([, ]|$)' "$log"; then
        echo "[FAIL] F/D 指令未陷入但计算结果错误（不是 SKIP）" >&2
        show_log "$log"
        return 1
    fi
    if grep -Eq 'HIT BAD TRAP.*code = 40([, ]|$)' "$log"; then
        echo "[FAIL] 浮点站点产生了非 illegal-instruction 异常" >&2
        show_log "$log"
        return 1
    fi
    echo "[FAIL] F/D 探针异常（exit=$status，既非正确执行也非明确 illegal）" >&2
    show_log "$log"
    return 1
}

check_diff_log() {
    local log="$1"
    local require_fp="${2:-1}"
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
    # MMIO/mcycle 由现有 DifftestStep 在退休边界注入，测试镜像只访问 RAM，
    # 因此这里不要求 injected 计数为 0；只接受现有的非确定性处理机制。
}

# NEMU REF 能力探针：区分 “DUT 已实现、REF 尚未实现” 与真实分叉。
LAST_REF_KIND=""
probe_ref() {
    local ref="$1" mode="$2" log="$3" ref_probe="$4"
    local status=0
    mkdir -p "$(dirname -- "$log")"
    if timeout 60 "$ref_probe" "$ref" "$mode" >"$log" 2>&1; then
        status=0
    else
        status=$?
    fi
    case "$status" in
        0) LAST_REF_KIND=ok; return 0 ;;
        10) LAST_REF_KIND=no_f; return 2 ;;
        11) LAST_REF_KIND=no_d; return 2 ;;
        *) show_log "$log"; return 1 ;;
    esac
}

run_forward() {
    local xlen="$1" binary="$2" probe="$3" guest="$4" trap_guest="$5" ref="$6"
    local ref_probe="$7" logdir="$8"
    local c_guest="${9:-}"

    local probe_log="$logdir/probe-rv${xlen}.log"
    local ref_log="$logdir/ref-probe-rv${xlen}.log"

    require_file "$binary" "RV${xlen} NPC binary"
    require_file "$probe" "RV${xlen} F/D 探针镜像"
    require_file "$guest" "RV${xlen} 正向镜像"
    require_file "$trap_guest" "RV${xlen} FS/trap 镜像"
    require_file "$ref" "RV${xlen} NEMU REF"
    require_file "$ref_probe" "REF F/D 能力探针"

    local probe_status=0
    probe_fpu "$xlen" "$binary" "$probe" "$probe_log" || probe_status=$?
    case "$probe_status" in
        0) ;;
        2)
            if [[ "$LAST_PROBE_KIND" == no_f ]]; then
                skip "RV${xlen} DUT 未启用 F：首条浮点指令按 illegal instruction 处理"
            else
                skip "RV${xlen} DUT 只有 F、未启用 D：RV${xlen}D 正向用例前提不成立"
            fi
            ;;
        *) exit 1 ;;
    esac

    local ref_mode=f
    [[ "$xlen" == 64 ]] && ref_mode=d
    local ref_status=0
    probe_ref "$ref" "$ref_mode" "$ref_log" "$ref_probe" || ref_status=$?
    case "$ref_status" in
        0) ;;
        2)
            if [[ "$LAST_REF_KIND" == no_f ]]; then
                skip "RV${xlen} NEMU REF 尚未实现 F"
            else
                skip "RV${xlen} NEMU REF 尚未实现 D"
            fi
            ;;
        *) fail "REF F/D 能力探针失败" ;;
    esac

    local fpu_name="F"
    [[ "$xlen" == 64 ]] && fpu_name="F+D"
    echo "[INFO] RV${xlen} DUT/REF ${fpu_name} 探针通过，开始真实 NEMU REF DiffTest"

    local -a images=("$guest" "$trap_guest")
    local -a names=("正向语义" "FS/trap 上下文")
    if [[ -n "$c_guest" ]]; then
        images+=("$c_guest")
        names+=("GCC 硬浮点 C 程序")
    fi

    local i
    for i in "${!images[@]}"; do
        local log="$logdir/diff-rv${xlen}-${i}.log"
        local status=0
        run_npc 120 "$log" "$binary" "${images[$i]}" --diff "$ref"
        status="$LAST_STATUS"
        [[ "$status" -eq 0 ]] || {
            show_first_divergence "$log"
            show_log "$log"
            fail "RV${xlen} ${names[$i]} DiffTest 运行失败（exit=$status）"
        }
        check_diff_log "$log"
        echo "[PASS] RV${xlen} ${names[$i]} DiffTest ($(basename "${images[$i]}"))"
    done
}

run_fp_mismatch() {
    local xlen="$1" binary="$2" guest="$3" shim="$4" real_ref="$5" logdir="$6"
    local log="$logdir/fp-mismatch-rv${xlen}.log"

    require_file "$binary" "RV${xlen} NPC binary"
    require_file "$guest" "RV${xlen} 浮点探针镜像"
    require_file "$shim" "浮点分叉 REF shim"
    require_file "$real_ref" "真实 NEMU REF"

    echo "[INFO] 用 corrupt-fp REF shim 注入 f2 差异，验证逐 f 寄存器比较"
    export DIFFTEST_REAL_REF="$real_ref"
    run_npc 120 "$log" "$binary" "$guest" --diff "$shim"
    local status="$LAST_STATUS"
    unset DIFFTEST_REAL_REF

    [[ "$status" -ne 0 ]] || {
        show_log "$log"
        fail "注入 f2 差异后 NPC 仍正常退出"
    }
    ! grep -Fq 'DiffTest: final state PASS' "$log" ||
        fail "注入 f2 差异后仍报告 final state PASS"
    grep -Fq 'DiffTest 校验失败' "$log" ||
        {
            show_log "$log"
            fail "缺少浮点分叉报告"
        }
    grep -Fq '差异 FPR' "$log" ||
        {
            show_first_divergence "$log"
            fail "分叉报告没有 FPR 差异，NPC 可能仍在只比较整数"
        }
    grep -Fq 'f2 ' "$log" ||
        {
            show_first_divergence "$log"
            fail "分叉报告未定位到被注入的 f2"
        }
    grep -Fq '差异 GPR：无' "$log" ||
        {
            show_first_divergence "$log"
            fail "注入 f2 差异时不应同时出现 GPR 差异"
        }
    echo "[PASS] RV${xlen} 注入 f2 差异后报告 FPR 分叉"
}

run_legacy_fp() {
    local xlen="$1" binary="$2" guest="$3" shim="$4" real_ref="$5" logdir="$6"
    local log="$logdir/legacy-fp-rv${xlen}.log"

    require_file "$binary" "RV${xlen} NPC binary"
    require_file "$guest" "RV${xlen} 浮点探针镜像"
    require_file "$shim" "旧浮点 ABI REF shim"
    require_file "$real_ref" "真实 NEMU REF"

    echo "[INFO] 用不导出 difftest_fp_* 的 shim 验证旧 REF 降级路径"
    export DIFFTEST_REAL_REF="$real_ref"
    run_npc 120 "$log" "$binary" "$guest" --diff "$shim"
    local status="$LAST_STATUS"
    unset DIFFTEST_REAL_REF

    [[ "$status" -eq 0 ]] || {
        show_first_divergence "$log"
        show_log "$log"
        fail "旧 REF/无浮点 ABI 路径运行失败（exit=$status）"
    }
    grep -Fq 'REF 未导出可选浮点状态 ABI' "$log" ||
        {
            show_log "$log"
            fail "旧 REF 路径没有明确告警浮点状态未比对"
        }
    ! grep -Fq 'DiffTest: 浮点状态 ABI ON' "$log" ||
        fail "旧 REF 路径不应启用浮点状态比较"
    check_diff_log "$log" 0
    echo "[PASS] RV${xlen} 旧 REF/无浮点 ABI：明确告警并继续整数比对"
}

run_negative() {
    local xlen="$1" binary="$2" probe="$3" logdir="$4"
    local probe_log="$logdir/negative-rv${xlen}.log"

    require_file "$binary" "RV${xlen} 负向 NPC binary"
    require_file "$probe" "RV${xlen} F/D 探针镜像"

    local probe_status=0
    probe_fpu "$xlen" "$binary" "$probe" "$probe_log" || probe_status=$?
    case "$probe_status" in
        0)
            skip "RV${xlen} DUT 已启用 F/D；负向用例需要 RISCV_F=n 或 RISCV_D=n 的 binary"
            ;;
        2)
            if [[ "$LAST_PROBE_KIND" == no_f ]]; then
                grep -Fq 'HIT GOOD TRAP' "$probe_log" &&
                    fail "RISCV_F=n 时浮点指令仍到达 GOOD TRAP"
                echo "[PASS] RV${xlen} RISCV_F=n：F 与 D 指令均触发 illegal instruction，未静默执行"
            else
                grep -Fq 'HIT GOOD TRAP' "$probe_log" &&
                    fail "RISCV_D=n 时 D 指令仍到达 GOOD TRAP"
                echo "[PASS] RV${xlen} RISCV_D=n：F 正常、D 指令触发 illegal instruction"
            fi
            ;;
        *) exit 1 ;;
    esac
}

run_fdiv_shared() {
    local xlen="$1" binary="$2" guest="$3" ref="$4" logdir="$5"
    local log="$logdir/fdiv-shared-rv${xlen}.log"

    require_file "$binary" "RV${xlen} NPC binary"
    require_file "$guest" "RV${xlen} 共享 FDIV/FSQRT 压力镜像"
    require_file "$ref" "RV${xlen} NEMU REF"

    local status=0
    run_npc 240 "$log" "$binary" "$guest" --diff "$ref"
    status="$LAST_STATUS"
    [[ "$status" -eq 0 ]] || {
        show_first_divergence "$log"
        show_log "$log"
        fail "RV${xlen} 共享 FDIV/FSQRT 压力 DiffTest 运行失败（exit=$status）"
    }
    check_diff_log "$log"
    echo "[PASS] RV${xlen} 共享 FDIV/FSQRT 边界+随机 DiffTest"
}

run_mismatch() {    local xlen="$1" binary="$2" guest="$3" shim="$4" real_ref="$5" logdir="$6"
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

# 配置错误检查：在 /tmp 的 npc 副本里跑 elaboration，避免污染仓库。
run_config_errors() {
    local npc_dir="$1" logdir="$2"

    case "${CONFIG_CHECK:-auto}" in
        0) skip "CONFIG_CHECK=0：跳过配置错误检查" ;;
        auto|1) ;;
        *) fail "CONFIG_CHECK 只接受 auto/0/1" ;;
    esac
    command -v mill >/dev/null 2>&1 || skip "缺少 mill，无法验证 F/D 配置错误"
    [[ -f "$npc_dir/Makefile" && -d "$npc_dir/src" ]] ||
        skip "缺少 NPC 源码目录：$npc_dir"

    mkdir -p "$logdir"
    CONFIG_TMP_ROOT="$(mktemp -d "${logdir%/}/config.XXXXXX")" || fail "无法创建临时目录"
    trap 'rm -rf "${CONFIG_TMP_ROOT:-}"' EXIT
    local root="$CONFIG_TMP_ROOT"
    local copy="$root/npc"
    mkdir -p "$copy"
    tar -C "$npc_dir" \
        --exclude=./build --exclude=./out --exclude=./log \
        --exclude=./NPCSimResult --exclude=./yosys-sta-result \
        --exclude=./.git -cf - . 2>/dev/null | tar -C "$copy" -xf -
    [[ -f "$copy/Makefile" ]] || fail "复制 NPC 源码失败"

    local config_log="$logdir/config-defconfig.log"
    : >"$config_log"
    if ! timeout 180 make -C "$copy" rv64_c_defconfig git_commit= \
        >"$config_log" 2>&1; then
        show_log "$config_log"
        fail "生成临时 defconfig 失败"
    fi

    config_skipped=0
    case_skip() {
        if [[ "${STRICT:-0}" == 1 ]]; then
            fail "STRICT=1: $*"
        fi
        echo "[SKIP] $*"
        config_skipped=1
    }

    run_one_config_case() {
        local name="$1" envspec="$2" needle_desc="$3"
        local log="$logdir/config-${name}.log"
        local status=0
        : >"$log"
        # shellcheck disable=SC2086
        if timeout 300 env $envspec make -C "$copy" verilog \
            BUILD_DIR="$root/build-$name" git_commit= \
            >>"$log" 2>&1; then
            status=0
        else
            status=$?
        fi

        [[ "$status" -ne 0 ]] || {
            show_log "$log"
            fail "配置错误未被拒绝（$name）：elaboration 成功"
        }
        [[ "$status" -ne 124 ]] || {
            show_log "$log"
            fail "配置错误检查超时（$name）"
        }

        # 当前主线 F/D 只是 Kconfig 占位，elaboration 的拒绝信息带有
        # “Kconfig/RTL”字样；这不能冒充对应配置规则的验证。
        if grep -Eq '占位|尚未实现|requirement failed: RISCV_[FD].*(Kconfig|RTL)' "$log"; then
            case_skip "$name 被“F/D 占位未实现”拒绝，尚未验证 $needle_desc"
            return 2
        fi
        if ! grep -Eq "$needle_desc" "$log"; then
            show_log "$log"
            fail "$name 失败原因不明确，未包含 $needle_desc"
        fi
        echo "[PASS] 配置错误明确失败：$name（$needle_desc）"
        return 0
    }

    # D 依赖 F：RISCV_D=y 且 RISCV_F=n 必须拒绝。
    run_one_config_case "d-without-f" \
        "RISCV_F=n RISCV_D=y FPU_IMPL=none" \
        'RISCV_D.*(RISCV_F|依赖|requires)|(RISCV_F|F 扩展).*RISCV_D'

    # D 需要 FLEN>=64：F+D 且 FLEN=32 必须拒绝。
    run_one_config_case "flen32-with-d" \
        "RISCV_F=y RISCV_D=y FPU_IMPL=hardfloat FPU_FLEN=32 CONFIG_FPU_FLEN=32 FLEN=32" \
        '(FPU_)?FLEN.*(32|64)|64.*(FPU_)?FLEN'

    if [[ "$config_skipped" == 1 ]]; then
        echo "[SKIP] 配置错误检查只完成了占位拒绝，未验证真实 F/D 配置规则"
        exit 0
    fi
}

if [[ $# -lt 1 ]]; then
    echo "用法: $0 <forward|negative|mismatch|fp-mismatch|legacy-fp|config> ..." >&2
    exit 2
fi

mode="$1"
shift
case "$mode" in
    forward)
        [[ $# -eq 8 || $# -eq 9 ]] || { echo "forward 需要 8 或 9 个参数" >&2; exit 2; }
        run_forward "$@"
        ;;
    fp-mismatch)
        [[ $# -eq 6 ]] || { echo "fp-mismatch 需要 6 个参数" >&2; exit 2; }
        run_fp_mismatch "$@"
        ;;
    legacy-fp)
        [[ $# -eq 6 ]] || { echo "legacy-fp 需要 6 个参数" >&2; exit 2; }
        run_legacy_fp "$@"
        ;;
    negative)
        [[ $# -eq 4 ]] || { echo "negative 需要 4 个参数" >&2; exit 2; }
        run_negative "$@"
        ;;
    mismatch)
        [[ $# -eq 6 ]] || { echo "mismatch 需要 6 个参数" >&2; exit 2; }
        run_mismatch "$@"
        ;;
    fdiv-shared)
        [[ $# -eq 5 ]] || { echo "fdiv-shared 需要 5 个参数" >&2; exit 2; }
        run_fdiv_shared "$@"
        ;;
    config)
        [[ $# -eq 2 ]] || { echo "config 需要 2 个参数" >&2; exit 2; }
        run_config_errors "$@"
        ;;
    *)
        echo "未知模式: $mode" >&2
        exit 2
        ;;
esac
