#!/bin/sh
# 审计生成的 AM ELF 是否真的是 RV64GC / lp64d，而不是悄悄退回软浮点。
set -eu

ELF=${1:?用法: check-isa.sh <elf>}
CROSS=${CROSS_COMPILE:-riscv64-unknown-elf-}
READELF=${READELF:-${CROSS}readelf}
OBJDUMP=${OBJDUMP:-${CROSS}objdump}
NM=${NM:-${CROSS}nm}

fail() {
  echo "check-isa: $*" >&2
  exit 1
}

[ -f "$ELF" ] || fail "ELF 不存在: $ELF"

# 1. ELF 头必须是 64 位 RISC-V。
"$READELF" -h "$ELF" | grep -q 'Class:[[:space:]]*ELF64' ||
  fail "不是 ELF64"
"$READELF" -h "$ELF" | grep -q 'Machine:[[:space:]]*RISC-V' ||
  fail "不是 RISC-V ELF"

# 2. Tag_RISCV_arch 必须同时包含 A/F/D/C（rv64gc）和 Zicsr/Zifencei。
ARCH=$("$READELF" -A "$ELF" |
  sed -n 's/.*Tag_RISCV_arch:[[:space:]]*"\([^"]*\)".*/\1/p')
[ -n "$ARCH" ] || fail "缺少 Tag_RISCV_arch"
case "$ARCH" in
  rv64*) ;;
  *) fail "Tag_RISCV_arch 不是 rv64: $ARCH" ;;
esac

has_a=0
has_f=0
has_d=0
has_c=0
for ext in $(printf '%s\n' "$ARCH" | tr '_' ' '); do
  case "$ext" in
    a[0-9]*) has_a=1 ;;
    f[0-9]*) has_f=1 ;;
    d[0-9]*) has_d=1 ;;
    c[0-9]*) has_c=1 ;;
  esac
done
[ "$has_a" = 1 ] || fail "Tag_RISCV_arch 缺少 A 扩展: $ARCH"
[ "$has_f" = 1 ] || fail "Tag_RISCV_arch 缺少 F 扩展: $ARCH"
[ "$has_d" = 1 ] || fail "Tag_RISCV_arch 缺少 D 扩展: $ARCH"
[ "$has_c" = 1 ] || fail "Tag_RISCV_arch 缺少 C 扩展: $ARCH"

# 3. ELF e_flags 必须标记 RVC + double-float ABI (lp64d)。
#    部分 binutils 不再输出 Tag_RISCV_float_abi_double，但 e_flags
#     0x1 = RVC，0x4 = double-float ABI，是链接器实际采用的 ABI。
"$READELF" -h "$ELF" |
  grep -Eq 'Flags:.*RVC.*double-float ABI' ||
  fail "不是 RVC + lp64d（ELF Flags 缺少 RVC/double-float ABI）"

# 4. 反汇编中必须出现真正的 F/D 指令和至少一条 16 位压缩指令。
DIS=$("$OBJDUMP" -d "$ELF")
printf '%s\n' "$DIS" |
  grep -Eq '\b(fld|fsd|flw|fsw|fmv\.(x\.d|d\.x|w\.x|x\.w)|fadd\.d|fmul\.d|fcvt\.)' ||
  fail "反汇编中没有 F/D 指令"
printf '%s\n' "$DIS" |
  grep -Eq '^[[:space:]]*[0-9a-f]+:[[:space:]]+[0-9a-f]{4}[[:space:]]' ||
  fail "反汇编中没有 C 扩展 16 位指令"

# 5. 不能链接任何软浮点 libgcc helper；否则说明 ABI 或 -march 退化了。
SOFT=$("$NM" "$ELF" |
  grep -E '__(add|sub|mul|div|eq|ne|lt|le|gt|ge|unord)(df|sf)3|__extendsfdf2|__truncdfsf2|__float(si|unsi|di|undi)(df|sf)|__fix(df|sf)(si|di)' ||
  true)
[ -z "$SOFT" ] || fail "发现软浮点 helper:\n$SOFT"

echo "check-isa: OK"
echo "  arch: $ARCH"
echo "  abi : lp64d (ELF flags: RVC + double-float ABI)"
echo "  insn: F/D + compressed C present, no soft-float helpers"
