#!/usr/bin/env bash
#
# 把 OpenSBI FW_JUMP、用户提供的 RISC-V Linux Image、可选 initramfs 和
# NEMU 专用 DTB 打包成 NEMU 可直接加载的单一 binary。
#
# 本脚本不会下载、编译或运行 Linux；只读取用户已经准备好的文件。
# 这不是 NPC 打包脚本的替代品：它先构建 NEMU 的 1MHz DTB/FW_JUMP，
# 再复用 npc/scripts/pack_linux_image.sh 的布局校验与拼接逻辑，并
# 显式把 NEMU 的 CPU/MMU 描述传给 DTB 生成器，避免误用 NPC 的
# 450MHz/SYNTH_FREQ 约定。
#
# 输入：
#   NEMU_IMAGE_PATH 或 LINUX_IMAGE_PATH   必填，用户自己的 Image
#   NEMU_INITRAMFS_ENABLE                 y/n，默认由 PATH 是否设置决定
#   NEMU_INITRAMFS_PATH                   可选 initramfs
#   NEMU_INITRAMFS_ADDR                   默认 0x82000000
#   NEMU_BOOTARGS                         默认 console=ttyS0,115200 earlycon=sbi
#   NEMU_PACK_OUT                         默认 nemu/build/linux/pack
#
# 输出：
#   $NEMU_PACK_OUT/linux-image.bin
#   $NEMU_PACK_OUT/linux-image.bin.info
#   $NEMU_PACK_OUT/linux-image.bin.sha256
#
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NEMU_HOME="${NEMU_HOME:-$(cd -- "$HERE/.." && pwd)}"
WORKBENCH_HOME="${WORKBENCH_HOME:-$(cd -- "$NEMU_HOME/.." && pwd)}"
CONFIG_FILE="${CONFIG_FILE:-$NEMU_HOME/.config}"
NEMU_PACK_OUT="${NEMU_PACK_OUT:-$NEMU_HOME/build/linux/pack}"
NEMU_LINUX_OUT="${NEMU_LINUX_OUT:-$NEMU_PACK_OUT/sbi}"

die() {
	echo "错误：$*" >&2
	exit 2
}

info() {
	echo "[nemu-pack] $*"
}

cfg_value() {
	local key="$1" default="$2" value
	if [[ -f "$CONFIG_FILE" ]]; then
		value="$(sed -n "s/^${key}=//p" "$CONFIG_FILE" | tail -n 1)"
	fi
	value="${value%\"}"
	value="${value#\"}"
	if [[ -z "$value" ]]; then
		printf '%s' "$default"
	else
		printf '%s' "$value"
	fi
}

parse_int() {
	local name="$1" value="$2"
	if [[ ! "$value" =~ ^(0[xX][0-9a-fA-F]+|[0-9]+)$ ]]; then
		die "$name='$value' 不是合法整数（支持十进制或 0x 十六进制）"
	fi
	printf '%d' "$((value))"
}

IMAGE_PATH="${NEMU_IMAGE_PATH:-${LINUX_IMAGE_PATH:-}}"
[[ -n "$IMAGE_PATH" ]] || die \
	"请用 NEMU_IMAGE_PATH（或 LINUX_IMAGE_PATH）指定真实 RISC-V Linux Image"
[[ -f "$IMAGE_PATH" ]] || die "Linux Image 不存在：$IMAGE_PATH"
IMAGE_PATH="$(cd -- "$(dirname -- "$IMAGE_PATH")" && pwd)/$(basename -- "$IMAGE_PATH")"

MBASE="$(parse_int CONFIG_MBASE "$(cfg_value CONFIG_MBASE 0x80000000)")"
MSIZE="$(parse_int CONFIG_MSIZE "$(cfg_value CONFIG_MSIZE 0x04000000)")"
MTIME_FREQ="$(parse_int CONFIG_RISCV_XV6_MTIME_FREQ \
	"$(cfg_value CONFIG_RISCV_XV6_MTIME_FREQ 1000000)")"
(( MBASE == 0x80000000 )) || die "NEMU Linux 打包固定 RAM 基地址 0x80000000"
NEXT_ADDR=$((MBASE + 0x00200000))
FDT_ADDR=$((MBASE + MSIZE - 0x00100000))
(( FDT_ADDR > NEXT_ADDR )) || die "CONFIG_MSIZE 太小，DTB 与 Image 区重叠"

INITRAMFS_PATH="${NEMU_INITRAMFS_PATH:-${LINUX_INITRAMFS_PATH:-}}"
if [[ -n "${NEMU_INITRAMFS_ENABLE:-}" ]]; then
	INITRAMFS_ENABLE="$NEMU_INITRAMFS_ENABLE"
elif [[ -n "$INITRAMFS_PATH" ]]; then
	INITRAMFS_ENABLE=y
else
	INITRAMFS_ENABLE=n
fi
case "$INITRAMFS_ENABLE" in
	y|n) ;;
	*) die "NEMU_INITRAMFS_ENABLE='$INITRAMFS_ENABLE' 非法（只允许 y/n）" ;;
esac
if [[ "$INITRAMFS_ENABLE" == "y" ]]; then
	[[ -n "$INITRAMFS_PATH" ]] || die "启用 initramfs 但 NEMU_INITRAMFS_PATH 为空"
	[[ -f "$INITRAMFS_PATH" ]] || die "initramfs 不存在：$INITRAMFS_PATH"
	INITRAMFS_PATH="$(cd -- "$(dirname -- "$INITRAMFS_PATH")" && pwd)/$(basename -- "$INITRAMFS_PATH")"
fi
INITRAMFS_ADDR="${NEMU_INITRAMFS_ADDR:-${LINUX_INITRAMFS_ADDR:-0x82000000}}"
BOOTARGS="${NEMU_BOOTARGS:-${LINUX_BOOTARGS:-console=ttyS0,115200 earlycon=sbi}}"

info "Image：$IMAGE_PATH"
info "RAM：0x$(printf '%x' "$MBASE")+0x$(printf '%x' "$MSIZE")"
info "布局：OpenSBI=0x80000000 Image=0x$(printf '%x' "$NEXT_ADDR") DTB=0x$(printf '%x' "$FDT_ADDR")"
info "timebase：$MTIME_FREQ Hz"

# 1) 构建 FW_JUMP + NEMU DTB，不嵌入测试 payload。
NEMU_HOME="$NEMU_HOME" \
WORKBENCH_HOME="$WORKBENCH_HOME" \
CONFIG_FILE="$CONFIG_FILE" \
NEMU_LINUX_OUT="$NEMU_LINUX_OUT" \
NEMU_FW_MODE=jump \
NEMU_MMU_LEVELS="${NEMU_MMU_LEVELS:-3}" \
NEMU_INITRAMFS_ENABLE="$INITRAMFS_ENABLE" \
NEMU_INITRAMFS_PATH="$INITRAMFS_PATH" \
NEMU_INITRAMFS_ADDR="$INITRAMFS_ADDR" \
NEMU_BOOTARGS="$BOOTARGS" \
	"$HERE/build_linux_fw.sh"

SBI_OUT="$NEMU_LINUX_OUT/opensbi"
[[ -f "$SBI_OUT/fw.bin" && -f "$SBI_OUT/fw.info" ]] || die \
	"FW_JUMP 构建失败：缺少 $SBI_OUT/fw.bin 或 fw.info"

# 2) 复用 NPC 打包脚本做地址/重叠/Image header 校验与拼接；显式提供
#    NEMU 平台描述，避免它从 NEMU .config 读取 NPC 专属符号的默认值。
SBI_ENABLE=y \
SBI_IMPL=opensbi \
SBI_OUT_DIR="$SBI_OUT" \
SBI_FW_BASE=0x80000000 \
SBI_FW_SIZE=0x00200000 \
SBI_NEXT_ADDR="0x$(printf '%x' "$NEXT_ADDR")" \
SBI_FDT_ENABLE=y \
SBI_FDT_PATH="$NEMU_HOME/dts/nemu.dts" \
SBI_FDT_ADDR="0x$(printf '%x' "$FDT_ADDR")" \
SBI_FDT_OUT_DIR="$NEMU_PACK_OUT/dtb" \
SBI_TIMEBASE_FREQ="$MTIME_FREQ" \
SBI_FDT_LINUX=y \
SBI_XLEN=64 \
SBI_RISCV_M=y \
SBI_RISCV_A=y \
SBI_RISCV_C=y \
SBI_RISCV_F=y \
SBI_RISCV_D=y \
SBI_HAS_S=y \
SBI_HAS_U=y \
SBI_HAS_MMU=y \
SBI_MMU_LEVELS="${NEMU_MMU_LEVELS:-3}" \
SBI_MMU_GEOMETRY_MODE=standard \
SBI_MMU_PAGE_SIZE=4096 \
SBI_MMU_PTE_SIZE=8 \
SBI_MMU_PAGE_TABLE_SIZE=4096 \
SBI_MMU_PTE_FORMAT=riscv64 \
SBI_INITRAMFS_ENABLE="$INITRAMFS_ENABLE" \
SBI_INITRAMFS_PATH="$INITRAMFS_PATH" \
SBI_INITRAMFS_ADDR="$INITRAMFS_ADDR" \
SBI_BOOTARGS="$BOOTARGS" \
LINUX_BOOT_ENABLE=y \
LINUX_IMAGE_PATH="$IMAGE_PATH" \
LINUX_INITRAMFS_ENABLE="$INITRAMFS_ENABLE" \
LINUX_INITRAMFS_PATH="$INITRAMFS_PATH" \
LINUX_INITRAMFS_ADDR="$INITRAMFS_ADDR" \
LINUX_BOOTARGS="$BOOTARGS" \
LINUX_OUT_DIR="$NEMU_PACK_OUT" \
NPC_HOME="$WORKBENCH_HOME/npc" \
WORKBENCH_HOME="$WORKBENCH_HOME" \
SBI_CONFIG_JSON=/dev/null \
NPC_RAM_BASE="0x$(printf '%x' "$MBASE")" \
NPC_RAM_SIZE="0x$(printf '%x' "$MSIZE")" \
	"$WORKBENCH_HOME/npc/scripts/pack_linux_image.sh"

PACKED_BIN="$NEMU_PACK_OUT/linux-image.bin"
[[ -f "$PACKED_BIN" ]] || die "打包脚本没有生成 $PACKED_BIN"
info "完成：$PACKED_BIN"
info "运行：cd $NEMU_HOME && ./build/riscv64-nemu-interpreter -b $PACKED_BIN"
