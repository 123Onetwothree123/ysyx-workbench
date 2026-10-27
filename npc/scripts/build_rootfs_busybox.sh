#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NPC_HOME="${NPC_HOME:-$(cd -- "$HERE/.." && pwd)}"
WORKBENCH_HOME="${WORKBENCH_HOME:-$(cd -- "$NPC_HOME/.." && pwd)}"
CONFIG_FILE="${ROOTFS_CONFIG:-$NPC_HOME/.config}"

command -v realpath >/dev/null 2>&1 || {
	echo "错误：缺少必要工具：realpath" >&2
	exit 2
}

die() {
	echo "错误：$*" >&2
	exit 2
}

info() {
	echo "[rootfs] $*"
}

usage() {
	cat <<'EOF'
用法：build_rootfs_busybox.sh [--clean]

  --clean   删除 ROOTFS_OUT_DIR（默认 npc/build/rootfs），不触碰
            vendor/busybox 源码。
EOF
}

cfg_raw() {
	local key="$1"
	[ -f "$CONFIG_FILE" ] || return 0
	sed -n "s/^${key}=//p" "$CONFIG_FILE" | tail -n 1
}

cfg_str() {
	local value
	value="$(cfg_raw "CONFIG_$1")"
	value="${value%\"}"
	value="${value#\"}"
	printf '%s' "$value"
}

cfg_bool() {
	if [ "$(cfg_raw "CONFIG_$1")" = "y" ]; then
		printf 'y'
	else
		printf 'n'
	fi
}

cfg_bool_or() {
	local key="CONFIG_$1"
	local default="$2"
	local value
	if [ -f "$CONFIG_FILE" ] && grep -Fqx -- "# $key is not set" "$CONFIG_FILE"; then
		printf 'n'
		return
	fi
	value="$(cfg_raw "$key")"
	case "$value" in
		y) printf 'y' ;;
		n) printf 'n' ;;
		*) printf '%s' "$default" ;;
	esac
}

fastfetch_version() {
	local bin="$1"
	local version=""
	if [ -f "$bin.info" ]; then
		version="$(sed -n 's/^fastfetch \([0-9][0-9.]*\).*/\1/p' "$bin.info" | head -n 1)"
	fi
	if [ -z "$version" ] && command -v strings >/dev/null 2>&1; then
		version="$(strings "$bin" | grep -m 1 -xE '[0-9]+\.[0-9]+\.[0-9]+' || true)"
	fi
	if [ -z "$version" ] && [ -f "$WORKBENCH_HOME/vendor/fastfetch/CMakeLists.txt" ]; then
		version="$(sed -n 's/^    VERSION \([0-9][0-9.]*\)$/\1/p' \
			"$WORKBENCH_HOME/vendor/fastfetch/CMakeLists.txt" | head -n 1)"
	fi
	printf '%s' "${version:-unknown}"
}

CLEAN=0
while (($# > 0)); do
	case "$1" in
		--clean)
			CLEAN=1
			;;
		-h|--help)
			usage
			exit 0
			;;
		*)
			die "未知参数：$1（只支持 --clean）"
			;;
	esac
	shift
done

ROOTFS_ENABLE="${ROOTFS_ENABLE:-$(cfg_bool ROOTFS_ENABLE)}"
ROOTFS_TYPE="${ROOTFS_TYPE:-$(cfg_str ROOTFS_TYPE)}"
ROOTFS_TYPE="${ROOTFS_TYPE:-none}"
ROOTFS_BUSYBOX="${ROOTFS_BUSYBOX:-$(cfg_bool ROOTFS_BUSYBOX)}"

ROOTFS_BUSYBOX_PATH="${ROOTFS_BUSYBOX_PATH:-$(cfg_str ROOTFS_BUSYBOX_PATH)}"
ROOTFS_BUSYBOX_PATH="${ROOTFS_BUSYBOX_PATH:-vendor/busybox}"
ROOTFS_BUSYBOX_VERSION="${ROOTFS_BUSYBOX_VERSION:-$(cfg_str ROOTFS_BUSYBOX_VERSION)}"
ROOTFS_BUSYBOX_VERSION="${ROOTFS_BUSYBOX_VERSION:-1.36.1}"
ROOTFS_BUSYBOX_TOOLCHAIN_PREFIX="${ROOTFS_BUSYBOX_TOOLCHAIN_PREFIX:-$(cfg_str ROOTFS_BUSYBOX_TOOLCHAIN_PREFIX)}"
ROOTFS_BUSYBOX_TOOLCHAIN_PREFIX="${ROOTFS_BUSYBOX_TOOLCHAIN_PREFIX:-riscv64-linux-gnu-}"
ROOTFS_BUSYBOX_MARCH="${ROOTFS_BUSYBOX_MARCH:-$(cfg_str ROOTFS_BUSYBOX_MARCH)}"
ROOTFS_BUSYBOX_MARCH="${ROOTFS_BUSYBOX_MARCH:-rv64gc}"
ROOTFS_BUSYBOX_MABI="${ROOTFS_BUSYBOX_MABI:-$(cfg_str ROOTFS_BUSYBOX_MABI)}"
ROOTFS_BUSYBOX_MABI="${ROOTFS_BUSYBOX_MABI:-lp64d}"
ROOTFS_BUSYBOX_STATIC="${ROOTFS_BUSYBOX_STATIC:-$(cfg_bool ROOTFS_BUSYBOX_STATIC)}"
ROOTFS_BUSYBOX_STATIC="${ROOTFS_BUSYBOX_STATIC:-y}"
ROOTFS_BUSYBOX_INIT_SCRIPT="${ROOTFS_BUSYBOX_INIT_SCRIPT:-$(cfg_bool ROOTFS_BUSYBOX_INIT_SCRIPT)}"
ROOTFS_BUSYBOX_INIT_SCRIPT="${ROOTFS_BUSYBOX_INIT_SCRIPT:-y}"
ROOTFS_BUSYBOX_APPLETS="${ROOTFS_BUSYBOX_APPLETS:-$(cfg_str ROOTFS_BUSYBOX_APPLETS)}"
ROOTFS_BUSYBOX_JOBS="${ROOTFS_BUSYBOX_JOBS:-$(cfg_raw CONFIG_ROOTFS_BUSYBOX_JOBS)}"
ROOTFS_BUSYBOX_JOBS="${ROOTFS_BUSYBOX_JOBS:-0}"

ROOTFS_FASTFETCH="${ROOTFS_FASTFETCH:-$(cfg_bool_or ROOTFS_FASTFETCH y)}"
ROOTFS_FASTFETCH_PATH="${ROOTFS_FASTFETCH_PATH:-$(cfg_str ROOTFS_FASTFETCH_PATH)}"
ROOTFS_FASTFETCH_PATH="${ROOTFS_FASTFETCH_PATH:-build/fastfetch/fastfetch}"

ROOTFS_OUT_DIR="${ROOTFS_OUT_DIR:-$(cfg_str ROOTFS_OUT_DIR)}"
ROOTFS_OUT_DIR="${ROOTFS_OUT_DIR:-build/rootfs}"
case "$ROOTFS_OUT_DIR" in
	/*) ;;
	*) ROOTFS_OUT_DIR="$NPC_HOME/$ROOTFS_OUT_DIR" ;;
esac
ROOTFS_OUT_DIR="$(realpath -m -- "$ROOTFS_OUT_DIR")"

ROOTFS_BUSYBOX_BUILD_DIR="${ROOTFS_BUSYBOX_BUILD_DIR:-$ROOTFS_OUT_DIR/busybox}"
ROOTFS_BUSYBOX_BUILD_DIR="$(realpath -m -- "$ROOTFS_BUSYBOX_BUILD_DIR")"
ROOTFS_TREE="${ROOTFS_TREE:-$ROOTFS_OUT_DIR/tree}"
ROOTFS_TREE="$(realpath -m -- "$ROOTFS_TREE")"
ROOTFS_IMAGE="${ROOTFS_IMAGE:-$ROOTFS_OUT_DIR/busybox-initramfs.cpio.gz}"
ROOTFS_IMAGE="$(realpath -m -- "$ROOTFS_IMAGE")"
INFO_FILE="$ROOTFS_IMAGE.info"

case "$ROOTFS_FASTFETCH_PATH" in
	/*) FASTFETCH_BIN="$ROOTFS_FASTFETCH_PATH" ;;
	*) FASTFETCH_BIN="$NPC_HOME/$ROOTFS_FASTFETCH_PATH" ;;
esac
FASTFETCH_BIN="$(realpath -m -- "$FASTFETCH_BIN")"
ELF_AUDIT_BIN="${ELF_AUDIT_BIN:-$NPC_HOME/tools/build/audit_rootfs_elf}"

safe_rm_rf() {
	local path="$1"
	case "$path" in
		""|"/"|"."|"..")
			die "拒绝删除危险路径：'$path'"
			;;
	esac
	if [ "$path" = "$NPC_HOME" ] || [ "$path" = "$WORKBENCH_HOME" ]; then
		die "拒绝删除工作区根目录：$path"
	fi
	if [ -n "${HOME:-}" ] && [ "$path" = "$HOME" ]; then
		die "拒绝删除用户主目录：$path"
	fi
	rm -rf -- "$path"
}

if ((CLEAN)); then
	safe_rm_rf "$ROOTFS_OUT_DIR"
	info "已删除 rootfs 输出目录：$ROOTFS_OUT_DIR"
	exit 0
fi

[ -x "$ELF_AUDIT_BIN" ] || die \
	"找不到 ELF 审计工具 $ELF_AUDIT_BIN；先运行 make -C $NPC_HOME audit-rootfs-elf-tool。"

[ -f "$CONFIG_FILE" ] || die "找不到配置文件 $CONFIG_FILE；请先运行 make menuconfig 或 defconfig。"
[ "$ROOTFS_ENABLE" = "y" ] || die \
	"ROOTFS_ENABLE=$ROOTFS_ENABLE，rootfs 未启用。请先运行 make menuconfig 打开“根文件系统 (rootfs)”，不要静默跳过。"
[ "$ROOTFS_BUSYBOX" = "y" ] || die "ROOTFS_ENABLE=y 但没有选择 BusyBox（ROOTFS_BUSYBOX != y）。"
[ "$ROOTFS_TYPE" = "busybox" ] || die "ROOTFS_TYPE='$ROOTFS_TYPE' 不是 busybox；当前只实现了 BusyBox rootfs。"
[ "$ROOTFS_BUSYBOX_STATIC" = "y" ] || die \
	"ROOTFS_BUSYBOX_STATIC=$ROOTFS_BUSYBOX_STATIC；最小 initramfs 只包含 BusyBox，必须静态链接。"
[ -n "$ROOTFS_BUSYBOX_APPLETS" ] || die "ROOTFS_BUSYBOX_APPLETS 为空；至少需要包含 sh。"
case " $ROOTFS_BUSYBOX_APPLETS " in
	*" sh "*) ;;
	*) die "ROOTFS_BUSYBOX_APPLETS 必须包含 sh，否则 /init 无法启动交互 shell。" ;;
esac
case "$ROOTFS_BUSYBOX_PATH" in
	/*) ROOTFS_BUSYBOX_SRC="$ROOTFS_BUSYBOX_PATH" ;;
	*) ROOTFS_BUSYBOX_SRC="$WORKBENCH_HOME/$ROOTFS_BUSYBOX_PATH" ;;
esac
ROOTFS_BUSYBOX_SRC="$(realpath -m -- "$ROOTFS_BUSYBOX_SRC")"
[ -d "$ROOTFS_BUSYBOX_SRC" ] || die "BusyBox 源码目录不存在：$ROOTFS_BUSYBOX_SRC"
[ -f "$ROOTFS_BUSYBOX_SRC/Makefile" ] || die "BusyBox 源码目录缺少 Makefile：$ROOTFS_BUSYBOX_SRC"
if [ -e "$ROOTFS_BUSYBOX_SRC/.config" ]; then
	die "BusyBox 源码树存在 $ROOTFS_BUSYBOX_SRC/.config，会破坏 out-of-tree 构建；请先清理该文件。"
fi
if [ "$ROOTFS_BUSYBOX_SRC" = "$ROOTFS_BUSYBOX_BUILD_DIR" ]; then
	die "ROOTFS_BUSYBOX_BUILD_DIR 不能等于 BusyBox 源码目录。"
fi
if [ "$ROOTFS_TREE" = "$ROOTFS_BUSYBOX_SRC" ]; then
	die "ROOTFS_TREE 不能等于 BusyBox 源码目录。"
fi
[ "$ROOTFS_TREE" != "$ROOTFS_OUT_DIR" ] || die "ROOTFS_TREE 不能等于 ROOTFS_OUT_DIR。"
[ "$ROOTFS_BUSYBOX_BUILD_DIR" != "$ROOTFS_OUT_DIR" ] || die "ROOTFS_BUSYBOX_BUILD_DIR 不能等于 ROOTFS_OUT_DIR。"
for path in "$ROOTFS_OUT_DIR" "$ROOTFS_BUSYBOX_BUILD_DIR" "$ROOTFS_TREE" "$ROOTFS_IMAGE"; do
	case "$path" in
		"$ROOTFS_BUSYBOX_SRC"|"$ROOTFS_BUSYBOX_SRC"/*)
			die "rootfs 输出路径不能位于 BusyBox 源码树内：$path"
			;;
	esac
done
if [ "$ROOTFS_FASTFETCH" = "y" ]; then
	case "$FASTFETCH_BIN" in
		"$ROOTFS_TREE"|"$ROOTFS_TREE"/*)
			die "ROOTFS_FASTFETCH_PATH 不能位于 ROOTFS_TREE 内（构建时会删除 tree）：$FASTFETCH_BIN"
			;;
	esac
fi

TOOLCHAIN_PREFIX="$ROOTFS_BUSYBOX_TOOLCHAIN_PREFIX"
TOOLCHAIN_CC="${TOOLCHAIN_PREFIX}gcc"
command -v "$TOOLCHAIN_CC" >/dev/null 2>&1 || die \
	"找不到交叉编译器 $TOOLCHAIN_CC；请安装 riscv64-linux-gnu 工具链或修改 ROOTFS_BUSYBOX_TOOLCHAIN_PREFIX。"
for tool in readelf cpio gzip sha256sum stat awk find sort paste realpath; do
	command -v "$tool" >/dev/null 2>&1 || die "缺少必要工具：$tool"
done

TOOLCHAIN_TARGET="$("$TOOLCHAIN_CC" -dumpmachine)"
case "$TOOLCHAIN_TARGET" in
	riscv64*) ;;
	*) die "工具链 $TOOLCHAIN_CC 的目标是 '$TOOLCHAIN_TARGET'，不是 riscv64。" ;;
esac
TOOLCHAIN_VERSION="$("$TOOLCHAIN_CC" --version | head -n 1)"
[ -n "$TOOLCHAIN_VERSION" ] || die "无法读取 $TOOLCHAIN_CC 的版本信息。"

SRC_VERSION="$(
	{
		sed -n 's/^VERSION = //p' "$ROOTFS_BUSYBOX_SRC/Makefile" | head -n 1
		sed -n 's/^PATCHLEVEL = //p' "$ROOTFS_BUSYBOX_SRC/Makefile" | head -n 1
		sed -n 's/^SUBLEVEL = //p' "$ROOTFS_BUSYBOX_SRC/Makefile" | head -n 1
	} | paste -sd. -
)"
[ "$SRC_VERSION" = "$ROOTFS_BUSYBOX_VERSION" ] || die \
	"BusyBox 源码版本 $SRC_VERSION 与 ROOTFS_BUSYBOX_VERSION=$ROOTFS_BUSYBOX_VERSION 不一致。"

case "$ROOTFS_BUSYBOX_JOBS" in
	''|*[!0-9]*) die "ROOTFS_BUSYBOX_JOBS='$ROOTFS_BUSYBOX_JOBS' 不是非负整数。" ;;
esac
if [ "$ROOTFS_BUSYBOX_JOBS" -eq 0 ]; then
	ROOTFS_BUSYBOX_JOBS="$(nproc 2>/dev/null || echo 4)"
fi
[ "$ROOTFS_BUSYBOX_JOBS" -le 256 ] || die "ROOTFS_BUSYBOX_JOBS=$ROOTFS_BUSYBOX_JOBS 超过上限 256。"

if [ "$ROOTFS_FASTFETCH" = "y" ]; then
	if [ ! -f "$FASTFETCH_BIN" ]; then
		[ -x "$NPC_HOME/scripts/build_fastfetch.sh" ] || die \
			"fastfetch 不存在（$FASTFETCH_BIN），且找不到可执行的 npc/scripts/build_fastfetch.sh。"
		info "fastfetch 不存在，自动构建：$FASTFETCH_BIN"
		if ! FASTFETCH_OUT_DIR="$(dirname -- "$FASTFETCH_BIN")" \
			"$NPC_HOME/scripts/build_fastfetch.sh"; then
			die "fastfetch 自动构建失败（目标：$FASTFETCH_BIN）。"
		fi
	fi
	[ -f "$FASTFETCH_BIN" ] || die \
		"fastfetch 自动构建后仍找不到 $FASTFETCH_BIN；请检查 ROOTFS_FASTFETCH_PATH 是否指向名为 fastfetch 的文件。"

	if ! FASTFETCH_ELF_AUDIT="$("$ELF_AUDIT_BIN" --kind fastfetch "$FASTFETCH_BIN")"; then
		die "fastfetch ELF 审计失败：$FASTFETCH_BIN"
	fi
	IFS=$'\t' read -r FASTFETCH_ELF_FLAGS FASTFETCH_ELF_ARCH FASTFETCH_ELF_ABI <<<"$FASTFETCH_ELF_AUDIT"
	FASTFETCH_VERSION="$(fastfetch_version "$FASTFETCH_BIN")"
	FASTFETCH_BYTES="$(stat -c '%s' "$FASTFETCH_BIN")"
	FASTFETCH_SHA256="$(sha256sum "$FASTFETCH_BIN" | awk '{print $1}')"
	info "fastfetch：$FASTFETCH_BIN（版本 $FASTFETCH_VERSION，$FASTFETCH_BYTES 字节，静态）"
	info "fastfetch ELF：$FASTFETCH_ELF_FLAGS，Tag_RISCV_arch=$FASTFETCH_ELF_ARCH"
else
	info "fastfetch：ROOTFS_FASTFETCH=n，不安装"
fi

mkdir -p "$ROOTFS_OUT_DIR/logs" "$ROOTFS_BUSYBOX_BUILD_DIR"
CONFIG_LOG="$ROOTFS_OUT_DIR/logs/busybox-config.log"
BUILD_LOG="$ROOTFS_OUT_DIR/logs/busybox-build.log"
BUSYBOX_BIN="$ROOTFS_BUSYBOX_BUILD_DIR/busybox"
APPLET_LIST_FILE="$ROOTFS_OUT_DIR/applets.txt"

info "BusyBox 源码：$ROOTFS_BUSYBOX_SRC（$SRC_VERSION）"
info "工具链：$TOOLCHAIN_CC（$TOOLCHAIN_TARGET）"
info "目标：$ROOTFS_BUSYBOX_MARCH / $ROOTFS_BUSYBOX_MABI / 静态"
info "构建目录：$ROOTFS_BUSYBOX_BUILD_DIR"

if [ ! -f "$ROOTFS_BUSYBOX_BUILD_DIR/.config" ]; then
	info "生成 BusyBox defconfig ..."
	if ! make -C "$ROOTFS_BUSYBOX_SRC" O="$ROOTFS_BUSYBOX_BUILD_DIR" \
		ARCH=riscv CROSS_COMPILE="$TOOLCHAIN_PREFIX" defconfig \
		>"$CONFIG_LOG" 2>&1; then
		tail -n 40 "$CONFIG_LOG" >&2 || true
		die "BusyBox defconfig 失败，完整日志：$CONFIG_LOG"
	fi
fi

set_config_raw() {
	local key="$1"
	local value="$2"
	local config="$ROOTFS_BUSYBOX_BUILD_DIR/.config"
	local tmp="$config.tmp.$$"
	grep -v -E "^${key}=|^# ${key} is not set$" "$config" >"$tmp" || true
	printf '%s=%s\n' "$key" "$value" >>"$tmp"
	mv -f -- "$tmp" "$config"
}

set_config_bool() {
	local key="$1"
	local enabled="$2"
	local config="$ROOTFS_BUSYBOX_BUILD_DIR/.config"
	local tmp="$config.tmp.$$"
	if [ "$enabled" = "y" ]; then
		set_config_raw "$key" "y"
		return
	fi
	grep -v -E "^${key}=|^# ${key} is not set$" "$config" >"$tmp" || true
	printf '# %s is not set\n' "$key" >>"$tmp"
	mv -f -- "$tmp" "$config"
}

set_config_raw CONFIG_CROSS_COMPILER_PREFIX "\"$TOOLCHAIN_PREFIX\""
set_config_raw CONFIG_EXTRA_CFLAGS "\"-march=$ROOTFS_BUSYBOX_MARCH -mabi=$ROOTFS_BUSYBOX_MABI\""
set_config_bool CONFIG_STATIC "$ROOTFS_BUSYBOX_STATIC"
set_config_bool CONFIG_TC "n"
set_config_bool CONFIG_INSTALL_APPLET_SYMLINKS "y"

info "同步 BusyBox 配置 ..."
if ! make -C "$ROOTFS_BUSYBOX_SRC" O="$ROOTFS_BUSYBOX_BUILD_DIR" \
	ARCH=riscv CROSS_COMPILE="$TOOLCHAIN_PREFIX" oldconfig </dev/null \
	>>"$CONFIG_LOG" 2>&1; then
	tail -n 40 "$CONFIG_LOG" >&2 || true
	die "BusyBox oldconfig 失败，完整日志：$CONFIG_LOG"
fi
grep -qx 'CONFIG_STATIC=y' "$ROOTFS_BUSYBOX_BUILD_DIR/.config" || die \
	"BusyBox 配置未生效：CONFIG_STATIC != y。"
grep -Fqx 'CONFIG_CROSS_COMPILER_PREFIX="'"$TOOLCHAIN_PREFIX"'"' "$ROOTFS_BUSYBOX_BUILD_DIR/.config" || die \
	"BusyBox 配置未生效：CONFIG_CROSS_COMPILER_PREFIX 与工具链不一致。"
if grep -qx 'CONFIG_TC=y' "$ROOTFS_BUSYBOX_BUILD_DIR/.config"; then
	die "BusyBox 配置未生效：CONFIG_TC 仍为 y。"
fi

info "编译 BusyBox（-j$ROOTFS_BUSYBOX_JOBS）..."
if ! make -C "$ROOTFS_BUSYBOX_SRC" O="$ROOTFS_BUSYBOX_BUILD_DIR" \
	ARCH=riscv CROSS_COMPILE="$TOOLCHAIN_PREFIX" -j"$ROOTFS_BUSYBOX_JOBS" busybox \
	2>&1 | tee "$BUILD_LOG"; then
	die "BusyBox 编译失败，完整日志：$BUILD_LOG"
fi
[ -f "$BUSYBOX_BIN" ] || die "BusyBox 编译结束但找不到 $BUSYBOX_BIN"

if ! ELF_AUDIT_RESULT="$("$ELF_AUDIT_BIN" --kind busybox "$BUSYBOX_BIN")"; then
	die "ELF 审计失败：$BUSYBOX_BIN"
fi
IFS=$'\t' read -r ELF_FLAGS ELF_ARCH ELF_ABI <<<"$ELF_AUDIT_RESULT"
info "ELF 审计通过：ELF64 RISC-V，$ELF_FLAGS，Tag_RISCV_arch=$ELF_ARCH"
[ -n "$ELF_ABI" ] && info "ELF Tag_RISCV_abi：$ELF_ABI"

safe_rm_rf "$ROOTFS_TREE"
install -d -m 0755 \
	"$ROOTFS_TREE/bin" \
	"$ROOTFS_TREE/dev" \
	"$ROOTFS_TREE/proc" \
	"$ROOTFS_TREE/sys" \
	"$ROOTFS_TREE/tmp" \
	"$ROOTFS_TREE/etc"
install -m 0755 "$BUSYBOX_BIN" "$ROOTFS_TREE/bin/busybox"
if [ "$ROOTFS_FASTFETCH" = "y" ]; then
	if ! install -d -m 0755 "$ROOTFS_TREE/usr/bin" ||
		! install -m 0755 "$FASTFETCH_BIN" "$ROOTFS_TREE/usr/bin/fastfetch"; then
		die "安装 fastfetch 到 rootfs tree 失败：$ROOTFS_TREE/usr/bin/fastfetch"
	fi
	[ "$(stat -c '%a' "$ROOTFS_TREE/usr/bin/fastfetch")" = "755" ] || die \
		"fastfetch 安装后权限不是 0755：$ROOTFS_TREE/usr/bin/fastfetch"
fi

sed -n '/^const char applet_names/,/;$/p' "$ROOTFS_BUSYBOX_BUILD_DIR/include/applet_tables.h" |
	sed -n 's/^"\([^"]*\)"[[:space:]]*"\\0"$/\1/p' >"$APPLET_LIST_FILE"
[ -s "$APPLET_LIST_FILE" ] || die "无法从 applet_tables.h 提取 applet 清单。"

for applet in $ROOTFS_BUSYBOX_APPLETS; do
	case "$applet" in
		""|"."|".."|*/*)
			die "非法 applet 名称：'$applet'"
			;;
		busybox)
			die "ROOTFS_BUSYBOX_APPLETS 不应包含 busybox 自身。"
			;;
	esac
	grep -Fqx -- "$applet" "$APPLET_LIST_FILE" || die \
		"applet '$applet' 未在当前 BusyBox 配置中启用；请修改 ROOTFS_BUSYBOX_APPLETS 或 BusyBox defconfig。"
	ln -sfn busybox "$ROOTFS_TREE/bin/$applet"
done

if [ "$ROOTFS_BUSYBOX_INIT_SCRIPT" = "y" ]; then
	cat >"$ROOTFS_TREE/init" <<'EOF'
#!/bin/busybox sh
export PATH=/bin:/sbin:/usr/bin:/usr/sbin
/bin/busybox mkdir -p /proc /sys /dev /tmp /etc
/bin/busybox mount -t proc proc /proc 2>/dev/null || true
/bin/busybox mount -t sysfs sysfs /sys 2>/dev/null || true
/bin/busybox mount -t devtmpfs devtmpfs /dev 2>/dev/null || true
[ -c /dev/console ] || /bin/busybox mknod -m 600 /dev/console c 5 1 2>/dev/null || true
[ -c /dev/null ] || /bin/busybox mknod -m 666 /dev/null c 1 3 2>/dev/null || true
if [ -c /dev/console ]; then
	exec /bin/sh </dev/console >/dev/console 2>&1
fi
exec /bin/sh
EOF
	chmod 0755 "$ROOTFS_TREE/init"
	INIT_KIND="script"
else
	install -d -m 0755 "$ROOTFS_TREE/sbin"
	ln -sfn ../bin/busybox "$ROOTFS_TREE/sbin/init"
	cat >"$ROOTFS_TREE/etc/inittab" <<'EOF'
::sysinit:/bin/busybox mount -t proc proc /proc
::sysinit:/bin/busybox mount -t sysfs sysfs /sys
::sysinit:/bin/busybox mknod -m 600 /dev/console c 5 1
::askfirst:/bin/sh
::ctrlaltdel:/bin/busybox reboot -f
::shutdown:/bin/busybox umount -a -r
EOF
	chmod 0644 "$ROOTFS_TREE/etc/inittab"
	INIT_KIND="busybox-init"
fi

CPIO_TMP="$ROOTFS_OUT_DIR/.busybox-initramfs.cpio.tmp.$$"
IMAGE_TMP="$ROOTFS_IMAGE.tmp.$$"
cleanup() {
	rm -f -- "$CPIO_TMP" "$IMAGE_TMP"
}
trap cleanup EXIT

info "生成 initramfs（newc + gzip）..."
(
	cd "$ROOTFS_TREE"
	find . -print0 | LC_ALL=C sort -z |
		cpio --null --create --format=newc --owner=0:0 --quiet >"$CPIO_TMP"
)
gzip -9 -n -c "$CPIO_TMP" >"$IMAGE_TMP"
mv -f -- "$IMAGE_TMP" "$ROOTFS_IMAGE"

CPIO_BYTES="$(stat -c '%s' "$CPIO_TMP")"
IMAGE_BYTES="$(stat -c '%s' "$ROOTFS_IMAGE")"
IMAGE_SHA256="$(sha256sum "$ROOTFS_IMAGE" | awk '{print $1}')"
BUSYBOX_BYTES="$(stat -c '%s' "$BUSYBOX_BIN")"
BUSYBOX_SHA256="$(sha256sum "$BUSYBOX_BIN" | awk '{print $1}')"

ARCHIVE_LIST="$(gzip -dc "$ROOTFS_IMAGE" | cpio -it --quiet | sed 's|^\./||')"
REQUIRED_PATHS=(bin/busybox bin/sh dev proc sys tmp etc)
if [ "$ROOTFS_FASTFETCH" = "y" ]; then
	REQUIRED_PATHS+=(usr/bin/fastfetch)
fi
for path in "${REQUIRED_PATHS[@]}"; do
	grep -Fqx -- "$path" <<<"$ARCHIVE_LIST" || die "initramfs 缺少关键路径：$path"
done
if [ "$INIT_KIND" = "script" ]; then
	grep -Fqx -- "init" <<<"$ARCHIVE_LIST" || die "initramfs 缺少关键路径：init"
else
	grep -Fqx -- "sbin/init" <<<"$ARCHIVE_LIST" || die "initramfs 缺少关键路径：sbin/init"
	grep -Fqx -- "etc/inittab" <<<"$ARCHIVE_LIST" || die "initramfs 缺少关键路径：etc/inittab"
fi
ARCHIVE_ENTRIES="$(printf '%s\n' "$ARCHIVE_LIST" | wc -l | tr -d ' ')"

INFO_TMP="$INFO_FILE.tmp.$$"
{
	printf 'format=1\n'
	printf 'rootfs_type=busybox\n'
	printf 'image=%s\n' "$ROOTFS_IMAGE"
	printf 'image_bytes=%s\n' "$IMAGE_BYTES"
	printf 'image_sha256=%s\n' "$IMAGE_SHA256"
	printf 'cpio_bytes=%s\n' "$CPIO_BYTES"
	printf 'cpio_format=newc\n'
	printf 'compression=gzip\n'
	printf 'archive_entries=%s\n' "$ARCHIVE_ENTRIES"
	printf 'tree=%s\n' "$ROOTFS_TREE"
	printf 'busybox_version=%s\n' "$SRC_VERSION"
	printf 'busybox_source=%s\n' "$ROOTFS_BUSYBOX_SRC"
	printf 'busybox_binary=%s\n' "$BUSYBOX_BIN"
	printf 'busybox_binary_bytes=%s\n' "$BUSYBOX_BYTES"
	printf 'busybox_binary_sha256=%s\n' "$BUSYBOX_SHA256"
	printf 'fastfetch=%s\n' "$ROOTFS_FASTFETCH"
	if [ "$ROOTFS_FASTFETCH" = "y" ]; then
		printf 'fastfetch_version=%s\n' "$FASTFETCH_VERSION"
		printf 'fastfetch_path=/usr/bin/fastfetch\n'
		printf 'fastfetch_binary=%s\n' "$FASTFETCH_BIN"
		printf 'fastfetch_binary_bytes=%s\n' "$FASTFETCH_BYTES"
		printf 'fastfetch_binary_sha256=%s\n' "$FASTFETCH_SHA256"
		printf 'fastfetch_static=yes\n'
		printf 'fastfetch_elf_flags=%s\n' "$FASTFETCH_ELF_FLAGS"
		printf 'fastfetch_elf_riscv_arch=%s\n' "$FASTFETCH_ELF_ARCH"
	fi
	printf 'toolchain_prefix=%s\n' "$TOOLCHAIN_PREFIX"
	printf 'toolchain_cc=%s\n' "$TOOLCHAIN_CC"
	printf 'toolchain_target=%s\n' "$TOOLCHAIN_TARGET"
	printf 'toolchain_version=%s\n' "$TOOLCHAIN_VERSION"
	printf 'march=%s\n' "$ROOTFS_BUSYBOX_MARCH"
	printf 'mabi=%s\n' "$ROOTFS_BUSYBOX_MABI"
	printf 'static=yes\n'
	printf 'elf_class=ELF64\n'
	printf 'elf_machine=RISC-V\n'
	printf 'elf_flags=%s\n' "$ELF_FLAGS"
	printf 'elf_riscv_arch=%s\n' "$ELF_ARCH"
	printf 'elf_riscv_abi=%s\n' "${ELF_ABI:-lp64d}"
	printf 'dynamic_interpreter=none\n'
	printf 'init=%s\n' "$INIT_KIND"
	printf 'applets=%s\n' "$ROOTFS_BUSYBOX_APPLETS"
} >"$INFO_TMP"
mv -f -- "$INFO_TMP" "$INFO_FILE"

rm -f -- "$CPIO_TMP" "$IMAGE_TMP"
trap - EXIT

info "完成：$ROOTFS_IMAGE"
info "大小：$IMAGE_BYTES 字节（cpio $CPIO_BYTES 字节），条目 $ARCHIVE_ENTRIES"
info "sha256：$IMAGE_SHA256"
info "摘要：$INFO_FILE"
