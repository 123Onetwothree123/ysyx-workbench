#!/usr/bin/env bash
#
# NEMU Linux 平台冒烟测试：
#   1. 用 riscv64-linux_defconfig 构建 NEMU；
#   2. 生成 NEMU 专用 1MHz DTB，构建 OpenSBI FW_PAYLOAD + 测试 payload；
#   3. 在 NEMU 上跑到 S 模式，检查 FDT PASS / payload PASS；
#   4. 检查 DTB/OpenSBI/NEMU 三方 timebase 一致；
#   5. 用 dummy Image 只验证 pack_linux_image.sh 的布局/打包，
#      不运行、不冒充 Linux 启动。
#
# 本脚本不下载/编译/运行 Linux kernel。
#
# 用法：
#   nemu/tests/linux-sbi-smoke.sh
#   PACK_DUMMY=0 nemu/tests/linux-sbi-smoke.sh   # 跳过打包检查
#
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NEMU_HOME=$ROOT/nemu
AM_HOME=$ROOT/abstract-machine
TEST_OUT=$NEMU_HOME/build/linux/test
LOG=$TEST_OUT/nemu-sbi.log
TIMEOUT_SEC=${NEMU_LINUX_TIMEOUT:-60}

mkdir -p "$TEST_OUT"

die() {
	echo "FAIL: $*" >&2
	exit 1
}

info() {
	echo "[linux-sbi-smoke] $*"
}

info "[1/5] 构建 NEMU riscv64-linux_defconfig"
make -C "$NEMU_HOME" NEMU_HOME="$NEMU_HOME" ARCH=riscv64-nemu \
	AM_HOME="$AM_HOME" riscv64-linux_defconfig >/dev/null
make -C "$NEMU_HOME" NEMU_HOME="$NEMU_HOME" ARCH=riscv64-nemu \
	AM_HOME="$AM_HOME" -j"$(nproc)" >/dev/null
NEMU_BIN=$NEMU_HOME/build/riscv64-nemu-interpreter
[[ -x "$NEMU_BIN" ]] || die "NEMU 可执行文件不存在：$NEMU_BIN"

# 配置契约：缺一项都明确失败，避免悄悄换 timebase/内存布局。
check_config() {
	local key=$1
	grep -qxF "$key" "$NEMU_HOME/.config" || die "配置缺少 $key"
}
for key in \
	CONFIG_RV64=y \
	CONFIG_RISCV_A=y \
	CONFIG_RISCV_C=y \
	CONFIG_RISCV_F=y \
	CONFIG_RISCV_D=y \
	CONFIG_TARGET_NATIVE_ELF=y \
	CONFIG_DEVICE=y \
	CONFIG_HAS_RISCV_XV6=y \
	CONFIG_MBASE=0x80000000 \
	CONFIG_MSIZE=0x04000000 \
	CONFIG_RISCV_XV6_MTIME_FREQ=1000000 \
	CONFIG_SDB=y; do
	check_config "$key"
done
grep -qxF "# CONFIG_TARGET_SHARE is not set" "$NEMU_HOME/.config" || \
	die "NEMU Linux 必须是独立可执行文件（CONFIG_TARGET_SHARE=n）"
grep -qxF "# CONFIG_DIFFTEST is not set" "$NEMU_HOME/.config" || \
	die "NEMU Linux 不应启用 DiffTest"
info "配置契约通过"

info "[2/5] 构建 NEMU DTB + OpenSBI FW_PAYLOAD + 测试 payload"
NEMU_HOME="$NEMU_HOME" WORKBENCH_HOME="$ROOT" \
CONFIG_FILE="$NEMU_HOME/.config" \
NEMU_LINUX_OUT="$TEST_OUT/linux" \
NEMU_FW_MODE=payload \
	"$NEMU_HOME/scripts/build_linux_fw.sh" >"$TEST_OUT/build.log" 2>&1 || {
		tail -80 "$TEST_OUT/build.log" >&2
		die "固件构建失败"
	}

FW_BIN=$TEST_OUT/linux/opensbi/fw.bin
FW_INFO=$TEST_OUT/linux/opensbi/fw.info
DTB=$TEST_OUT/linux/dtb/nemu.dtb
[[ -f "$FW_BIN" && -f "$FW_INFO" && -f "$DTB" ]] || die "构建产物缺失"

grep -qxF "timebase_freq=1000000" "$FW_INFO" || die "OpenSBI fw.info timebase 不是 1MHz"
grep -qxF "fdt_sha256=$(sha256sum "$DTB" | cut -d' ' -f1)" "$FW_INFO" || \
	die "fw.info 内嵌 DTB 哈希与 nemu.dtb 不一致"

if command -v fdtget >/dev/null 2>&1; then
	ACTUAL_TIMEBASE=$(fdtget -t u "$DTB" /cpus timebase-frequency)
	[[ "$ACTUAL_TIMEBASE" == "1000000" ]] || \
		die "DTB timebase-frequency=$ACTUAL_TIMEBASE，期望 1000000"
fi
if command -v dtc >/dev/null 2>&1; then
	dtc -q -I dtb -O dts "$DTB" >"$TEST_OUT/nemu.dts"
	grep -Eq "timebase-frequency = <0x0?f4240>" "$TEST_OUT/nemu.dts" || \
		die "dtc 回读 DTB 的 timebase-frequency 不是 0xf4240 (1MHz)"
	grep -q "mmu-type = \"riscv,sv39\"" "$TEST_OUT/nemu.dts" || \
		die "DTB 未声明 riscv,sv39"
fi
info "DTB timebase/MMU 检查通过"

info "[3/5] 在 NEMU 上运行 OpenSBI + DTB + payload"
# Capstone 以相对路径 tools/capstone/... 加载，必须在 nemu/ 目录运行。
cd "$NEMU_HOME"
rc=0
timeout -k 5 "$TIMEOUT_SEC" "$NEMU_BIN" -b "$FW_BIN" </dev/null >"$LOG" 2>&1 || rc=$?
# payload 完成后在 wfi 循环，timeout=124 是预期；其他非零退出必须失败。
if [[ "$rc" != "0" && "$rc" != "124" ]]; then
	tail -80 "$LOG" >&2
	die "NEMU 异常退出：rc=$rc"
fi

grep -qF "OpenSBI v1.9" "$LOG" || die "没有 OpenSBI banner"
grep -Eq "Platform Timer Device[[:space:]]*: aclint-mtimer @ 1000000Hz" "$LOG" || \
	die "OpenSBI 定时器频率不是 1MHz"
grep -Eq "Boot HART Base ISA[[:space:]]*: rv64imafdc" "$LOG" || \
	die "OpenSBI 看到的 CPU ISA 不是 rv64imafdc"
grep -qF "SBI-NPC-PAYLOAD: entered S-mode via OpenSBI" "$LOG" || \
	die "没有进入 S 模式"
grep -qF "SBI-NPC-PAYLOAD: FDT PASS" "$LOG" || die "DTB 校验未输出 FDT PASS"
grep -qF "SBI-NPC-PAYLOAD: mtime(time CSR)=0x" "$LOG" || \
	die "payload 没有通过 time CSR 读取 mtime"
grep -qF "SBI-NPC-PAYLOAD: console ecall OK" "$LOG" || die "SBI console ecall 失败"
grep -qF "SBI-NPC-PAYLOAD: PASS" "$LOG" || die "payload 最终 PASS 未出现"
if grep -qF "SBI-NPC-PAYLOAD: FAIL" "$LOG"; then
	die "payload 输出 FAIL"
fi
info "S 模式/FDT/SBI 全部 PASS（NEMU exit=$rc，124=payload 在 wfi 循环）"

if [[ "${PACK_DUMMY:-1}" != "1" ]]; then
	info "[4/5] 跳过 dummy Image 打包"
else
	info "[4/5] dummy Image 只验证打包（不运行 Linux）"
	DUMMY=$TEST_OUT/dummy-Image
	python3 - "$DUMMY" <<'PY'
import struct
import sys

path = sys.argv[1]
size = 8192
blob = bytearray(size)
# RISC-V Linux Image header 的最小合法字段；payload 本身只是占位。
struct.pack_into("<Q", blob, 8, 0x200000)   # text_offset
struct.pack_into("<Q", blob, 16, size)      # image_size
blob[0x38:0x40] = b"RISCV\0\0\0"
blob[0x40:0x44] = b"RSC\x05"
blob[0x100:] = b"\x13\x00\x00\x00" * ((size - 0x100) // 4)
with open(path, "wb") as f:
    f.write(blob)
PY
	NEMU_HOME="$NEMU_HOME" WORKBENCH_HOME="$ROOT" \
	CONFIG_FILE="$NEMU_HOME/.config" \
	NEMU_IMAGE_PATH="$DUMMY" \
	NEMU_PACK_OUT="$TEST_OUT/pack" \
		"$NEMU_HOME/scripts/pack_linux_image.sh" >"$TEST_OUT/pack.log" 2>&1 || {
			tail -80 "$TEST_OUT/pack.log" >&2
			die "dummy Image 打包失败"
		}
	PACK_INFO=$TEST_OUT/pack/linux-image.bin.info
	[[ -f "$TEST_OUT/pack/linux-image.bin" && -f "$PACK_INFO" ]] || die "打包产物缺失"
	grep -qxF "image_addr=0x80200000" "$PACK_INFO" || die "打包 Image 地址不是 0x80200000"
	grep -qxF "dtb_addr=0x83f00000" "$PACK_INFO" || die "打包 DTB 地址不是 0x83f00000"
	grep -qxF "timebase_freq=1000000" "$PACK_INFO" || die "打包 timebase 不是 1MHz"
	grep -qxF "dtb_sha256=$(sha256sum "$DTB" | cut -d' ' -f1)" "$PACK_INFO" || \
		die "打包 DTB 与启动用 DTB 不一致"
	info "dummy Image 打包布局/DTB/timebase 检查通过（未运行）"
fi

info "[5/5] 完成"
echo
echo "== NEMU Linux SBI 测试摘要 =="
grep -aE "OpenSBI v|Platform Timer Device|Boot HART Base ISA|SBI-NPC-PAYLOAD: (entered|FDT PASS|mtime|PASS)" "$LOG" | tail -20
echo "日志：$LOG"
