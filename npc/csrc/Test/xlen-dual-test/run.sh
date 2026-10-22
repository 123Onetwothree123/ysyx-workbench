#!/usr/bin/env bash
# xlen-dual-test 的运行器：按 CONFIG_XLEN 选择 RV32/RV64 期望值。
# 所有仿真都用短超时，失败时打印对应日志尾部。
set -euo pipefail

mode="${1:?mode}"
npc_bin="${2:?npc binary}"
guest="${3:?guest image}"
xlen="${4:?xlen}"
log="${5:?log path}"

mkdir -p "$(dirname -- "$log")"

fail() {
    local message="$1"
    shift
    echo "[FAIL] $message" >&2
    for file in "$@"; do
        if [[ -f "$file" ]]; then
            echo "---- $file (tail) ----" >&2
            tail -n 30 "$file" >&2 || true
        fi
    done
    exit 1
}

run_sdb() {
    local regs_log="${log%.log}-regs.log"
    local pc_log="${log%.log}-pc.log"
    local -a commands

    if [[ "$xlen" == 64 ]]; then
        commands=(
            'c'
            'p $s1'
            'p $s2'
            'p 0x100000000'
            'x 1g 0x80001000'
            'p read64(0x80001000)'
            'q'
        )
    else
        commands=(
            'c'
            'p $s1'
            'p $s2'
            'p 0xffffffff'
            'x 1w 0x80001000'
            'p read32(0x80001000)'
            'q'
        )
    fi

    printf '%s\n' "${commands[@]}" |
        timeout 15 "$npc_bin" "$guest" >"$regs_log" 2>&1 ||
        fail "SDB 寄存器/内存命令失败" "$regs_log"

    # 单独再跑一次，把 halt 后的 $pc 与其它高值输出分开，便于精确断言。
    printf 'c\np $pc\nq\n' |
        timeout 15 "$npc_bin" "$guest" >"$pc_log" 2>&1 ||
        fail "SDB continue/PC 检查失败" "$pc_log"

    grep -Fq 'HIT GOOD TRAP' "$regs_log" ||
        fail "guest 未触发 HIT GOOD TRAP" "$regs_log"
    grep -Fq 'HIT GOOD TRAP' "$pc_log" ||
        fail "guest 未触发 HIT GOOD TRAP" "$pc_log"

    if [[ "$xlen" == 64 ]]; then
        grep -Fq '0x123456789abcdef0' "$regs_log" ||
            fail "SDB 未保留 64 位 GPR 高半区" "$regs_log"
        grep -Fq '0xffffffff80000000' "$regs_log" ||
            fail "SDB 未保留 64 位符号扩展 GPR" "$regs_log"
        grep -Fq '无符号（十进制）：4294967296' "$regs_log" ||
            fail "SDB 未把 2^32 当作合法 64 位值" "$regs_log"
        grep -Fq '0x80001000: 0x1122334455667788' "$regs_log" ||
            fail "SDB x/g 未显示 64 位数据" "$regs_log"
        grep -Fq '0x1122334455667788' "$regs_log" ||
            fail "SDB read64 未显示 64 位数据" "$regs_log"
        grep -Eq '十六进制： +0x1[0-9a-f]{8,}' "$pc_log" ||
            fail "RV64 halt 后 PC 不是 4GiB 以上的 64 位值" "$pc_log"
    else
        grep -Fq '0x89abcdef' "$regs_log" ||
            fail "RV32 SDB 未显示 32 位 GPR" "$regs_log"
        grep -Fq '0x80000000' "$regs_log" ||
            fail "RV32 SDB 未显示 32 位高半区值" "$regs_log"
        grep -Fq '0x80001000: 0x12345678' "$regs_log" ||
            fail "RV32 SDB x/w 未显示 32 位数据" "$regs_log"
        grep -Eq '十六进制： +0x8000[0-9a-f]+' "$pc_log" ||
            fail "RV32 halt 后 PC 异常" "$pc_log"
    fi
}

run_mtrace() {
    local mtrace_log="${log%.log}-mtrace.log"
    printf 'c\nq\n' |
        timeout 15 "$npc_bin" "$guest" >"$mtrace_log" 2>&1 ||
        fail "mtrace 运行失败" "$mtrace_log"

    grep -Fq 'HIT GOOD TRAP' "$mtrace_log" ||
        fail "mtrace guest 未触发 HIT GOOD TRAP" "$mtrace_log"

    if [[ "$xlen" == 64 ]]; then
        # 高 VA 取指后 halt：mtrace 的 PC 与 64 位 Data 必须完整。
        # Addr 是 MMU 翻译后的物理地址（0x80002000），这是当前 RTL 的
        # mtrace 语义；高 VA 本身由上面的 SDB p $pc 覆盖。
        grep -Eq 'Memory Write: PC=0x1[0-9a-f]{8,}, Addr=0x80002000, Data=0x8877665544332211, Width=8B' \
            "$mtrace_log" ||
            fail "RV64 mtrace 未记录高 PC / 64 位数据 store" "$mtrace_log"
    else
        grep -Eq 'Memory Write: PC=0x8000[0-9a-f]+, Addr=0x80001000, Data=0x12345678, Width=4B' \
            "$mtrace_log" ||
            fail "RV32 mtrace 未记录 32 位 store" "$mtrace_log"
    fi
}

run_difftest() {
    local diff_log="${log%.log}-difftest.log"
    local status=0
    timeout 15 "$npc_bin" "$guest" --diff /definitely/not/a/real/nemu-ref.so \
        >"$diff_log" 2>&1 || status=$?

    [[ $status -ne 0 ]] ||
        fail "DiffTest 在 $([[ "$xlen" == 64 ]] && echo RV64 || echo RV32) 下错误地接受了 REF" "$diff_log"

    if [[ "$xlen" == 64 ]]; then
        grep -Fq 'DiffTest 初始化失败' "$diff_log" ||
            fail "RV64 DiffTest 失败信息不明确" "$diff_log"
        grep -Fq 'RV64 DiffTest/NEMU 未支持' "$diff_log" ||
            fail "RV64 DiffTest 未明确说明 REF ABI 只支持 32 位" "$diff_log"
    else
        # RV32 应走到 dlopen 失败，而不是误报“RV64 不支持”。
        grep -Fq 'DiffTest 初始化失败' "$diff_log" ||
            fail "RV32 DiffTest 失败信息不明确" "$diff_log"
        grep -Fq '打开 DiffTest REF 失败' "$diff_log" ||
            fail "RV32 DiffTest 未按预期加载 REF" "$diff_log"
    fi
}

case "$mode" in
    sdb) run_sdb ;;
    mtrace) run_mtrace ;;
    difftest) run_difftest ;;
    *)
        echo "未知 mode: $mode" >&2
        exit 2
        ;;
esac

echo "[PASS] $mode (CONFIG_XLEN=$xlen)"
