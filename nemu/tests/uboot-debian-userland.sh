#!/usr/bin/env bash
#
# Debian EFI kernel + BusyBox initramfs 用户态冒烟测试。
#
# 该测试验证的是“Linux 内核真正执行 /init”，不是只打印 early boot：
#   1. 用 riscv64-linux-128m_defconfig 构建 NEMU；
#   2. 把调用者提供的 BusyBox initramfs 重打包，/init 换成
#      nemu/tests/debian-userland-init.sh（非交互，打印固定标记后 poweroff）；
#   3. 通过 U-Boot S-mode + bootefi 启动 Debian EFI vmlinux；
#   4. 检查 /init 的固定标记、uname、/proc/cpuinfo 和用户态命令输出。
#
# 不下载/编译 Linux，也不构建 BusyBox rootfs；vmlinux 和 initramfs
# 都是调用者提供的现成文件。
#
# 用法：
#   NEMU_DEBIAN_VMLINUX=/path/to/install/vmlinux \
#   NEMU_DEBIAN_INITRD=/path/to/busybox-initramfs.cpio.gz \
#     nemu/tests/uboot-debian-userland.sh
#
# 可选：
#   NEMU_DEBIAN_TIMEOUT=1800   整个 NEMU 运行的秒数上限
#   NEMU_DEBIAN_WORKDIR=...    测试输出目录（默认 nemu/build/uboot/debian-test）
#
# 说明：NEMU 解释执行，首次启动到用户态约需数分钟；测试默认超时 30 分钟。
#
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NEMU_HOME=$ROOT/nemu
AM_HOME=$ROOT/abstract-machine
TEST_OUT=${NEMU_DEBIAN_WORKDIR:-$NEMU_HOME/build/uboot/debian-test}
LOG=$TEST_OUT/nemu-debian-userland.log
TIMEOUT_SEC=${NEMU_DEBIAN_TIMEOUT:-1800}
VMLINUX=${NEMU_DEBIAN_VMLINUX:-}
INITRD=${NEMU_DEBIAN_INITRD:-}

die() {
	echo "FAIL: $*" >&2
	exit 1
}

info() {
	echo "[debian-userland] $*"
}

[[ -n "$VMLINUX" && -f "$VMLINUX" ]] || die \
	"请设置 NEMU_DEBIAN_VMLINUX 指向 Debian EFI vmlinux"
[[ -n "$INITRD" && -f "$INITRD" ]] || die \
	"请设置 NEMU_DEBIAN_INITRD 指向 BusyBox initramfs（cpio.gz）"
for tool in gzip cpio python3; do
	command -v "$tool" >/dev/null 2>&1 || die "缺少工具：$tool"
done

mkdir -p "$TEST_OUT"

info "[1/4] 构建 NEMU riscv64-linux-128m_defconfig"
make -C "$NEMU_HOME" NEMU_HOME="$NEMU_HOME" ARCH=riscv64-nemu \
	AM_HOME="$AM_HOME" riscv64-linux-128m_defconfig >/dev/null
make -C "$NEMU_HOME" NEMU_HOME="$NEMU_HOME" ARCH=riscv64-nemu \
	AM_HOME="$AM_HOME" -j"$(nproc)" >/dev/null
NEMU_BIN=$NEMU_HOME/build/riscv64-nemu-interpreter
[[ -x "$NEMU_BIN" ]] || die "NEMU 可执行文件不存在：$NEMU_BIN"
grep -qxF 'CONFIG_MSIZE=0x08000000' "$NEMU_HOME/.config" || \
	die "128 MiB 配置未生效（CONFIG_MSIZE != 0x08000000）"

info "[2/4] 重打包 initramfs，用非交互 /init 替换原入口"
TREE=$TEST_OUT/initramfs-tree
rm -rf "$TREE"
mkdir -p "$TREE"
(
	cd "$TREE"
	gzip -dc "$INITRD" | cpio -id --quiet
)
install -m 0755 "$NEMU_HOME/tests/debian-userland-init.sh" "$TREE/init"
(
	cd "$TREE"
	find . -print0 | LC_ALL=C sort -z |
		cpio --null -o --format=newc --owner=0:0 --quiet |
		gzip -9 -n >"$TEST_OUT/debian-userland-initramfs.cpio.gz"
)
TEST_INITRD=$TEST_OUT/debian-userland-initramfs.cpio.gz
gzip -dc "$TEST_INITRD" | cpio -it --quiet | grep -qx 'init' || \
	die "测试 initramfs 缺少 /init"

info "[3/4] 构建 U-Boot + OpenSBI + EFI 镜像"
NEMU_UBOOT_CONFIG_FILE="$NEMU_HOME/.config" \
NEMU_UBOOT_OUT="$TEST_OUT/uboot" \
NEMU_UBOOT_INITRD="$TEST_INITRD" \
NEMU_UBOOT_KERNEL="$VMLINUX" \
	"$NEMU_HOME/scripts/build_uboot_nemu.sh" >"$TEST_OUT/build.log" 2>&1 || {
		tail -80 "$TEST_OUT/build.log" >&2
		die "构建 U-Boot/OpenSBI/EFI 镜像失败"
	}
IMAGE=$TEST_OUT/uboot/nemu-uboot.bin
INFO=$IMAGE.info
[[ -f "$IMAGE" && -f "$INFO" ]] || die "打包脚本没有生成 $IMAGE / $INFO"
KERNEL_ADDR=$(sed -n 's/^kernel_addr=//p' "$INFO")
KERNEL_BYTES=$(sed -n 's/^kernel_bytes_hex=//p' "$INFO")
INITRD_ADDR=$(sed -n 's/^initrd_addr=//p' "$INFO")
INITRD_BYTES=$(sed -n 's/^initrd_bytes_hex=//p' "$INFO")
for value in "$KERNEL_ADDR" "$KERNEL_BYTES" "$INITRD_ADDR" "$INITRD_BYTES"; do
	[[ "$value" =~ ^0x[0-9a-fA-F]+$ ]] || die "镜像信息缺少地址/大小：$value"
done

info "[4/4] 运行 NEMU 并检查用户态日志"
# Capstone 以相对路径 tools/capstone/... 加载，必须在 nemu/ 目录运行。
cd "$NEMU_HOME"
python3 - "$NEMU_BIN" "$IMAGE" "$LOG" "$TIMEOUT_SEC" \
	"$KERNEL_ADDR:$KERNEL_BYTES" "$INITRD_ADDR:$INITRD_BYTES" <<'PY'
import os
import select
import subprocess
import sys
import time

nemu, image, logpath, timeout, kernel, initrd = sys.argv[1:7]
timeout = float(timeout)
bootargs = ("console=ttyS0,115200 earlycon=sbi no5lvl "
            "cpuidle.off=1 loglevel=7")
cmds = [
    "setenv bootargs " + bootargs,
    "nemu_reserve %s %s" % (initrd.split(":")[0], initrd.split(":")[1]),
    "nemu_reserve %s %s" % (kernel.split(":")[0], kernel.split(":")[1]),
    "bootefi %s %s" % (kernel, initrd),
]

p = subprocess.Popen([nemu, "-b", image], stdin=subprocess.PIPE,
                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                     bufsize=0)
os.set_blocking(p.stdout.fileno(), False)
start = time.time()
last = start
sent = 0
buf = b""
all_data = b""
marker_at = None

with open(logpath, "wb") as log:
    while time.time() - start < timeout:
        r, _, _ = select.select([p.stdout], [], [], 0.2)
        if r:
            data = os.read(p.stdout.fileno(), 65536)
            if not data:
                break
            log.write(data)
            log.flush()
            all_data += data
            buf += data
            if marker_at is None and b"NEMU_USER_INIT_END" in all_data:
                marker_at = time.time()
        if marker_at is not None and time.time() - marker_at > 90.0:
            break
        if b"Power off not available: System halted instead" in all_data:
            break
        if sent < len(cmds) and b"=> " in buf and time.time() - last > 0.2:
            p.stdin.write(cmds[sent].encode() + b"\n")
            p.stdin.flush()
            sent += 1
            last = time.time()
            buf = b""
        if p.poll() is not None:
            break

p.kill()
if sent != len(cmds):
    sys.exit(f"只发送了 {sent}/{len(cmds)} 条 U-Boot 命令")
PY

grep -aq 'Run /init as init process' "$LOG" || die "日志缺少 Run /init"
grep -aq 'NEMU_USER_INIT_START' "$LOG" || die "日志缺少用户态 START 标记"
grep -aq 'NEMU_USER_CMD_OK' "$LOG" || die "日志缺少用户态命令标记"
grep -aq 'NEMU_USER_INIT_END' "$LOG" || die "日志缺少用户态 END 标记"
grep -aqE '^Linux .* riscv64' "$LOG" || die "用户态 uname 输出异常"
grep -aq 'processor' "$LOG" || die "/proc/cpuinfo 没有 processor"
grep -aqE 'uid=0|uid=0\(root\)' "$LOG" || die "用户态 id 输出异常"
grep -aq 'Power off not available: System halted instead' "$LOG" || \
	grep -aq 'NEMU_USER_POWEROFF_RETURNED' "$LOG" || \
	die "没有看到内核停机/关机输出"
if grep -aq 'Kernel panic' "$LOG"; then
	die "日志出现 Kernel panic"
fi

info "PASS: $LOG"
