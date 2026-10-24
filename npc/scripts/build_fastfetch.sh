#!/usr/bin/env bash
# 交叉编译 fastfetch（RV64GC / lp64d），供 Debian riscv64 rootfs 使用。
#
# 源码：vendor/fastfetch（官方 2.70.0，普通文件，非 submodule）
# 工具链：riscv64-linux-gnu-gcc（可用 CROSS_COMPILE 覆盖）
# 输出：
#   npc/build/fastfetch/fastfetch
#   npc/build/fastfetch/fastfetch.info
#
# 默认静态链接，避免依赖目标 rootfs 的 glibc 版本。若静态链接确实不可行，
# 可用 FASTFETCH_LINK_TYPE=dynamic 重新构建；.info 会列出 DT_NEEDED。
#
# 本脚本只做 out-of-tree 构建，不修改 vendor/fastfetch。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SRC_DIR="$REPO_ROOT/vendor/fastfetch"
OUT_DIR="${FASTFETCH_OUT_DIR:-$REPO_ROOT/npc/build/fastfetch}"
BUILD_DIR="$OUT_DIR/build"
OUT_BIN="$OUT_DIR/fastfetch"
OUT_INFO="$OUT_DIR/fastfetch.info"
LOG="$OUT_DIR/build.log"
CROSS_COMPILE="${CROSS_COMPILE:-riscv64-linux-gnu-}"
CC="${CC:-${CROSS_COMPILE}gcc}"
READELF="${READELF:-${CROSS_COMPILE}readelf}"
LINK_TYPE="${FASTFETCH_LINK_TYPE:-static}"
JOBS="${FASTFETCH_JOBS:-$(nproc 2>/dev/null || echo 4)}"
KEEP_BUILD="${FASTFETCH_KEEP_BUILD:-0}"

die() {
    echo "错误: $*" >&2
    exit 1
}

command -v "$CC" >/dev/null 2>&1 || die "找不到交叉编译器 $CC"
command -v cmake >/dev/null 2>&1 || die "找不到 cmake"
command -v "$READELF" >/dev/null 2>&1 || die "找不到 $READELF"
command -v file >/dev/null 2>&1 || die "找不到 file"
[ -f "$SRC_DIR/CMakeLists.txt" ] || die "缺少 $SRC_DIR/CMakeLists.txt；vendor/fastfetch 未就绪"
[ -f "$SRC_DIR/LICENSE" ] || die "缺少 $SRC_DIR/LICENSE"

case "$LINK_TYPE" in
    static|dynamic) ;;
    *) die "FASTFETCH_LINK_TYPE 必须是 static 或 dynamic（当前: $LINK_TYPE）" ;;
esac

VERSION="$(sed -n 's/^    VERSION \([0-9][0-9.]*\)$/\1/p' "$SRC_DIR/CMakeLists.txt" | head -n 1)"
[ -n "$VERSION" ] || die "无法从 vendor/fastfetch/CMakeLists.txt 解析版本号"

if command -v ninja >/dev/null 2>&1; then
    GENERATOR="Ninja"
else
    GENERATOR="Unix Makefiles"
fi

# 每次从干净目录配置，避免残留 cache 把静态/动态或主机库带进来。
rm -rf "$BUILD_DIR"
mkdir -p "$OUT_DIR"
: > "$LOG"

TOOLCHAIN="$BUILD_DIR/riscv64-toolchain.cmake"
SYSROOT="${FASTFETCH_SYSROOT:-/usr/riscv64-linux-gnu}"
# 即使未来打开某个可选依赖，也让 pkg-config 只在目标 sysroot 里查找，
# 不把主机的 x86_64 .pc 文件带进交叉构建。
export PKG_CONFIG_LIBDIR="$SYSROOT/usr/lib/pkgconfig:$SYSROOT/usr/share/pkgconfig"
export PKG_CONFIG_SYSROOT_DIR="$SYSROOT"
mkdir -p "$BUILD_DIR"
cat > "$TOOLCHAIN" <<EOF
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR riscv64)
set(CMAKE_C_COMPILER "$CC")
set(CMAKE_C_COMPILER_TARGET riscv64-linux-gnu)
set(CMAKE_ASM_COMPILER "$CC")
set(CMAKE_C_FLAGS_INIT "-march=rv64gc -mabi=lp64d")
set(CMAKE_EXE_LINKER_FLAGS_INIT "-march=rv64gc -mabi=lp64d")
set(CMAKE_FIND_ROOT_PATH "$SYSROOT")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
EOF

# 与当前 fastfetch CMakeLists.txt 的选项名保持一致。
# 所有外部图形/桌面/网络/压缩/脚本依赖关闭，只保留 Linux 基础信息检测。
CMAKE_OPTIONS=(
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN"
    -DCMAKE_BUILD_TYPE=Release
    -DBINARY_LINK_TYPE="$LINK_TYPE"
    -DBUILD_FLASHFETCH=OFF
    -DBUILD_TESTS=OFF
    -DENABLE_THREADS=OFF
    -DENABLE_ZLIB=OFF
    -DENABLE_SYSTEM_YYJSON=OFF
    -DENABLE_LUA=OFF
    -DENABLE_LIBZFS=OFF
    -DENABLE_ELF=OFF
    -DENABLE_IMAGE_LOGO=OFF
    -DENABLE_IMAGEMAGICK6=OFF
    -DENABLE_IMAGEMAGICK7=OFF
    -DENABLE_SIXEL=OFF
    -DENABLE_CHAFA=OFF
    -DENABLE_FREETYPE=OFF
    -DENABLE_VULKAN=OFF
    -DENABLE_WAYLAND=OFF
    -DENABLE_XCB_RANDR=OFF
    -DENABLE_XRANDR=OFF
    -DENABLE_DRM=OFF
    -DENABLE_VADRM=OFF
    -DENABLE_VAX11=OFF
    -DENABLE_VDPAU=OFF
    -DENABLE_GIO=OFF
    -DENABLE_DCONF=OFF
    -DENABLE_EET=OFF
    -DENABLE_DBUS=OFF
    -DENABLE_SQLITE3=OFF
    -DENABLE_RPM=OFF
    -DENABLE_EGL=OFF
    -DENABLE_GLX=OFF
    -DENABLE_OPENCL=OFF
    -DENABLE_EMBEDDED_PCIIDS=OFF
    -DENABLE_EMBEDDED_AMDGPUIDS=OFF
)

{
    echo "== fastfetch $VERSION =="
    echo "source: $SRC_DIR"
    echo "target: rv64gc / lp64d"
    echo "link:   $LINK_TYPE"
    echo "cc:     $("$CC" --version | head -n 1)"
    echo "cmake:  $(cmake --version | head -n 1)"
    echo
    echo "== configure =="
} >> "$LOG"

cmake -S "$SRC_DIR" -B "$BUILD_DIR" -G "$GENERATOR" \
    "${CMAKE_OPTIONS[@]}" 2>&1 | tee -a "$LOG"

{
    echo
    echo "== build =="
} >> "$LOG"

cmake --build "$BUILD_DIR" --parallel "$JOBS" 2>&1 | tee -a "$LOG"

[ -x "$BUILD_DIR/fastfetch" ] || die "构建结束但找不到 $BUILD_DIR/fastfetch"
cp -f "$BUILD_DIR/fastfetch" "$OUT_BIN"
chmod 755 "$OUT_BIN"

ELF_CLASS="$("$READELF" -h "$OUT_BIN" | sed -n 's/^ *Class: *//p')"
ELF_MACHINE="$("$READELF" -h "$OUT_BIN" | sed -n 's/^ *Machine: *//p')"
RISCV_ARCH="$("$READELF" -A "$OUT_BIN" | sed -n 's/.*Tag_RISCV_arch: *//p' | tr -d '"')"
[ "$ELF_CLASS" = "ELF64" ] || die "输出不是 ELF64: $ELF_CLASS"
echo "$ELF_MACHINE" | grep -q 'RISC-V' || die "输出不是 RISC-V: $ELF_MACHINE"
echo "$RISCV_ARCH" | grep -q '^rv64' || die "Tag_RISCV_arch 不是 rv64: $RISCV_ARCH"
echo "$RISCV_ARCH" | grep -q '_d' || die "Tag_RISCV_arch 缺少 double-float: $RISCV_ARCH"

NEEDED="$("$READELF" -d "$OUT_BIN" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' || true)"
if [ -n "$NEEDED" ]; then
    RUNTIME_DEPS="$(echo "$NEEDED" | paste -sd ' ' -)"
else
    RUNTIME_DEPS="none"
fi

{
    echo "fastfetch $VERSION (riscv64)"
    echo "built_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "source: vendor/fastfetch (upstream $VERSION)"
    echo "target: rv64gc / lp64d"
    echo "link: $LINK_TYPE"
    echo "compiler: $("$CC" --version | head -n 1)"
    echo
    echo "[file]"
    file "$OUT_BIN"
    echo
    echo "[elf header]"
    "$READELF" -h "$OUT_BIN" | grep -E 'Class:|Data:|Type:|Machine:'
    echo
    echo "[riscv attributes]"
    "$READELF" -A "$OUT_BIN" | grep -E 'Tag_RISCV_arch|Tag_RISCV_stack_align'
    echo
    echo "[dynamic dependencies]"
    if [ -n "$NEEDED" ]; then
        echo "$NEEDED"
    else
        echo "none (statically linked, no DT_NEEDED)"
    fi
    echo
    echo "size_bytes: $(stat -c %s "$OUT_BIN")"
    echo "size_human: $(du -h "$OUT_BIN" | cut -f1)"
    echo "sha256: $(sha256sum "$OUT_BIN" | cut -d ' ' -f1)"
} > "$OUT_INFO"

if [ "$KEEP_BUILD" != "1" ]; then
    rm -rf "$BUILD_DIR"
fi

echo
echo "输出: $OUT_BIN"
echo "信息: $OUT_INFO"
echo "日志: $LOG"
echo "链接: $LINK_TYPE，运行时依赖: $RUNTIME_DEPS"
echo "安装: 复制到 Debian riscv64 rootfs 的 /usr/bin/fastfetch"
