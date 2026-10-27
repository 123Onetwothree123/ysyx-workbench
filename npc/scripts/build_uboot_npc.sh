#!/usr/bin/env bash
#
# 构建 NPC 的 OpenSBI FW_JUMP + U-Boot S-mode EFI 启动镜像。
#
# 该脚本不下载/编译 Linux；Debian EFI vmlinux 和 BusyBox initramfs
# 只作为调用者提供的输入文件嵌入。U-Boot 板级复用 vendor/u-boot 的
# nemu_riscv64_smode_defconfig：NPC 与 NEMU 在 S-mode 看到相同的
# UART/CLINT/PLIC 地址和 SBI 接口，区别只在 NPC 的 450 MHz DTB 与
# 可配置的 RAM 大小。
#
# 固定布局（默认 64 MiB；128 MiB 配置只把 DTB 移到 0x87f00000）：
#   0x80000000  OpenSBI FW_JUMP（预留 SBI_FW_SIZE，默认 2 MiB）
#   0x80200000  U-Boot（CONFIG_TEXT_BASE / SBI_NEXT_ADDR）
#   0x80600000  可选 EFI 测试应用（默认 initrddump.efi）
#   0x80800000  可选 initramfs
#   0x81000000  可选 EFI 内核（Debian install/vmlinux）
#   0x83f00000  64 MiB 配置的 DTB（128 MiB 时为 0x87f00000）
#   0x84000000  64 MiB RAM 结束（128 MiB 时为 0x88000000）
#
# 所有地址都来自 Makefile/Kconfig 传入的环境变量；脚本只负责校验
# 实际文件大小、拼接和生成摘要。最终 raw image 由 C++ 工具
# tools/pack_uboot_image.cpp 生成（Makefile 通过 NPC_UBOOT_PACK_BIN 传入）。
#
# 输出（$NPC_UBOOT_OUT_DIR，默认 npc/build/uboot）：
#   npc-uboot.bin        direct-NPC 可直接加载的 raw binary
#   npc-uboot.bin.info   布局、大小、SHA256、运行命令
#
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NPC_HOME="${NPC_HOME:-$(cd -- "$HERE/.." && pwd)}"
WORKBENCH_HOME="${WORKBENCH_HOME:-$(cd -- "$NPC_HOME/.." && pwd)}"
CONFIG_FILE="${NPC_UBOOT_CONFIG_FILE:-${NPC_CONFIG_FILE:-$NPC_HOME/.config}}"
OUT="${NPC_UBOOT_OUT_DIR:-$NPC_HOME/build/uboot}"
UBOOT_DIR="$WORKBENCH_HOME/vendor/u-boot"
UBOOT_BUILD="$OUT/uboot-build"
UBOOT_DEFCONFIG="${NPC_UBOOT_DEFCONFIG:-nemu_riscv64_smode_defconfig}"
CROSS_COMPILE="${CROSS_COMPILE:-riscv64-linux-gnu-}"
JOBS="${NPC_UBOOT_JOBS:-0}"
if [[ "$JOBS" == "0" ]]; then
	JOBS="$(nproc 2>/dev/null || echo 4)"
fi

FW_BASE="${SBI_FW_BASE:-0x80000000}"
FW_SIZE="${SBI_FW_SIZE:-0x00200000}"
NEXT_ADDR="${SBI_NEXT_ADDR:-0x80200000}"
DTB_ADDR="${SBI_FDT_ADDR:-0x83f00000}"
RAM_BASE="${NPC_RAM_BASE:-0x80000000}"
RAM_SIZE="${NPC_RAM_SIZE:-0x04000000}"
EFI_APP_ADDR="${NPC_UBOOT_EFI_APP_ADDR:-0x80600000}"
INITRAMFS_ADDR="${NPC_UBOOT_INITRAMFS_ADDR:-0x80800000}"
KERNEL_ADDR="${NPC_UBOOT_KERNEL_ADDR:-0x81000000}"
BOOTARGS="${NPC_UBOOT_BOOTARGS:-console=ttyS0,115200 earlycon=sbi no5lvl cpuidle.off=1 loglevel=7}"
# NPC 的 mtime 每 CPU 周期 +1，timebase=450MHz。U-Boot 默认 bootdelay=2
# 会等待 900M 个仿真周期；-1 表示直接进入 prompt，不启动 autoboot。
# 这不是 NEMU 的 1MHz 行为，必须作为 NPC 板级适配显式处理。
BOOTDELAY="${NPC_UBOOT_BOOTDELAY:--1}"
EFI_APP_PATH="${NPC_UBOOT_EFI_APP_PATH:-}"
INITRAMFS_PATH="${NPC_UBOOT_INITRAMFS_PATH:-}"
KERNEL_PATH="${NPC_UBOOT_KERNEL_PATH:-}"
SBI_IMPL="${SBI_IMPL:-opensbi}"
SBI_PLATFORM="${SBI_PLATFORM:-npc}"
SBI_FDT_PATH="${SBI_FDT_PATH:-dts/npc.dts}"
SBI_TIMEBASE_FREQ="${SBI_TIMEBASE_FREQ:-450000000}"
SBI_TOOLCHAIN_PREFIX="${SBI_TOOLCHAIN_PREFIX:-riscv64-unknown-elf-}"
SBI_ISA="${SBI_ISA:-rv64ima_zicsr_zifencei}"
SBI_ABI="${SBI_ABI:-lp64}"
SBI_LD="${SBI_LD:-ld.lld}"
SBI_VENDOR_DIR="${SBI_VENDOR_DIR:-$WORKBENCH_HOME/vendor/opensbi}"

die() {
	echo "错误：$*" >&2
	exit 2
}

info() {
	echo "[uboot-npc] $*"
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
		"~/"*) printf '%s' "$HOME/${path#~/}" ;;
		/*) printf '%s' "$path" ;;
		*) printf '%s/%s' "$WORKBENCH_HOME" "$path" ;;
	esac
}

[[ "${SBI_ENABLE:-n}" == "y" ]] || die \
	"SBI_ENABLE=${SBI_ENABLE:-n}；请先运行 make rv64_linux_uboot_128m_defconfig"
[[ "${SBI_FDT_ENABLE:-n}" == "y" ]] || die \
	"SBI_FDT_ENABLE=${SBI_FDT_ENABLE:-n}；U-Boot 需要 a1=DTB"
[[ "$SBI_IMPL" == "opensbi" ]] || die "当前脚本只支持 SBI_IMPL=opensbi"
[[ -d "$UBOOT_DIR/board/nemu/riscv64" ]] || die \
	"找不到 U-Boot 板级目录：$UBOOT_DIR/board/nemu/riscv64"
[[ -d "$SBI_VENDOR_DIR" ]] || die "找不到 OpenSBI 源码：$SBI_VENDOR_DIR"
command -v "${CROSS_COMPILE}gcc" >/dev/null || die \
	"找不到 ${CROSS_COMPILE}gcc"

FW_BASE_NUM="$(parse_int SBI_FW_BASE "$FW_BASE")"
FW_SIZE_NUM="$(parse_int SBI_FW_SIZE "$FW_SIZE")"
NEXT_NUM="$(parse_int SBI_NEXT_ADDR "$NEXT_ADDR")"
DTB_ADDR_NUM="$(parse_int SBI_FDT_ADDR "$DTB_ADDR")"
RAM_BASE_NUM="$(parse_int NPC_RAM_BASE "$RAM_BASE")"
RAM_SIZE_NUM="$(parse_int NPC_RAM_SIZE "$RAM_SIZE")"
EFI_APP_ADDR_NUM="$(parse_int NPC_UBOOT_EFI_APP_ADDR "$EFI_APP_ADDR")"
INITRAMFS_ADDR_NUM="$(parse_int NPC_UBOOT_INITRAMFS_ADDR "$INITRAMFS_ADDR")"
KERNEL_ADDR_NUM="$(parse_int NPC_UBOOT_KERNEL_ADDR "$KERNEL_ADDR")"
RAM_END_NUM=$((RAM_BASE_NUM + RAM_SIZE_NUM))

(( RAM_BASE_NUM == 0x80000000 )) || die \
	"本启动链固定 RAM_BASE=0x80000000，收到 0x$(printf '%x' "$RAM_BASE_NUM")"
(( RAM_SIZE_NUM >= 0x04000000 )) || die \
	"U-Boot/debian 布局至少需要 64 MiB RAM，当前 0x$(printf '%x' "$RAM_SIZE_NUM")"
(( (RAM_SIZE_NUM & (RAM_SIZE_NUM - 1)) == 0 )) || die \
	"NPC_RAM_SIZE=0x$(printf '%x' "$RAM_SIZE_NUM") 必须是 2 的幂"
(( FW_BASE_NUM == 0x80000000 )) || die "SBI_FW_BASE 必须为 0x80000000"
(( FW_SIZE_NUM > 0 && FW_SIZE_NUM % 4096 == 0 )) || die "SBI_FW_SIZE 必须是正的 4 KiB 倍数"
(( FW_BASE_NUM + FW_SIZE_NUM <= NEXT_NUM )) || die \
	"OpenSBI 固件区越过 SBI_NEXT_ADDR=0x$(printf '%x' "$NEXT_NUM")"
(( DTB_ADDR_NUM % 8 == 0 )) || die "SBI_FDT_ADDR 必须 8 字节对齐"
(( DTB_ADDR_NUM >= RAM_BASE_NUM && DTB_ADDR_NUM + 0x10000 <= RAM_END_NUM )) || die \
	"SBI_FDT_ADDR=0x$(printf '%x' "$DTB_ADDR_NUM") 超出 RAM"
for pair in \
	"NPC_UBOOT_EFI_APP_ADDR:$EFI_APP_ADDR_NUM" \
	"NPC_UBOOT_INITRAMFS_ADDR:$INITRAMFS_ADDR_NUM" \
	"NPC_UBOOT_KERNEL_ADDR:$KERNEL_ADDR_NUM"; do
	name="${pair%%:*}"
	value="${pair#*:}"
	(( value % 4096 == 0 )) || die "$name=0x$(printf '%x' "$value") 必须 4 KiB 对齐"
	(( value > NEXT_NUM )) || die "$name=0x$(printf '%x' "$value") 位于 U-Boot 之前"
done
(( EFI_APP_ADDR_NUM < INITRAMFS_ADDR_NUM && INITRAMFS_ADDR_NUM < KERNEL_ADDR_NUM )) || \
	die "EFI 应用/initramfs/内核地址必须按顺序递增"
(( KERNEL_ADDR_NUM < DTB_ADDR_NUM )) || die \
	"内核地址 0x$(printf '%x' "$KERNEL_ADDR_NUM") 必须位于 DTB 之前"
if [[ "$SBI_TIMEBASE_FREQ" =~ ^[0-9]+$ ]] && (( SBI_TIMEBASE_FREQ > 0 )); then
	:
else
	die "SBI_TIMEBASE_FREQ='$SBI_TIMEBASE_FREQ' 非法"
fi

mkdir -p "$OUT"

info "配置：$CONFIG_FILE"
info "RAM：0x$(printf '%x' "$RAM_BASE_NUM")+0x$(printf '%x' "$RAM_SIZE_NUM")，DTB 0x$(printf '%x' "$DTB_ADDR_NUM")"

# ---------------------------------------------------------------------------
# 1) U-Boot：板级与 NEMU 相同（同地址 UART/CLINT/PLIC + OpenSBI SBI）。
# ---------------------------------------------------------------------------
info "构建 U-Boot（$UBOOT_DIR，O=$UBOOT_BUILD）"
make -C "$UBOOT_DIR" O="$UBOOT_BUILD" ARCH=riscv \
	CROSS_COMPILE="$CROSS_COMPILE" "$UBOOT_DEFCONFIG" \
	</dev/null >"$OUT/uboot-defconfig.log" 2>&1
make -C "$UBOOT_DIR" O="$UBOOT_BUILD" ARCH=riscv \
	CROSS_COMPILE="$CROSS_COMPILE" olddefconfig \
	</dev/null >>"$OUT/uboot-defconfig.log" 2>&1
# 只在构建产物 .config 中覆盖 bootdelay，不修改 vendor/u-boot 的
# defconfig。负数表示跳过 autoboot，直接进入 U-Boot prompt。
sed -i "s/^CONFIG_BOOTDELAY=.*/CONFIG_BOOTDELAY=$BOOTDELAY/" \
	"$UBOOT_BUILD/.config"
make -C "$UBOOT_DIR" O="$UBOOT_BUILD" ARCH=riscv \
	CROSS_COMPILE="$CROSS_COMPILE" -j"$JOBS" \
	</dev/null >"$OUT/uboot-build.log" 2>&1
UBOOT_BIN="$UBOOT_BUILD/u-boot.bin"
[[ -f "$UBOOT_BIN" ]] || die "U-Boot 构建没有生成 $UBOOT_BIN"
UBOOT_BYTES="$(stat -c '%s' "$UBOOT_BIN")"
(( UBOOT_BYTES > 0 )) || die "U-Boot 映像为空"

# U-Boot 配置契约：EFI loader/bootefi/EFI_LOAD_FILE2 缺一不可。
for key in \
	CONFIG_RISCV_SMODE=y \
	CONFIG_EFI_LOADER=y \
	CONFIG_CMD_BOOTEFI=y \
	CONFIG_CMD_BOOTEFI_BINARY=y \
	CONFIG_EFI_LOAD_FILE2_INITRD=y \
	CONFIG_OF_HAS_PRIOR_STAGE=y
do
	grep -qx "$key" "$UBOOT_BUILD/.config" || die "U-Boot 配置缺少 $key"
done
grep -qx "CONFIG_TEXT_BASE=0x$(printf '%x' "$NEXT_NUM")" "$UBOOT_BUILD/.config" || \
	die "U-Boot CONFIG_TEXT_BASE 与 SBI_NEXT_ADDR=0x$(printf '%x' "$NEXT_NUM") 不一致"

# ---------------------------------------------------------------------------
# 2) OpenSBI FW_JUMP + NPC DTB（timebase/RAM 来自当前 NPC 配置）。
# ---------------------------------------------------------------------------
info "生成 NPC DTB + OpenSBI FW_JUMP"
NPC_CONFIG_FILE="$CONFIG_FILE" \
NPC_HOME="$NPC_HOME" \
WORKBENCH_HOME="$WORKBENCH_HOME" \
SBI_ENABLE=y \
SBI_IMPL=opensbi \
SBI_PLATFORM="$SBI_PLATFORM" \
SBI_FW_BASE="$FW_BASE" \
SBI_FW_SIZE="$FW_SIZE" \
SBI_NEXT_ADDR="$NEXT_ADDR" \
SBI_BUILD_JOBS="$JOBS" \
SBI_FDT_ENABLE=y \
SBI_FDT_PATH="$SBI_FDT_PATH" \
SBI_FDT_ADDR="$DTB_ADDR" \
SBI_FDT_OUT_DIR="$OUT/dtb" \
SBI_TIMEBASE_FREQ="$SBI_TIMEBASE_FREQ" \
SBI_FDT_LINUX=n \
SBI_INITRAMFS_ENABLE=n \
SBI_BOOTARGS="$BOOTARGS" \
SBI_ISA="$SBI_ISA" \
SBI_ABI="$SBI_ABI" \
SBI_TOOLCHAIN_PREFIX="$SBI_TOOLCHAIN_PREFIX" \
SBI_LD="$SBI_LD" \
SBI_VENDOR_DIR="$SBI_VENDOR_DIR" \
SBI_OUT_DIR="$OUT/opensbi" \
NPC_RAM_BASE="$RAM_BASE" \
NPC_RAM_SIZE="$RAM_SIZE" \
	"$HERE/build_opensbi.sh" >"$OUT/opensbi-build.log" 2>&1
FW_BIN="$OUT/opensbi/fw.bin"
FW_INFO="$OUT/opensbi/fw.info"
[[ -f "$FW_BIN" && -f "$FW_INFO" ]] || die "OpenSBI 构建没有生成 $FW_BIN/fw.info"
FW_BYTES="$(stat -c '%s' "$FW_BIN")"
(( FW_BYTES <= FW_SIZE_NUM )) || die \
	"OpenSBI fw.bin $FW_BYTES 字节超过 SBI_FW_SIZE=0x$(printf '%x' "$FW_SIZE_NUM")"
INFO_FDT_ADDR="$(sed -n 's/^fdt_addr=//p' "$FW_INFO" | tail -n1)"
[[ "$INFO_FDT_ADDR" == "0x$(printf '%x' "$DTB_ADDR_NUM")" ]] || die \
	"$FW_INFO 的 fdt_addr=$INFO_FDT_ADDR 与 SBI_FDT_ADDR=0x$(printf '%x' "$DTB_ADDR_NUM") 不一致"
DTB_PATH="$OUT/dtb/npc.dtb"
[[ -f "$DTB_PATH" ]] || die "没有生成 $DTB_PATH"
DTB_BYTES="$(stat -c '%s' "$DTB_PATH")"

# ---------------------------------------------------------------------------
# 3) 可选载荷路径解析。
# ---------------------------------------------------------------------------
if [[ -n "$EFI_APP_PATH" ]]; then
	EFI_APP="$(resolve_path "$EFI_APP_PATH")"
else
	EFI_APP=""
fi
if [[ -n "$INITRAMFS_PATH" ]]; then
	INITRAMFS="$(resolve_path "$INITRAMFS_PATH")"
else
	INITRAMFS=""
fi
if [[ -n "$KERNEL_PATH" ]]; then
	KERNEL="$(resolve_path "$KERNEL_PATH")"
else
	KERNEL=""
fi
[[ -z "$EFI_APP" || -f "$EFI_APP" ]] || die "EFI 应用不存在：$EFI_APP"
[[ -z "$INITRAMFS" || -f "$INITRAMFS" ]] || die "initramfs 不存在：$INITRAMFS"
[[ -z "$KERNEL" || -f "$KERNEL" ]] || die "EFI 内核不存在：$KERNEL"

# 有 initramfs 但没有指定 EFI 应用时，使用 U-Boot 自带的
# initrddump.efi 验证 EFI_LOAD_FILE2（不会执行 Linux）。
if [[ -n "$INITRAMFS" && -z "$EFI_APP" ]]; then
	EFI_APP="$UBOOT_BUILD/lib/efi_loader/initrddump.efi"
	[[ -f "$EFI_APP" ]] || die "找不到 U-Boot initrddump.efi：$EFI_APP"
fi

# U-Boot 源文件占用 [NEXT_ADDR, NEXT_ADDR+UBOOT_BYTES)。它的 BSS/栈会
# 在运行时重定位到 RAM 顶部，不占这里；EFI 应用地址必须在这段之后。
(( NEXT_NUM + UBOOT_BYTES <= EFI_APP_ADDR_NUM )) || die \
	"U-Boot $UBOOT_BYTES 字节与 EFI 应用地址 0x$(printf '%x' "$EFI_APP_ADDR_NUM") 重叠"
for pair in \
	"initramfs:$INITRAMFS:$INITRAMFS_ADDR_NUM" \
	"kernel:$KERNEL:$KERNEL_ADDR_NUM" \
	"efi_app:$EFI_APP:$EFI_APP_ADDR_NUM"; do
	name="${pair%%:*}"
	rest="${pair#*:}"
	path="${rest%%:*}"
	addr="${rest##*:}"
	if [[ -n "$path" ]]; then
		bytes="$(stat -c '%s' "$path")"
		(( bytes > 0 )) || die "$name 文件为空：$path"
		end=$((addr + bytes))
		(( end > addr )) || die "$name 地址+大小溢出"
		(( end <= DTB_ADDR_NUM )) || die \
			"$name [0x$(printf '%x' "$addr"), 0x$(printf '%x' "$end")) 越过 DTB 0x$(printf '%x' "$DTB_ADDR_NUM")"
	fi
done

# ---------------------------------------------------------------------------
# 4) 拼接 raw image：与 NEMU 一样从 0x80000000 起，地址写进文件偏移。
# ---------------------------------------------------------------------------
info "打包 $OUT/npc-uboot.bin"

NPC_UBOOT_PACK_BIN="${NPC_UBOOT_PACK_BIN:-$WORKBENCH_HOME/npc/tools/build/pack_uboot_image}"
[[ -x "$NPC_UBOOT_PACK_BIN" ]] || die \
	"找不到 U-Boot 打包工具：$NPC_UBOOT_PACK_BIN（先运行 make uboot-image）"
"$NPC_UBOOT_PACK_BIN" "$OUT/npc-uboot.bin" "$FW_BIN" "$UBOOT_BIN" \
	"$EFI_APP" "$INITRAMFS" "$KERNEL" \
	"$((FW_BASE_NUM - RAM_BASE_NUM))" \
	"$((NEXT_NUM - RAM_BASE_NUM))" \
	"$((EFI_APP_ADDR_NUM - RAM_BASE_NUM))" \
	"$((INITRAMFS_ADDR_NUM - RAM_BASE_NUM))" \
	"$((KERNEL_ADDR_NUM - RAM_BASE_NUM))" \
	"$((DTB_ADDR_NUM - RAM_BASE_NUM))" \
	"$RAM_SIZE_NUM"

PACK_INFO="$OUT/npc-uboot.bin.info"
{
	echo "platform=npc"
	echo "uboot_defconfig=$UBOOT_DEFCONFIG"
	echo "image=$OUT/npc-uboot.bin"
	echo "image_bytes=$(stat -c '%s' "$OUT/npc-uboot.bin")"
	echo "image_sha256=$(sha256sum "$OUT/npc-uboot.bin" | cut -d' ' -f1)"
	echo "ram_base=0x$(printf '%x' "$RAM_BASE_NUM")"
	echo "ram_size=0x$(printf '%x' "$RAM_SIZE_NUM")"
	echo "timebase_freq=$SBI_TIMEBASE_FREQ"
	echo "bootargs=$BOOTARGS"
	echo "bootdelay=$BOOTDELAY"
	echo "opensbi_addr=0x$(printf '%x' "$FW_BASE_NUM")"
	echo "opensbi_bytes=$FW_BYTES"
	echo "uboot_addr=0x$(printf '%x' "$NEXT_NUM")"
	echo "uboot_bytes=$UBOOT_BYTES"
	echo "dtb_addr=0x$(printf '%x' "$DTB_ADDR_NUM")"
	echo "dtb_bytes=$DTB_BYTES"
	echo "dtb_sha256=$(sha256sum "$DTB_PATH" | cut -d' ' -f1)"
	if [[ -n "$EFI_APP" ]]; then
		echo "efi_app=$EFI_APP"
		echo "efi_app_addr=0x$(printf '%x' "$EFI_APP_ADDR_NUM")"
		echo "efi_app_bytes=$(stat -c '%s' "$EFI_APP")"
		printf 'efi_app_bytes_hex=0x%x\n' "$(stat -c '%s' "$EFI_APP")"
		echo "efi_app_sha256=$(sha256sum "$EFI_APP" | cut -d' ' -f1)"
	fi
	if [[ -n "$INITRAMFS" ]]; then
		echo "initrd=$INITRAMFS"
		echo "initrd_addr=0x$(printf '%x' "$INITRAMFS_ADDR_NUM")"
		echo "initrd_bytes=$(stat -c '%s' "$INITRAMFS")"
		printf 'initrd_bytes_hex=0x%x\n' "$(stat -c '%s' "$INITRAMFS")"
		echo "initrd_sha256=$(sha256sum "$INITRAMFS" | cut -d' ' -f1)"
	fi
	if [[ -n "$KERNEL" ]]; then
		echo "kernel=$KERNEL"
		echo "kernel_addr=0x$(printf '%x' "$KERNEL_ADDR_NUM")"
		echo "kernel_bytes=$(stat -c '%s' "$KERNEL")"
		printf 'kernel_bytes_hex=0x%x\n' "$(stat -c '%s' "$KERNEL")"
		echo "kernel_sha256=$(sha256sum "$KERNEL" | cut -d' ' -f1)"
	fi
} >"$PACK_INFO"

info "完成：$OUT/npc-uboot.bin"
info "布局：OpenSBI 0x$(printf '%x' "$FW_BASE_NUM")，U-Boot 0x$(printf '%x' "$NEXT_NUM")，DTB 0x$(printf '%x' "$DTB_ADDR_NUM")"
if [[ -n "$KERNEL" ]]; then
	info "EFI 内核：0x$(printf '%x' "$KERNEL_ADDR_NUM")（$(stat -c '%s' "$KERNEL") 字节）"
fi
if [[ -n "$INITRAMFS" ]]; then
	info "initramfs：0x$(printf '%x' "$INITRAMFS_ADDR_NUM")（$(stat -c '%s' "$INITRAMFS") 字节）"
fi
if [[ -n "$EFI_APP" ]]; then
	info "EFI 应用：0x$(printf '%x' "$EFI_APP_ADDR_NUM")（$(stat -c '%s' "$EFI_APP") 字节）"
fi
info "运行：cd $NPC_HOME && ./build/ysyx_26030103_npc $OUT/npc-uboot.bin --batch"
info "摘要：$PACK_INFO"
