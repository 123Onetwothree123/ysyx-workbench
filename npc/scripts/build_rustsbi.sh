#!/usr/bin/env bash
set -euo pipefail

NPC_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FW_DIR="$NPC_HOME/rustsbi-fw"
VENDOR_DIR="$NPC_HOME/../vendor/rustsbi"
OUT_DIR="${RUSTSBI_OUT_DIR:-${SBI_OUT_DIR:-$NPC_HOME/build/rustsbi}}"
TARGET="riscv64imac-unknown-none-elf"
TARGET_DIR="$OUT_DIR/target"

die() {
    echo "错误: $*" >&2
    exit 1
}

if ! rustup target list --installed | grep -qx "$TARGET"; then
    die "未安装 Rust target $TARGET。
请执行：
  rustup target add $TARGET
网络较慢时可使用镜像：
  export RUSTUP_DIST_SERVER=https://rsproxy.cn
  export RUSTUP_UPDATE_ROOT=https://rsproxy.cn/rustup
  rustup target add $TARGET"
fi

XLEN="${SBI_XLEN:-64}"
if [ "$XLEN" != "64" ]; then
    die "RustSBI NPC 固件当前只支持 RV64（SBI_XLEN=$XLEN）。
请选择 RV64 配置，或为 RV32 单独实现启动/陷入汇编。"
fi
if [ "${SBI_HAS_S:-n}" != "y" ]; then
    die "RustSBI 需要 S 模式（SBI_HAS_S=y）；当前配置没有 S 模式"
fi
FW_BASE="${SBI_FW_BASE:-0x80000000}"
FW_SIZE="${SBI_FW_SIZE:-0x00200000}"
NEXT_ADDR="${SBI_NEXT_ADDR:-0x80200000}"
PLATFORM="${SBI_PLATFORM:-npc}"
JOBS="${SBI_BUILD_JOBS:-0}"
FDT_ENABLE="${SBI_FDT_ENABLE:-n}"
FDT_PATH="${SBI_FDT_PATH:-dts/npc.dts}"
FDT_ADDR="${SBI_FDT_ADDR:-0x80f00000}"
FDT_OUT_DIR="${SBI_FDT_OUT_DIR:-$OUT_DIR/fdt}"
FDT_LINUX="${SBI_FDT_LINUX:-n}"
INITRAMFS_ENABLE="${SBI_INITRAMFS_ENABLE:-n}"
INITRAMFS_PATH="${SBI_INITRAMFS_PATH:-}"
INITRAMFS_ADDR="${SBI_INITRAMFS_ADDR:-0x82000000}"
BOOTARGS="${SBI_BOOTARGS:-console=ttyS0,115200 earlycon=sbi}"
RAM_BASE="${NPC_RAM_BASE:-0x80000000}"
RAM_SIZE="${NPC_RAM_SIZE:-0x04000000}"
TIMEBASE_FREQ="${SBI_TIMEBASE_FREQ:-450000000}"
HAS_PMP="${SBI_HAS_PMP:-n}"
CACHE_PADDING="${SBI_CACHE_PADDING:-0}"
RESET_PC=$((0x80000000 + CACHE_PADDING))
if [ $((FW_BASE)) -ne "$RESET_PC" ]; then
    die "CONFIG_SBI_FW_BASE=$FW_BASE 与 NPC 复位 PC=$RESET_PC（0x80000000 + CACHE_PADDING）不一致"
fi
if [ $((NEXT_ADDR)) -lt $((FW_BASE + FW_SIZE)) ]; then
    die "CONFIG_SBI_NEXT_ADDR=$NEXT_ADDR 落在固件保留区 [$(printf '0x%x' $((FW_BASE))), $(printf '0x%x' $((FW_BASE + FW_SIZE)))) 内"
fi

if [ "$JOBS" -gt 0 ] 2>/dev/null; then
    JOBS_ARGS=(-j "$JOBS")
else
    JOBS_ARGS=(-j "$(nproc 2>/dev/null || echo 4)")
fi

if [ "${SBI_ENABLE:-n}" != "y" ]; then
    echo "提示: CONFIG_SBI_ENABLE=n，本次仅按显式请求构建 RustSBI 固件。"
fi
IMPL="${SBI_IMPL:-none}"
if [ "$IMPL" != "rustsbi" ]; then
    echo "提示: 当前 CONFIG_SBI_IMPL=$IMPL；make rustsbi 仍会构建 RustSBI，但默认 SBI 选择不是它。"
fi

FDT_DTB_PATH=""
if [ "$FDT_ENABLE" = "y" ]; then
    SBI_FDT_OUT_DIR="$FDT_OUT_DIR" \
    SBI_FDT_PATH="$FDT_PATH" \
    SBI_FDT_ADDR="$FDT_ADDR" \
    SBI_FW_BASE="$FW_BASE" \
    SBI_FW_SIZE="$FW_SIZE" \
    SBI_NEXT_ADDR="$NEXT_ADDR" \
    SBI_TIMEBASE_FREQ="$TIMEBASE_FREQ" \
    SBI_FDT_LINUX="$FDT_LINUX" \
    SBI_INITRAMFS_ENABLE="$INITRAMFS_ENABLE" \
    SBI_INITRAMFS_PATH="$INITRAMFS_PATH" \
    SBI_INITRAMFS_ADDR="$INITRAMFS_ADDR" \
    SBI_BOOTARGS="$BOOTARGS" \
    NPC_RAM_BASE="$RAM_BASE" \
    NPC_RAM_SIZE="$RAM_SIZE" \
    NPC_HOME="$NPC_HOME" \
        "$NPC_HOME/scripts/build_dtb.sh"
    FDT_DTB_PATH="$FDT_OUT_DIR/npc.dtb"
    [ -f "$FDT_DTB_PATH" ] || die "DTB 构建脚本未生成 $FDT_DTB_PATH"
fi

echo "构建 RustSBI NPC 固件"
echo "  源码:   $VENDOR_DIR (rustsbi 0.4.1, HEAD 27a9c7d)"
echo "  target: $TARGET"
echo "  基地址: $FW_BASE  大小: $FW_SIZE"
echo "  next:   $NEXT_ADDR  platform: $PLATFORM  PMP: $HAS_PMP"
echo "  FDT:    $FDT_ENABLE${FDT_DTB_PATH:+ ($FDT_DTB_PATH -> $FDT_ADDR)}"
echo "  输出:   $OUT_DIR"

mkdir -p "$OUT_DIR"

export SBI_FW_BASE="$FW_BASE"
export SBI_FW_SIZE="$FW_SIZE"
export SBI_NEXT_ADDR="$NEXT_ADDR"
export SBI_PLATFORM="$PLATFORM"
export SBI_HAS_PMP="$HAS_PMP"
export SBI_FDT_ENABLE="$FDT_ENABLE"
export SBI_FDT_PATH="$FDT_PATH"
export SBI_FDT_ADDR="$FDT_ADDR"
export SBI_FDT_DTB_PATH="$FDT_DTB_PATH"
export CARGO_TARGET_DIR="$TARGET_DIR"

(
    cd "$FW_DIR"
    cargo build --release --locked "${JOBS_ARGS[@]}"
)

ELF_SRC="$TARGET_DIR/$TARGET/release/rustsbi-npc"
[ -f "$ELF_SRC" ] || die "cargo 构建结束但找不到 $ELF_SRC"
cp "$ELF_SRC" "$OUT_DIR/rustsbi-npc.elf"

OBJCOPY=""
for candidate in riscv64-unknown-elf-objcopy llvm-objcopy rust-objcopy objcopy; do
    if command -v "$candidate" >/dev/null 2>&1; then
        OBJCOPY="$candidate"
        break
    fi
done
[ -n "$OBJCOPY" ] || die "找不到 objcopy，无法生成 rustsbi-npc.bin"
"$OBJCOPY" -O binary "$OUT_DIR/rustsbi-npc.elf" "$OUT_DIR/rustsbi-npc.bin"

READELF=""
for candidate in riscv64-unknown-elf-readelf llvm-readelf readelf; do
    if command -v "$candidate" >/dev/null 2>&1; then
        READELF="$candidate"
        break
    fi
done

if [ -n "$READELF" ]; then
    ENTRY="$("$READELF" -h "$OUT_DIR/rustsbi-npc.elf" |
        sed -n 's/.*Entry point address:[[:space:]]*//p' | head -n 1)"
    if [ -n "$ENTRY" ]; then
        ENTRY_NUM=$((ENTRY))
        BASE_NUM=$((FW_BASE))
        if [ "$ENTRY_NUM" -ne "$BASE_NUM" ]; then
            die "ELF 入口 $ENTRY 与 CONFIG_SBI_FW_BASE=$FW_BASE 不一致"
        fi
    fi

    ARCH_ATTR="$("$READELF" -A "$OUT_DIR/rustsbi-npc.elf" 2>/dev/null |
        grep 'Tag_RISCV_arch' | head -n 1 || true)"
    case "$ARCH_ATTR" in
        *zca*|*_c[0-9]*)
            die "固件包含压缩指令（$ARCH_ATTR）。
请使用 CONFIG_RISCV_C=y 的 NPC 配置，或改用 nightly 的 -Zbuild-std 重新编译 libcore。"
            ;;
    esac
fi

BIN_SIZE="$(stat -c '%s' "$OUT_DIR/rustsbi-npc.bin")"
FW_SIZE_NUM=$((FW_SIZE))
if [ "$BIN_SIZE" -gt "$FW_SIZE_NUM" ]; then
    die "固件 $BIN_SIZE 字节超过 CONFIG_SBI_FW_SIZE=$FW_SIZE"
fi

{
    echo "impl=rustsbi"
    echo "platform=$PLATFORM"
    echo "fw_base=$(printf '0x%x' $((FW_BASE)))"
    echo "fw_size=$(printf '0x%x' $((FW_SIZE)))"
    echo "next_addr=$(printf '0x%x' $((NEXT_ADDR)))"
    echo "fdt_enable=$FDT_ENABLE"
    if [ -n "$FDT_DTB_PATH" ]; then
        echo "fdt_path=$FDT_DTB_PATH"
        echo "fdt_addr=$(printf '0x%x' $((FDT_ADDR)))"
        echo "fdt_bytes=$(stat -c '%s' "$FDT_DTB_PATH")"
        echo "fdt_sha256=$(sha256sum "$FDT_DTB_PATH" | cut -d' ' -f1)"
    fi
    echo "timebase_freq=$TIMEBASE_FREQ"
    echo "fw_bytes=$BIN_SIZE"
    echo "fw_sha256=$(sha256sum "$OUT_DIR/rustsbi-npc.bin" | cut -d' ' -f1)"
} >"$OUT_DIR/rustsbi-npc.info.tmp"
mv -f "$OUT_DIR/rustsbi-npc.info.tmp" "$OUT_DIR/rustsbi-npc.info"

echo "完成:"
echo "  $OUT_DIR/rustsbi-npc.elf"
echo "  $OUT_DIR/rustsbi-npc.bin ($BIN_SIZE 字节)"
