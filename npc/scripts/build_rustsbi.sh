#!/usr/bin/env bash
set -euo pipefail

NPC_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_FILE="$NPC_HOME/.config"
FW_DIR="$NPC_HOME/rustsbi-fw"
VENDOR_DIR="$NPC_HOME/../vendor/rustsbi"
OUT_DIR="${RUSTSBI_OUT_DIR:-${SBI_OUT_DIR:-$NPC_HOME/build/rustsbi}}"
TARGET="riscv64imac-unknown-none-elf"
TARGET_DIR="$OUT_DIR/target"

die() {
    echo "错误: $*" >&2
    exit 1
}

cfg_value() {
    local key="$1"
    local default="$2"
    local value=""
    if [ -f "$CONFIG_FILE" ]; then
        value="$(sed -n "s/^${key}=//p" "$CONFIG_FILE" | tail -n 1)"
    fi
    value="${value%\"}"
    value="${value#\"}"
    if [ -z "$value" ]; then
        printf '%s' "$default"
    else
        printf '%s' "$value"
    fi
}

cfg_bool() {
    local key="$1"
    local default="$2"
    local value
    value="$(cfg_value "$key" "$default")"
    case "$value" in
        y|Y|1|true|TRUE) printf 'y' ;;
        *) printf 'n' ;;
    esac
}

[ -f "$CONFIG_FILE" ] || die "缺少 npc/.config；请先在 npc/ 下执行 make defconfig 或 make menuconfig"
[ -d "$FW_DIR" ] || die "缺少固件源码目录 $FW_DIR"
[ -d "$VENDOR_DIR/library/rustsbi" ] || die "缺少 vendor/rustsbi；请确认源码已纳入仓库"
[ -f "$FW_DIR/Cargo.lock" ] || die "缺少 $FW_DIR/Cargo.lock；拒绝在无锁文件的情况下构建"

command -v cargo >/dev/null 2>&1 || die "找不到 cargo；请安装 Rust 工具链"
command -v rustc >/dev/null 2>&1 || die "找不到 rustc；请安装 Rust 工具链"
command -v rustup >/dev/null 2>&1 || die "找不到 rustup；无法检查 RISC-V target 是否安装"

if ! rustup target list --installed | grep -qx "$TARGET"; then
    die "未安装 Rust target $TARGET。
请执行：
  rustup target add $TARGET
网络较慢时可使用镜像：
  export RUSTUP_DIST_SERVER=https://rsproxy.cn
  export RUSTUP_UPDATE_ROOT=https://rsproxy.cn/rustup
  rustup target add $TARGET"
fi

XLEN="$(cfg_value CONFIG_XLEN 32)"
if [ "$XLEN" != "64" ]; then
    die "RustSBI NPC 固件当前只支持 RV64（CONFIG_XLEN=$XLEN）。
请选择 RV64 配置，或为 RV32 单独实现启动/陷入汇编。"
fi
if [ "$(cfg_bool CONFIG_HAS_S n)" != "y" ]; then
    die "RustSBI 需要 S 模式（CONFIG_HAS_S=y）；当前配置没有 S 模式"
fi

FW_BASE="${SBI_FW_BASE:-$(cfg_value CONFIG_SBI_FW_BASE 0x80000000)}"
FW_SIZE="${SBI_FW_SIZE:-$(cfg_value CONFIG_SBI_FW_SIZE 0x00200000)}"
NEXT_ADDR="${SBI_NEXT_ADDR:-$(cfg_value CONFIG_SBI_NEXT_ADDR 0x80200000)}"
PLATFORM="${SBI_PLATFORM:-$(cfg_value CONFIG_SBI_PLATFORM npc)}"
JOBS="$(cfg_value CONFIG_SBI_BUILD_JOBS 0)"
FDT_ENABLE="${SBI_FDT_ENABLE:-$(cfg_bool CONFIG_SBI_FDT_ENABLE n)}"
FDT_PATH="${SBI_FDT_PATH:-$(cfg_value CONFIG_SBI_FDT_PATH dts/npc.dts)}"
FDT_ADDR="${SBI_FDT_ADDR:-$(cfg_value CONFIG_SBI_FDT_ADDR 0x80f00000)}"
FDT_OUT_DIR="${SBI_FDT_OUT_DIR:-$OUT_DIR/fdt}"
FDT_LINUX="${SBI_FDT_LINUX:-$(cfg_bool CONFIG_LINUX_BOOT_ENABLE n)}"
INITRAMFS_ENABLE="${SBI_INITRAMFS_ENABLE:-$(cfg_bool CONFIG_LINUX_INITRAMFS_ENABLE n)}"
INITRAMFS_PATH="${SBI_INITRAMFS_PATH:-$(cfg_value CONFIG_LINUX_INITRAMFS_PATH "")}"
INITRAMFS_ADDR="${SBI_INITRAMFS_ADDR:-$(cfg_value CONFIG_LINUX_INITRAMFS_ADDR 0x82000000)}"
BOOTARGS="${SBI_BOOTARGS:-$(cfg_value CONFIG_LINUX_BOOTARGS "console=ttyS0,115200 earlycon=sbi")}"
RAM_BASE="${NPC_RAM_BASE:-0x80000000}"
RAM_SIZE="${NPC_RAM_SIZE:-0x04000000}"
# mtime 每个 CPU 周期 +1，频率 = CONFIG_SYNTH_FREQ(MHz) * 1,000,000。
# RustSBI 本身只写 mtimecmp，不需要频率；这里传给 DTB，保证 Linux 看到
# 的 timebase 与 RTL/OpenSBI 一致。
SYNTH_FREQ="$(cfg_value CONFIG_SYNTH_FREQ 450)"
TIMEBASE_FREQ="${SBI_TIMEBASE_FREQ:-$((SYNTH_FREQ * 1000000))}"
HAS_PMP="${SBI_HAS_PMP:-$(cfg_bool CONFIG_HAS_PMP n)}"
case "$HAS_PMP" in
    y|Y|1|true|TRUE) HAS_PMP=y ;;
    *) HAS_PMP=n ;;
esac

case "$FW_BASE" in 0x*|0X*) ;; *) die "CONFIG_SBI_FW_BASE 必须是 0x 开头的十六进制数：$FW_BASE" ;; esac
case "$FW_SIZE" in 0x*|0X*) ;; *) die "CONFIG_SBI_FW_SIZE 必须是 0x 开头的十六进制数：$FW_SIZE" ;; esac
case "$NEXT_ADDR" in 0x*|0X*) ;; *) die "CONFIG_SBI_NEXT_ADDR 必须是 0x 开头的十六进制数：$NEXT_ADDR" ;; esac
case "$FDT_ENABLE" in
    y|Y|1|true|TRUE) FDT_ENABLE=y ;;
    n|N|0|false|FALSE|'') FDT_ENABLE=n ;;
    *) die "CONFIG_SBI_FDT_ENABLE 必须是 y/n：$FDT_ENABLE" ;;
esac
case "$FDT_ADDR" in 0x*|0X*) ;; *) die "CONFIG_SBI_FDT_ADDR 必须是 0x 开头的十六进制数：$FDT_ADDR" ;; esac

# direct-NPC 的复位 PC = 0x80000000 + CONFIG_CACHE_PADDING。固件 ELF
# 入口必须与它完全一致，否则 ImageLoader 会拒绝加载。
CACHE_PADDING="$(cfg_value CONFIG_CACHE_PADDING 0)"
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

if [ "$(cfg_bool CONFIG_SBI_ENABLE n)" != "y" ]; then
    echo "提示: CONFIG_SBI_ENABLE=n，本次仅按显式请求构建 RustSBI 固件。"
fi
IMPL="$(cfg_value CONFIG_SBI_IMPL none)"
if [ "$IMPL" != "rustsbi" ]; then
    echo "提示: 当前 CONFIG_SBI_IMPL=$IMPL；make rustsbi 仍会构建 RustSBI，但默认 SBI 选择不是它。"
fi

# 设备树：RustSBI 不支持 OpenSBI 的 FW_FDT_PATH，因此由本脚本先
# 生成 DTB，再通过 cargo 环境变量让 build.rs 把 DTB 编进 .rodata；
# 运行时 rust_main 会把它复制到 SBI_FDT_ADDR，并以 a1 传给 S 模式。
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

    # RISC-V 2024 命名下压缩指令标记为 zca；核心/依赖若带压缩指令，
    # NPC 在 CONFIG_RISCV_C=n 时无法执行。这里显式拒绝，而不是静默产出一个
    # 只能在实际启用 C 扩展的配置上运行的固件。
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
