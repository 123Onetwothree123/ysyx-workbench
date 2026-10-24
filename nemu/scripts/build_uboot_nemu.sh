#!/usr/bin/env bash
#
# 构建 NEMU 的 OpenSBI FW_JUMP + U-Boot S-mode 启动镜像。
#
# 该脚本只做三件事：
#   1. 用 vendor/u-boot 的 nemu_riscv64_smode_defconfig 构建 U-Boot；
#   2. 复用 build_linux_fw.sh 生成 NEMU 专用 1MHz DTB + OpenSBI FW_JUMP；
#   3. 按固定地址把 OpenSBI、U-Boot 和可选的 EFI 应用/initramfs/EFI
#      内核拼成一个 NEMU 可直接加载的 raw image。
#
# 不下载/编译/运行 Linux，也不构建 rootfs；Debian vmlinux 和 BusyBox
# initramfs 只作为调用者提供的输入文件嵌入。
#
# 固定布局（64 MiB 基准；128 MiB 评估配置复用同一组载荷地址）：
#   0x80000000  OpenSBI FW_JUMP（预留 2 MiB）
#   0x80200000  U-Boot
#   0x80600000  可选 EFI 测试应用（默认 initrddump.efi）
#   0x80800000  可选 initramfs
#   0x81000000  可选 EFI 内核（Debian install/vmlinux）
#   0x83f00000  64 MiB 配置的 DTB（128 MiB 时为 0x87f00000）
#   0x84000000  64 MiB RAM 结束（128 MiB 时为 0x88000000）
#
# 用法：
#   nemu/scripts/build_uboot_nemu.sh
#   NEMU_UBOOT_INITRD=/path/to/initramfs.cpio.gz nemu/scripts/build_uboot_nemu.sh
#   NEMU_UBOOT_KERNEL=/path/to/vmlinux \
#     NEMU_UBOOT_INITRD=/path/to/initramfs.cpio.gz \
#     nemu/scripts/build_uboot_nemu.sh
#
# 默认使用 nemu/.config（64 MiB）。Debian EFI 评估可用 128 MiB 配置：
#   NEMU_UBOOT_CONFIG_FILE=$PWD/.config \
#     nemu/scripts/build_uboot_nemu.sh
# 载荷地址仍固定在 64 MiB 范围内；更大的 RAM 只扩大 EFI allocator
# 可用空间，DTB 地址由 OpenSBI/fw.info 决定。
#
# 输出（$NEMU_UBOOT_OUT，默认 nemu/build/uboot）：
#   nemu-uboot.bin        NEMU -b 直接加载的镜像
#   nemu-uboot.bin.info   布局、大小、SHA256、推荐命令
#
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NEMU_HOME="$(cd -- "$HERE/.." && pwd)"
WORKBENCH_HOME="$(cd -- "$NEMU_HOME/.." && pwd)"
CONFIG_FILE="${NEMU_UBOOT_CONFIG_FILE:-${CONFIG_FILE:-$NEMU_HOME/.config}}"
OUT="${NEMU_UBOOT_OUT:-$NEMU_HOME/build/uboot}"
UBOOT_DIR="$WORKBENCH_HOME/vendor/u-boot"
UBOOT_BUILD="$OUT/uboot-build"
CROSS_COMPILE="${CROSS_COMPILE:-riscv64-linux-gnu-}"
JOBS="${NEMU_UBOOT_JOBS:-$(nproc)}"

FW_OFF=0x000000
UBOOT_OFF=0x200000
EFI_APP_OFF=0x600000
INITRD_OFF=0x800000
KERNEL_OFF=0x1000000
DTB_ADDR=0x83f00000
RAM_END=0x84000000

die() {
	echo "错误：$*" >&2
	exit 2
}

info() {
	echo "[uboot-nemu] $*"
}

[[ -d "$UBOOT_DIR/board/nemu/riscv64" ]] || die \
	"找不到 U-Boot NEMU 板级目录：$UBOOT_DIR/board/nemu/riscv64"
[[ -f "$CONFIG_FILE" ]] || die \
	"找不到 NEMU 配置 $CONFIG_FILE；请先 make riscv64-linux_defconfig"
command -v "python3" >/dev/null || die "找不到 python3（打包镜像需要）"
command -v "${CROSS_COMPILE}gcc" >/dev/null || die \
	"找不到 ${CROSS_COMPILE}gcc"

# 固定载荷地址按 64 MiB 布局设计；更大的 RAM 只是让 EFI allocator
# 有更多空间，载荷地址不变。小于 64 MiB 会越过 DTB/预留区，直接拒绝。
MSIZE=$(sed -n 's/^CONFIG_MSIZE=//p' "$CONFIG_FILE" | tail -n 1)
[[ "$MSIZE" =~ ^0x[0-9a-fA-F]+$ ]] || die \
	"$CONFIG_FILE 的 CONFIG_MSIZE='$MSIZE' 不是 0x 十六进制"
(( MSIZE >= 0x04000000 )) || die \
	"本布局至少需要 64 MiB RAM，当前 CONFIG_MSIZE=$MSIZE"

mkdir -p "$OUT"

info "构建 U-Boot（$UBOOT_DIR，O=$UBOOT_BUILD）"
make -C "$UBOOT_DIR" O="$UBOOT_BUILD" ARCH=riscv \
	CROSS_COMPILE="$CROSS_COMPILE" nemu_riscv64_smode_defconfig \
	</dev/null >"$OUT/uboot-defconfig.log" 2>&1
make -C "$UBOOT_DIR" O="$UBOOT_BUILD" ARCH=riscv \
	CROSS_COMPILE="$CROSS_COMPILE" olddefconfig \
	</dev/null >>"$OUT/uboot-defconfig.log" 2>&1
make -C "$UBOOT_DIR" O="$UBOOT_BUILD" ARCH=riscv \
	CROSS_COMPILE="$CROSS_COMPILE" -j"$JOBS" \
	</dev/null >"$OUT/uboot-build.log" 2>&1
UBOOT_BIN="$UBOOT_BUILD/u-boot.bin"
[[ -f "$UBOOT_BIN" ]] || die "U-Boot 构建没有生成 $UBOOT_BIN"

# 配置契约：EFI loader、bootefi、EFI_LOAD_FILE2 initrd 缺一不可。
for key in \
	CONFIG_RISCV_SMODE=y \
	CONFIG_EFI_LOADER=y \
	CONFIG_CMD_BOOTEFI=y \
	CONFIG_CMD_BOOTEFI_BINARY=y \
	CONFIG_EFI_LOAD_FILE2_INITRD=y \
	CONFIG_OF_HAS_PRIOR_STAGE=y \
	CONFIG_TEXT_BASE=0x80200000
do
	grep -qx "$key" "$UBOOT_BUILD/.config" || die "U-Boot 配置缺少 $key"
done

info "生成 NEMU DTB + OpenSBI FW_JUMP"
NEMU_HOME="$NEMU_HOME" \
WORKBENCH_HOME="$WORKBENCH_HOME" \
CONFIG_FILE="$CONFIG_FILE" \
NPC_HOME="$WORKBENCH_HOME/npc" \
SBI_VENDOR_DIR="$WORKBENCH_HOME/vendor/opensbi" \
NEMU_FW_MODE=jump \
NEMU_LINUX_OUT="$OUT/linux" \
	"$NEMU_HOME/scripts/build_linux_fw.sh" >"$OUT/opensbi-build.log" 2>&1
FW_BIN="$OUT/linux/opensbi/fw.bin"
FW_INFO="$OUT/linux/opensbi/fw.info"
[[ -f "$FW_BIN" && -f "$FW_INFO" ]] || die "OpenSBI 构建没有生成 $FW_BIN/fw.info"
DTB_ADDR=$(sed -n 's/^fdt_addr=//p' "$FW_INFO" | tail -n 1)
[[ "$DTB_ADDR" =~ ^0x[0-9a-fA-F]+$ ]] || die "无法从 $FW_INFO 读取 fdt_addr"
DTB_OFF=$((DTB_ADDR - 0x80000000))
(( DTB_OFF > 0x1000000 )) || die "DTB 地址 $DTB_ADDR 不可信"

EFI_APP="${NEMU_UBOOT_EFI_APP:-}"
INITRD="${NEMU_UBOOT_INITRD:-}"
KERNEL="${NEMU_UBOOT_KERNEL:-}"

# 有 initramfs 但没有指定 EFI 应用时，默认用 U-Boot 自带的
# initrddump.efi 验证 EFI_LOAD_FILE2；它不会执行 Linux。
if [[ -n "$INITRD" && -z "$EFI_APP" ]]; then
	EFI_APP="$UBOOT_BUILD/lib/efi_loader/initrddump.efi"
fi

for f in "$EFI_APP" "$INITRD" "$KERNEL"; do
	[[ -z "$f" || -f "$f" ]] || die "输入文件不存在：$f"
done

info "打包 $OUT/nemu-uboot.bin"
python3 - "$OUT/nemu-uboot.bin" "$FW_BIN" "$UBOOT_BIN" \
	"$EFI_APP" "$INITRD" "$KERNEL" "$DTB_OFF" <<'PY'
import hashlib
import os
import sys

out, fw, uboot, efi_app, initrd, kernel, dtb_off = sys.argv[1:8]
dtb_off = int(dtb_off, 0)
offsets = {
    "opensbi": 0x000000,
    "u-boot": 0x200000,
    "efi_app": 0x600000,
    "initrd": 0x800000,
    "kernel": 0x1000000,
}
files = {
    "opensbi": fw,
    "u-boot": uboot,
    "efi_app": efi_app,
    "initrd": initrd,
    "kernel": kernel,
}
loaded = []
for name, path in files.items():
    if not path:
        continue
    data = open(path, "rb").read()
    loaded.append((name, offsets[name], data))

end = max(off + len(data) for _, off, data in loaded)
if end > dtb_off:
    sys.exit(f"错误：镜像结束地址 0x{end:x} 超过 DTB 区偏移 0x{dtb_off:x}")

spans = sorted((off, off + len(data), name) for name, off, data in loaded)
for (_, prev_end, prev_name), (cur_start, _, cur_name) in zip(spans, spans[1:]):
    if prev_end > cur_start:
        sys.exit(f"错误：{prev_name} 与 {cur_name} 在 RAM 中重叠")

buf = bytearray(end)
for name, off, data in loaded:
    buf[off:off + len(data)] = data

with open(out, "wb") as f:
    f.write(buf)

for name, off, data in loaded:
    print(f"{name}_addr=0x{0x80000000 + off:x}")
    print(f"{name}_bytes={len(data)}")
    print(f"{name}_sha256={hashlib.sha256(data).hexdigest()}")
print(f"image_bytes={len(buf)}")
print(f"image_sha256={hashlib.sha256(buf).hexdigest()}")
PY
PACK_INFO="$OUT/nemu-uboot.bin.info"
{
	echo "uboot_version=2026.07"
	echo "uboot_bin=$UBOOT_BIN"
	echo "opensbi_fw=$FW_BIN"
	echo "image=$OUT/nemu-uboot.bin"
	echo "image_bytes=$(stat -c '%s' "$OUT/nemu-uboot.bin")"
	echo "image_sha256=$(sha256sum "$OUT/nemu-uboot.bin" | cut -d' ' -f1)"
	echo "ram_base=0x80000000"
	echo "ram_size=$MSIZE"
	echo "dtb_addr=$DTB_ADDR"
	echo "opensbi_addr=0x80000000"
	echo "uboot_addr=0x80200000"
	echo "efi_app_addr=0x80600000"
	echo "initrd_addr=0x80800000"
	echo "kernel_addr=0x81000000"
	if [[ -n "$EFI_APP" ]]; then
		echo "efi_app=$EFI_APP"
		echo "efi_app_bytes=$(stat -c '%s' "$EFI_APP")"
		printf 'efi_app_bytes_hex=0x%x\n' "$(stat -c '%s' "$EFI_APP")"
		echo "efi_app_sha256=$(sha256sum "$EFI_APP" | cut -d' ' -f1)"
	fi
	if [[ -n "$INITRD" ]]; then
		echo "initrd=$INITRD"
		echo "initrd_bytes=$(stat -c '%s' "$INITRD")"
		printf 'initrd_bytes_hex=0x%x\n' "$(stat -c '%s' "$INITRD")"
		echo "initrd_sha256=$(sha256sum "$INITRD" | cut -d' ' -f1)"
	fi
	if [[ -n "$KERNEL" ]]; then
		echo "kernel=$KERNEL"
		echo "kernel_bytes=$(stat -c '%s' "$KERNEL")"
		printf 'kernel_bytes_hex=0x%x\n' "$(stat -c '%s' "$KERNEL")"
		echo "kernel_sha256=$(sha256sum "$KERNEL" | cut -d' ' -f1)"
	fi
} >"$PACK_INFO"

info "完成：$OUT/nemu-uboot.bin"
info "布局：OpenSBI 0x80000000，U-Boot 0x80200000，DTB $DTB_ADDR"
if [[ -n "$KERNEL" ]]; then
	info "EFI 内核：0x81000000（$(stat -c '%s' "$KERNEL") 字节）"
fi
if [[ -n "$INITRD" ]]; then
	info "initramfs：0x80800000（$(stat -c '%s' "$INITRD") 字节）"
fi
if [[ -n "$EFI_APP" ]]; then
	info "EFI 应用：0x80600000（$(stat -c '%s' "$EFI_APP") 字节）"
fi
info "运行：cd $NEMU_HOME && ./build/riscv64-nemu-interpreter -b $OUT/nemu-uboot.bin"
info "详见 UBOOT_NEMU.md；布局与命令在 $PACK_INFO"
