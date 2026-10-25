#!/usr/bin/env bash
#
# 把 fastfetch initramfs 放进 NPC U-Boot EFI 镜像的 initrd 区域
# （0x80800000，相对镜像偏移 8 MiB），供用户态 checkpoint 长跑使用。
#
set -euo pipefail
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NPC_HOME="${NPC_HOME:-$(cd -- "$HERE/.." && pwd)}"
OUT="${NPC_USER_DIR:-/home/abc/ysyx-workbench/npc/checkpoints/npc-user}"
SRC="${NPC_UBOOT_SRC_IMAGE:-$NPC_HOME/build/uboot/npc-uboot.bin}"
SRC_INFO="${NPC_UBOOT_SRC_INFO:-$SRC.info}"
INITRD="${NPC_USER_INITRD:-$OUT/initramfs.cpio.gz}"
DST="$OUT/npc-uboot-user.bin"
DST_INFO="$OUT/npc-uboot-user.bin.info"

die() { echo "错误：$*" >&2; exit 2; }
info() { echo "[npc-user-image] $*"; }

[[ -f "$SRC" ]] || die "缺少 U-Boot 镜像：$SRC"
[[ -f "$SRC_INFO" ]] || die "缺少镜像摘要：$SRC_INFO"
[[ -f "$INITRD" ]] || die "缺少 initramfs：$INITRD"
INITRD_BYTES=$(stat -c '%s' "$INITRD")
(( INITRD_BYTES > 0 && INITRD_BYTES <= 8 * 1024 * 1024 )) || \
	die "initramfs 大小 $INITRD_BYTES 不在 0..8 MiB"

cp "$SRC" "$DST"
dd if="$INITRD" of="$DST" bs=1M seek=8 conv=notrunc status=none

cp "$SRC_INFO" "$DST_INFO"
sed -i \
	-e "s|^image=.*|image=$DST|" \
	-e "s|^image_sha256=.*|image_sha256=$(sha256sum "$DST" | cut -d' ' -f1)|" \
	-e "s|^initrd=.*|initrd=$INITRD|" \
	-e "s|^initrd_bytes=.*|initrd_bytes=$INITRD_BYTES|" \
	-e "s|^initrd_bytes_hex=.*|initrd_bytes_hex=$(printf '0x%x' "$INITRD_BYTES")|" \
	-e "s|^initrd_sha256=.*|initrd_sha256=$(sha256sum "$INITRD" | cut -d' ' -f1)|" \
	-e "s|^bootargs=.*|bootargs=console=ttyS0,115200 earlycon=sbi no5lvl cpuidle.off=1 quiet loglevel=3 initcall_blacklist=jent_mod_init,check_unaligned_access_all_cpus init_on_alloc=0 init_on_free=0 random.trust_cpu=on|" \
	"$DST_INFO"

info "生成：$DST"
info "initramfs：$INITRD（$INITRD_BYTES 字节）"
info "摘要：$DST_INFO"
