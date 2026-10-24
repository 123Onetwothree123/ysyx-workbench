#!/usr/bin/env bash
#
# 生成 NPC 设备树 DTB。
#
# 输入（Makefile 的 SBI_ENV 或手工环境变量可覆盖 .config）：
#   SBI_CONFIG_FILE        Kconfig 文件（默认 npc/.config）
#   SBI_FDT_PATH           DTS 或 DTB 源文件；相对路径按 npc/ 解析
#   SBI_FDT_ADDR           DTB 加载/传递地址（默认取 CONFIG_SBI_FDT_ADDR）
#   SBI_FW_BASE            固件基地址
#   SBI_FW_SIZE            固件保留区大小
#   SBI_NEXT_ADDR          next-stage S 模式入口（Linux Image 地址）
#   SBI_FDT_OUT_DIR        输出目录（默认 npc/build/dtb）
#   SBI_FDT_RESERVE_SIZE   DTS reserved-memory 中为 DTB 声明的保留大小
#   SBI_NEXT_RESERVE_SIZE  DTS reserved-memory 中为 next-stage 声明的大小
#   SBI_TIMEBASE_FREQ      mtime 实际增量频率（Hz）；未设置时由
#                          CONFIG_SYNTH_FREQ（MHz）推导
#   SBI_INITRAMFS_ENABLE   是否使用独立 initramfs（y/n）
#   SBI_INITRAMFS_PATH     initramfs 文件路径（相对 workbench 根目录）
#   SBI_INITRAMFS_ADDR     initramfs 加载地址
#   SBI_INITRAMFS_SIZE     显式 initramfs 大小（可选；设置时必须与文件一致）
#   SBI_BOOTARGS           /chosen/bootargs
#   SBI_FDT_LINUX          是否生成 Linux 可用的 DTB（y/n）；CUSTOM MMU 会失败
#   NPC_RAM_BASE/SIZE      direct-NPC 的 RAM 窗口
#
# CPU 描述（riscv,isa / riscv,isa-extensions / mmu-type）全部由
# .config 的 CONFIG_XLEN、CONFIG_RISCV_M/A/C/F/D、CONFIG_HAS_S/HAS_U、
# CONFIG_HAS_MMU、CONFIG_MMU_* 生成，不在 DTS 中硬编码。
#
# 输出：
#   $SBI_FDT_OUT_DIR/npc.dtb       最终 DTB
#   $SBI_FDT_OUT_DIR/npc.dts       cpp 展开后的可读 DTS（源为 DTS 时）
#   $SBI_FDT_OUT_DIR/npc.dtb.info  地址、大小、ISA、timebase、sha256 摘要
#
# 本脚本不修改 .config；是否把 DTB 嵌入/传递给 next-stage 由
# build_opensbi.sh / build_rustsbi.sh 根据 SBI_FDT_ENABLE 决定。
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
	echo "[dtb] $*"
}

# ---- 只读解析 .config，不 source，避免把配置值当 shell 代码执行 ----
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
	# Kconfig 对显式关闭的符号写 "# CONFIG_X is not set"；如果只看
	# CONFIG_X= 会把默认 y 的符号误判为 y。
	if [[ -f "$CONFIG_FILE" ]] && grep -qxF "# ${key} is not set" "$CONFIG_FILE"; then
		printf 'n'
		return
	fi
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

# cpp 字符串字面量转义：只处理反斜杠和双引号。
cpp_escape() {
	local value="$1"
	value="${value//\\/\\\\}"
	value="${value//\"/\\\"}"
	printf '%s' "$value"
}

cfg_geometry_mode() {
	local value
	value="$(cfg_value CONFIG_MMU_GEOMETRY_MODE "")"
	if [[ -n "$value" ]]; then
		printf '%s' "$value"
		return
	fi
	if [[ "$(cfg_bool CONFIG_MMU_GEOMETRY_CUSTOM n)" == "y" ]]; then
		printf 'custom'
	else
		printf 'standard'
	fi
}

cfg_pte_format() {
	local value
	value="$(cfg_value CONFIG_MMU_PTE_FORMAT "")"
	if [[ -n "$value" ]]; then
		printf '%s' "$value"
		return
	fi
	if [[ "$(cfg_bool CONFIG_MMU_PTE_CUSTOM n)" == "y" ]]; then
		printf 'custom'
	elif [[ "$(cfg_bool CONFIG_MMU_PTE_RISCV64 n)" == "y" ]]; then
		printf 'riscv64'
	else
		printf 'riscv32'
	fi
}

# ---- 读取布局与 CPU 配置（环境变量优先，便于测试/命令行覆盖） ----
SBI_FDT_PATH="${SBI_FDT_PATH:-$(cfg_value CONFIG_SBI_FDT_PATH dts/npc.dts)}"
SBI_FDT_ADDR="${SBI_FDT_ADDR:-$(cfg_value CONFIG_SBI_FDT_ADDR 0x80f00000)}"
SBI_FW_BASE="${SBI_FW_BASE:-$(cfg_value CONFIG_SBI_FW_BASE 0x80000000)}"
SBI_FW_SIZE="${SBI_FW_SIZE:-$(cfg_value CONFIG_SBI_FW_SIZE 0x00200000)}"
SBI_NEXT_ADDR="${SBI_NEXT_ADDR:-$(cfg_value CONFIG_SBI_NEXT_ADDR 0x80200000)}"
SBI_FDT_OUT_DIR="${SBI_FDT_OUT_DIR:-$NPC_HOME/build/dtb}"
SBI_FDT_RESERVE_SIZE="${SBI_FDT_RESERVE_SIZE:-0x00010000}"
SBI_NEXT_RESERVE_SIZE="${SBI_NEXT_RESERVE_SIZE:-0x00001000}"
NPC_RAM_BASE="${NPC_RAM_BASE:-0x80000000}"
NPC_RAM_SIZE="${NPC_RAM_SIZE:-0x04000000}"

XLEN="${SBI_XLEN:-$(cfg_value CONFIG_XLEN 64)}"
RISCV_M="${SBI_RISCV_M:-$(cfg_bool CONFIG_RISCV_M y)}"
RISCV_A="${SBI_RISCV_A:-$(cfg_bool CONFIG_RISCV_A y)}"
RISCV_C="${SBI_RISCV_C:-$(cfg_bool CONFIG_RISCV_C n)}"
RISCV_F="${SBI_RISCV_F:-$(cfg_bool CONFIG_RISCV_F n)}"
RISCV_D="${SBI_RISCV_D:-$(cfg_bool CONFIG_RISCV_D n)}"
HAS_S="${SBI_HAS_S:-$(cfg_bool CONFIG_HAS_S y)}"
HAS_U="${SBI_HAS_U:-$(cfg_bool CONFIG_HAS_U n)}"
HAS_MMU="${SBI_HAS_MMU:-$(cfg_bool CONFIG_HAS_MMU y)}"
MMU_LEVELS="${SBI_MMU_LEVELS:-$(cfg_value CONFIG_MMU_LEVELS 3)}"
MMU_GEOMETRY_MODE="${SBI_MMU_GEOMETRY_MODE:-$(cfg_geometry_mode)}"
MMU_PAGE_SIZE="${SBI_MMU_PAGE_SIZE:-$(cfg_value CONFIG_MMU_PAGE_SIZE_BYTES 4096)}"
MMU_PTE_SIZE="${SBI_MMU_PTE_SIZE:-$(cfg_value CONFIG_MMU_PTE_SIZE_BYTES 8)}"
MMU_PAGE_TABLE_SIZE="${SBI_MMU_PAGE_TABLE_SIZE:-$(cfg_value CONFIG_MMU_PAGE_TABLE_SIZE_BYTES 4096)}"
MMU_PTE_FORMAT="${SBI_MMU_PTE_FORMAT:-$(cfg_pte_format)}"
SYNTH_FREQ="${SBI_SYNTH_FREQ:-$(cfg_value CONFIG_SYNTH_FREQ 450)}"
SBI_FDT_LINUX="${SBI_FDT_LINUX:-$(cfg_bool CONFIG_LINUX_BOOT_ENABLE n)}"
SBI_INITRAMFS_ENABLE="${SBI_INITRAMFS_ENABLE:-$(cfg_bool CONFIG_LINUX_INITRAMFS_ENABLE n)}"
SBI_INITRAMFS_PATH="${SBI_INITRAMFS_PATH:-$(cfg_value CONFIG_LINUX_INITRAMFS_PATH "")}"
SBI_INITRAMFS_ADDR="${SBI_INITRAMFS_ADDR:-$(cfg_value CONFIG_LINUX_INITRAMFS_ADDR 0x82000000)}"
SBI_BOOTARGS="${SBI_BOOTARGS:-$(cfg_value CONFIG_LINUX_BOOTARGS "console=ttyS0,115200 earlycon=sbi")}"

# 校验开关值，避免拼写错误被当成 n。
case "$SBI_FDT_LINUX" in
	y|n) ;;
	*) die "SBI_FDT_LINUX='$SBI_FDT_LINUX' 非法（只允许 y/n）" ;;
esac
case "$SBI_INITRAMFS_ENABLE" in
	y|n) ;;
	*) die "SBI_INITRAMFS_ENABLE='$SBI_INITRAMFS_ENABLE' 非法（只允许 y/n）" ;;
esac

FDT_ADDR_NUM="$(parse_int SBI_FDT_ADDR "$SBI_FDT_ADDR")"
FW_BASE_NUM="$(parse_int SBI_FW_BASE "$SBI_FW_BASE")"
FW_SIZE_NUM="$(parse_int SBI_FW_SIZE "$SBI_FW_SIZE")"
NEXT_NUM="$(parse_int SBI_NEXT_ADDR "$SBI_NEXT_ADDR")"
RESERVE_NUM="$(parse_int SBI_FDT_RESERVE_SIZE "$SBI_FDT_RESERVE_SIZE")"
NEXT_RESERVE_NUM="$(parse_int SBI_NEXT_RESERVE_SIZE "$SBI_NEXT_RESERVE_SIZE")"
RAM_BASE_NUM="$(parse_int NPC_RAM_BASE "$NPC_RAM_BASE")"
RAM_SIZE_NUM="$(parse_int NPC_RAM_SIZE "$NPC_RAM_SIZE")"
XLEN_NUM="$(parse_int XLEN "$XLEN")"
MMU_LEVELS_NUM="$(parse_int MMU_LEVELS "$MMU_LEVELS")"
MMU_PAGE_SIZE_NUM="$(parse_int MMU_PAGE_SIZE "$MMU_PAGE_SIZE")"
MMU_PTE_SIZE_NUM="$(parse_int MMU_PTE_SIZE "$MMU_PTE_SIZE")"
MMU_PAGE_TABLE_SIZE_NUM="$(parse_int MMU_PAGE_TABLE_SIZE "$MMU_PAGE_TABLE_SIZE")"
SYNTH_FREQ_NUM="$(parse_int SYNTH_FREQ "$SYNTH_FREQ")"

# ---- 由 Kconfig 生成标准 ISA 字符串 ----
[[ "$XLEN_NUM" == "32" || "$XLEN_NUM" == "64" ]] || \
	die "CONFIG_XLEN=$XLEN_NUM 非法：只允许 32 或 64"
if [[ "$RISCV_D" == "y" && "$RISCV_F" != "y" ]]; then
	die "CONFIG_RISCV_D=y 但 CONFIG_RISCV_F!=y：D 不能脱离 F"
fi

ISA_BASE="rv${XLEN_NUM}i"
ISA="$ISA_BASE"
ISA_EXTS=("i")
EXT_LIST="\"i\""

append_ext() {
	local ext="$1"
	ISA+="$ext"
	ISA_EXTS+=("$ext")
	EXT_LIST+=", \"$ext\""
}

# 规范顺序：base 后按 i,m,a,f,d,c；未启用的扩展不写。
[[ "$RISCV_M" == "y" ]] && append_ext m
[[ "$RISCV_A" == "y" ]] && append_ext a
[[ "$RISCV_F" == "y" ]] && append_ext f
[[ "$RISCV_D" == "y" ]] && append_ext d
[[ "$RISCV_C" == "y" ]] && append_ext c
# NPC 始终实现 Zicsr/Zifencei（CSR 与 fence.i），且 Linux 的 S 模式
# 启动需要它们；不在扩展列表中时也明确写入。
ISA+="_zicsr_zifencei"
ISA_EXTS+=(zicsr zifencei)
EXT_LIST+=", \"zicsr\", \"zifencei\""

# ---- 由 Kconfig 推导可被 Linux 识别的标准 MMU 类型 ----
MMU_TYPE=""
MMU_TYPE_VALID=0
MMU_STANDARD=0
if [[ "$HAS_MMU" == "y" ]]; then
	if [[ "$MMU_GEOMETRY_MODE" == "standard" ]]; then
		if [[ "$XLEN_NUM" == "32" ]]; then
			# 标准 Sv32：4 KiB 页、4B PTE、2 级、4 KiB 页表。
			if [[ "$MMU_LEVELS_NUM" == "2" && \
			      "$MMU_PAGE_SIZE_NUM" == "4096" && \
			      "$MMU_PTE_SIZE_NUM" == "4" && \
			      "$MMU_PAGE_TABLE_SIZE_NUM" == "4096" && \
			      "$MMU_PTE_FORMAT" == "riscv32" ]]; then
				MMU_TYPE="riscv,sv32"
				MMU_TYPE_VALID=1
				MMU_STANDARD=1
			fi
		else
			# 标准 Sv39/Sv48：4 KiB 页、8B PTE、4 KiB 页表、3/4 级。
			if [[ "$MMU_PAGE_SIZE_NUM" == "4096" && \
			      "$MMU_PTE_SIZE_NUM" == "8" && \
			      "$MMU_PAGE_TABLE_SIZE_NUM" == "4096" && \
			      "$MMU_PTE_FORMAT" == "riscv64" ]]; then
				case "$MMU_LEVELS_NUM" in
					3) MMU_TYPE="riscv,sv39"; MMU_TYPE_VALID=1; MMU_STANDARD=1 ;;
					4) MMU_TYPE="riscv,sv48"; MMU_TYPE_VALID=1; MMU_STANDARD=1 ;;
				esac
			fi
		fi
	fi
fi

if [[ "$MMU_TYPE_VALID" != "1" ]]; then
	if [[ "$SBI_FDT_LINUX" == "y" ]]; then
		die "Kconfig 的 MMU 几何（XLEN=$XLEN_NUM mode=$MMU_GEOMETRY_MODE levels=$MMU_LEVELS_NUM page=$MMU_PAGE_SIZE_NUM pte=$MMU_PTE_SIZE_NUM table=$MMU_PAGE_TABLE_SIZE_NUM format=$MMU_PTE_FORMAT）不是标准 Sv32/Sv39/Sv48，不能生成 Linux DTB"
	fi
	info "非标准 MMU 几何：DTB 不写 mmu-type（避免伪装成标准类型）"
else
	info "MMU 类型：$MMU_TYPE"
fi

if [[ "$SBI_FDT_LINUX" == "y" && "$HAS_S" != "y" ]]; then
	die "生成 Linux DTB 需要 CONFIG_HAS_S=y"
fi

# ---- timebase 必须与 RTL mtime 的实际增量频率一致 ----
# mtime 每个 CPU 周期 +1，因此频率 = SYNTH_FREQ(MHz) * 1,000,000。
TIMEBASE_FREQ="${SBI_TIMEBASE_FREQ:-$((SYNTH_FREQ_NUM * 1000000))}"
TIMEBASE_FREQ_NUM="$(parse_int SBI_TIMEBASE_FREQ "$TIMEBASE_FREQ")"
(( TIMEBASE_FREQ_NUM > 0 )) || die "SBI_TIMEBASE_FREQ 必须为正"
(( TIMEBASE_FREQ_NUM <= 0xffffffff )) || die \
	"SBI_TIMEBASE_FREQ=$TIMEBASE_FREQ_NUM 超出 DTB timebase-frequency 的 32 位范围"

# ---- initramfs 大小与地址：只使用实际文件大小 ----
INITRAMFS_ADDR_NUM="$(parse_int SBI_INITRAMFS_ADDR "$SBI_INITRAMFS_ADDR")"
INITRAMFS_SIZE_NUM=0
INITRAMFS_END_NUM=0
INITRAMFS_PATH_ABS=""
if [[ "$SBI_INITRAMFS_ENABLE" == "y" ]]; then
	[[ -n "$SBI_INITRAMFS_PATH" ]] || die \
		"CONFIG_LINUX_INITRAMFS_ENABLE=y 但 LINUX_INITRAMFS_PATH 为空"
	INITRAMFS_PATH_ABS="$(resolve_path "$SBI_INITRAMFS_PATH")"
	[[ -f "$INITRAMFS_PATH_ABS" ]] || die \
		"initramfs 文件不存在：$SBI_INITRAMFS_PATH（解析为 $INITRAMFS_PATH_ABS）"
	ACTUAL_SIZE="$(stat -c '%s' "$INITRAMFS_PATH_ABS")"
	(( ACTUAL_SIZE > 0 )) || die "initramfs 文件为空：$INITRAMFS_PATH_ABS"
	if [[ -n "${SBI_INITRAMFS_SIZE:-}" ]]; then
		INITRAMFS_SIZE_NUM="$(parse_int SBI_INITRAMFS_SIZE "$SBI_INITRAMFS_SIZE")"
		(( INITRAMFS_SIZE_NUM == ACTUAL_SIZE )) || die \
			"SBI_INITRAMFS_SIZE=$INITRAMFS_SIZE_NUM 与文件实际大小 $ACTUAL_SIZE 不一致"
	else
		INITRAMFS_SIZE_NUM="$ACTUAL_SIZE"
	fi
	if (( INITRAMFS_ADDR_NUM == 0 )); then
		die "启用 initramfs 时 SBI_INITRAMFS_ADDR 不能为 0"
	fi
	(( INITRAMFS_ADDR_NUM % 4096 == 0 )) || die \
		"LINUX_INITRAMFS_ADDR=0x$(printf '%x' "$INITRAMFS_ADDR_NUM") 必须按 4 KiB 对齐"
	INITRAMFS_END_NUM=$((INITRAMFS_ADDR_NUM + INITRAMFS_SIZE_NUM))
	(( INITRAMFS_END_NUM > INITRAMFS_ADDR_NUM )) || die "initramfs 地址+大小溢出"
fi

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
if [[ "$INITRAMFS_SIZE_NUM" -gt 0 ]]; then
	(( INITRAMFS_ADDR_NUM >= RAM_BASE_NUM && INITRAMFS_END_NUM <= RAM_END )) || die \
		"initramfs 范围 [0x$(printf '%x' "$INITRAMFS_ADDR_NUM"), 0x$(printf '%x' "$INITRAMFS_END_NUM")) 超出 NPC RAM [0x$(printf '%x' "$RAM_BASE_NUM"), 0x$(printf '%x' "$RAM_END"))"
	if (( INITRAMFS_ADDR_NUM < FW_END && INITRAMFS_END_NUM > FW_BASE_NUM )); then
		die "initramfs 范围 [0x$(printf '%x' "$INITRAMFS_ADDR_NUM"), 0x$(printf '%x' "$INITRAMFS_END_NUM")) 与 SBI 固件区重叠"
	fi
	if (( INITRAMFS_ADDR_NUM < FDT_END && INITRAMFS_END_NUM > FDT_ADDR_NUM )); then
		die "initramfs 范围 [0x$(printf '%x' "$INITRAMFS_ADDR_NUM"), 0x$(printf '%x' "$INITRAMFS_END_NUM")) 与 DTB 保留区重叠"
	fi
	if (( INITRAMFS_ADDR_NUM < NEXT_NUM + NEXT_RESERVE_NUM && \
	      INITRAMFS_END_NUM > NEXT_NUM )); then
		die "initramfs 范围与 next-stage 入口页重叠"
	fi
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
if [[ "$SBI_FDT_LINUX" == "y" && "$SRC" == *.dtb ]]; then
	die "生成 Linux DTB 时 SBI_FDT_PATH 必须是 .dts：预编译 .dtb 无法按 Kconfig/initramfs 重新生成"
fi

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
			"-DNPC_TIMEBASE_FREQ=$TIMEBASE_FREQ_NUM"
			"-DNPC_RISCV_ISA=\"$(cpp_escape "$ISA")\""
			"-DNPC_RISCV_ISA_BASE=\"$(cpp_escape "$ISA_BASE")\""
			"-DNPC_RISCV_ISA_EXTENSIONS=$EXT_LIST"
			"-DNPC_MMU_TYPE=\"$(cpp_escape "$MMU_TYPE")\""
			"-DNPC_MMU_TYPE_VALID=$MMU_TYPE_VALID"
			"-DNPC_BOOTARGS=\"$(cpp_escape "$SBI_BOOTARGS")\""
			"-DSBI_INITRAMFS_ADDR=0x$(printf '%x' "$INITRAMFS_ADDR_NUM")"
			"-DSBI_INITRAMFS_SIZE=0x$(printf '%x' "$INITRAMFS_SIZE_NUM")"
			"-DSBI_INITRAMFS_END=0x$(printf '%x' "$INITRAMFS_END_NUM")"
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
	echo "isa=$ISA"
	echo "isa_base=$ISA_BASE"
	echo "isa_extensions=${ISA_EXTS[*]}"
	echo "has_s=$HAS_S"
	echo "has_u=$HAS_U"
	echo "mmu_type=${MMU_TYPE:-none}"
	echo "mmu_standard=$MMU_STANDARD"
	echo "timebase_freq=$TIMEBASE_FREQ_NUM"
	echo "synth_freq_mhz=$SYNTH_FREQ_NUM"
	echo "bootargs=$SBI_BOOTARGS"
	echo "linux_compatible=$SBI_FDT_LINUX"
	echo "fdt_addr=0x$(printf '%x' "$FDT_ADDR_NUM")"
	echo "fdt_reserve_size=0x$(printf '%x' "$RESERVE_NUM")"
	echo "dtb_bytes=$DTB_SIZE"
	echo "dtb_sha256=$(sha256sum "$DTB_OUT" | cut -d' ' -f1)"
	echo "fw_base=0x$(printf '%x' "$FW_BASE_NUM")"
	echo "fw_size=0x$(printf '%x' "$FW_SIZE_NUM")"
	echo "next_addr=0x$(printf '%x' "$NEXT_NUM")"
	echo "ram_base=0x$(printf '%x' "$RAM_BASE_NUM")"
	echo "ram_size=0x$(printf '%x' "$RAM_SIZE_NUM")"
	echo "initramfs_enable=$SBI_INITRAMFS_ENABLE"
	if (( INITRAMFS_SIZE_NUM > 0 )); then
		echo "initramfs_path=$INITRAMFS_PATH_ABS"
		echo "initramfs_addr=0x$(printf '%x' "$INITRAMFS_ADDR_NUM")"
		echo "initramfs_size=0x$(printf '%x' "$INITRAMFS_SIZE_NUM")"
		echo "initramfs_end=0x$(printf '%x' "$INITRAMFS_END_NUM")"
	fi
} >"$INFO_OUT"

info "完成：$DTB_OUT（$DTB_SIZE 字节，加载地址 0x$(printf '%x' "$FDT_ADDR_NUM")）"
info "ISA：$ISA；timebase=${TIMEBASE_FREQ_NUM}Hz；mmu-type=${MMU_TYPE:-none}"
