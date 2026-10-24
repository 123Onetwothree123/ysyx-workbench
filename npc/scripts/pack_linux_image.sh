#!/usr/bin/env bash
#
# 把 SBI 固件、用户提供的 Linux Image、可选 initramfs 和 DTB 打包成
# NPC 可直接加载的单一 binary。
#
# 本脚本不会下载、编译或运行 Linux；只读取用户已经准备好的文件。
#
# 输入（Makefile 的 LINUX_ENV/SBI_ENV，或手工环境变量）：
#   SBI_ENABLE / SBI_IMPL / SBI_OUT_DIR
#   SBI_FW_BASE / SBI_FW_SIZE / SBI_NEXT_ADDR
#   SBI_FDT_ENABLE / SBI_FDT_ADDR / SBI_FDT_PATH / SBI_FDT_OUT_DIR
#   SBI_TIMEBASE_FREQ / SBI_FDT_LINUX
#   SBI_INITRAMFS_ENABLE / SBI_INITRAMFS_PATH / SBI_INITRAMFS_ADDR
#   SBI_BOOTARGS
#   LINUX_BOOT_ENABLE / LINUX_IMAGE_PATH
#   LINUX_INITRAMFS_ENABLE / LINUX_INITRAMFS_PATH / LINUX_INITRAMFS_ADDR
#   LINUX_BOOTARGS / LINUX_OUT_DIR
#   NPC_RAM_BASE / NPC_RAM_SIZE
#
# 地址约定：
#   - Linux Image 地址固定复用 SBI_NEXT_ADDR（不提供第二个 Image 地址）；
#   - DTB 地址复用 SBI_FDT_ADDR；
#   - initramfs 使用 LINUX_INITRAMFS_ADDR；
#   - 所有区域必须位于 NPC RAM 内、互不重叠，且不越过 RAM 结束地址。
#
# 输出：
#   $LINUX_OUT_DIR/linux-image.bin          可加载的单一 binary
#   $LINUX_OUT_DIR/linux-image.bin.info     布局、组件大小与 sha256
#   $LINUX_OUT_DIR/linux-image.bin.sha256   可直接 sha256sum -c 的校验文件
#   $LINUX_OUT_DIR/dtb/npc.dtb              按实际 initramfs 大小重生成的 DTB
#
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NPC_HOME="${NPC_HOME:-$(cd -- "$HERE/.." && pwd)}"
WORKBENCH_HOME="${WORKBENCH_HOME:-$(cd -- "$NPC_HOME/.." && pwd)}"
CONFIG_FILE="${SBI_CONFIG_FILE:-${NPC_CONFIG_FILE:-$NPC_HOME/.config}}"

die() {
	echo "错误：$*" >&2
	exit 2
}

info() {
	echo "[linux-image] $*"
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
	local key="$1" default="$2" value
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

resolve_path() {
	local path="$1"
	case "$path" in
		/*) printf '%s' "$path" ;;
		\~/*) printf '%s/%s' "$HOME" "${path#\~/}" ;;
		*) printf '%s/%s' "$WORKBENCH_HOME" "$path" ;;
	esac
}

hex_prefix() {
	local file="$1" count="$2"
	od -An -tx1 -N"$count" "$file" | tr -d ' \n'
}

read_le64() {
	local file="$1" offset="$2"
	od -An -tu8 -j"$offset" -N8 "$file" | tr -d ' '
}

# ---- 读取配置（LINUX_* 优先，其次 SBI_*，最后 .config） ----
LINUX_BOOT_ENABLE="${LINUX_BOOT_ENABLE:-$(cfg_bool CONFIG_LINUX_BOOT_ENABLE n)}"
LINUX_IMAGE_PATH="${LINUX_IMAGE_PATH:-$(cfg_value CONFIG_LINUX_IMAGE_PATH "")}"
LINUX_INITRAMFS_ENABLE="${LINUX_INITRAMFS_ENABLE:-${SBI_INITRAMFS_ENABLE:-$(cfg_bool CONFIG_LINUX_INITRAMFS_ENABLE n)}}"
LINUX_INITRAMFS_PATH="${LINUX_INITRAMFS_PATH:-${SBI_INITRAMFS_PATH:-$(cfg_value CONFIG_LINUX_INITRAMFS_PATH "")}}"
LINUX_INITRAMFS_ADDR="${LINUX_INITRAMFS_ADDR:-${SBI_INITRAMFS_ADDR:-$(cfg_value CONFIG_LINUX_INITRAMFS_ADDR 0x82000000)}}"
LINUX_BOOTARGS="${LINUX_BOOTARGS:-${SBI_BOOTARGS:-$(cfg_value CONFIG_LINUX_BOOTARGS "console=ttyS0,115200 earlycon=sbi")}}"
LINUX_OUT_DIR="${LINUX_OUT_DIR:-$NPC_HOME/build/linux}"

SBI_ENABLE="${SBI_ENABLE:-$(cfg_bool CONFIG_SBI_ENABLE n)}"
SBI_IMPL="${SBI_IMPL:-$(cfg_value CONFIG_SBI_IMPL opensbi)}"
SBI_OUT_DIR="${SBI_OUT_DIR:-$NPC_HOME/build/$SBI_IMPL}"
SBI_FW_BASE="${SBI_FW_BASE:-$(cfg_value CONFIG_SBI_FW_BASE 0x80000000)}"
SBI_FW_SIZE="${SBI_FW_SIZE:-$(cfg_value CONFIG_SBI_FW_SIZE 0x00200000)}"
SBI_NEXT_ADDR="${SBI_NEXT_ADDR:-$(cfg_value CONFIG_SBI_NEXT_ADDR 0x80200000)}"
SBI_FDT_ENABLE="${SBI_FDT_ENABLE:-$(cfg_bool CONFIG_SBI_FDT_ENABLE n)}"
SBI_FDT_PATH="${SBI_FDT_PATH:-$(cfg_value CONFIG_SBI_FDT_PATH dts/npc.dts)}"
SBI_FDT_ADDR="${SBI_FDT_ADDR:-$(cfg_value CONFIG_SBI_FDT_ADDR 0x83f00000)}"
SBI_FDT_OUT_DIR="${SBI_FDT_OUT_DIR:-$LINUX_OUT_DIR/dtb}"
SBI_TIMEBASE_FREQ="${SBI_TIMEBASE_FREQ:-}"
NPC_RAM_BASE="${NPC_RAM_BASE:-0x80000000}"
NPC_RAM_SIZE="${NPC_RAM_SIZE:-0x04000000}"

case "$LINUX_BOOT_ENABLE" in
	y|n) ;;
	*) die "LINUX_BOOT_ENABLE='$LINUX_BOOT_ENABLE' 非法（只允许 y/n）" ;;
esac
case "$LINUX_INITRAMFS_ENABLE" in
	y|n) ;;
	*) die "LINUX_INITRAMFS_ENABLE='$LINUX_INITRAMFS_ENABLE' 非法（只允许 y/n）" ;;
esac
[[ "$SBI_ENABLE" == "y" ]] || die "SBI_ENABLE=$SBI_ENABLE；请先启用并构建 SBI 固件"
[[ "$SBI_FDT_ENABLE" == "y" ]] || die "SBI_FDT_ENABLE=$SBI_FDT_ENABLE；Linux 需要 a1=DTB"
[[ -n "$LINUX_IMAGE_PATH" ]] || die "LINUX_IMAGE_PATH 为空；请提供用户自己的 Linux Image"

case "$SBI_IMPL" in
	opensbi)
		FW_BIN="$SBI_OUT_DIR/fw.bin"
		FW_INFO="$SBI_OUT_DIR/fw.info"
		;;
	rustsbi)
		FW_BIN="$SBI_OUT_DIR/rustsbi-npc.bin"
		FW_INFO="$SBI_OUT_DIR/rustsbi-npc.info"
		;;
	*)
		die "SBI_IMPL='$SBI_IMPL' 非法（只允许 opensbi/rustsbi）"
		;;
esac

FW_BASE_NUM="$(parse_int SBI_FW_BASE "$SBI_FW_BASE")"
FW_SIZE_NUM="$(parse_int SBI_FW_SIZE "$SBI_FW_SIZE")"
NEXT_NUM="$(parse_int SBI_NEXT_ADDR "$SBI_NEXT_ADDR")"
FDT_ADDR_NUM="$(parse_int SBI_FDT_ADDR "$SBI_FDT_ADDR")"
RAM_BASE_NUM="$(parse_int NPC_RAM_BASE "$NPC_RAM_BASE")"
RAM_SIZE_NUM="$(parse_int NPC_RAM_SIZE "$NPC_RAM_SIZE")"
RAM_END=$((RAM_BASE_NUM + RAM_SIZE_NUM))
(( RAM_SIZE_NUM > 0 )) || die "NPC_RAM_SIZE 必须为正"
(( FW_BASE_NUM >= RAM_BASE_NUM )) || die \
	"SBI_FW_BASE=0x$(printf '%x' "$FW_BASE_NUM") 低于 NPC RAM 基地址 0x$(printf '%x' "$RAM_BASE_NUM")"
(( FW_SIZE_NUM > 0 )) || die "SBI_FW_SIZE 必须为正"
FW_END=$((FW_BASE_NUM + FW_SIZE_NUM))
(( FW_END <= RAM_END )) || die \
	"SBI 固件区 [0x$(printf '%x' "$FW_BASE_NUM"), 0x$(printf '%x' "$FW_END")) 超出 NPC RAM"

# ---- 输入文件与 Image header 校验 ----
IMAGE_PATH="$(resolve_path "$LINUX_IMAGE_PATH")"
[[ -f "$IMAGE_PATH" ]] || die \
	"Linux Image 不存在：$LINUX_IMAGE_PATH（解析为 $IMAGE_PATH）"
IMAGE_BYTES="$(stat -c '%s' "$IMAGE_PATH")"
(( IMAGE_BYTES >= 64 )) || die \
	"$IMAGE_PATH 只有 $IMAGE_BYTES 字节，不是有效的 RISC-V Linux Image（至少需要 64 字节 header）"
IMAGE_MAGIC="$(od -An -tx1 -j56 -N8 "$IMAGE_PATH" | tr -d ' \n')"
IMAGE_MAGIC2="$(od -An -tx1 -j64 -N4 "$IMAGE_PATH" | tr -d ' \n')"
[[ "$IMAGE_MAGIC" == "5249534356000000" ]] || die \
	"$IMAGE_PATH 缺少 RISC-V Linux Image magic（offset 0x38 应为 \"RISCV\\0\\0\\0\"，实际 $IMAGE_MAGIC）"
[[ "$IMAGE_MAGIC2" == "52534305" ]] || die \
	"$IMAGE_PATH 缺少 RISC-V Linux Image magic2（offset 0x40 应为 \"RSC\\x05\"，实际 $IMAGE_MAGIC2）"
IMAGE_SIZE="$(read_le64 "$IMAGE_PATH" 16)"
IMAGE_TEXT_OFFSET="$(read_le64 "$IMAGE_PATH" 8)"
(( IMAGE_SIZE >= 64 )) || die "$IMAGE_PATH header image_size=$IMAGE_SIZE 非法"
(( IMAGE_SIZE <= RAM_SIZE_NUM )) || die \
	"$IMAGE_PATH header image_size=$IMAGE_SIZE 超过 NPC RAM 大小 $RAM_SIZE_NUM"
EXPECTED_TEXT_OFFSET=$((NEXT_NUM - RAM_BASE_NUM))
if (( IMAGE_TEXT_OFFSET != 0 && IMAGE_TEXT_OFFSET != EXPECTED_TEXT_OFFSET )); then
	die "$IMAGE_PATH header text_offset=0x$(printf '%x' "$IMAGE_TEXT_OFFSET") 与 SBI_NEXT_ADDR=0x$(printf '%x' "$NEXT_NUM") 不匹配（期望 0x$(printf '%x' "$EXPECTED_TEXT_OFFSET")）"
fi
# Image 的 BSS 可能不在文件里；内存占用取 header image_size 与文件大小
# 的较大者，布局检查不能只看文件长度。
IMAGE_SPAN="$IMAGE_SIZE"
(( IMAGE_BYTES > IMAGE_SPAN )) && IMAGE_SPAN="$IMAGE_BYTES"
IMAGE_END=$((NEXT_NUM + IMAGE_SPAN))
(( IMAGE_END > NEXT_NUM )) || die "Linux Image 地址+大小溢出"

INITRAMFS_PATH=""
INITRAMFS_BYTES=0
INITRAMFS_ADDR_NUM=0
INITRAMFS_END=0
INITRAMFS_FORMAT=none
if [[ "$LINUX_INITRAMFS_ENABLE" == "y" ]]; then
	[[ -n "$LINUX_INITRAMFS_PATH" ]] || die \
		"LINUX_INITRAMFS_ENABLE=y 但 LINUX_INITRAMFS_PATH 为空"
	INITRAMFS_PATH="$(resolve_path "$LINUX_INITRAMFS_PATH")"
	[[ -f "$INITRAMFS_PATH" ]] || die \
		"initramfs 不存在：$LINUX_INITRAMFS_PATH（解析为 $INITRAMFS_PATH）"
	INITRAMFS_BYTES="$(stat -c '%s' "$INITRAMFS_PATH")"
	(( INITRAMFS_BYTES > 0 )) || die "initramfs 文件为空：$INITRAMFS_PATH"
	INITRAMFS_ADDR_NUM="$(parse_int LINUX_INITRAMFS_ADDR "$LINUX_INITRAMFS_ADDR")"
	if (( INITRAMFS_ADDR_NUM % 4096 != 0 )); then
		die "LINUX_INITRAMFS_ADDR=0x$(printf '%x' "$INITRAMFS_ADDR_NUM") 必须按 4 KiB 对齐"
	fi
	INITRAMFS_END=$((INITRAMFS_ADDR_NUM + INITRAMFS_BYTES))
	(( INITRAMFS_END > INITRAMFS_ADDR_NUM )) || die "initramfs 地址+大小溢出"
	MAGIC_HEX="$(hex_prefix "$INITRAMFS_PATH" 6)"
	case "$MAGIC_HEX" in
		303730373031*|303730373032*|303730373037*) INITRAMFS_FORMAT=cpio ;;
		1f8b*) INITRAMFS_FORMAT=gzip ;;
		fd377a585a00*) INITRAMFS_FORMAT=xz ;;
		28b52ffd*) INITRAMFS_FORMAT=zstd ;;
		04224d18*) INITRAMFS_FORMAT=lz4 ;;
		425a68*) INITRAMFS_FORMAT=bzip2 ;;
		5d0000*) INITRAMFS_FORMAT=lzma ;;
		*) die "$INITRAMFS_PATH 不是支持的 initramfs 格式（cpio/gzip/xz/zstd/lz4/bzip2/lzma，实际 magic=$MAGIC_HEX）" ;;
	esac
fi

# ---- 固件与 DTB 一致性 ----
[[ -f "$FW_BIN" ]] || die \
	"SBI 固件不存在：$FW_BIN。请先运行 'make sbi' 或 'make linux-image'。"
[[ -f "$FW_INFO" ]] || die \
	"SBI 固件信息不存在：$FW_INFO。请先运行 'make sbi' 或 'make linux-image'。"
FW_BYTES="$(stat -c '%s' "$FW_BIN")"
(( FW_BYTES > 0 )) || die "SBI 固件为空：$FW_BIN"
(( FW_BYTES <= FW_SIZE_NUM )) || die \
	"SBI 固件 $FW_BYTES 字节超过 SBI_FW_SIZE=0x$(printf '%x' "$FW_SIZE_NUM")"
INFO_FW_BASE="$(sed -n 's/^fw_base=//p' "$FW_INFO" | tail -n1)"
INFO_FW_SIZE="$(sed -n 's/^fw_size=//p' "$FW_INFO" | tail -n1)"
INFO_FW_SHA="$(sed -n 's/^fw_sha256=//p' "$FW_INFO" | tail -n1)"
INFO_FDT_ENABLE="$(sed -n 's/^fdt_enable=//p' "$FW_INFO" | tail -n1)"
INFO_FDT_ADDR="$(sed -n 's/^fdt_addr=//p' "$FW_INFO" | tail -n1)"
INFO_FDT_SHA="$(sed -n 's/^fdt_sha256=//p' "$FW_INFO" | tail -n1)"
[[ "$INFO_FW_BASE" == "0x$(printf '%x' "$FW_BASE_NUM")" ]] || die \
	"$FW_INFO 的 fw_base=$INFO_FW_BASE 与 SBI_FW_BASE=0x$(printf '%x' "$FW_BASE_NUM") 不一致"
[[ "$INFO_FW_SIZE" == "0x$(printf '%x' "$FW_SIZE_NUM")" ]] || die \
	"$FW_INFO 的 fw_size=$INFO_FW_SIZE 与 SBI_FW_SIZE=0x$(printf '%x' "$FW_SIZE_NUM") 不一致"
[[ -n "$INFO_FW_SHA" && "$INFO_FW_SHA" == "$(sha256sum "$FW_BIN" | cut -d' ' -f1)" ]] || die \
	"SBI 固件哈希与 $FW_INFO 不一致；请重新运行 make sbi/linux-image"
[[ "$INFO_FDT_ENABLE" == "y" ]] || die "$FW_INFO 记录 fdt_enable=$INFO_FDT_ENABLE；固件没有内嵌 DTB"
[[ "$INFO_FDT_ADDR" == "0x$(printf '%x' "$FDT_ADDR_NUM")" ]] || die \
	"$FW_INFO 的 fdt_addr=$INFO_FDT_ADDR 与 SBI_FDT_ADDR=0x$(printf '%x' "$FDT_ADDR_NUM") 不一致"
[[ -n "$INFO_FDT_SHA" ]] || die \
	"$FW_INFO 缺少 fdt_sha256；请重新运行 make sbi/linux-image（旧固件无法校验内嵌 DTB）"

# 按实际 initramfs 地址和文件大小重新生成 DTB。即使 Makefile 已经生成过，
# 这里也再生成一次，保证 /chosen/linux,initrd-start/end 与真实文件一致。
info "按当前 Kconfig/initramfs 重新生成 DTB：$SBI_FDT_OUT_DIR/npc.dtb"
SBI_CONFIG_FILE="$CONFIG_FILE" \
SBI_FDT_OUT_DIR="$SBI_FDT_OUT_DIR" \
SBI_FDT_PATH="$SBI_FDT_PATH" \
SBI_FDT_ADDR="0x$(printf '%x' "$FDT_ADDR_NUM")" \
SBI_FW_BASE="0x$(printf '%x' "$FW_BASE_NUM")" \
SBI_FW_SIZE="0x$(printf '%x' "$FW_SIZE_NUM")" \
SBI_NEXT_ADDR="0x$(printf '%x' "$NEXT_NUM")" \
SBI_TIMEBASE_FREQ="$SBI_TIMEBASE_FREQ" \
SBI_FDT_LINUX=y \
SBI_INITRAMFS_ENABLE="$LINUX_INITRAMFS_ENABLE" \
SBI_INITRAMFS_PATH="$LINUX_INITRAMFS_PATH" \
SBI_INITRAMFS_ADDR="$LINUX_INITRAMFS_ADDR" \
SBI_BOOTARGS="$LINUX_BOOTARGS" \
NPC_RAM_BASE="0x$(printf '%x' "$RAM_BASE_NUM")" \
NPC_RAM_SIZE="0x$(printf '%x' "$RAM_SIZE_NUM")" \
NPC_HOME="$NPC_HOME" \
WORKBENCH_HOME="$WORKBENCH_HOME" \
	"$HERE/build_dtb.sh"

DTB_PATH="$SBI_FDT_OUT_DIR/npc.dtb"
[[ -f "$DTB_PATH" ]] || die "DTB 生成脚本未生成 $DTB_PATH"
DTB_BYTES="$(stat -c '%s' "$DTB_PATH")"
DTB_MAGIC="$(hex_prefix "$DTB_PATH" 4)"
[[ "$DTB_MAGIC" == "d00dfeed" ]] || die "$DTB_PATH magic 不是 0xd00dfeed（实际 $DTB_MAGIC）"
DTB_SHA="$(sha256sum "$DTB_PATH" | cut -d' ' -f1)"
[[ "$DTB_SHA" == "$INFO_FDT_SHA" ]] || die \
	"当前 DTB 与 SBI 固件内嵌 DTB 不一致（dtb=$DTB_SHA fw_info=$INFO_FDT_SHA）；请重新运行 make linux-image"

DTB_END=$((FDT_ADDR_NUM + DTB_BYTES))
(( DTB_END > FDT_ADDR_NUM )) || die "DTB 地址+大小溢出"

# ---- 布局校验：所有区域互不重叠且不越过 RAM 结束 ----
check_range() {
	local name="$1" start="$2" end="$3"
	(( start >= RAM_BASE_NUM && end <= RAM_END && end > start )) || die \
		"$name 范围 [0x$(printf '%x' "$start"), 0x$(printf '%x' "$end")) 超出 NPC RAM [0x$(printf '%x' "$RAM_BASE_NUM"), 0x$(printf '%x' "$RAM_END")) 或为空"
}

check_overlap() {
	local name1="$1" start1="$2" end1="$3" name2="$4" start2="$5" end2="$6"
	if (( start1 < end2 && start2 < end1 )); then
		die "$name1 [0x$(printf '%x' "$start1"), 0x$(printf '%x' "$end1")) 与 $name2 [0x$(printf '%x' "$start2"), 0x$(printf '%x' "$end2")) 重叠"
	fi
}

check_range "SBI 固件" "$FW_BASE_NUM" "$FW_END"
check_range "Linux Image" "$NEXT_NUM" "$IMAGE_END"
check_overlap "SBI 固件" "$FW_BASE_NUM" "$FW_END" "Linux Image" "$NEXT_NUM" "$IMAGE_END"
if [[ "$LINUX_INITRAMFS_ENABLE" == "y" ]]; then
	check_range "initramfs" "$INITRAMFS_ADDR_NUM" "$INITRAMFS_END"
	check_overlap "initramfs" "$INITRAMFS_ADDR_NUM" "$INITRAMFS_END" "SBI 固件" "$FW_BASE_NUM" "$FW_END"
	check_overlap "initramfs" "$INITRAMFS_ADDR_NUM" "$INITRAMFS_END" "Linux Image" "$NEXT_NUM" "$IMAGE_END"
	check_overlap "initramfs" "$INITRAMFS_ADDR_NUM" "$INITRAMFS_END" "DTB" "$FDT_ADDR_NUM" "$DTB_END"
fi
check_range "DTB" "$FDT_ADDR_NUM" "$DTB_END"
check_overlap "DTB" "$FDT_ADDR_NUM" "$DTB_END" "SBI 固件" "$FW_BASE_NUM" "$FW_END"
check_overlap "DTB" "$FDT_ADDR_NUM" "$DTB_END" "Linux Image" "$NEXT_NUM" "$IMAGE_END"

MAX_END="$FW_END"
(( IMAGE_END > MAX_END )) && MAX_END="$IMAGE_END"
(( DTB_END > MAX_END )) && MAX_END="$DTB_END"
if [[ "$LINUX_INITRAMFS_ENABLE" == "y" ]] && (( INITRAMFS_END > MAX_END )); then
	MAX_END="$INITRAMFS_END"
fi
BIN_BYTES=$((MAX_END - RAM_BASE_NUM))
(( BIN_BYTES > 0 && BIN_BYTES <= RAM_SIZE_NUM )) || die \
	"打包大小 0x$(printf '%x' "$BIN_BYTES") 超出 NPC RAM 大小 0x$(printf '%x' "$RAM_SIZE_NUM")"

mkdir -p "$LINUX_OUT_DIR"
OUT_BIN="$LINUX_OUT_DIR/linux-image.bin"
OUT_INFO="$OUT_BIN.info"
OUT_SHA="$OUT_BIN.sha256"
TMP_BIN="$OUT_BIN.tmp.$$"
trap 'rm -f "$TMP_BIN"' EXIT

info "布局：RAM [0x$(printf '%x' "$RAM_BASE_NUM"), 0x$(printf '%x' "$RAM_END"))"
info "  SBI       [0x$(printf '%x' "$FW_BASE_NUM"), 0x$(printf '%x' "$FW_END"))  $FW_BYTES 字节 ($SBI_IMPL)"
info "  Image     [0x$(printf '%x' "$NEXT_NUM"), 0x$(printf '%x' "$IMAGE_END"))  file=$IMAGE_BYTES header_size=$IMAGE_SIZE"
if [[ "$LINUX_INITRAMFS_ENABLE" == "y" ]]; then
	info "  initramfs [0x$(printf '%x' "$INITRAMFS_ADDR_NUM"), 0x$(printf '%x' "$INITRAMFS_END"))  $INITRAMFS_BYTES 字节 ($INITRAMFS_FORMAT)"
else
	info "  initramfs 关闭（DTB 不生成 linux,initrd-*）"
fi
info "  DTB       [0x$(printf '%x' "$FDT_ADDR_NUM"), 0x$(printf '%x' "$DTB_END"))  $DTB_BYTES 字节"
info "  输出      $OUT_BIN ($BIN_BYTES 字节)"

truncate -s "$BIN_BYTES" "$TMP_BIN"

write_component() {
	local src="$1" offset="$2" bs="$3"
	if (( offset % bs == 0 )); then
		dd if="$src" of="$TMP_BIN" bs="$bs" seek=$((offset / bs)) conv=notrunc status=none
	else
		dd if="$src" of="$TMP_BIN" bs=1 seek="$offset" conv=notrunc status=none
	fi
	if ! cmp -n "$(stat -c '%s' "$src")" -i "$offset":0 "$TMP_BIN" "$src"; then
		die "写入 $src 后校验失败（offset=0x$(printf '%x' "$offset")）"
	fi
}

write_component "$FW_BIN" $((FW_BASE_NUM - RAM_BASE_NUM)) 4096
write_component "$IMAGE_PATH" $((NEXT_NUM - RAM_BASE_NUM)) 4096
if [[ "$LINUX_INITRAMFS_ENABLE" == "y" ]]; then
	write_component "$INITRAMFS_PATH" $((INITRAMFS_ADDR_NUM - RAM_BASE_NUM)) 4096
fi
write_component "$DTB_PATH" $((FDT_ADDR_NUM - RAM_BASE_NUM)) 1

mv -f "$TMP_BIN" "$OUT_BIN"
trap - EXIT
BIN_SHA="$(sha256sum "$OUT_BIN" | cut -d' ' -f1)"

{
	echo "format=1"
	echo "impl=$SBI_IMPL"
	echo "binary=$OUT_BIN"
	echo "binary_bytes=$BIN_BYTES"
	echo "binary_sha256=$BIN_SHA"
	echo "ram_base=0x$(printf '%x' "$RAM_BASE_NUM")"
	echo "ram_size=0x$(printf '%x' "$RAM_SIZE_NUM")"
	echo "fw_path=$FW_BIN"
	echo "fw_base=0x$(printf '%x' "$FW_BASE_NUM")"
	echo "fw_size=0x$(printf '%x' "$FW_SIZE_NUM")"
	echo "fw_bytes=$FW_BYTES"
	echo "fw_sha256=$(sha256sum "$FW_BIN" | cut -d' ' -f1)"
	echo "image_path=$IMAGE_PATH"
	echo "image_addr=0x$(printf '%x' "$NEXT_NUM")"
	echo "image_bytes=$IMAGE_BYTES"
	echo "image_header_size=$IMAGE_SIZE"
	echo "image_text_offset=0x$(printf '%x' "$IMAGE_TEXT_OFFSET")"
	echo "image_sha256=$(sha256sum "$IMAGE_PATH" | cut -d' ' -f1)"
	echo "initramfs_enable=$LINUX_INITRAMFS_ENABLE"
	if [[ "$LINUX_INITRAMFS_ENABLE" == "y" ]]; then
		echo "initramfs_path=$INITRAMFS_PATH"
		echo "initramfs_addr=0x$(printf '%x' "$INITRAMFS_ADDR_NUM")"
		echo "initramfs_bytes=$INITRAMFS_BYTES"
		echo "initramfs_end=0x$(printf '%x' "$INITRAMFS_END")"
		echo "initramfs_format=$INITRAMFS_FORMAT"
		echo "initramfs_sha256=$(sha256sum "$INITRAMFS_PATH" | cut -d' ' -f1)"
	fi
	echo "dtb_path=$DTB_PATH"
	echo "dtb_addr=0x$(printf '%x' "$FDT_ADDR_NUM")"
	echo "dtb_bytes=$DTB_BYTES"
	echo "dtb_sha256=$DTB_SHA"
	echo "timebase_freq=$(sed -n 's/^timebase_freq=//p' "$SBI_FDT_OUT_DIR/npc.dtb.info" | tail -n1)"
	echo "bootargs=$LINUX_BOOTARGS"
} >"$OUT_INFO.tmp"
mv -f "$OUT_INFO.tmp" "$OUT_INFO"

printf '%s  %s\n' "$BIN_SHA" "$(basename "$OUT_BIN")" >"$OUT_SHA.tmp"
mv -f "$OUT_SHA.tmp" "$OUT_SHA"

info "完成：$OUT_BIN"
info "sha256：$BIN_SHA"
info "摘要：$OUT_INFO"
info "校验：$OUT_SHA"
