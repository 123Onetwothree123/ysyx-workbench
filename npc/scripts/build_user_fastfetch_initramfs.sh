#!/usr/bin/env bash
#
# 从持久目录里的 fastfetch base initramfs 生成 NPC 用户态检查点用的
# initramfs：
#   - /init 输出 NPC_USER_READY 后阻塞等待串口输入；
#   - 收到一行输入后执行 fastfetch --logo debian；
#   - 输出 FASTFETCH_INIT_END 后 poweroff。
#
# 默认目录：/home/abc/ysyx-workbench/npc/checkpoints/npc-user
# 输出：   initramfs.cpio.gz
#
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NPC_HOME="${NPC_HOME:-$(cd -- "$HERE/.." && pwd)}"
OUT="${NPC_USER_DIR:-/home/abc/ysyx-workbench/npc/checkpoints/npc-user}"
BASE="${NPC_USER_BASE_INITRD:-$OUT/base-fastfetch.cpio.gz}"
WORK="$OUT/initramfs-work"
ROOT="$WORK/root"
IMAGE="$OUT/initramfs.cpio.gz"

die() { echo "错误：$*" >&2; exit 2; }
info() { echo "[npc-user-initrd] $*"; }

[[ -f "$BASE" ]] || die "缺少 base fastfetch initramfs：$BASE"
command -v cpio >/dev/null || die "找不到 cpio"
command -v gzip >/dev/null || die "找不到 gzip"

rm -rf "$WORK"
mkdir -p "$ROOT"
cd "$ROOT"
gzip -dc "$BASE" | cpio -idm --quiet
[[ -x usr/bin/fastfetch ]] || die "base initramfs 中缺少 /usr/bin/fastfetch"
[[ -x bin/busybox ]] || die "base initramfs 中缺少 /bin/busybox"

cat >init <<'EOF'
#!/bin/busybox sh
export PATH=/bin:/sbin:/usr/bin:/usr/sbin
export TERM=linux
/bin/busybox mkdir -p /proc /sys /dev /tmp
[ -c /dev/console ] || /bin/busybox mknod -m 600 /dev/console c 5 1 2>/dev/null
[ -c /dev/null ] || /bin/busybox mknod -m 666 /dev/null c 1 3 2>/dev/null
exec >/dev/console 2>&1
/bin/busybox mount -t proc proc /proc 2>/dev/null
/bin/busybox mount -t sysfs sysfs /sys 2>/dev/null

echo "NPC_USER_READY"
# 检查点在这里保存；恢复后向串口发送一行（例如 go）即可继续。
while true; do
    if read -r TRIGGER </dev/console; then
        break
    fi
    /bin/busybox sleep 1
done

echo "NPC_USER_FASTFETCH_BEGIN"
/usr/bin/fastfetch --logo debian
echo "FASTFETCH_INIT_END"
/bin/busybox sync
/bin/busybox poweroff -f
/bin/busybox echo "FASTFETCH_POWEROFF_RETURNED"
while true; do /bin/busybox sleep 1; done
EOF
chmod 0755 init

find . -print0 | cpio --null -o -H newc --quiet | gzip -9 >"$IMAGE"
cp "$IMAGE" "$OUT/initramfs-latest.cpio.gz"
info "生成：$IMAGE"
info "大小：$(stat -c '%s' "$IMAGE") 字节"
info "base：$BASE"
