#!/usr/bin/env bash
#
# 把静态 RV64GC/lp64d fastfetch 安装到任意 Debian/RISC-V rootfs 目录。
#
# 用法：
#   install_fastfetch_rootfs.sh <rootfs-dir>
#
# 行为：
#   - 从 npc/.config 读取 ROOTFS_FASTFETCH_PATH（默认
#     npc/build/fastfetch/fastfetch）；也可用 FASTFETCH_PATH 环境变量覆盖。
#   - 目标不存在时调用 npc/scripts/build_fastfetch.sh 交叉编译。
#   - 校验 ELF64 RISC-V、RVC、double-float ABI、无 INTERP、无 NEEDED，
#     再以 0755 安装到 <rootfs>/usr/bin/fastfetch。
#
# 本脚本不会在 rootfs 内安装 glibc 或其它运行库。
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NPC_HOME="$(cd -- "$HERE/.." && pwd)"
WORKBENCH_HOME="$(cd -- "$NPC_HOME/.." && pwd)"
CONFIG_FILE="${ROOTFS_CONFIG:-$NPC_HOME/.config}"

die() {
	echo "错误：$*" >&2
	exit 2
}

info() {
	echo "[fastfetch-rootfs] $*"
}

usage() {
	cat <<'EOF'
用法：install_fastfetch_rootfs.sh <rootfs-dir>

  <rootfs-dir>   Debian/RISC-V rootfs 目录，二进制安装到
                 <rootfs-dir>/usr/bin/fastfetch。

环境变量：
  FASTFETCH_PATH   覆盖 npc/.config 中的 ROOTFS_FASTFETCH_PATH
  ROOTFS_CONFIG    覆盖 npc/.config 路径
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

# 校验静态 RV64GC/lp64d ELF。成功时设置 FASTFETCH_ELF_ARCH/FLAGS。
audit_fastfetch() {
	local bin="$1"
	local header attrs program_headers arch flags

	[ -f "$bin" ] || die "fastfetch 二进制不存在：$bin"
	header="$(readelf -h "$bin")"
	attrs="$(readelf -A "$bin")"
	program_headers="$(readelf -l "$bin")"

	grep -q 'Class:[[:space:]]*ELF64' <<<"$header" || die "ELF 审计失败：不是 ELF64：$bin"
	grep -q 'Machine:[[:space:]]*RISC-V' <<<"$header" || die "ELF 审计失败：Machine 不是 RISC-V：$bin"
	if grep -q 'Type:[[:space:]]*EXEC' <<<"$header"; then
		:
	elif grep -q 'Type:[[:space:]]*DYN' <<<"$header" &&
		! grep -q 'INTERP' <<<"$program_headers"; then
		: # 允许无解释器的 static PIE
	else
		die "ELF 审计失败：不是静态可执行文件（需要 EXEC 或 static PIE）：$bin"
	fi
	grep -Eq 'Flags:.*double-float ABI' <<<"$header" || die \
		"ELF 审计失败：不是 lp64d/double-float ABI：$bin"
	if grep -q 'INTERP' <<<"$program_headers"; then
		die "ELF 审计失败：存在动态解释器（INTERP）：$bin"
	fi
	if readelf -d "$bin" 2>/dev/null | grep -q 'NEEDED'; then
		die "ELF 审计失败：存在动态库依赖（NEEDED）：$bin"
	fi

	arch="$(sed -n 's/.*Tag_RISCV_arch: "\(.*\)"/\1/p' <<<"$attrs" | head -n 1)"
	flags="$(sed -n 's/^[[:space:]]*Flags:[[:space:]]*//p' <<<"$header" | head -n 1)"
	case "$arch" in
		rv64*) ;;
		*) die "ELF 审计失败：Tag_RISCV_arch='$arch' 不是 rv64*。" ;;
	esac
	case "$arch" in
		*_f*_d*|*_d*_f*) ;;
		*) die "ELF 审计失败：Tag_RISCV_arch='$arch' 未同时包含 F/D 扩展。" ;;
	esac
	case "$arch" in
		*_c*) ;;
		*) die "ELF 审计失败：Tag_RISCV_arch='$arch' 不包含 C 扩展。" ;;
	esac

	FASTFETCH_ELF_ARCH="$arch"
	FASTFETCH_ELF_FLAGS="$flags"
}

if (($# == 1)) && { [ "$1" = "-h" ] || [ "$1" = "--help" ]; }; then
	usage
	exit 0
fi
(($# == 1)) || {
	usage >&2
	exit 2
}

ROOTFS_DIR="$1"
[ -d "$ROOTFS_DIR" ] || die "rootfs 目录不存在：$ROOTFS_DIR"
ROOTFS_DIR="$(realpath -- "$ROOTFS_DIR")"
[ "$ROOTFS_DIR" != "/" ] || die "拒绝直接安装到宿主机 /；请传入目标 rootfs 目录。"

for tool in readelf realpath stat sha256sum install awk; do
	command -v "$tool" >/dev/null 2>&1 || die "缺少必要工具：$tool"
done

FASTFETCH_PATH="${FASTFETCH_PATH:-}"
if [ -z "$FASTFETCH_PATH" ]; then
	FASTFETCH_PATH="$(cfg_str ROOTFS_FASTFETCH_PATH)"
fi
FASTFETCH_PATH="${FASTFETCH_PATH:-build/fastfetch/fastfetch}"
case "$FASTFETCH_PATH" in
	/*) FASTFETCH_BIN="$FASTFETCH_PATH" ;;
	*) FASTFETCH_BIN="$NPC_HOME/$FASTFETCH_PATH" ;;
esac
FASTFETCH_BIN="$(realpath -m -- "$FASTFETCH_BIN")"

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
	"自动构建后仍找不到 $FASTFETCH_BIN；请检查 FASTFETCH_PATH/ROOTFS_FASTFETCH_PATH。"

audit_fastfetch "$FASTFETCH_BIN"
FASTFETCH_VERSION="$(fastfetch_version "$FASTFETCH_BIN")"
FASTFETCH_BYTES="$(stat -c '%s' "$FASTFETCH_BIN")"
FASTFETCH_SHA256="$(sha256sum "$FASTFETCH_BIN" | awk '{print $1}')"

info "源：$FASTFETCH_BIN"
info "版本：$FASTFETCH_VERSION，大小：$FASTFETCH_BYTES 字节，sha256：$FASTFETCH_SHA256"
info "ELF：$FASTFETCH_ELF_FLAGS，Tag_RISCV_arch=$FASTFETCH_ELF_ARCH"

TARGET="$ROOTFS_DIR/usr/bin/fastfetch"
if ! install -d -m 0755 "$ROOTFS_DIR/usr/bin" ||
	! install -m 0755 "$FASTFETCH_BIN" "$TARGET"; then
	die "安装 fastfetch 失败：$TARGET"
fi
[ "$(stat -c '%a' "$TARGET")" = "755" ] || die "目标权限不是 0755：$TARGET"

# 复制后再次审计目标文件，确认 rootfs 中的就是静态 RISC-V 二进制。
audit_fastfetch "$TARGET"
TARGET_SHA256="$(sha256sum "$TARGET" | awk '{print $1}')"
[ "$TARGET_SHA256" = "$FASTFETCH_SHA256" ] || die \
	"安装后 sha256 不一致：源 $FASTFETCH_SHA256，目标 $TARGET_SHA256"

info "已安装：$TARGET（0755，静态，无 glibc/动态加载器依赖）"
