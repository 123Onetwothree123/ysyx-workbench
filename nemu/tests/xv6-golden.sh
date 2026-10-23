#!/usr/bin/env bash
# 生成 xv6 userret/usertrap 的 SDB golden trace。
#
# 与 xv6-ref.sh 的区别：本脚本不跑批处理，而是用 NEMU SDB 的 PC 断点、
# si/info/p/x/iringbuf/ftrace 抓取第一次用户 ecall 附近的现场。
#
# 用法：
#   nemu/tests/xv6-golden.sh
#   OUT=/tmp/opencode/nemu-xv6-golden BUILD_NEMU=0 nemu/tests/xv6-golden.sh
set -euo pipefail

NEMU_HOME=$(cd "$(dirname "$0")/.." && pwd)
ROOT=$(cd "$NEMU_HOME/.." && pwd)
XV6_DIR=$ROOT/xv6-riscv
CROSS=${CROSS:-riscv64-unknown-elf-}
OUT=${OUT:-/tmp/opencode/nemu-xv6-golden}
NEMU=$NEMU_HOME/build/riscv64-nemu-interpreter
KERNEL=$XV6_DIR/kernel/kernel
KERNEL_BIN=$XV6_DIR/kernel/kernel.bin
SYM=$XV6_DIR/kernel/kernel.sym

mkdir -p "$OUT"

if [ "${BUILD_NEMU:-1}" = 1 ]; then
  make -C "$NEMU_HOME" NEMU_HOME="$NEMU_HOME" riscv64-xv6_defconfig >/dev/null
  make -C "$NEMU_HOME" NEMU_HOME="$NEMU_HOME" -j"$(nproc)" >/dev/null
fi
if [ "${BUILD_XV6:-1}" = 1 ]; then
  make -C "$XV6_DIR" -j"$(nproc)" PLATFORM=nemu TOOLPREFIX="$CROSS" >/dev/null
fi

sym() {
  awk -v s="$1" '$2 == s { print "0x" $1; exit }' "$SYM"
}

usertrap=$(sym usertrap)
userret_phys=$(sym userret)
trampoline_phys=$(sym trampoline)
[ -n "$usertrap" ] && [ -n "$userret_phys" ] && [ -n "$trampoline_phys" ] || {
  echo "kernel.sym 缺少 usertrap/userret/trampoline" >&2
  exit 1
}

# trampoline 在用户/内核页表中都映射到 MAXVA-PGSIZE。
userret=$((0x3ffffff000 + userret_phys - trampoline_phys))
sret=$((userret + 0x98))
# usertrap + 0x44 是保存 sepc 到 trapframe 之后（本构建为 0x80003828）。
trapframe_ready=$((usertrap + 0x44))

cat >"$OUT/sdb-script.txt" <<EOF
b $(printf '0x%x' "$userret")
c
p \$pc
p \$sepc
p \$satp
p \$sstatus
p \$sscratch
p \$stvec
info r
si 1
p \$pc
b $(printf '0x%x' "$sret")
c
p \$pc
p \$sepc
p \$sstatus
p \$satp
p \$stvec
p \$sscratch
info r
ftrace on
si 1
p \$pc
p \$sepc
p \$sstatus
p \$satp
info r
ftrace off
ftrace status
ftrace now
b $(printf '0x%x' "$usertrap")
c
p \$pc
p \$sepc
p \$scause
p \$stval
p \$sstatus
p \$satp
p \$stvec
p \$sscratch
p \$a0
info r
iringbuf
ftrace on
b $(printf '0x%x' "$trapframe_ready")
c
p \$s1
p \$a0
x 1g \$s1+0x50
x 1g \$s1+0x58
x 32g *(\$s1+0x58)
ftrace off
ftrace status
ftrace history
q
EOF

cd "$NEMU_HOME"
timeout "${TIMEOUT:-90}" "$NEMU" --elf "$KERNEL" "$KERNEL_BIN" \
  <"$OUT/sdb-script.txt" >"$OUT/sdb-userret-usertrap.log" 2>&1 || status=$?
if [ "${status:-0}" -ne 0 ] && [ "${status:-0}" -ne 124 ] && [ "${status:-0}" -ne 143 ]; then
  echo "SDB 运行失败，status=$status" >&2
  tail -40 "$OUT/sdb-userret-usertrap.log" >&2
  exit 1
fi

{
  echo "usertrap=$usertrap"
  printf 'userret=0x%x\n' "$userret"
  printf 'sret=0x%x\n' "$sret"
  echo "trampoline=0x3ffffff000"
  printf 'trapframe_ready=0x%x\n' "$trapframe_ready"
} >"$OUT/SYMBOLS.txt"

echo "golden SDB 日志：$OUT/sdb-userret-usertrap.log"
echo "断点/符号：$OUT/SYMBOLS.txt"
