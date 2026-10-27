#!/usr/bin/env bash
#
# 为 NEMU 构建 OpenSBI FW_PAYLOAD + NEMU 专用 DTB + S 模式测试 payload。
#
# 该脚本不下载/编译/运行 Linux。默认 payload 是仓库内现有的
# npc/csrc/Test/sbi/opensbi_payload，用来验证：
#   OpenSBI 初始化 -> mret 到 S 模式 -> DTB 传递 -> FDT PASS。
#
# 真实 Linux Image 的打包请使用 nemu/scripts/pack_linux_image.sh，
# 并在运行前确认 DTB timebase 是 1000000（不是 NPC 的 450MHz）。
#
# 输出（$NEMU_LINUX_OUT，默认 nemu/build/linux）：
#   dtb/nemu.dtb                  NEMU 专用 DTB
#   opensbi/fw.bin                可直接加载到 NEMU 0x80000000
#   opensbi/fw.info               频率/哈希/布局摘要
#
# 用法：
#   nemu/scripts/build_linux_fw.sh
#   NEMU_LINUX_OUT=/tmp/nemu-linux nemu/scripts/build_linux_fw.sh
#
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NEMU_HOME="${NEMU_HOME:-$(cd -- "$HERE/.." && pwd)}"
WORKBENCH_HOME="${WORKBENCH_HOME:-$(cd -- "$NEMU_HOME/.." && pwd)}"
CONFIG_FILE="${CONFIG_FILE:-$NEMU_HOME/.config}"
NEMU_LINUX_OUT="${NEMU_LINUX_OUT:-$NEMU_HOME/build/linux}"
NEMU_DTB_OUT="${NEMU_DTB_OUT:-$NEMU_LINUX_OUT/dtb}"
NEMU_SBI_OUT="${NEMU_SBI_OUT:-$NEMU_LINUX_OUT/opensbi}"
NEMU_PAYLOAD_OUT="${NEMU_PAYLOAD_OUT:-$NEMU_LINUX_OUT/payload}"
NEMU_PAYLOAD_PATH="${NEMU_PAYLOAD_PATH:-}"
NEMU_FW_MODE="${NEMU_FW_MODE:-payload}"
SBI_PLATFORM="${SBI_PLATFORM:-npc}"
SBI_ISA="${SBI_ISA:-rv64ima_zicsr_zifencei}"
SBI_ABI="${SBI_ABI:-lp64}"
SBI_TOOLCHAIN_PREFIX="${SBI_TOOLCHAIN_PREFIX:-riscv64-unknown-elf-}"
SBI_LD="${SBI_LD:-ld.lld}"
SBI_VENDOR_DIR="${SBI_VENDOR_DIR:-$WORKBENCH_HOME/vendor/opensbi}"
SBI_BUILD_JOBS="${SBI_BUILD_JOBS:-0}"

FW_BASE=0x80000000
FW_SIZE=0x00200000

die() {
	echo "错误：$*" >&2
	exit 2
}

info() {
	echo "[nemu-linux] $*"
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

parse_int() {
	local name="$1" value="$2"
	if [[ ! "$value" =~ ^(0[xX][0-9a-fA-F]+|[0-9]+)$ ]]; then
		die "$name='$value' 不是合法整数（支持十进制或 0x 十六进制）"
	fi
	printf '%d' "$((value))"
}

[[ -f "$CONFIG_FILE" ]] || die \
	"找不到 NEMU 配置 $CONFIG_FILE；请先运行 'make riscv64-linux_defconfig'"
[[ -d "$SBI_VENDOR_DIR" ]] || die "找不到 OpenSBI 源码：$SBI_VENDOR_DIR"
command -v "${SBI_TOOLCHAIN_PREFIX}gcc" >/dev/null 2>&1 || die \
	"找不到 ${SBI_TOOLCHAIN_PREFIX}gcc"
command -v "$SBI_LD" >/dev/null 2>&1 || die "找不到链接器 $SBI_LD（OpenSBI 需要 PIE）"
command -v cpp >/dev/null 2>&1 || die "找不到 cpp（生成 DTB 需要）"
command -v dtc >/dev/null 2>&1 || die "找不到 dtc（生成 DTB 需要）"

MBASE="$(parse_int CONFIG_MBASE "$(cfg_value CONFIG_MBASE 0x80000000)")"
MSIZE="$(parse_int CONFIG_MSIZE "$(cfg_value CONFIG_MSIZE 0x04000000)")"
MTIME_FREQ="$(parse_int CONFIG_RISCV_XV6_MTIME_FREQ \
	"$(cfg_value CONFIG_RISCV_XV6_MTIME_FREQ 1000000)")"
(( MBASE == 0x80000000 )) || die \
	"本启动链固定 FW_BASE=0x80000000，但 CONFIG_MBASE=0x$(printf '%x' "$MBASE")"
(( MSIZE >= 0x00400000 )) || die "CONFIG_MSIZE=0x$(printf '%x' "$MSIZE") 太小"
NEXT_ADDR=$((MBASE + FW_SIZE))
FDT_ADDR=$((MBASE + MSIZE - 0x00100000))
(( FDT_ADDR >= NEXT_ADDR )) || die \
	"RAM 太小：DTB 地址 0x$(printf '%x' "$FDT_ADDR") 落在 Image 区之前"
case "$NEMU_FW_MODE" in
	payload|jump) ;;
	*) die "NEMU_FW_MODE='$NEMU_FW_MODE' 非法（只允许 payload/jump）" ;;
esac

mkdir -p "$NEMU_LINUX_OUT" "$NEMU_DTB_OUT" "$NEMU_SBI_OUT" "$NEMU_PAYLOAD_OUT"

info "配置：$CONFIG_FILE"
info "RAM：0x$(printf '%x' "$MBASE")+0x$(printf '%x' "$MSIZE")"
info "timebase：$MTIME_FREQ Hz"

# 1) NEMU 专用 DTB（不与 NPC 共用 450MHz DTB）。
info "生成 NEMU DTB"
NEMU_HOME="$NEMU_HOME" \
WORKBENCH_HOME="$WORKBENCH_HOME" \
CONFIG_FILE="$CONFIG_FILE" \
NEMU_FDT_OUT_DIR="$NEMU_DTB_OUT" \
NEMU_MMU_LEVELS="${NEMU_MMU_LEVELS:-3}" \
	"$HERE/build_linux_dtb.sh"

DTB_PATH="$NEMU_DTB_OUT/nemu.dtb"
[[ -f "$DTB_PATH" ]] || die "没有生成 $DTB_PATH"
DTB_TIMEBASE="$(sed -n 's/^timebase_freq=//p' "$DTB_PATH.info" | tail -n1)"
[[ "$DTB_TIMEBASE" == "$MTIME_FREQ" ]] || die \
	"DTB timebase=$DTB_TIMEBASE 与 CONFIG_RISCV_XV6_MTIME_FREQ=$MTIME_FREQ 不一致"

# 2) S 模式 payload：
#    - payload 模式（默认）：现有 SBI 测试 payload，验证 FDT PASS；
#    - jump 模式：只构建 FW_JUMP，真实 Linux Image 由 pack_linux_image.sh
#      单独放到 0x80200000。
PAYLOAD_BIN=""
FDT_EXPECTED=0
if [[ "$NEMU_FW_MODE" == "payload" ]]; then
	if [[ -n "$NEMU_PAYLOAD_PATH" ]]; then
		[[ -f "$NEMU_PAYLOAD_PATH" ]] || die "NEMU_PAYLOAD_PATH 不存在：$NEMU_PAYLOAD_PATH"
		PAYLOAD_BIN="$NEMU_PAYLOAD_PATH"
		info "使用外部 payload：$PAYLOAD_BIN（不启用 FDT 断言）"
	else
		PAYLOAD_SRC="$WORKBENCH_HOME/npc/csrc/Test/sbi/opensbi_payload"
		[[ -d "$PAYLOAD_SRC" ]] || die "找不到测试 payload 源码：$PAYLOAD_SRC"
		# FDT_EXPECTED 是命令行宏，make 不会因宏变化重建；显式清旧产物。
		rm -f "$NEMU_PAYLOAD_OUT/payload.elf" "$NEMU_PAYLOAD_OUT/payload.bin"
		info "构建 S 模式测试 payload（FDT_EXPECTED=1）"
		make -C "$PAYLOAD_SRC" \
			OUT="$NEMU_PAYLOAD_OUT" \
			ADDR="$NEXT_ADDR" \
			ISA="$SBI_ISA" \
			ABI="$SBI_ABI" \
			CROSS_COMPILE="$SBI_TOOLCHAIN_PREFIX" \
			SBI_NAME=OpenSBI \
			FDT_EXPECTED=1 \
			FDT_ADDR="$FDT_ADDR" \
			USE_TIME_CSR=1 \
			-j"$(nproc)"
		PAYLOAD_BIN="$NEMU_PAYLOAD_OUT/payload.bin"
		FDT_EXPECTED=1
	fi
else
	info "FW_JUMP 模式：不嵌入 next-stage，供 pack_linux_image.sh 使用"
fi

# 3) OpenSBI：payload 模式嵌入测试 payload；jump 模式只保留固件，
#    由打包脚本把真实 Linux Image 放到 NEXT_ADDR。DTB 都由通用固件
#    复制到 FDT_ADDR 并通过 a1 传递。SBI_FDT_LINUX=n 只因为这里传入
#    已生成的 .dtb，不代表 NEMU DTB 不是 Linux DTB。
info "构建 OpenSBI FW_${NEMU_FW_MODE}（platform=$SBI_PLATFORM）"
NEMU_CONFIG_FILE="$CONFIG_FILE" \
NPC_HOME="$WORKBENCH_HOME/npc" \
WORKBENCH_HOME="$WORKBENCH_HOME" \
SBI_ENABLE=y \
SBI_IMPL=opensbi \
SBI_PLATFORM="$SBI_PLATFORM" \
SBI_FW_BASE="$FW_BASE" \
SBI_FW_SIZE="$FW_SIZE" \
SBI_NEXT_ADDR="$NEXT_ADDR" \
SBI_BUILD_JOBS="$SBI_BUILD_JOBS" \
SBI_FDT_ENABLE=y \
SBI_FDT_PATH="$DTB_PATH" \
SBI_FDT_ADDR="$FDT_ADDR" \
SBI_FDT_OUT_DIR="$NEMU_SBI_OUT/fdt" \
SBI_FDT_LINUX=n \
SBI_TIMEBASE_FREQ="$MTIME_FREQ" \
SBI_PAYLOAD_PATH="$PAYLOAD_BIN" \
SBI_ISA="$SBI_ISA" \
SBI_ABI="$SBI_ABI" \
SBI_TOOLCHAIN_PREFIX="$SBI_TOOLCHAIN_PREFIX" \
SBI_LD="$SBI_LD" \
SBI_XLEN=64 \
SBI_RISCV_M=y \
SBI_RISCV_A=y \
SBI_RISCV_F=y \
SBI_RISCV_D=y \
SBI_RISCV_C=y \
SBI_HAS_S=y \
SBI_HAS_U=y \
SBI_HAS_MMU=y \
SBI_MMU_LEVELS="${NEMU_MMU_LEVELS:-3}" \
SBI_MMU_GEOMETRY_MODE=standard \
SBI_MMU_PAGE_SIZE=4096 \
SBI_MMU_PTE_SIZE=8 \
SBI_MMU_PAGE_TABLE_SIZE=4096 \
SBI_MMU_PTE_FORMAT=riscv64 \
SBI_VENDOR_DIR="$SBI_VENDOR_DIR" \
SBI_OUT_DIR="$NEMU_SBI_OUT" \
NPC_RAM_BASE="0x$(printf '%x' "$MBASE")" \
NPC_RAM_SIZE="0x$(printf '%x' "$MSIZE")" \
	"$WORKBENCH_HOME/npc/scripts/build_opensbi.sh"

FW_BIN="$NEMU_SBI_OUT/fw.bin"
FW_INFO="$NEMU_SBI_OUT/fw.info"
[[ -f "$FW_BIN" && -f "$FW_INFO" ]] || die "OpenSBI 构建没有生成 fw.bin/fw.info"
FW_TIMEBASE="$(sed -n 's/^timebase_freq=//p' "$FW_INFO" | tail -n1)"
[[ "$FW_TIMEBASE" == "$MTIME_FREQ" ]] || die \
	"OpenSBI NPC_MTIME_FREQ=$FW_TIMEBASE 与 NEMU mtime=$MTIME_FREQ 不一致"
FW_FDT_SHA="$(sed -n 's/^fdt_sha256=//p' "$FW_INFO" | tail -n1)"
[[ "$FW_FDT_SHA" == "$(sha256sum "$DTB_PATH" | cut -d' ' -f1)" ]] || die \
	"OpenSBI 内嵌 DTB 与 $DTB_PATH 不一致"

{
	echo "nemu_linux_fw=1"
	echo "config=$CONFIG_FILE"
	echo "fw_bin=$FW_BIN"
	echo "fw_elf=$NEMU_SBI_OUT/fw.elf"
	echo "fw_bytes=$(stat -c '%s' "$FW_BIN")"
	echo "fw_sha256=$(sha256sum "$FW_BIN" | cut -d' ' -f1)"
	echo "dtb=$DTB_PATH"
	echo "dtb_sha256=$FW_FDT_SHA"
	echo "timebase_freq=$MTIME_FREQ"
	echo "mbase=0x$(printf '%x' "$MBASE")"
	echo "msize=0x$(printf '%x' "$MSIZE")"
	echo "fw_base=$FW_BASE"
	echo "next_addr=$NEXT_ADDR"
	echo "fdt_addr=$FDT_ADDR"
	echo "payload=$PAYLOAD_BIN"
	echo "payload_fdt_expected=$FDT_EXPECTED"
	echo "sbi_isa=$SBI_ISA"
	echo "sbi_abi=$SBI_ABI"
} >"$NEMU_LINUX_OUT/fw.info"

info "完成：$FW_BIN"
info "DTB：$DTB_PATH（timebase=$MTIME_FREQ）"
if [[ "$NEMU_FW_MODE" == "jump" ]]; then
	info "FW_JUMP 不含 next-stage；请用 nemu/scripts/pack_linux_image.sh 打包 Image 后再运行"
else
	info "运行：cd $NEMU_HOME && ./build/riscv64-nemu-interpreter -b $FW_BIN"
fi
