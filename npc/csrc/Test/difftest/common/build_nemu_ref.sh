#!/usr/bin/env bash
# 在隔离的 NEMU 配置下构建一个 DiffTest REF，避免污染当前 NEMU 的 RV64 配置。
# 用法:
#   build_nemu_ref.sh <isa> <output> <objdir> <share|native> [diff] [rve] [ext]
#   isa: riscv32 | riscv32e | riscv64
#   ext: 空 | rvc | fd
#        rvc: 使用 RISCV_C（优先 *-rvc-ref_defconfig）
#        fd : 使用 RISC-V F/D（优先 *-fd-ref_defconfig）
# 配置备份/恢复在 flock 下完成，多个 REF 构建不会互相踩踏。
set -euo pipefail

ISA=${1:?isa}
OUT=${2:?output}
OBJ=${3:?objdir}
MODE=${4:-share}
DIFF=${5:-0}
RVE=${6:-0}
EXT=${7:-}

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../../../.." && pwd)
NEMU="$ROOT/nemu"
mkdir -p "$(dirname "$OUT")" "$OBJ"

case "$ISA" in
  riscv32|riscv32e) BITS=32; DEFCONFIG=riscv32-ref_defconfig; GUEST_ISA=riscv32 ;;
  riscv64) BITS=64; DEFCONFIG=riscv64-ref_defconfig; GUEST_ISA=riscv64 ;;
  *) echo "unknown isa: $ISA" >&2; exit 2 ;;
esac

EXTRA_CONFIG=()
case "$EXT" in
  ""|none) ;;
  rvc)
    CANDIDATE="riscv${BITS}-rvc-ref_defconfig"
    EXTRA_CONFIG=(CONFIG_RISCV_A=y CONFIG_RISCV_C=y)
    ;;
  fd)
    CANDIDATE="riscv${BITS}-fd-ref_defconfig"
    EXTRA_CONFIG=(CONFIG_RISCV_A=y CONFIG_RISCV_C=y CONFIG_RISCV_F=y CONFIG_RISCV_D=y)
    ;;
  *)
    echo "unknown ext: $EXT (expected rvc or fd)" >&2
    exit 2
    ;;
esac
if [ -n "$EXT" ] && [ "$EXT" != none ] && [ -f "$NEMU/configs/$CANDIDATE" ]; then
  DEFCONFIG=$CANDIDATE
  EXTRA_CONFIG=()
fi

LOCK=/tmp/opencode/nemu-ref-build.lock
mkdir -p "$(dirname "$LOCK")"
exec 9>"$LOCK"
flock 9

BACKUP=$(mktemp -d /tmp/opencode/nemu-config-XXXXXX)
cleanup() {
  rm -rf "$BACKUP"
}
restore() {
  if [ -f "$BACKUP/config.tar" ]; then
    tar -xf "$BACKUP/config.tar" -C "$NEMU"
  fi
  return 0
}
trap cleanup EXIT
BACKUP_ITEMS=()
for item in .config include/config include/generated; do
  if [ -e "$NEMU/$item" ]; then
    BACKUP_ITEMS+=("$item")
  fi
done
if [ "${#BACKUP_ITEMS[@]}" -gt 0 ]; then
  tar -cf "$BACKUP/config.tar" -C "$NEMU" "${BACKUP_ITEMS[@]}"
fi
trap 'restore; cleanup' EXIT

make -C "$NEMU" "$DEFCONFIG" >/dev/null

CONFIG_CHANGED=0
if [ "$RVE" = 1 ]; then
  printf 'CONFIG_RVE=y\n' >> "$NEMU/.config"
  CONFIG_CHANGED=1
fi
if [ "${#EXTRA_CONFIG[@]}" -gt 0 ]; then
  printf '%s\n' "${EXTRA_CONFIG[@]}" >> "$NEMU/.config"
  CONFIG_CHANGED=1
fi
# 直接追加 .config 不会自动刷新 include/config/auto.conf；在构建前
# 显式 syncconfig，保证 CONFIG_RISCV_* 真正进入编译与文件列表。
if [ "$CONFIG_CHANGED" = 1 ]; then
  ( cd "$NEMU" && ./tools/kconfig/build/conf -s --syncconfig Kconfig )
fi

ARGS=(GUEST_ISA="$GUEST_ISA" ENGINE=interpreter)
if [ "$MODE" = native ]; then
  ARGS+=(CONFIG_TARGET_SHARE= CONFIG_TARGET_NATIVE_ELF=y)
  if [ "$DIFF" = 1 ]; then
    ARGS+=(CONFIG_DIFFTEST=y)
  fi
else
  ARGS+=(CONFIG_TARGET_SHARE=y CONFIG_TARGET_NATIVE_ELF=)
fi
CFLAGS=""
# 与 syncconfig 写出的 autoconf.h 保持相同的替换文本，避免 -Werror 下的
# 宏重定义告警。
if [ "$RVE" = 1 ]; then CFLAGS+=" -DCONFIG_RVE=1"; fi
if [ "$DIFF" = 1 ]; then CFLAGS+=" -DCONFIG_DIFFTEST=1"; fi
if [ -n "$CFLAGS" ]; then
  ARGS+=(CFLAGS_BUILD="$CFLAGS")
fi
ARGS+=(BINARY="$OUT" OBJ_DIR="$OBJ")

make -C "$NEMU" "${ARGS[@]}"
