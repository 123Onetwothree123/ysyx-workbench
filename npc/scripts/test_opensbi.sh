#!/usr/bin/env bash
#
# OpenSBI NPC 冒烟测试：
#   1. 构建最小 S 模式 payload（链接到 SBI_NEXT_ADDR）；
#   2. 以 FW_PAYLOAD 方式构建 OpenSBI（固件 + payload 放进一个 ELF）；
#   3. 用已构建的 direct-NPC 运行，检查 OpenSBI banner、SBI 控制台与
#      S-mode 入口标记。
#
# 前置条件：
#   - 已运行 make rv64_sbi_defconfig（SBI_ENABLE=y、SDB=y）；
#   - 已构建 npc/build/ysyx_26030103_npc。
#
# 用法：
#   make sbi-test                 # 或 make opensbi-test
#   NPC_BIN=/path/to/npc SBI_TEST_TIMEOUT=30 ./scripts/test_opensbi.sh
#
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NPC_HOME="${NPC_HOME:-$(cd -- "$HERE/.." && pwd)}"
SBI_OUT_DIR="${SBI_OUT_DIR:-$NPC_HOME/build/opensbi}"
SBI_NEXT_ADDR="${SBI_NEXT_ADDR:-0x80200000}"
SBI_ISA="${SBI_ISA:-rv64ima_zicsr_zifencei}"
SBI_ABI="${SBI_ABI:-lp64}"
SBI_FDT_ENABLE="${SBI_FDT_ENABLE:-n}"
SBI_FDT_ADDR="${SBI_FDT_ADDR:-0x80f00000}"
SBI_TOOLCHAIN_PREFIX="${SBI_TOOLCHAIN_PREFIX:-riscv64-unknown-elf-}"
NPC_BIN="${NPC_BIN:-$NPC_HOME/build/ysyx_26030103_npc}"
SBI_TEST_TIMEOUT="${SBI_TEST_TIMEOUT:-60}"

TEST_OUT="${SBI_TEST_OUT:-$SBI_OUT_DIR/test}"
PAYLOAD_OUT="$TEST_OUT/payload"
LOG="$TEST_OUT/run.log"

die() {
	echo "错误：$*" >&2
	exit 2
}

info() {
	echo "[opensbi-test] $*"
}

[[ "${SBI_ENABLE:-n}" == "y" ]] || die "SBI_ENABLE=${SBI_ENABLE:-n}，请先运行 make rv64_sbi_defconfig"
[[ -x "$NPC_BIN" ]] || die \
	"NPC 仿真器不存在或不可执行：$NPC_BIN。请先构建 direct-NPC（需要 CONFIG_SDB=y 才能用 --batch）。"

mkdir -p "$TEST_OUT"

info "构建最小 S-mode payload（ADDR=$SBI_NEXT_ADDR）"
# FDT_EXPECTED/FDT_ADDR 是命令行宏，make 不会因为宏变化而重建目标；
# 这里显式清掉旧产物，避免 FDT=n 复用上次的 FDT 断言 payload。
rm -f "$PAYLOAD_OUT/payload.elf" "$PAYLOAD_OUT/payload.bin"
FDT_EXPECTED=0
if [[ "$SBI_FDT_ENABLE" == "y" ]]; then
	FDT_EXPECTED=1
fi
make -C "$NPC_HOME/csrc/Test/sbi/opensbi_payload" \
	OUT="$PAYLOAD_OUT" \
	ADDR="$SBI_NEXT_ADDR" \
	ISA="$SBI_ISA" \
	ABI="$SBI_ABI" \
	CROSS_COMPILE="$SBI_TOOLCHAIN_PREFIX" \
	SBI_NAME=OpenSBI \
	FDT_EXPECTED="$FDT_EXPECTED" \
	FDT_ADDR="$SBI_FDT_ADDR" \
	NPC_RAM_SIZE="${NPC_RAM_SIZE:-0x04000000}" \
	-j"$(nproc)"

info "构建 OpenSBI FW_PAYLOAD"
SBI_OUT_DIR="$TEST_OUT" \
SBI_FDT_OUT_DIR="$TEST_OUT/fdt" \
SBI_PAYLOAD_PATH="$PAYLOAD_OUT/payload.bin" \
	"$HERE/build_opensbi.sh"

info "运行 $NPC_BIN（timeout ${SBI_TEST_TIMEOUT}s）"
# NPC ImageLoader 只接受 ET_EXEC，而 OpenSBI 输出的是 PIE(ET_DYN)；
# 因此这里加载原始 fw.bin（固件 + payload 已按 next-stage 偏移拼好）。
rc=0
timeout -k 5 "$SBI_TEST_TIMEOUT" "$NPC_BIN" "$TEST_OUT/fw.bin" --batch >"$LOG" 2>&1 || rc=$?

cat "$LOG"

echo
echo "== OpenSBI NPC 测试摘要 =="
echo "simulator exit: $rc（124 = 超时，payload 不返回属于预期）"

fail=0
if grep -q "OpenSBI" "$LOG"; then
	echo "PASS: OpenSBI banner"
else
	echo "FAIL: 未看到 OpenSBI banner"
	fail=1
fi
if grep -qF "SBI-NPC-PAYLOAD: entered S-mode via OpenSBI" "$LOG"; then
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
