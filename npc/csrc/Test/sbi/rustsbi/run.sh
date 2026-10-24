#!/usr/bin/env bash
# RustSBI NPC 冒烟测试。
#
# 用法：
#   ./run.sh npc     # 默认，使用 npc/build/ysyx_26030103_npc
#   ./run.sh qemu    # 交叉验证（QEMU virt 实现 PMP，固件需带 PMP）
#
# 依赖：
#   - 已完成 make defconfig（RV64 + S 模式）
#   - riscv64-unknown-elf-gcc / objcopy
#   - NPC 模式需要先构建 npc/build/ysyx_26030103_npc
#   - QEMU 模式需要 qemu-system-riscv64
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NPC_HOME="$(cd "$HERE/../../../.." && pwd)"
MODE="${1:-npc}"
BUILD="$NPC_HOME/build/rustsbi-test"
CC="${RISCV_CC:-riscv64-unknown-elf-gcc}"
OBJCOPY="${RISCV_OBJCOPY:-riscv64-unknown-elf-objcopy}"

die() {
    echo "错误: $*" >&2
    exit 1
}

cfg_value() {
    local key="$1" default="$2" value=""
    if [ -f "$NPC_HOME/.config" ]; then
        value="$(sed -n "s/^${key}=//p" "$NPC_HOME/.config" | tail -n 1)"
    fi
    value="${value%\"}"
    value="${value#\"}"
    printf '%s' "${value:-$default}"
}

[ -f "$NPC_HOME/.config" ] || die "缺少 npc/.config，请先执行 make defconfig"
command -v "$CC" >/dev/null 2>&1 || die "找不到 $CC；请安装 RISC-V 裸机工具链"
command -v "$OBJCOPY" >/dev/null 2>&1 || die "找不到 $OBJCOPY"

FW_BASE="$(cfg_value CONFIG_SBI_FW_BASE 0x80000000)"
NEXT_ADDR="$(cfg_value CONFIG_SBI_NEXT_ADDR 0x80200000)"
OFFSET=$(($NEXT_ADDR - $FW_BASE))

mkdir -p "$BUILD"
rm -f "$BUILD"/payload-* "$BUILD"/combined.bin "$BUILD"/*.log

case "$MODE" in
    npc)
        MAKE_ENV=()
        FW_OUT="$NPC_HOME/build/rustsbi"
        ;;
    qemu)
        # QEMU virt 实现了 PMP；NPC 默认 CONFIG_HAS_PMP=n。把带 PMP 的
        # 固件放到独立目录，避免覆盖 NPC 用的无 PMP 固件。
        MAKE_ENV=(SBI_HAS_PMP=y RUSTSBI_OUT_DIR="$NPC_HOME/build/rustsbi-qemu")
        FW_OUT="$NPC_HOME/build/rustsbi-qemu"
        ;;
    *)
        die "未知模式 '$MODE'（只支持 npc / qemu）"
        ;;
esac

echo "==> 构建 RustSBI 固件 ($MODE)"
env "${MAKE_ENV[@]}" make -C "$NPC_HOME" rustsbi

echo "==> 构建 S 模式 payload"
EXTRA_CFLAGS=()
if [ "$MODE" = "npc" ]; then
    EXTRA_CFLAGS=(-DNPC_HALT)
fi
"$CC" -march=rv64ima_zicsr -mabi=lp64 -mcmodel=medany \
    -nostdlib -nostartfiles "${EXTRA_CFLAGS[@]}" \
    -T "$HERE/payload.ld" -o "$BUILD/payload-$MODE.elf" "$HERE/payload.S"
"$OBJCOPY" -O binary "$BUILD/payload-$MODE.elf" "$BUILD/payload-$MODE.bin"

if [ "$MODE" = "npc" ]; then
    NPC_BIN="${NPC_BIN:-$NPC_HOME/build/ysyx_26030103_npc}"
    [ -x "$NPC_BIN" ] || die "找不到 NPC 仿真器 $NPC_BIN。
请先构建：
  make -C npc build/ysyx_26030103_npc -j\$(nproc)"

    COMBINED="$BUILD/combined.bin"
    cp "$FW_OUT/rustsbi-npc.bin" "$COMBINED"
    truncate -s "$OFFSET" "$COMBINED"
    dd if="$BUILD/payload-$MODE.bin" of="$COMBINED" bs=1 seek="$OFFSET" conv=notrunc status=none

    echo "==> 运行 NPC"
    LOG="$BUILD/npc.log"
    timeout 60 "$NPC_BIN" "$COMBINED" --batch 2>&1 | tee "$LOG"
    grep -q 'LDBPTIOK' "$LOG" || die "未看到期望的 SBI 输出 LDBPTIOK"
    grep -q 'HIT GOOD TRAP' "$LOG" || die "NPC 未以 GOOD TRAP 结束"
else
    command -v qemu-system-riscv64 >/dev/null 2>&1 || die "找不到 qemu-system-riscv64"
    echo "==> 运行 QEMU virt"
    LOG="$BUILD/qemu.log"
    # QEMU 版本没有 NPC custom halt，测试 payload 在 WFI 循环中等待 timeout。
    timeout 8 qemu-system-riscv64 -machine virt -m 256M -nographic \
        -bios "$FW_OUT/rustsbi-npc.elf" -kernel "$BUILD/payload-$MODE.elf" \
        >"$LOG" 2>&1 || true
    cat "$LOG"
    grep -q 'LDBPTIOK' "$LOG" || die "QEMU 未看到期望的 SBI 输出 LDBPTIOK"
fi

echo "PASS: RustSBI ($MODE)"
