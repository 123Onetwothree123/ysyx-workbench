#!/usr/bin/env bash
# 构建并运行官方 riscv-arch-test（RV64 I/M）用例，非交互、逐个运行。
#
# 特点：
#   * 使用 riscv-arch-test-am 的测试源码和官方 Makefile 流程生成 RV64 镜像；
#   * 每生成一个镜像就调用 NPC 的 --batch 模式跑完，不依赖 stdin/readline；
#   * 每个用例都有独立日志和短超时，超时/未触发 trap/GOOD/BAD 都单独报告；
#   * 支持 ALL 子集、排除列表、privilege 等 ISA 目录；
#   * privilege 用例自动注入 rvtest_mtrap_routine 包装头，生成官方 trap
#     prolog（仓库自带的 model_test.h 缺少该宏，否则 ecall/misalign 必失败）。
#
# 示例：
#   npc/scripts/rv64_arch_test.sh                      # 全部 I+M
#   npc/scripts/rv64_arch_test.sh add-01 mul-01 ecall  # 指定用例
#   ALL="add-01 lw-align-01" npc/scripts/rv64_arch_test.sh
#   npc/scripts/rv64_arch_test.sh -i "I M privilege" --expect-fail ecall
#   npc/scripts/rv64_arch_test.sh --list
#
# 不修改 riscv-arch-test-am 源码；镜像、中间文件和日志默认放在
# npc/build/arch-test-rv64（已被 git 忽略），可随时删除。

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NPC_DIR="$(dirname -- "$SCRIPT_DIR")"
ROOT_DIR="$(dirname -- "$NPC_DIR")"

SUITE_DIR="${ARCH_TEST_DIR:-$ROOT_DIR/riscv-arch-test-am}"
# 始终优先使用本 checkout 的 abstract-machine，避免在 worktree 中误用
# 另一个工作区的 AM/NPC。需要外部 AM 时显式设置 ARCH_TEST_AM_HOME。
AM_HOME="${ARCH_TEST_AM_HOME:-$ROOT_DIR/abstract-machine}"
ARCH="${ARCH:-riscv64-npc}"

ISA_LIST="${TEST_ISA:-I M}"
TIMEOUT_SEC="${TIMEOUT:-10}"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"
NPC_BIN="${NPC_BIN:-$NPC_DIR/build/ysyx_26030103_npc}"
WORK_DIR="${WORK_DIR:-$NPC_DIR/build/arch-test-rv64}"

BUILD_ONLY=0
RUN_ONLY=0
LIST_ONLY=0
CLEAN=0
BUILD_NPC=0
FAIL_FAST=0

REQUESTED_TEXT="${ALL:-}"
EXCLUDE_TEXT="${EXCLUDE_TEST:-}"
EXPECTED_TEXT="${EXPECTED_FAIL:-}"

usage() {
    cat <<'EOF'
用法: npc/scripts/rv64_arch_test.sh [选项] [测试名...]

默认构建并运行官方 arch-test 的 RV64 I+M 全部用例（64 个）。

选项:
  -a, --all LIST        只构建/运行 LIST 中的用例（空格或逗号分隔）
  -e, --exclude LIST    排除用例（空格或逗号分隔）
  -i, --isa LIST        测试目录列表，默认 "I M"；可加 privilege 等
  -t, --timeout SEC     单个用例运行超时，默认 10 秒
  -j, --jobs N          构建并行度，默认 nproc
      --npc-bin PATH    NPC 可执行文件路径
      --work-dir DIR    镜像/日志目录，默认 npc/build/arch-test-rv64
      --expect-fail LIST 预期失败/超时的用例，报告 KNOWN-FAIL 且不影响退出码
      --build-only      只构建镜像，不运行
      --run-only        跳过构建，直接运行已有镜像（需配合 --all 或已有全部镜像）
      --build-npc       若 NPC 不存在，先按当前配置构建 npc/build/ysyx_26030103_npc
      --list            列出选定 ISA 目录中的用例后退出
      --fail-fast       第一个非预期失败后立即停止
      --clean           运行前清空 --work-dir 下的旧构建产物（保留 logs/）
  -h, --help            显示本帮助

环境变量:
  ALL / EXCLUDE_TEST / TEST_ISA / TIMEOUT / NPC_BIN / WORK_DIR
  ARCH_TEST_DIR      外部 riscv-arch-test-am 路径
  ARCH_TEST_AM_HOME  外部 abstract-machine 路径

退出码: 0 全部通过（或仅预期失败）；1 有非预期失败/超时/构建失败。
EOF
}

die() {
    echo "错误: $*" >&2
    exit 2
}

log() {
    echo "[arch-test] $*"
}

# 把空格/逗号分隔的列表按空白切开并输出到 stdout。
split_list() {
    local text="$1"
    text="${text//,/ }"
    # shellcheck disable=SC2086
    printf '%s\n' $text
}

# 解析命令行；位置参数和 --all 都视为测试名。
CLI_TESTS=()
while (($# > 0)); do
    case "$1" in
        -a|--all)
            [[ $# -ge 2 ]] || die "$1 需要参数"
            REQUESTED_TEXT+=" $2"
            shift 2
            ;;
        -e|--exclude)
            [[ $# -ge 2 ]] || die "$1 需要参数"
            EXCLUDE_TEXT+=" $2"
            shift 2
            ;;
        -i|--isa)
            [[ $# -ge 2 ]] || die "$1 需要参数"
            ISA_LIST="$2"
            shift 2
            ;;
        -t|--timeout)
            [[ $# -ge 2 ]] || die "$1 需要参数"
            TIMEOUT_SEC="$2"
            shift 2
            ;;
        -j|--jobs)
            [[ $# -ge 2 ]] || die "$1 需要参数"
            JOBS="$2"
            shift 2
            ;;
        --npc-bin)
            [[ $# -ge 2 ]] || die "$1 需要参数"
            NPC_BIN="$2"
            shift 2
            ;;
        --work-dir)
            [[ $# -ge 2 ]] || die "$1 需要参数"
            WORK_DIR="$2"
            shift 2
            ;;
        --expect-fail)
            [[ $# -ge 2 ]] || die "$1 需要参数"
            EXPECTED_TEXT+=" $2"
            shift 2
            ;;
        --build-only)
            BUILD_ONLY=1
            shift
            ;;
        --run-only)
            RUN_ONLY=1
            shift
            ;;
        --build-npc)
            BUILD_NPC=1
            shift
            ;;
        --list)
            LIST_ONLY=1
            shift
            ;;
        --fail-fast)
            FAIL_FAST=1
            shift
            ;;
        --clean)
            CLEAN=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        --)
            shift
            while (($# > 0)); do
                CLI_TESTS+=("$1")
                shift
            done
            ;;
        -*)
            die "未知选项: $1（--help 查看用法）"
            ;;
        *)
            CLI_TESTS+=("$1")
            shift
            ;;
    esac
done

[[ "$TIMEOUT_SEC" =~ ^[1-9][0-9]*$ ]] || die "--timeout 必须是正整数"
[[ "$JOBS" =~ ^[1-9][0-9]*$ ]] || die "--jobs 必须是正整数"
NPC_BIN="$(realpath -m -- "$NPC_BIN")"
WORK_DIR="$(realpath -m -- "$WORK_DIR")"
SUITE_DIR="$(realpath -m -- "$SUITE_DIR")"
AM_HOME="$(realpath -m -- "$AM_HOME")"
[[ "$ARCH" == riscv64* ]] || die "本脚本只支持 RV64（当前 ARCH=$ARCH）"
[[ -f "$SUITE_DIR/Makefile" ]] ||
    die "找不到 $SUITE_DIR/Makefile；请先 git submodule update --init riscv-arch-test-am"
[[ -f "$AM_HOME/Makefile" ]] ||
    die "找不到 $AM_HOME/Makefile；请设置 ARCH_TEST_AM_HOME 指向 abstract-machine"
command -v riscv64-unknown-elf-gcc >/dev/null 2>&1 ||
    die "缺少 riscv64-unknown-elf-gcc（AM 的 riscv64-npc 构建依赖它）"

# ---- 发现并筛选用例 -------------------------------------------------------
declare -a DISCOVERED=()
declare -A TEST_ISA_DIR=()
for isa in $ISA_LIST; do
    isa_dir="$SUITE_DIR/riscv-test-suite/rv64i_m/$isa/src"
    [[ -d "$isa_dir" ]] || die "找不到 ISA 目录: $isa_dir"
    while IFS= read -r src; do
        name="$(basename -- "$src" .S)"
        if [[ -z "${TEST_ISA_DIR[$name]+x}" ]]; then
            DISCOVERED+=("$name")
            TEST_ISA_DIR["$name"]="$isa"
        fi
    done < <(find "$isa_dir" -maxdepth 1 -type f -name '*.S' | sort)
done
((${#DISCOVERED[@]} > 0)) || die "在 $ISA_LIST 中没有找到任何测试"

declare -a REQUESTED=()
while IFS= read -r name; do
    [[ -n "$name" ]] || continue
    name="${name%.S}"
    REQUESTED+=("$name")
done < <(split_list "$REQUESTED_TEXT")
for name in "${CLI_TESTS[@]}"; do
    name="${name%.S}"
    REQUESTED+=("$name")
done

declare -a EXCLUDED=()
while IFS= read -r name; do
    [[ -n "$name" ]] || continue
    EXCLUDED+=("${name%.S}")
done < <(split_list "$EXCLUDE_TEXT")

declare -A EXCLUDE_SET=()
for name in "${EXCLUDED[@]}"; do
    EXCLUDE_SET["$name"]=1
done

declare -a SELECTED=()
declare -A SELECTED_SET=()
if ((${#REQUESTED[@]} > 0)); then
    for name in "${REQUESTED[@]}"; do
        [[ -n "${TEST_ISA_DIR[$name]+x}" ]] ||
            die "用例 '$name' 不在 ISA 列表 '$ISA_LIST' 中（--list 查看可用用例）"
        if [[ -n "${EXCLUDE_SET[$name]+x}" ]]; then
            continue
        fi
        if [[ -z "${SELECTED_SET[$name]+x}" ]]; then
            SELECTED+=("$name")
            SELECTED_SET["$name"]=1
        fi
    done
else
    for name in "${DISCOVERED[@]}"; do
        if [[ -n "${EXCLUDE_SET[$name]+x}" ]]; then
            continue
        fi
        SELECTED+=("$name")
        SELECTED_SET["$name"]=1
    done
fi
((${#SELECTED[@]} > 0)) || die "筛选后没有用例可运行"

if ((LIST_ONLY)); then
    for name in "${SELECTED[@]}"; do
        printf '%-28s %s\n' "$name" "${TEST_ISA_DIR[$name]}"
    done
    echo "共 ${#SELECTED[@]} 个用例"
    exit 0
fi

declare -A EXPECTED_SET=()
while IFS= read -r name; do
    [[ -n "$name" ]] || continue
    EXPECTED_SET["${name%.S}"]=1
done < <(split_list "$EXPECTED_TEXT")
for name in "${!EXPECTED_SET[@]}"; do
    [[ -n "${TEST_ISA_DIR[$name]+x}" ]] || die "预期失败用例 '$name' 不在当前 ISA 列表中"
done

# ---- 输出/日志目录 --------------------------------------------------------
RUN_ID="$(date +%Y%m%d-%H%M%S)"
LOG_DIR="$WORK_DIR/logs/$RUN_ID"
if [[ -e "$LOG_DIR" ]]; then
    LOG_DIR="$LOG_DIR-$$"
fi
BUILD_DIR="$WORK_DIR/build"
MK_DIR="$WORK_DIR/mk"
WRAP_DIR="$WORK_DIR/wrap"
BUILD_LOG="$LOG_DIR/build.log"
SUMMARY_LOG="$LOG_DIR/summary.txt"
mkdir -p "$LOG_DIR" "$MK_DIR" "$BUILD_DIR" "$WRAP_DIR"
: >"$SUMMARY_LOG"

# 官方 privilege 用例自身声明 def rvtest_mtrap_routine=True，但仓库里的
# model_test.h 没有为 NPC 定义这个宏；不定义时 arch_test.h 不生成 mtvec
# 初始化/trap prolog，ecall/ebreak/misalign 会直接跳到 0 地址。这里在
# out-of-tree 包装头里补上宏，再 include_next 官方 model_test.h，不修改
# riscv-arch-test-am 源码。
cat >"$WRAP_DIR/model_test.h" <<'EOF'
#ifndef NPC_ARCH_TEST_MODEL_TEST_WRAPPER
#define NPC_ARCH_TEST_MODEL_TEST_WRAPPER
#ifndef rvtest_mtrap_routine
#define rvtest_mtrap_routine
#endif
#include_next "model_test.h"
#endif
EOF

if ((CLEAN)); then
    log "清理 $BUILD_DIR 和 $MK_DIR"
    rm -rf "$BUILD_DIR" "$MK_DIR"
    mkdir -p "$BUILD_DIR" "$MK_DIR"
fi

# ---- NPC 构建/检查 --------------------------------------------------------
if ((BUILD_NPC)) && [[ ! -x "$NPC_BIN" ]]; then
    log "构建 NPC: $NPC_BIN"
    (
        cd "$NPC_DIR"
        if [[ ! -f .config || ! -f include/config/auto.conf ]]; then
            make rv64_soft_defconfig
        fi
        npc_target="${NPC_BIN#"$NPC_DIR"/}"
        make -j"$JOBS" "$npc_target"
    )
fi
if ((!BUILD_ONLY)); then
    [[ -x "$NPC_BIN" ]] ||
        die "NPC 可执行文件不存在或不可执行: $NPC_BIN（先构建，或加 --build-npc，或 --npc-bin）"
fi

# ---- 构建镜像 -------------------------------------------------------------
image_path() {
    printf '%s/%s-%s.bin' "$BUILD_DIR" "$1" "$ARCH"
}
elf_path() {
    printf '%s/%s-%s.elf' "$BUILD_DIR" "$1" "$ARCH"
}

build_one() {
    local name="$1"
    local isa="${TEST_ISA_DIR[$name]}"
    local src="$SUITE_DIR/riscv-test-suite/rv64i_m/$isa/src/$name.S"
    local mk="$MK_DIR/$name.mk"
    [[ -f "$src" ]] || die "找不到测试源文件: $src"
    {
        printf 'NAME = %s\n' "$name"
        printf 'SRCS = %s\n' "$src"
        if [[ "$isa" == "privilege" ]]; then
            # 包装头必须排在官方 env 之前，才能抢在 model_test.h 前定义宏。
            printf 'INC_PATH := %s\n' "$WRAP_DIR"
            printf 'INC_PATH += %s\n' "$SUITE_DIR/riscv-test-suite/env"
        else
            printf 'INC_PATH += %s\n' "$SUITE_DIR/riscv-test-suite/env"
        fi
        printf 'include $(AM_HOME)/Makefile\n'
    } >"$mk"

    log "构建 $name"
    if ! (cd "$WORK_DIR" &&
          make -s -f "$mk" ARCH="$ARCH" AM_HOME="$AM_HOME" \
              NPC_HOME="$NPC_DIR" -j"$JOBS" image) >>"$BUILD_LOG" 2>&1; then
        echo "[BUILD-FAIL] $name" | tee -a "$SUMMARY_LOG" >&2
        echo "构建日志: $BUILD_LOG" >&2
        tail -n 30 "$BUILD_LOG" >&2 || true
        exit 1
    fi
    rm -f "$mk"
    [[ -f "$(image_path "$name")" ]] ||
        die "构建后找不到镜像: $(image_path "$name")"
}

if ((!RUN_ONLY)); then
    log "镜像目录: $BUILD_DIR"
    for name in "${SELECTED[@]}"; do
        build_one "$name"
    done
    echo "构建完成: ${#SELECTED[@]} 个镜像 (日志: $BUILD_LOG)" | tee -a "$SUMMARY_LOG"
else
    for name in "${SELECTED[@]}"; do
        [[ -f "$(image_path "$name")" ]] ||
            die "--run-only 但镜像不存在: $(image_path "$name")"
    done
fi

if ((BUILD_ONLY)); then
    echo "镜像目录: $BUILD_DIR"
    exit 0
fi

# ---- 逐条运行 -------------------------------------------------------------
if [[ -t 1 ]]; then
    C_PASS=$'\033[1;32m'
    C_FAIL=$'\033[1;31m'
    C_TIMEOUT=$'\033[1;33m'
    C_RESET=$'\033[0m'
else
    C_PASS=""
    C_FAIL=""
    C_TIMEOUT=""
    C_RESET=""
fi

pass_count=0
fail_count=0
timeout_count=0
known_fail_count=0
unexpected_fail=0
declare -a FAILED_NAMES=()

log "运行 ${#SELECTED[@]} 个用例（超时 ${TIMEOUT_SEC}s，NPC: $NPC_BIN）"
for name in "${SELECTED[@]}"; do
    bin="$(image_path "$name")"
    elf="$(elf_path "$name")"
    test_log="$LOG_DIR/$name.log"
    cmd=("$NPC_BIN" "$bin")
    if [[ -f "$elf" ]]; then
        cmd+=(--elf "$elf")
    fi
    cmd+=(--batch)

    rc=0
    set +e
    timeout --kill-after=2s "${TIMEOUT_SEC}s" "${cmd[@]}" >"$test_log" 2>&1
    rc=$?
    set -e

    verdict=""
    detail=""
    if [[ $rc -eq 124 || $rc -eq 137 ]]; then
        verdict="TIMEOUT"
        detail="timeout ${TIMEOUT_SEC}s"
    elif grep -Fq 'HIT GOOD TRAP' "$test_log" && [[ $rc -eq 0 ]]; then
        verdict="PASS"
    elif grep -Fq 'HIT BAD TRAP' "$test_log"; then
        verdict="FAIL"
        detail="$(grep -Fm1 'HIT BAD TRAP' "$test_log" | tr -d '\r')"
    elif grep -Fq 'NPC在未触发陷阱的情况下退出' "$test_log"; then
        verdict="FAIL"
        detail="no trap / SDB exited before halt"
    else
        verdict="FAIL"
        detail="rc=$rc, no GOOD/BAD trap in log"
    fi

    if [[ "$verdict" == "PASS" ]]; then
        pass_count=$((pass_count + 1))
        printf '%s[PASS]%s     %-28s %s\n' "$C_PASS" "$C_RESET" "$name" "$test_log"
        echo "PASS $name" >>"$SUMMARY_LOG"
    else
        if [[ -n "${EXPECTED_SET[$name]+x}" ]]; then
            known_fail_count=$((known_fail_count + 1))
            if [[ "$verdict" == "TIMEOUT" ]]; then
                timeout_count=$((timeout_count + 1))
            fi
            printf '%s[KNOWN-FAIL]%s %-28s %s (%s)\n' \
                "$C_TIMEOUT" "$C_RESET" "$name" "$verdict" "$detail"
            echo "KNOWN-FAIL $name $verdict $detail" >>"$SUMMARY_LOG"
        else
            if [[ "$verdict" == "TIMEOUT" ]]; then
                timeout_count=$((timeout_count + 1))
                printf '%s[TIMEOUT]%s   %-28s %s\n' \
                    "$C_TIMEOUT" "$C_RESET" "$name" "$detail"
            else
                fail_count=$((fail_count + 1))
                printf '%s[FAIL]%s      %-28s %s\n' \
                    "$C_FAIL" "$C_RESET" "$name" "$detail"
            fi
            echo "FAIL $name $verdict $detail" >>"$SUMMARY_LOG"
            unexpected_fail=$((unexpected_fail + 1))
            FAILED_NAMES+=("$name")
            if ((FAIL_FAST)); then
                break
            fi
        fi
    fi
done

echo
echo "结果: PASS=$pass_count FAIL=$fail_count TIMEOUT=$timeout_count KNOWN-FAIL=$known_fail_count"
echo "日志目录: $LOG_DIR"
echo "汇总: $SUMMARY_LOG"
if ((${#FAILED_NAMES[@]} > 0)); then
    echo "失败用例: ${FAILED_NAMES[*]}"
fi
{
    echo
    echo "PASS=$pass_count FAIL=$fail_count TIMEOUT=$timeout_count KNOWN-FAIL=$known_fail_count"
    echo "NPC=$NPC_BIN"
    echo "ARCH=$ARCH TEST_ISA=$ISA_LIST"
} >>"$SUMMARY_LOG"

((unexpected_fail == 0))
