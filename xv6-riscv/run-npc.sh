#!/usr/bin/env bash
#
# Build xv6-riscv and boot it on the direct-NPC simulator.
#
# Usage:
#   ./run-npc.sh                 # build kernel, run with 240 s timeout
#   BUILD_NPC=1 ./run-npc.sh     # also regenerate the NPC RTL/binary
#   NPC=/path/to/ysyx_26030103_npc ./run-npc.sh
#   TIMEOUT=600 LOG=/tmp/xv6.log ./run-npc.sh
#
# The simulator is run in --batch mode (requires CONFIG_SDB=y) and killed by
# timeout.  UART is TX-only on this platform, so no shell input is attempted.
#
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$HERE/.." && pwd)"
TOOLPREFIX="${TOOLPREFIX:-riscv64-unknown-elf-}"
NPC="${NPC:-$ROOT/npc/build/ysyx_26030103_npc}"
TIMEOUT="${TIMEOUT:-300}"
LOG="${LOG:-$HERE/npc-xv6.log}"

if [ "${BUILD_NPC:-0}" = "1" ]; then
  echo "== building NPC (rv64_xv6_defconfig) =="
  make -C "$ROOT/npc" rv64_xv6_defconfig
  make -C "$ROOT/npc" -j"$(nproc)" build/ysyx_26030103_npc
fi

if [ ! -x "$NPC" ]; then
  cat >&2 <<EOF
error: NPC simulator not found/executable: $NPC

Build it first:
  make -C $ROOT/npc rv64_xv6_defconfig
  make -C $ROOT/npc -j\$(nproc) build/ysyx_26030103_npc

or point NPC at an existing build.
EOF
  exit 2
fi

echo "== building xv6 (PLATFORM=npc) =="
make -C "$HERE" -j"$(nproc)" PLATFORM=npc TOOLPREFIX="$TOOLPREFIX" kernel/kernel

echo "== running $NPC (timeout ${TIMEOUT}s, log $LOG) =="
rc=0
timeout -k 5 "$TIMEOUT" "$NPC" "$HERE/kernel/kernel" --batch >"$LOG" 2>&1 || rc=$?

cat "$LOG"

echo
echo "== summary =="
echo "simulator exit: $rc (124 = timeout, expected for a non-halting guest)"
if grep -qF "xv6 kernel is booting" "$LOG"; then
  echo "PASS: xv6 kernel is booting"
else
  echo "FAIL: 'xv6 kernel is booting' not found"
  exit 1
fi
if grep -qF "[npc] /init loaded, entering user mode" "$LOG"; then
  echo "PASS: /init loaded, user mode entered"
else
  echo "PARTIAL: /init did not reach user mode"
fi
if grep -qF "init: starting sh" "$LOG"; then
  echo "PASS: init: starting sh"
else
  echo "BLOCKED: 'init: starting sh' not reached (see XV6_NPC_BOOT.md)"
fi
exit 0
