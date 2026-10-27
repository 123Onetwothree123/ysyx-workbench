#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NPC_HOME="${NPC_HOME:-$(cd -- "$HERE/.." && pwd)}"
SBI_OUT_DIR="${SBI_OUT_DIR:-$NPC_HOME/build/rustsbi}"
SBI_NEXT_ADDR="${SBI_NEXT_ADDR:-0x80200000}"
SBI_FW_BASE="${SBI_FW_BASE:-0x80000000}"
SBI_ISA="${SBI_ISA:-rv64ima_zicsr_zifencei}"
SBI_ABI="${SBI_ABI:-lp64}"
SBI_FDT_ENABLE="${SBI_FDT_ENABLE:-n}"
SBI_FDT_ADDR="${SBI_FDT_ADDR:-0x80f00000}"
SBI_TOOLCHAIN_PREFIX="${SBI_TOOLCHAIN_PREFIX:-riscv64-unknown-elf-}"
NPC_BIN="${NPC_BIN:-$NPC_HOME/build/ysyx_26030103_npc}"
SBI_TEST_TIMEOUT="${SBI_TEST_TIMEOUT:-60}"

TEST_OUT="${SBI_TEST_OUT:-$SBI_OUT_DIR/test}"
PAYLOAD_OUT="$TEST_OUT/payload"
FW_OUT="$TEST_OUT/fw"
LOG="$TEST_OUT/run.log"

die() {
	echo "错误：$*" >&2
	exit 2
}

info() {
	echo "[rustsbi-test] $*"
}

[[ "${SBI_ENABLE:-n}" == "y" ]] || die "SBI_ENABLE=${SBI_ENABLE:-n}，请先运行 make rv64_sbi_defconfig"
[[ -x "$NPC_BIN" ]] || die \
	"NPC 仿真器不存在或不可执行：$NPC_BIN。请先构建 direct-NPC（需要 CONFIG_SDB=y 才能用 --batch）。"

mkdir -p "$TEST_OUT"

FDT_EXPECTED=0
if [[ "$SBI_FDT_ENABLE" == "y" ]]; then
	FDT_EXPECTED=1
fi

info "构建最小 S-mode payload（ADDR=$SBI_NEXT_ADDR）"
rm -f "$PAYLOAD_OUT/payload.elf" "$PAYLOAD_OUT/payload.bin"
make -C "$NPC_HOME/csrc/Test/sbi/opensbi_payload" \
	OUT="$PAYLOAD_OUT" \
	ADDR="$SBI_NEXT_ADDR" \
	ISA="$SBI_ISA" \
	ABI="$SBI_ABI" \
	CROSS_COMPILE="$SBI_TOOLCHAIN_PREFIX" \
	SBI_NAME=RustSBI \
	FDT_EXPECTED="$FDT_EXPECTED" \
	FDT_ADDR="$SBI_FDT_ADDR" \
	-j"$(nproc)"

info "构建 RustSBI 固件"
SBI_OUT_DIR="$FW_OUT" \
SBI_FDT_OUT_DIR="$TEST_OUT/fdt" \
RUSTSBI_OUT_DIR="$FW_OUT" \
	"$HERE/build_rustsbi.sh"

FW_BIN="$FW_OUT/rustsbi-npc.bin"
PAYLOAD_BIN="$PAYLOAD_OUT/payload.bin"
[[ -f "$FW_BIN" ]] || die "RustSBI 构建脚本未生成 $FW_BIN"
[[ -f "$PAYLOAD_BIN" ]] || die "payload 未生成 $PAYLOAD_BIN"

OFFSET=$((SBI_NEXT_ADDR - SBI_FW_BASE))
FW_BYTES="$(stat -c '%s' "$FW_BIN")"
if (( FW_BYTES > OFFSET )); then
	die "RustSBI 固件 $FW_BYTES 字节超过 next-stage 偏移 0x$(printf '%x' "$OFFSET")，会覆盖 payload"
fi

COMBINED="$TEST_OUT/combined.bin"
cp "$FW_BIN" "$COMBINED"
truncate -s "$OFFSET" "$COMBINED"
dd if="$PAYLOAD_BIN" of="$COMBINED" bs=1 seek="$OFFSET" conv=notrunc status=none

info "运行 $NPC_BIN（timeout ${SBI_TEST_TIMEOUT}s）"
rc=0
timeout -k 5 "$SBI_TEST_TIMEOUT" "$NPC_BIN" "$COMBINED" --batch >"$LOG" 2>&1 || rc=$?

cat "$LOG"

echo
echo "== RustSBI NPC 测试摘要 =="
echo "simulator exit: $rc（124 = 超时，payload 不返回属于预期）"

fail=0
if grep -qF "[rustsbi-npc] RustSBI" "$LOG"; then
	echo "PASS: RustSBI banner"
else
	echo "FAIL: 未看到 RustSBI banner"
	fail=1
fi
if grep -qF "SBI-NPC-PAYLOAD: entered S-mode via RustSBI" "$LOG"; then
	echo "PASS: 已进入 S 模式并执行 payload"
else
	echo "FAIL: 未看到 S 模式 payload 标记"
	fail=1
fi
if grep -qF "SBI-NPC-PAYLOAD: console ecall OK" "$LOG"; then
	echo "PASS: SBI console ecall"
else
	echo "FAIL: SBI console ecall 未成功"
	fail=1
fi
if [[ "$SBI_FDT_ENABLE" == "y" ]]; then
	if grep -qF "SBI-NPC-PAYLOAD: FDT PASS" "$LOG"; then
		echo "PASS: next-stage 收到并解析 DTB (a1=0x$(printf '%x' "$SBI_FDT_ADDR"))"
	else
		echo "FAIL: DTB 未正确传递给 next-stage 或节点校验失败"
		fail=1
	fi
else
	if grep -qF "SBI-NPC-PAYLOAD: FDT none (a1=0)" "$LOG"; then
		echo "PASS: SBI_FDT=n 时 a1=0（行为不变）"
	else
		echo "FAIL: SBI_FDT=n 但 payload 没有看到 a1=0"
		fail=1
	fi
fi
if grep -qF "SBI-NPC-PAYLOAD: PASS" "$LOG"; then
	echo "PASS: payload 完成"
else
	echo "FAIL: payload 未完成"
	fail=1
fi

if (( fail != 0 )); then
	echo "日志：$LOG" >&2
	exit 1
fi

echo "日志：$LOG"
