#!/usr/bin/env bash
#
# 构建 NPC 平台的 OpenSBI 固件。
#
# 该脚本是 SBI_IMPL=opensbi 的实现后端；Makefile 只负责按 SBI_IMPL
# 分派，不包含任何 OpenSBI 专属逻辑。后续 RustSBI 应提供同签名的
# scripts/build_rustsbi.sh。
#
# 输入（全部来自 Makefile 的 SBI_ENV，也允许手工覆盖）：
#   SBI_ENABLE             必须为 y
#   SBI_PLATFORM           OpenSBI PLATFORM，目前只支持 npc
#   SBI_FW_BASE            固件链接/加载地址
#   SBI_FW_SIZE            固件区域最大尺寸
#   SBI_NEXT_ADDR          next-stage S 模式入口
#   SBI_BUILD_JOBS         make -j 并行度，0 表示 nproc
#   SBI_TOOLCHAIN_PREFIX   RISC-V GCC 前缀
#   SBI_ISA / SBI_ABI      固件 ISA/ABI（默认 rv64ima_zicsr_zifencei/lp64）
#   SBI_LD                 链接器（默认为支持 PIE 的 ld.lld）
#   SBI_OUT_DIR            输出目录，默认 npc/build/opensbi
#   SBI_VENDOR_DIR         OpenSBI 源码目录
#   SBI_PAYLOAD_PATH       可选；非空时构建 FW_PAYLOAD 测试镜像
#
# 输出：
#   $SBI_OUT_DIR/fw.bin    可直接加载的固件
#   $SBI_OUT_DIR/fw.elf    带调试信息的 ELF
#   $SBI_OUT_DIR/fw.info   来源 commit、配置和大小摘要
#
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NPC_HOME="${NPC_HOME:-$(cd -- "$HERE/.." && pwd)}"
WORKBENCH_HOME="${WORKBENCH_HOME:-$(cd -- "$NPC_HOME/.." && pwd)}"

SBI_ENABLE="${SBI_ENABLE:-n}"
SBI_IMPL="${SBI_IMPL:-opensbi}"
SBI_PLATFORM="${SBI_PLATFORM:-npc}"
SBI_FW_BASE="${SBI_FW_BASE:-0x80000000}"
SBI_FW_SIZE="${SBI_FW_SIZE:-0x00200000}"
SBI_NEXT_ADDR="${SBI_NEXT_ADDR:-0x80200000}"
SBI_BUILD_JOBS="${SBI_BUILD_JOBS:-0}"
SBI_TOOLCHAIN_PREFIX="${SBI_TOOLCHAIN_PREFIX:-riscv64-unknown-elf-}"
SBI_ISA="${SBI_ISA:-rv64ima_zicsr_zifencei}"
SBI_ABI="${SBI_ABI:-lp64}"
SBI_LD="${SBI_LD:-ld.lld}"
SBI_VENDOR_DIR="${SBI_VENDOR_DIR:-$WORKBENCH_HOME/vendor/opensbi}"
SBI_OUT_DIR="${SBI_OUT_DIR:-$NPC_HOME/build/opensbi}"
SBI_PAYLOAD_PATH="${SBI_PAYLOAD_PATH:-}"

# 固定来源，便于报告/复现；与 vendor/opensbi/VENDOR.md 保持一致。
OPENSBI_VERSION="v1.9"
OPENSBI_COMMIT="cbf9f6734dd85a982c63e3cb5db7ffe09da839ca"

die() {
	echo "错误：$*" >&2
	exit 2
}

info() {
	echo "[opensbi] $*"
}

# Kconfig 的 hex 值在 shell 里用 $((...)) 解析；先严格校验，避免把
# 非法字符串带进 make/链接命令。
parse_int() {
	local name="$1" value="$2"
	if [[ ! "$value" =~ ^(0[xX][0-9a-fA-F]+|[0-9]+)$ ]]; then
		die "$name='$value' 不是合法的整数（支持十进制或 0x 十六进制）"
	fi
	printf '%d' "$((value))"
}

[[ "$SBI_ENABLE" == "y" ]] || die \
	"SBI_ENABLE=$SBI_ENABLE，SBI 固件未启用。请先运行 'make rv64_sbi_defconfig' 或 'make menuconfig' 打开 SBI。"
[[ "$SBI_IMPL" == "opensbi" ]] || die \
	"当前脚本只实现 SBI_IMPL=opensbi，收到 SBI_IMPL='$SBI_IMPL'。"[[ -d "$SBI_VENDOR_DIR" ]] || die \
	"OpenSBI 源码目录不存在：$SBI_VENDOR_DIR。请确认 vendor/opensbi 已纳入仓库。"
[[ -f "$SBI_VENDOR_DIR/Makefile" ]] || die \
	"$SBI_VENDOR_DIR 不是有效的 OpenSBI 源码树（缺少 Makefile）。"
[[ -d "$SBI_VENDOR_DIR/platform/$SBI_PLATFORM" ]] || die \
	"OpenSBI 平台 '$SBI_PLATFORM' 不存在：$SBI_VENDOR_DIR/platform/$SBI_PLATFORM。当前仅提供 npc。"

BASE="$(parse_int SBI_FW_BASE "$SBI_FW_BASE")"
SIZE="$(parse_int SBI_FW_SIZE "$SBI_FW_SIZE")"
NEXT="$(parse_int SBI_NEXT_ADDR "$SBI_NEXT_ADDR")"

[[ "$BASE" -ge 0 && "$SIZE" -gt 0 && "$NEXT" -gt 0 ]] || \
	die "SBI_FW_BASE/SBI_FW_SIZE/SBI_NEXT_ADDR 必须为正值"
(( BASE % 4096 == 0 )) || die "SBI_FW_BASE=0x$(printf '%x' "$BASE") 必须按 4 KiB 对齐"
(( SIZE % 4096 == 0 )) || die "SBI_FW_SIZE=0x$(printf '%x' "$SIZE") 必须按 4 KiB 对齐"
(( BASE + SIZE <= NEXT )) || die \
	"SBI_FW_BASE+SBI_FW_SIZE=0x$(printf '%x' $((BASE + SIZE))) 越过 SBI_NEXT_ADDR=0x$(printf '%x' "$NEXT")；固件会覆盖 next-stage"

# direct-NPC 的复位入口是 0x80000000；允许覆盖，但必须与工具链可链接
# 地址一致。OpenSBI 的 FW_JUMP_ADDR 按 XLEN 要求 2/4 MiB 对齐。
case "$SBI_ISA" in
	rv64*) SBI_XLEN=64; JUMP_ALIGN=$((2 * 1024 * 1024)) ;;
	rv32*) SBI_XLEN=32; JUMP_ALIGN=$((4 * 1024 * 1024)) ;;
	*) die "SBI_ISA='$SBI_ISA' 必须以 rv32 或 rv64 开头" ;;
esac
if [[ "$SBI_ABI" != "lp64" && "$SBI_ABI" != "lp64d" && \
      "$SBI_ABI" != "ilp32" && "$SBI_ABI" != "ilp32d" ]]; then
	die "SBI_ABI='$SBI_ABI' 非法：只支持 lp64/lp64d/ilp32/ilp32d"
fi
(( NEXT % JUMP_ALIGN == 0 )) || die \
	"SBI_NEXT_ADDR=0x$(printf '%x' "$NEXT") 必须按 $((JUMP_ALIGN / 1024 / 1024)) MiB 对齐（RV$SBI_XLEN）"

CC="${SBI_TOOLCHAIN_PREFIX}gcc"
OBJCOPY="${SBI_TOOLCHAIN_PREFIX}objcopy"
command -v "$CC" >/dev/null 2>&1 || die \
	"找不到 RISC-V GCC：$CC。请安装工具链或覆盖 SBI_TOOLCHAIN_PREFIX。"
command -v "$OBJCOPY" >/dev/null 2>&1 || die "找不到 objcopy：$OBJCOPY"

if (( SBI_BUILD_JOBS == 0 )); then
	JOBS="$(nproc 2>/dev/null || echo 4)"
else
	JOBS="$SBI_BUILD_JOBS"
fi

# OpenSBI 1.9 的固件是 PIE；当前 binutils 2.47 的 RISC-V ld.bfd 不支持
# -pie。默认用 ld.lld，并让 GCC 通过 -fuse-ld=lld 调用它。
LD_ARGS=()
if [[ -n "$SBI_LD" ]]; then
	command -v "$SBI_LD" >/dev/null 2>&1 || die \
		"找不到链接器 '$SBI_LD'。OpenSBI 需要 PIE；请安装 ld.lld 或把 SBI_LD 指到支持 -pie 的链接器。"
	LD_ARGS=("LD=$SBI_LD")
fi

MODE="jump"
FIRMWARE="fw_jump"
FW_ARGS=("FW_JUMP=y" "FW_JUMP_ADDR=0x$(printf '%x' "$NEXT")")
PAYLOAD_ABS=""

if [[ -n "$SBI_PAYLOAD_PATH" ]]; then
	[[ -f "$SBI_PAYLOAD_PATH" ]] || die "SBI_PAYLOAD_PATH 不存在：$SBI_PAYLOAD_PATH"
	PAYLOAD_ABS="$(cd -- "$(dirname -- "$SBI_PAYLOAD_PATH")" && pwd)/$(basename -- "$SBI_PAYLOAD_PATH")"
	OFFSET=$((NEXT - BASE))
	MODE="payload"
	FIRMWARE="fw_payload"
	FW_ARGS=(
		"FW_PAYLOAD=y"
		"FW_PAYLOAD_PATH=$PAYLOAD_ABS"
		"FW_PAYLOAD_OFFSET=0x$(printf '%x' "$OFFSET")"
	)
	info "构建 FW_PAYLOAD 测试镜像：payload=$PAYLOAD_ABS @ 0x$(printf '%x' "$NEXT")"
fi

mkdir -p "$SBI_OUT_DIR"
BUILD_DIR="$SBI_OUT_DIR/opensbi-build-$MODE"

info "源码：$SBI_VENDOR_DIR（$OPENSBI_VERSION / ${OPENSBI_COMMIT:0:12}）"
info "平台：$SBI_PLATFORM；ISA=$SBI_ISA；ABI=$SBI_ABI；XLEN=$SBI_XLEN"
info "固件：0x$(printf '%x' "$BASE")..0x$(printf '%x' $((BASE + SIZE)))；next-stage=0x$(printf '%x' "$NEXT")"
info "输出：$SBI_OUT_DIR"
info "并行度：$JOBS"

CMD=(
	make -C "$SBI_VENDOR_DIR"
	"O=$BUILD_DIR"
	"PLATFORM=$SBI_PLATFORM"
	"CROSS_COMPILE=$SBI_TOOLCHAIN_PREFIX"
	"PLATFORM_RISCV_XLEN=$SBI_XLEN"
	"PLATFORM_RISCV_ABI=$SBI_ABI"
	"PLATFORM_RISCV_ISA=$SBI_ISA"
	"FW_TEXT_START=0x$(printf '%x' "$BASE")"
	"${LD_ARGS[@]}"
	"${FW_ARGS[@]}"
	-j"$JOBS"
)

"${CMD[@]}"

SRC_BIN="$BUILD_DIR/platform/$SBI_PLATFORM/firmware/$FIRMWARE.bin"
SRC_ELF="$BUILD_DIR/platform/$SBI_PLATFORM/firmware/$FIRMWARE.elf"
[[ -f "$SRC_BIN" ]] || die "OpenSBI 构建完成但没有生成 $SRC_BIN"
[[ -f "$SRC_ELF" ]] || die "OpenSBI 构建完成但没有生成 $SRC_ELF"

ACTUAL_SIZE="$(stat -c '%s' "$SRC_BIN")"
if [[ "$MODE" == "jump" ]]; then
	(( ACTUAL_SIZE <= SIZE )) || die \
		"固件大小 $ACTUAL_SIZE 字节超过 SBI_FW_SIZE=$SIZE；请增大 SBI_FW_SIZE 或调整 next-stage"
else
	# FW_PAYLOAD 的 bin 包含 next-stage，因此总大小 = 偏移 + payload，
	# 不能拿 SBI_FW_SIZE 限制；固件与 payload 不重叠由链接脚本保证。
	(( ACTUAL_SIZE >= OFFSET )) || die \
		"FW_PAYLOAD 镜像大小 $ACTUAL_SIZE 小于 next-stage 偏移 0x$(printf '%x' "$OFFSET")"
fi

install -m 0644 "$SRC_BIN" "$SBI_OUT_DIR/fw.bin"
install -m 0644 "$SRC_ELF" "$SBI_OUT_DIR/fw.elf"
if [[ -n "$PAYLOAD_ABS" ]]; then
	install -m 0644 "$PAYLOAD_ABS" "$SBI_OUT_DIR/payload.bin"
fi

{
	echo "impl=opensbi"
	echo "opensbi_version=$OPENSBI_VERSION"
	echo "opensbi_commit=$OPENSBI_COMMIT"
	echo "platform=$SBI_PLATFORM"
	echo "mode=$MODE"
	echo "fw_base=0x$(printf '%x' "$BASE")"
	echo "fw_size=0x$(printf '%x' "$SIZE")"
	echo "next_addr=0x$(printf '%x' "$NEXT")"
	echo "isa=$SBI_ISA"
	echo "abi=$SBI_ABI"
	echo "build_jobs=$JOBS"
	echo "fw_bytes=$ACTUAL_SIZE"
	echo "fw_sha256=$(sha256sum "$SBI_OUT_DIR/fw.bin" | cut -d' ' -f1)"
	if [[ -n "$PAYLOAD_ABS" ]]; then
		echo "payload=$PAYLOAD_ABS"
	fi
} >"$SBI_OUT_DIR/fw.info.tmp"
mv -f "$SBI_OUT_DIR/fw.info.tmp" "$SBI_OUT_DIR/fw.info"

info "完成：$SBI_OUT_DIR/fw.elf / fw.bin（$ACTUAL_SIZE 字节）"
