#!/usr/bin/env bash
#
# 生成 NEMU 专用 Linux DTB。
#
# 本脚本只读取 NEMU 的 .config 和 nemu/dts/nemu.dts，复用
# npc/scripts/build_dtb.sh 的 cpp/dtc 展开与地址校验，不修改 NPC
# 配置，也不会把 NPC 的 SYNTH_FREQ=450MHz 带进 NEMU DTB。
#
# 关键契约：
#   - DTB timebase-frequency == CONFIG_RISCV_XV6_MTIME_FREQ；
#   - 同一频率必须传给 OpenSBI 的 NPC_MTIME_FREQ；
#   - RAM 基地址/大小来自 CONFIG_MBASE/CONFIG_MSIZE；
#   - ISA/MMU 来自 NEMU 的 RV64 IMAFDC 与 Sv39 默认值。
#
# 输出：
#   $NEMU_FDT_OUT_DIR/nemu.dtb
#   $NEMU_FDT_OUT_DIR/nemu.dtb.info
#
# 用法：
#   nemu/scripts/build_linux_dtb.sh
#   NEMU_FDT_OUT_DIR=/tmp/nemu-dtb nemu/scripts/build_linux_dtb.sh
#
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NEMU_HOME="${NEMU_HOME:-$(cd -- "$HERE/.." && pwd)}"
WORKBENCH_HOME="${WORKBENCH_HOME:-$(cd -- "$NEMU_HOME/.." && pwd)}"
CONFIG_FILE="${CONFIG_FILE:-$NEMU_HOME/.config}"
NEMU_FDT_OUT_DIR="${NEMU_FDT_OUT_DIR:-$NEMU_HOME/build/linux/dtb}"
NEMU_DTS="${NEMU_DTS:-$NEMU_HOME/dts/nemu.dts}"
NEMU_DTB_NAME="${NEMU_DTB_NAME:-nemu.dtb}"
NPC_DTB_SCRIPT="${NPC_DTB_SCRIPT:-$WORKBENCH_HOME/npc/scripts/build_dtb.sh}"

die() {
	echo "错误：$*" >&2
	exit 2
}

info() {
	echo "[nemu-dtb] $*"
}

cfg_raw() {
	local key="$1"
	if [[ -f "$CONFIG_FILE" ]]; then
		sed -n "s/^${key}=//p" "$CONFIG_FILE" | tail -n 1
	fi
}

cfg_value() {
	local key="$1" default="$2" value
	value="$(cfg_raw "$key")"
	value="${value%\"}"
	value="${value#\"}"
	if [[ -z "$value" ]]; then
		printf '%s' "$default"
	else
		printf '%s' "$value"
	fi
}

cfg_bool() {
	local key="$1" default="$2"
	if [[ -f "$CONFIG_FILE" ]] && grep -qxF "# ${key} is not set" "$CONFIG_FILE"; then
		printf 'n'
		return
	fi
	local value
	value="$(cfg_value "$key" "$default")"
	case "$value" in
		y|Y|1|true|TRUE) printf 'y' ;;
		*) printf 'n' ;;
	esac
}

parse_int() {
	local name="$1" value="$2"
	if [[ ! "$value" =~ ^(0[xX][0-9a-fA-F]+|[0-9]+)$ ]]; then
		die "$name='$value' 不是合法整数（支持十进制或 0x 十六进制）"
	fi
	printf '%d' "$((value))"
}

[[ -f "$CONFIG_FILE" ]] || die \
	"找不到 NEMU 配置 $CONFIG_FILE；请先运行 'make riscv64-linux_defconfig'"
[[ -f "$NEMU_DTS" ]] || die "找不到 NEMU DTS：$NEMU_DTS"
[[ -x "$NPC_DTB_SCRIPT" ]] || die "找不到可执行的 DTB 构建脚本：$NPC_DTB_SCRIPT"

XLEN="$(cfg_bool CONFIG_RV64 n)"
RV64=$([[ "$XLEN" == "y" ]] && echo 64 || echo 32)
# NEMU 没有独立的 M 扩展开关：RV64 解释器始终实现 M。
RISCV_M="$(cfg_bool CONFIG_RISCV_M y)"
RISCV_A="$(cfg_bool CONFIG_RISCV_A n)"
RISCV_C="$(cfg_bool CONFIG_RISCV_C n)"
RISCV_F="$(cfg_bool CONFIG_RISCV_F n)"
RISCV_D="$(cfg_bool CONFIG_RISCV_D n)"
[[ "$RV64" == "64" ]] || die "NEMU Linux DTB 需要 CONFIG_RV64=y（当前为 n）"
[[ "$(cfg_bool CONFIG_DEVICE n)" == "y" ]] || die "NEMU Linux DTB 需要 CONFIG_DEVICE=y"
[[ "$(cfg_bool CONFIG_HAS_RISCV_XV6 n)" == "y" ]] || die \
	"NEMU Linux DTB 需要 CONFIG_HAS_RISCV_XV6=y（CLINT/PLIC/UART）"
[[ "$(cfg_bool CONFIG_TARGET_NATIVE_ELF n)" == "y" ]] || die \
	"NEMU Linux 独立运行需要 CONFIG_TARGET_NATIVE_ELF=y"
[[ "$(cfg_bool CONFIG_TARGET_SHARE n)" != "y" ]] || die \
	"NEMU Linux 独立运行不能启用 CONFIG_TARGET_SHARE"
[[ "$RISCV_M" == "y" ]] || die "NEMU Linux DTB 需要 CONFIG_RISCV_M=y"
[[ "$RISCV_A" == "y" ]] || die "NEMU Linux DTB 需要 CONFIG_RISCV_A=y"
[[ "$RISCV_C" == "y" ]] || die "NEMU Linux DTB 需要 CONFIG_RISCV_C=y"
[[ "$RISCV_F" == "y" ]] || die "NEMU Linux DTB 需要 CONFIG_RISCV_F=y"
[[ "$RISCV_D" == "y" ]] || die "NEMU Linux DTB 需要 CONFIG_RISCV_D=y"

MBASE="$(parse_int CONFIG_MBASE "$(cfg_value CONFIG_MBASE 0x80000000)")"
MSIZE="$(parse_int CONFIG_MSIZE "$(cfg_value CONFIG_MSIZE 0x04000000)")"
MTIME_FREQ="$(parse_int CONFIG_RISCV_XV6_MTIME_FREQ \
	"$(cfg_value CONFIG_RISCV_XV6_MTIME_FREQ 1000000)")"
(( MSIZE > 0 )) || die "CONFIG_MSIZE 必须为正"
(( MTIME_FREQ > 0 && MTIME_FREQ <= 0xffffffff )) || die \
	"CONFIG_RISCV_XV6_MTIME_FREQ=$MTIME_FREQ 超出 DTB timebase-frequency 的 32 位范围"

# 标准 Linux 布局：SBI 固件 2 MiB，Image 在 +2 MiB，DTB 固定在 RAM
# 末尾前 1 MiB。地址必须落在 NEMU RAM 内，交给 build_dtb.sh 再校验
# 重叠/对齐。
FW_BASE="$MBASE"
FW_SIZE=0x00200000
NEXT_ADDR=$((MBASE + FW_SIZE))
FDT_ADDR=$((MBASE + MSIZE - 0x00100000))
RAM_END=$((MBASE + MSIZE))
(( FDT_ADDR >= NEXT_ADDR )) || die \
	"RAM 太小：DTB 地址 0x$(printf '%x' "$FDT_ADDR") 落在 Image 区之前"
(( FDT_ADDR < RAM_END )) || die "DTB 地址超出 NEMU RAM"

# NEMU 默认 DTB 只声明 Sv39；NEMU 本身支持 Sv39/Sv48，可用
# NEMU_MMU_LEVELS=4 覆盖生成 Sv48 描述。
MMU_LEVELS="${NEMU_MMU_LEVELS:-3}"
case "$MMU_LEVELS" in
	3) MMU_TYPE_NAME=sv39 ;;
	4) MMU_TYPE_NAME=sv48 ;;
	*) die "NEMU_MMU_LEVELS='$MMU_LEVELS' 非法（只允许 3=Sv39 或 4=Sv48）" ;;
esac

INITRAMFS_ENABLE="${NEMU_INITRAMFS_ENABLE:-n}"
INITRAMFS_PATH="${NEMU_INITRAMFS_PATH:-}"
INITRAMFS_ADDR="${NEMU_INITRAMFS_ADDR:-0x82000000}"
BOOTARGS="${NEMU_BOOTARGS:-console=ttyS0,115200 earlycon=sbi}"
FDT_LINUX="${NEMU_FDT_LINUX:-y}"

case "$INITRAMFS_ENABLE" in
	y|n) ;;
	*) die "NEMU_INITRAMFS_ENABLE='$INITRAMFS_ENABLE' 非法（只允许 y/n）" ;;
esac
case "$FDT_LINUX" in
	y|n) ;;
	*) die "NEMU_FDT_LINUX='$FDT_LINUX' 非法（只允许 y/n）" ;;
esac
if [[ "$INITRAMFS_ENABLE" == "y" ]]; then
	[[ -n "$INITRAMFS_PATH" ]] || die \
		"NEMU_INITRAMFS_ENABLE=y 但 NEMU_INITRAMFS_PATH 为空"
	[[ -f "$INITRAMFS_PATH" ]] || die "initramfs 不存在：$INITRAMFS_PATH"
fi

mkdir -p "$NEMU_FDT_OUT_DIR"
DTB_OUT="$NEMU_FDT_OUT_DIR/$NEMU_DTB_NAME"

info "配置：$CONFIG_FILE"
info "RAM：0x$(printf '%x' "$MBASE")+0x$(printf '%x' "$MSIZE")"
info "timebase：$MTIME_FREQ Hz（必须与 OpenSBI NPC_MTIME_FREQ 相同）"
info "布局：SBI=0x$(printf '%x' "$FW_BASE") Image=0x$(printf '%x' "$NEXT_ADDR") DTB=0x$(printf '%x' "$FDT_ADDR")"

NEMU_CONFIG_FILE="$CONFIG_FILE" \
NPC_HOME="$WORKBENCH_HOME/npc" \
WORKBENCH_HOME="$WORKBENCH_HOME" \
SBI_CONFIG_JSON=/dev/null \
SBI_FDT_PATH="$NEMU_DTS" \
SBI_FDT_OUT_DIR="$NEMU_FDT_OUT_DIR" \
SBI_FDT_ADDR="0x$(printf '%x' "$FDT_ADDR")" \
SBI_FW_BASE="0x$(printf '%x' "$FW_BASE")" \
SBI_FW_SIZE="0x$(printf '%x' "$FW_SIZE")" \
SBI_NEXT_ADDR="0x$(printf '%x' "$NEXT_ADDR")" \
SBI_TIMEBASE_FREQ="$MTIME_FREQ" \
SBI_FDT_LINUX="$FDT_LINUX" \
SBI_INITRAMFS_ENABLE="$INITRAMFS_ENABLE" \
SBI_INITRAMFS_PATH="$INITRAMFS_PATH" \
SBI_INITRAMFS_ADDR="$INITRAMFS_ADDR" \
SBI_BOOTARGS="$BOOTARGS" \
SBI_XLEN=64 \
SBI_RISCV_M=y \
SBI_RISCV_A=y \
SBI_RISCV_F=y \
SBI_RISCV_D=y \
SBI_RISCV_C=y \
SBI_HAS_S=y \
SBI_HAS_U=y \
SBI_HAS_MMU=y \
SBI_MMU_LEVELS="$MMU_LEVELS" \
SBI_MMU_GEOMETRY_MODE=standard \
SBI_MMU_PAGE_SIZE=4096 \
SBI_MMU_PTE_SIZE=8 \
SBI_MMU_PAGE_TABLE_SIZE=4096 \
SBI_MMU_PTE_FORMAT=riscv64 \
NPC_RAM_BASE="0x$(printf '%x' "$MBASE")" \
NPC_RAM_SIZE="0x$(printf '%x' "$MSIZE")" \
	"$NPC_DTB_SCRIPT"

NPC_DTB="$NEMU_FDT_OUT_DIR/npc.dtb"
[[ -f "$NPC_DTB" ]] || die "DTB 构建脚本没有生成 $NPC_DTB"
install -m 0644 "$NPC_DTB" "$DTB_OUT"

# 独立摘要：明确记录这是 NEMU 专用 DTB，而不是 NPC 的 450MHz DTB。
{
	echo "source=$NEMU_DTS"
	echo "config=$CONFIG_FILE"
	echo "isa=rv64imafdc_zicsr_zifencei"
	echo "mmu_type=riscv,$MMU_TYPE_NAME"
	echo "timebase_freq=$MTIME_FREQ"
	echo "mbase=0x$(printf '%x' "$MBASE")"
	echo "msize=0x$(printf '%x' "$MSIZE")"
	echo "fw_base=0x$(printf '%x' "$FW_BASE")"
	echo "fw_size=0x$(printf '%x' "$FW_SIZE")"
	echo "next_addr=0x$(printf '%x' "$NEXT_ADDR")"
	echo "fdt_addr=0x$(printf '%x' "$FDT_ADDR")"
	echo "dtb_bytes=$(stat -c '%s' "$DTB_OUT")"
	echo "dtb_sha256=$(sha256sum "$DTB_OUT" | cut -d' ' -f1)"
} >"$DTB_OUT.info"

# 直接检查二进制属性，防止脚本/缓存导致 timebase 与配置不一致。
if command -v fdtget >/dev/null 2>&1; then
	ACTUAL_TIMEBASE="$(fdtget -t u "$DTB_OUT" /cpus timebase-frequency)"
	[[ "$ACTUAL_TIMEBASE" == "$MTIME_FREQ" ]] || die \
		"DTB timebase-frequency=$ACTUAL_TIMEBASE 与配置 $MTIME_FREQ 不一致"
	info "校验：DTB timebase-frequency=$ACTUAL_TIMEBASE"
else
	info "提示：未安装 fdtget，跳过二进制 timebase 回读（smoke 脚本会用 dtc 复查）"
fi

info "完成：$DTB_OUT"
