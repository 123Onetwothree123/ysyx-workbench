#!/usr/bin/env bash
# 检查关键镜像确实使用硬件 F/D，而不是软浮点或空壳代码。
# 用法: audit.sh <c|rv64d|rv32f> <readelf> <objdump> <nm> <elf>
set -euo pipefail

mode="$1"
readelf_bin="$2"
objdump_bin="$3"
nm_bin="$4"
elf="$5"

fail() {
    echo "[FAIL] ELF 审计（$mode）: $*" >&2
    exit 1
}

[[ -f "$elf" ]] || fail "镜像不存在: $elf"

header="$("$readelf_bin" -h "$elf")"
attrs="$("$readelf_bin" -A "$elf" 2>/dev/null || true)"
disasm="$("$objdump_bin" -d "$elf")"

require_mnemonic() {
    local mnemonic="$1"
    grep -Eq "[[:space:]]${mnemonic}[[:space:]]" <<<"$disasm" ||
        fail "反汇编缺少 $mnemonic"
}

case "$mode" in
    c)
        grep -q 'double-float ABI' <<<"$header" ||
            fail "不是 lp64d 硬浮点 ABI"
        grep -Eq 'Tag_RISCV_arch: ".*_f[0-9].*_d[0-9]' <<<"$attrs" ||
            fail "ELF arch 属性没有同时包含 F/D"
        for mnemonic in \
            fadd.d fsub.d fmul.d fdiv.d fmadd.d \
            flw fsw fld fsd fcvt.w.d fcvt.l.d fcvt.wu.s \
            fmv.x.d fclass.d feq.d flt.d fle.d; do
            require_mnemonic "$mnemonic"
        done
        if grep -Eq '\b__((add|sub|mul|div|fix|float|cmp|neg|extend|trunc)[a-z0-9]*[sd]f?[0-9]|adddf3|subdf3|muldf3|divdf3)\b' <<<"$("$nm_bin" "$elf")"; then
            fail "出现 libgcc 软浮点符号"
        fi
        ;;
    rv64d)
        grep -Eq 'Tag_RISCV_arch: ".*_f[0-9].*_d[0-9]' <<<"$attrs" ||
            fail "ELF arch 属性没有同时包含 F/D"
        for mnemonic in \
            fadd.s fadd.d fsub.d fmul.d fdiv.d fsqrt.d \
            fmadd.d fmsub.d fnmsub.d fnmadd.d \
            fmin.d fmax.d fsgnj.d fsgnjn.d fsgnjx.d \
            flw fsw fld fsd fcvt.d.s fcvt.s.d fcvt.w.d fcvt.l.d \
            fmv.x.d fmv.d.x fclass.d feq.d flt.d fle.d; do
            require_mnemonic "$mnemonic"
        done
        ;;
    rv32f)
        grep -Eq 'Tag_RISCV_arch: ".*_f[0-9]' <<<"$attrs" ||
            fail "ELF arch 属性没有 F"
        if grep -Eq 'Tag_RISCV_arch: ".*_d[0-9]' <<<"$attrs"; then
            fail "RV32F 镜像不应启用 D"
        fi
        for mnemonic in \
            fadd.s fsub.s fmul.s fdiv.s fsqrt.s \
            fmadd.s fmsub.s fnmsub.s fnmadd.s \
            fmin.s fmax.s fsgnj.s fsgnjn.s fsgnjx.s \
            flw fsw fcvt.w.s fcvt.wu.s fcvt.s.w \
            fmv.x.w fmv.w.x feq.s flt.s fle.s; do
            require_mnemonic "$mnemonic"
        done
        ;;
    *)
        fail "未知模式: $mode"
        ;;
esac

echo "[PASS] ELF 审计：$mode $elf"
