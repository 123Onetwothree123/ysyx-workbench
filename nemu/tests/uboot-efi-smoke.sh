#!/usr/bin/env bash
#
# U-Boot S-mode + EFI loader 冒烟测试。
#
# 覆盖：
#   1. 构建 NEMU riscv64-linux 配置；
#   2. 构建 U-Boot nemu_riscv64_smode_defconfig + OpenSBI FW_JUMP；
#   3. OpenSBI -> U-Boot，检查 banner、64 MiB DRAM、串口；
#   4. bdinfo / coninfo / mtest / time 验证 UART、timer、RAM；
#   5. bootefi hello 验证 EFI loader；
#   6. 可选 NEMU_UBOOT_INITRD：用 initrddump.efi 验证 EFI_LOAD_FILE2
#      的长度和 CRC32。
#
# 不运行 Linux；initrddump.efi 只读取 initrd，不执行内核。
#
# 用法：
#   nemu/tests/uboot-efi-smoke.sh
#   NEMU_UBOOT_INITRD=/path/to/busybox-initramfs.cpio.gz \
#     nemu/tests/uboot-efi-smoke.sh
#
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NEMU_HOME=$ROOT/nemu
AM_HOME=$ROOT/abstract-machine
TEST_OUT=$NEMU_HOME/build/uboot/test
LOG=$TEST_OUT/nemu-uboot.log
TIMEOUT_SEC=${NEMU_UBOOT_TIMEOUT:-60}

mkdir -p "$TEST_OUT"

die() {
	echo "FAIL: $*" >&2
	exit 1
}

info() {
	echo "[uboot-efi-smoke] $*"
}

info "[1/5] 构建 NEMU riscv64-linux_defconfig"
make -C "$NEMU_HOME" NEMU_HOME="$NEMU_HOME" ARCH=riscv64-nemu \
	AM_HOME="$AM_HOME" riscv64-linux_defconfig >/dev/null
make -C "$NEMU_HOME" NEMU_HOME="$NEMU_HOME" ARCH=riscv64-nemu \
	AM_HOME="$AM_HOME" -j"$(nproc)" >/dev/null
NEMU_BIN=$NEMU_HOME/build/riscv64-nemu-interpreter
[[ -x "$NEMU_BIN" ]] || die "NEMU 可执行文件不存在：$NEMU_BIN"

info "[2/5] 构建 U-Boot + OpenSBI + NEMU 镜像"
NEMU_UBOOT_OUT="$TEST_OUT/uboot" \
NEMU_UBOOT_INITRD="${NEMU_UBOOT_INITRD:-}" \
	"$NEMU_HOME/scripts/build_uboot_nemu.sh" >/dev/null
IMAGE=$TEST_OUT/uboot/nemu-uboot.bin
INFO=$IMAGE.info
[[ -f "$IMAGE" && -f "$INFO" ]] || die "打包脚本没有生成 $IMAGE / $INFO"

info "[3/5] 启动 NEMU 并执行 U-Boot 命令"
CMDS=(
	bdinfo
	coninfo
	'mtest 0x80400000 0x8040ffff 0 1'
	'time sleep 1'
	'bootefi hello'
)
EXPECT_LEN=""
EXPECT_CRC=""
if [[ -n "${NEMU_UBOOT_INITRD:-}" ]]; then
	EFI_APP_ADDR=$(sed -n 's/^efi_app_addr=//p' "$INFO")
	EFI_APP_BYTES=$(sed -n 's/^efi_app_bytes_hex=//p' "$INFO")
	INITRD_ADDR=$(sed -n 's/^initrd_addr=//p' "$INFO")
	INITRD_BYTES=$(sed -n 's/^initrd_bytes_hex=//p' "$INFO")
	INITRD_SIZE=$(sed -n 's/^initrd_bytes=//p' "$INFO")
	[[ -n "$EFI_APP_ADDR" && -n "$EFI_APP_BYTES" ]] || die \
		"initrd 模式缺少 efi_app 信息；U-Boot 是否构建了 initrddump.efi？"
	[[ -n "$INITRD_ADDR" && -n "$INITRD_BYTES" ]] || die \
		"initrd 模式缺少 initrd 信息"
	EXPECT_LEN=$(printf '0x%x' "$INITRD_SIZE")
	EXPECT_CRC=$(python3 - "$NEMU_UBOOT_INITRD" <<'PY'
import sys, zlib
print(f"0x{zlib.crc32(open(sys.argv[1], 'rb').read()) & 0xffffffff:08x}")
PY
)
	CMDS+=(
		"nemu_reserve $EFI_APP_ADDR $EFI_APP_BYTES"
		"nemu_reserve $INITRD_ADDR $INITRD_BYTES"
		"bootefi $EFI_APP_ADDR:$EFI_APP_BYTES $INITRD_ADDR:$INITRD_BYTES"
		load
		exit
	)
fi

cd "$NEMU_HOME"
python3 - "$NEMU_BIN" "$IMAGE" "$LOG" "$TIMEOUT_SEC" "${CMDS[@]}" <<'PY'
import os
import select
import subprocess
import sys
import time

nemu, image, logpath, timeout = sys.argv[1:5]
cmds = sys.argv[5:]
timeout = float(timeout)

p = subprocess.Popen([nemu, '-b', image], stdin=subprocess.PIPE,
                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                     bufsize=0)
os.set_blocking(p.stdout.fileno(), False)
buf = b''
sent = 0
last = time.time()
done_at = None
start = time.time()

with open(logpath, 'wb') as log:
    while time.time() - start < timeout:
        r, _, _ = select.select([p.stdout], [], [], 0.1)
        if r:
            data = os.read(p.stdout.fileno(), 65536)
            if not data:
                break
            log.write(data)
            log.flush()
            buf += data
            sys.stdout.buffer.write(data)
            sys.stdout.buffer.flush()
        if sent < len(cmds) and b'=> ' in buf and time.time() - last > 0.15:
            p.stdin.write(cmds[sent].encode() + b'\n')
            p.stdin.flush()
            sent += 1
            last = time.time()
            buf = b''
        if sent == len(cmds) and done_at is None:
            done_at = time.time()
        if done_at and time.time() - done_at > 3.0:
            break
        if p.poll() is not None:
            break

p.kill()
if sent != len(cmds):
    print(f"\nFAIL: 只发送了 {sent}/{len(cmds)} 条命令", file=sys.stderr)
    sys.exit(1)
PY

info "[4/5] 检查日志"
check() {
	grep -aq "$1" "$LOG" || die "日志缺少：$1"
}
check 'U-Boot 2026.07'
check 'Model: ysyx,npc'
check 'DRAM:  64 MiB'
check 'NEMU U-Boot S-mode ready'
check 'serial@10000000'
check 'Tested 1 iteration(s) with 0 errors.'
grep -aqE 'time: 1\.0[0-9][0-9] seconds' "$LOG" || die "timer 读数异常"
check 'Hello, world!'
check 'Running on UEFI 2.11'

if [[ -n "$EXPECT_LEN" ]]; then
	info "[5/5] 检查 EFI_LOAD_FILE2 initrd"
	check 'INITRD Dump'
	check "length: $EXPECT_LEN"
	check "crc32: $EXPECT_CRC"
	info "initrd length=$EXPECT_LEN crc32=$EXPECT_CRC"
else
	info "[5/5] 未设置 NEMU_UBOOT_INITRD，跳过 EFI_LOAD_FILE2 检查"
fi

info "PASS: $LOG"
