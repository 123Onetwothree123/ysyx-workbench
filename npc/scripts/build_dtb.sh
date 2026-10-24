#!/usr/bin/env bash
#
# 生成 NPC 设备树 DTB。
#
# 输入（可由 Makefile 的 SBI_ENV 或手工环境变量覆盖）：
#   SBI_FDT_PATH          DTS 或 DTB 源文件；相对路径按 npc/ 解析
#   SBI_FDT_ADDR          DTB 加载/传递地址（默认 0x80f00000）
#   SBI_FW_BASE           固件基地址
#   SBI_FW_SIZE           固件保留区大小
#   SBI_NEXT_ADDR         next-stage S 模式入口
#   SBI_FDT_OUT_DIR       输出目录（默认 npc/build/dtb）
#   SBI_FDT_RESERVE_SIZE  DTS reserved-memory 中为 DTB 声明的保留大小
#   SBI_NEXT_RESERVE_SIZE DTS reserved-memory 中为 next-stage 声明的大小
#   NPC_RAM_BASE/SIZE     direct-NPC 的 RAM 窗口
#
# 输出：
#   $SBI_FDT_OUT_DIR/npc.dtb       最终 DTB
#   $SBI_FDT_OUT_DIR/npc.dts       cpp 展开后的可读 DTS（源为 DTS 时）
#   $SBI_FDT_OUT_DIR/npc.dtb.info  地址、大小、sha256 摘要
#
# 本脚本不修改 .config；是否把 DTB 嵌入/传递给 next-stage 由
# build_opensbi.sh / build_rustsbi.sh 根据 SBI_FDT_ENABLE 决定。
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NPC_HOME="${NPC_HOME:-$(cd -- "$HERE/.." && pwd)}"

SBI_FDT_PATH="${SBI_FDT_PATH:-dts/npc.dts}"
SBI_FDT_ADDR="${SBI_FDT_ADDR:-0x80f00000}"
SBI_FW_BASE="${SBI_FW_BASE:-0x80000000}"
SBI_FW_SIZE="${SBI_FW_SIZE:-0x00200000}"
SBI_NEXT_ADDR="${SBI_NEXT_ADDR:-0x80200000}"
SBI_FDT_OUT_DIR="${SBI_FDT_OUT_DIR:-$NPC_HOME/build/dtb}"
SBI_FDT_RESERVE_SIZE="${SBI_FDT_RESERVE_SIZE:-0x00010000}"
SBI_NEXT_RESERVE_SIZE="${SBI_NEXT_RESERVE_SIZE:-0x00001000}"
NPC_RAM_BASE="${NPC_RAM_BASE:-0x80000000}"
NPC_RAM_SIZE="${NPC_RAM_SIZE:-0x04000000}"

die() {
	echo "错误：$*" >&2
	exit 2
}

info() {
	echo "[dtb] $*"
}

parse_int() {
	local name="$1" value="$2"
	if [[ ! "$value" =~ ^(0[xX][0-9a-fA-F]+|[0-9]+)$ ]]; then
		die "$name='$value' 不是合法整数（支持十进制或 0x 十六进制）"
	fi
	printf '%d' "$((value))"
}

FDT_ADDR_NUM="$(parse_int SBI_FDT_ADDR "$SBI_FDT_ADDR")"
FW_BASE_NUM="$(parse_int SBI_FW_BASE "$SBI_FW_BASE")"
FW_SIZE_NUM="$(parse_int SBI_FW_SIZE "$SBI_FW_SIZE")"
NEXT_NUM="$(parse_int SBI_NEXT_ADDR "$SBI_NEXT_ADDR")"
RESERVE_NUM="$(parse_int SBI_FDT_RESERVE_SIZE "$SBI_FDT_RESERVE_SIZE")"
NEXT_RESERVE_NUM="$(parse_int SBI_NEXT_RESERVE_SIZE "$SBI_NEXT_RESERVE_SIZE")"
RAM_BASE_NUM="$(parse_int NPC_RAM_BASE "$NPC_RAM_BASE")"
RAM_SIZE_NUM="$(parse_int NPC_RAM_SIZE "$NPC_RAM_SIZE")"

# ---- 地址契约：在编译 DTB 之前先把明显错误的布局拒绝掉 ----
(( FDT_ADDR_NUM % 8 == 0 )) || \
	die "SBI_FDT_ADDR=0x$(printf '%x' "$FDT_ADDR_NUM") 必须按 8 字节对齐（DTB 规范要求）"
(( RESERVE_NUM > 0 && RESERVE_NUM % 4096 == 0 )) || \
	die "SBI_FDT_RESERVE_SIZE=0x$(printf '%x' "$RESERVE_NUM") 必须是正的 4 KiB 倍数"
(( NEXT_RESERVE_NUM > 0 )) || die "SBI_NEXT_RESERVE_SIZE 必须为正"
(( RAM_SIZE_NUM > 0 )) || die "NPC_RAM_SIZE 必须为正"
(( NEXT_NUM >= RAM_BASE_NUM && NEXT_NUM + NEXT_RESERVE_NUM <= RAM_BASE_NUM + RAM_SIZE_NUM )) || die \
	"next-stage 保留页 [0x$(printf '%x' "$NEXT_NUM"), 0x$(printf '%x' $((NEXT_NUM + NEXT_RESERVE_NUM)))) 超出 NPC RAM"

FDT_END=$((FDT_ADDR_NUM + RESERVE_NUM))
RAM_END=$((RAM_BASE_NUM + RAM_SIZE_NUM))
(( FDT_ADDR_NUM >= RAM_BASE_NUM && FDT_END <= RAM_END )) || die \
	"DTB 范围 [0x$(printf '%x' "$FDT_ADDR_NUM"), 0x$(printf '%x' "$FDT_END")) 超出 NPC RAM [0x$(printf '%x' "$RAM_BASE_NUM"), 0x$(printf '%x' "$RAM_END"))"

FW_END=$((FW_BASE_NUM + FW_SIZE_NUM))
if (( FDT_ADDR_NUM < FW_END && FDT_END > FW_BASE_NUM )); then
	die "DTB 范围 [0x$(printf '%x' "$FDT_ADDR_NUM"), 0x$(printf '%x' "$FDT_END")) 与 SBI 固件区 [0x$(printf '%x' "$FW_BASE_NUM"), 0x$(printf '%x' "$FW_END")) 重叠"
fi
if (( FDT_ADDR_NUM < NEXT_NUM + NEXT_RESERVE_NUM && \
      FDT_END > NEXT_NUM )); then
	die "DTB 范围 [0x$(printf '%x' "$FDT_ADDR_NUM"), 0x$(printf '%x' "$FDT_END")) 与 next-stage 保留页 [0x$(printf '%x' "$NEXT_NUM"), 0x$(printf '%x' $((NEXT_NUM + NEXT_RESERVE_NUM)))) 重叠"
fi
if (( FDT_ADDR_NUM < NEXT_NUM )); then
	(( FDT_END <= NEXT_NUM )) || die "DTB 位于 next-stage 之前时不能越过 SBI_NEXT_ADDR"
else
	GAP_MIB=$(((FDT_ADDR_NUM - NEXT_NUM) / 1024 / 1024))
	info "next-stage 可用区间 [0x$(printf '%x' "$NEXT_NUM"), 0x$(printf '%x' "$FDT_ADDR_NUM")) 约 ${GAP_MIB} MiB；请确保内核镜像不超过该范围"
fi

# ---- 解析源文件路径 ----
case "$SBI_FDT_PATH" in
	/*) SRC="$SBI_FDT_PATH" ;;
	*) SRC="$NPC_HOME/$SBI_FDT_PATH" ;;
esac
[[ -f "$SRC" ]] || die "SBI_FDT_PATH 不存在：$SBI_FDT_PATH（解析为 $SRC）"

mkdir -p "$SBI_FDT_OUT_DIR"
DTB_OUT="$SBI_FDT_OUT_DIR/npc.dtb"
EXPANDED_OUT="$SBI_FDT_OUT_DIR/npc.dts"
INFO_OUT="$SBI_FDT_OUT_DIR/npc.dtb.info"
TMP_DTB="$DTB_OUT.tmp"

case "$SRC" in
	*.dts)
		command -v cpp >/dev/null 2>&1 || die "找不到 cpp；无法展开 DTS 中的 Kconfig 地址宏"
		command -v dtc >/dev/null 2>&1 || die "找不到 dtc；请安装 device-tree-compiler"
		CPP_ARGS=(
			-P -nostdinc -undef -x assembler-with-cpp
			"-DNPC_RAM_BASE=0x$(printf '%x' "$RAM_BASE_NUM")"
			"-DNPC_RAM_SIZE=0x$(printf '%x' "$RAM_SIZE_NUM")"
			"-DNPC_RAM_BASE_NAME=$(printf '%x' "$RAM_BASE_NUM")"
			"-DSBI_FW_BASE=0x$(printf '%x' "$FW_BASE_NUM")"
			"-DSBI_FW_SIZE=0x$(printf '%x' "$FW_SIZE_NUM")"
			"-DSBI_FW_BASE_NAME=$(printf '%x' "$FW_BASE_NUM")"
			"-DSBI_NEXT_ADDR=0x$(printf '%x' "$NEXT_NUM")"
			"-DSBI_NEXT_ADDR_NAME=$(printf '%x' "$NEXT_NUM")"
			"-DSBI_NEXT_RESERVE_SIZE=0x$(printf '%x' "$NEXT_RESERVE_NUM")"
			"-DSBI_FDT_ADDR=0x$(printf '%x' "$FDT_ADDR_NUM")"
			"-DSBI_FDT_ADDR_NAME=$(printf '%x' "$FDT_ADDR_NUM")"
			"-DSBI_FDT_RESERVE_SIZE=0x$(printf '%x' "$RESERVE_NUM")"
		)
		info "展开 DTS：$SRC"
		cpp "${CPP_ARGS[@]}" "$SRC" -o "$EXPANDED_OUT"
		info "编译 DTB：$DTB_OUT"
		dtc -q -I dts -O dtb -o "$TMP_DTB" "$EXPANDED_OUT"
		mv -f "$TMP_DTB" "$DTB_OUT"
		;;
	*.dtb)
		info "使用预编译 DTB：$SRC"
		rm -f "$EXPANDED_OUT"
		install -m 0644 "$SRC" "$TMP_DTB"
		mv -f "$TMP_DTB" "$DTB_OUT"
		;;
	*)
		die "无法识别的设备树扩展名：$SBI_FDT_PATH（只支持 .dts / .dtb）"
		;;
esac

[[ -f "$DTB_OUT" ]] || die "DTB 生成失败：$DTB_OUT"
MAGIC="$(od -An -tx1 -N4 "$DTB_OUT" | tr -d ' \n')"
[[ "$MAGIC" == "d00dfeed" ]] || die "$DTB_OUT 的 magic 不是 0xd00dfeed（实际 $MAGIC）"

DTB_SIZE="$(stat -c '%s' "$DTB_OUT")"
(( DTB_SIZE > 0 && DTB_SIZE <= RESERVE_NUM )) || die \
	"DTB 大小 $DTB_SIZE 超出 SBI_FDT_RESERVE_SIZE=0x$(printf '%x' "$RESERVE_NUM")"
if (( FDT_ADDR_NUM < NEXT_NUM )); then
	(( DTB_SIZE <= NEXT_NUM - FDT_ADDR_NUM )) || die "DTB 实际大小 $DTB_SIZE 越过 SBI_NEXT_ADDR"
fi

{
	echo "source=$SRC"
	echo "fdt_addr=0x$(printf '%x' "$FDT_ADDR_NUM")"
	echo "fdt_reserve_size=0x$(printf '%x' "$RESERVE_NUM")"
	echo "dtb_bytes=$DTB_SIZE"
	echo "dtb_sha256=$(sha256sum "$DTB_OUT" | cut -d' ' -f1)"
	echo "fw_base=0x$(printf '%x' "$FW_BASE_NUM")"
	echo "fw_size=0x$(printf '%x' "$FW_SIZE_NUM")"
	echo "next_addr=0x$(printf '%x' "$NEXT_NUM")"
} >"$INFO_OUT"

info "完成：$DTB_OUT（$DTB_SIZE 字节，加载地址 0x$(printf '%x' "$FDT_ADDR_NUM")）"
