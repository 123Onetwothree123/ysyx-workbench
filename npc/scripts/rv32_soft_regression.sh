#!/usr/bin/env bash
# RV32 回归（确认 RV64 改动没有破坏默认路径）：
#   * nanos_defconfig 的 RV32E direct-NPC；
#   * AM riscv32e-npc 裸机 hello（bin 与 ELF32 两条加载路径）；
#   * Nanos-lite RV32 + 最小 ELF32 用户程序。
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NPC="$ROOT/npc"
AM="$ROOT/abstract-machine"
NANOS="$ROOT/nanos-lite"
WORK="$ROOT/build/rv32-soft"
BIN="$NPC/build/ysyx_26030103_npc"
TOOL=${CROSS_COMPILE:-riscv64-unknown-elf-}

mkdir -p "$WORK" "$NANOS/build"

log_step() { printf '\n===== %s =====\n' "$*"; }
run_sdb() { printf 'ftrace off\nc\n' | timeout 120 "$BIN" "$@" 2>&1; }

log_step "1. 构建 RV32E direct-NPC（nanos_defconfig）"
cd "$NPC"
make nanos_defconfig >"$WORK/npc-config.log" 2>&1
make -j"$(nproc)" build/ysyx_26030103_npc >"$WORK/npc-build.log" 2>&1
test -x "$BIN"

log_step "2. abstract-machine riscv32e-npc 裸机 hello"
rm -rf "$WORK/am-hello"
cp -r "$NPC/csrc/Test/rv64-am-hello" "$WORK/am-hello"
cd "$WORK/am-hello"
AM_HOME="$AM" make ARCH=riscv32e-npc mainargs=rv32 -j"$(nproc)" \
  >"$WORK/am-hello-build.log" 2>&1
HELLO_BIN="$WORK/am-hello/build/rv64-am-hello-riscv32e-npc.bin"
HELLO_ELF="$WORK/am-hello/build/rv64-am-hello-riscv32e-npc.elf"
file "$HELLO_ELF" | grep -q 'ELF 32-bit'
run_sdb "$HELLO_BIN" >"$WORK/am-hello-bin-run.log"
grep -q 'AM hello: sizeof(uintptr_t)=4' "$WORK/am-hello-bin-run.log"
grep -q 'HIT GOOD TRAP' "$WORK/am-hello-bin-run.log"

log_step "3. C++ ImageLoader ELF32 路径"
run_sdb "$HELLO_ELF" >"$WORK/am-hello-elf-run.log"
grep -q 'ELF 镜像加载完成' "$WORK/am-hello-elf-run.log"
grep -q 'HIT GOOD TRAP' "$WORK/am-hello-elf-run.log"

log_step "4. Nanos-lite RV32E + 最小 ELF32 用户程序"
USER_DIR="$WORK/nanos-user"
rm -rf "$USER_DIR"
mkdir -p "$USER_DIR"
cp "$NPC/csrc/Test/rv64-nanos/user.S" "$NPC/csrc/Test/rv64-nanos/user.ld" "$USER_DIR/"
cd "$USER_DIR"
"${TOOL}gcc" -march=rv32e_zicsr -mabi=ilp32e -nostdlib -nostartfiles \
  -T user.ld -Wl,--no-relax -o user.elf user.S
file user.elf | grep -q 'ELF 32-bit'
cat user.elf user.elf >"$NANOS/build/ramdisk.img"
SZ=$(stat -c %s user.elf)
printf '// file path, file size, offset in disk\n{"/bin/pal", %s, 0},\n{"/bin/hello", %s, %s},\n' \
  "$SZ" "$SZ" "$SZ" >"$NANOS/src/files.h"
cp "$ROOT/navy-apps/libs/libos/src/syscall.h" "$NANOS/src/syscall.h"
cd "$NANOS"
AM_HOME="$AM" make ARCH=riscv32e-npc HAS_NAVY= -j"$(nproc)" \
  >"$WORK/nanos-build.log" 2>&1
NANOS_BIN="$NANOS/build/nanos-lite-riscv32e-npc.bin"
timeout 120 bash -c "printf 'ftrace off\nc\n' | '$BIN' '$NANOS_BIN'" \
  >"$WORK/nanos-run.log" 2>&1
grep -q "'Hello World!' from Nanos-lite" "$WORK/nanos-run.log"
grep -q 'user: hello from Nanos-lite ELF user program' "$WORK/nanos-run.log"
grep -q 'HIT GOOD TRAP' "$WORK/nanos-run.log"

printf '\nPASS: RV32 software regression. 日志目录: %s\n' "$WORK"
