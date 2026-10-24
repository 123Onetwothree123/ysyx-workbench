#!/usr/bin/env bash
#
# 从用户态 checkpoint 恢复 NPC，向串口发送触发行，运行 fastfetch
# --logo debian，并抓取完整输出。
#
# 默认持久目录：/home/abc/ysyx-workbench/npc/checkpoints/npc-user
# 必需文件：cp-user.bin、npc-uboot-user.bin、npc-uboot-user.bin.info
# 日志：restore-fastfetch.log
#
set -euo pipefail
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NPC_HOME="${NPC_HOME:-$(cd -- "$HERE/.." && pwd)}"
DIR="${NPC_USER_DIR:-/home/abc/ysyx-workbench/npc/checkpoints/npc-user}"
CP="$DIR/cp-user.bin"
IMAGE="$DIR/npc-uboot-user.bin"
LOG="$DIR/restore-fastfetch.log"
NPC_BIN="${NPC_CP_BIN:-$NPC_HOME/build-cp/ysyx_26030103_npc}"
TRIGGER="${NPC_USER_TRIGGER:-go}"
DELAY="${NPC_TRIGGER_DELAY:-3}"
TIMEOUT="${NPC_RESTORE_TIMEOUT:-1800}"

die() { echo "错误：$*" >&2; exit 2; }
info() { echo "[restore-fastfetch] $*"; }

[[ -x "$NPC_BIN" ]] || die "缺少检查点仿真器：$NPC_BIN（先构建 VERILATOR_SAVABLE=y）"
[[ -f "$CP" ]] || die "缺少用户态检查点：$CP"
[[ -f "$IMAGE" ]] || die "缺少用户态镜像：$IMAGE"
if pgrep -f 'build-cp/ysyx_26030103_npc|build/ysyx_26030103_npc' >/dev/null 2>&1; then
	die "已有 NPC 实例在运行；请先清理"
fi

: >"$LOG"
info "恢复 $CP"
info "镜像 $IMAGE"
info "日志 $LOG"
set +e
(
	sleep "$DELAY"
	printf '%s\n' "$TRIGGER"
	sleep 1
) | timeout --foreground -k 5 "$TIMEOUT" "$NPC_BIN" "$IMAGE" --batch --restore "$CP" 2>&1 | tee "$LOG"
rc=${PIPESTATUS[1]}
set -e
if grep -qF 'FASTFETCH_INIT_END' "$LOG"; then
	info "PASS：fastfetch 完成（sim rc=$rc）"
	info "Debian 图标/信息见 $LOG"
	exit 0
fi
info "FAIL：未看到 FASTFETCH_INIT_END（sim rc=$rc），见 $LOG" >&2
exit 1
