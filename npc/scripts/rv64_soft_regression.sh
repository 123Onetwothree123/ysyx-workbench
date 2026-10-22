#!/usr/bin/env bash
# RV64 软件/工具链回归：
#   * RV64IM + Sv39 + ICache/DCache + TLB 的 direct-NPC；
#   * rv64-address-test / rv64-mmu-test 裸机镜像；
#   * 本地 RV64UI/RV64UM 定向子集（rv64-ui-um-test，不是外部 arch-test）；
#   * abstract-machine riscv64-npc 裸机 hello（bin 与 ELF64 两条加载路径）；
#   * SDB 64 位表达式/内存视图；
#   * Nanos-lite RV64 + 最小 ELF64 用户程序，跑到 HIT GOOD TRAP。
# 所有生成物放在 <repo>/build/rv64-soft/ 与 nanos-lite/build，不进入提交。
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NPC="$ROOT/npc"
AM="$ROOT/abstract-machine"
NANOS="$ROOT/nanos-lite"
WORK="$ROOT/build/rv64-soft"
BIN="$NPC/build/ysyx_26030103_npc"
TOOL=${CROSS_COMPILE:-riscv64-unknown-elf-}

mkdir -p "$WORK"

log_step() { printf '\n===== %s =====\n' "$*"; }
run_sdb() { # 非交互 SDB：关闭 ftrace 后 continue，避免观测日志与串口输出交错
  printf 'ftrace off\nc\n' | timeout 120 "$BIN" "$@" 2>&1
}

log_step "1. 构建 RV64 direct-NPC（rv64_soft_defconfig）"
cd "$NPC"
make rv64_soft_defconfig >"$WORK/npc-config.log" 2>&1
make -j"$(nproc)" build/ysyx_26030103_npc >"$WORK/npc-build.log" 2>&1
test -x "$BIN"

log_step "2. RV64 地址/Cache 裸机回归"
make -C "$NPC/csrc/Test/rv64-address-test" clean >/dev/null
make -C "$NPC/csrc/Test/rv64-address-test" main.bin >"$WORK/rv64-address-build.log" 2>&1
timeout 120 bash -c "printf 'c\n' | '$BIN' '$NPC/csrc/Test/rv64-address-test/main.bin'" \
  >"$WORK/rv64-address-run.log" 2>&1
grep -q 'HIT GOOD TRAP' "$WORK/rv64-address-run.log"

log_step "2b. 本地 RV64UI/RV64UM 定向小子集（非外部 arch-test）"
make -C "$NPC/csrc/Test/rv64-ui-um-test" clean >/dev/null
make -C "$NPC/csrc/Test/rv64-ui-um-test" main.bin >"$WORK/rv64-ui-um-build.log" 2>&1
timeout 120 bash -c "printf 'c\n' | '$BIN' '$NPC/csrc/Test/rv64-ui-um-test/main.bin'" \
  >"$WORK/rv64-ui-um-run.log" 2>&1
grep -q 'HIT GOOD TRAP' "$WORK/rv64-ui-um-run.log"

log_step "3. RV64 Sv39 MMU 裸机回归"
make -C "$NPC/csrc/Test/rv64-mmu-test" clean >/dev/null
make -C "$NPC/csrc/Test/rv64-mmu-test" main.bin >"$WORK/rv64-mmu-build.log" 2>&1
timeout 120 bash -c "printf 'c\n' | '$BIN' '$NPC/csrc/Test/rv64-mmu-test/main.bin'" \
  >"$WORK/rv64-mmu-run.log" 2>&1
grep -q 'HIT GOOD TRAP' "$WORK/rv64-mmu-run.log"

log_step "4. abstract-machine riscv64-npc 裸机 hello"
rm -rf "$WORK/am-hello"
cp -r "$NPC/csrc/Test/rv64-am-hello" "$WORK/am-hello"
cd "$WORK/am-hello"
AM_HOME="$AM" make ARCH=riscv64-npc mainargs=rv64 -j"$(nproc)" \
  >"$WORK/am-hello-build.log" 2>&1
HELLO_BIN="$WORK/am-hello/build/rv64-am-hello-riscv64-npc.bin"
HELLO_ELF="$WORK/am-hello/build/rv64-am-hello-riscv64-npc.elf"
file "$HELLO_ELF" | grep -q 'ELF 64-bit'
run_sdb "$HELLO_BIN" --elf "$HELLO_ELF" >"$WORK/am-hello-bin-run.log"
grep -q 'AM hello: sizeof(uintptr_t)=8' "$WORK/am-hello-bin-run.log"
grep -q 'HIT GOOD TRAP' "$WORK/am-hello-bin-run.log"

# ftrace：保持默认开启，用 ELF64 符号解析 call/ret。
printf 'c\n' | timeout 120 "$BIN" "$HELLO_BIN" --elf "$HELLO_ELF" \
  >"$WORK/am-hello-ftrace.log" 2>&1
grep -q 'call \[putch@' "$WORK/am-hello-ftrace.log"
grep -q 'HIT GOOD TRAP' "$WORK/am-hello-ftrace.log"

log_step "5. C++ ImageLoader ELF64 路径"
run_sdb "$HELLO_ELF" >"$WORK/am-hello-elf-run.log"
grep -q 'ELF 镜像加载完成' "$WORK/am-hello-elf-run.log"
grep -q 'HIT GOOD TRAP' "$WORK/am-hello-elf-run.log"

log_step "6. SDB 64 位表达式/内存读取"
printf 'ftrace off\np 0x100000000\np $pc\nx 2g 0x80000000\np read64(0x80000000)\nc\n' |
  timeout 120 "$BIN" "$HELLO_ELF" --elf "$HELLO_ELF" >"$WORK/sdb64.log" 2>&1
grep -q '无符号（十进制）：4294967296' "$WORK/sdb64.log"
grep -q '0x100000000' "$WORK/sdb64.log"
grep -q '0x80000000:' "$WORK/sdb64.log"
grep -q 'HIT GOOD TRAP' "$WORK/sdb64.log"

log_step "7. Nanos-lite RV64 + 最小 ELF64 用户程序"
mkdir -p "$NANOS/build"
USER_DIR="$WORK/nanos-user"
rm -rf "$USER_DIR"
mkdir -p "$USER_DIR"
cp "$NPC/csrc/Test/rv64-nanos/user.S" "$NPC/csrc/Test/rv64-nanos/user.ld" "$USER_DIR/"
cd "$USER_DIR"
"${TOOL}gcc" -march=rv64im -mabi=lp64 -nostdlib -nostartfiles \
  -T user.ld -Wl,--no-relax -o user.elf user.S
file user.elf | grep -q 'ELF 64-bit'
cat user.elf user.elf >"$NANOS/build/ramdisk.img"
SZ=$(stat -c %s user.elf)
printf '// file path, file size, offset in disk\n{"/bin/pal", %s, 0},\n{"/bin/hello", %s, %s},\n' \
  "$SZ" "$SZ" "$SZ" >"$NANOS/src/files.h"
cp "$ROOT/navy-apps/libs/libos/src/syscall.h" "$NANOS/src/syscall.h"
cd "$NANOS"
AM_HOME="$AM" make ARCH=riscv64-npc HAS_NAVY= -j"$(nproc)" \
  >"$WORK/nanos-build.log" 2>&1
NANOS_BIN="$NANOS/build/nanos-lite-riscv64-npc.bin"
timeout 120 bash -c "printf 'ftrace off\nc\n' | '$BIN' '$NANOS_BIN'" \
  >"$WORK/nanos-run.log" 2>&1
grep -q "'Hello World!' from Nanos-lite" "$WORK/nanos-run.log"
grep -q 'user: hello from Nanos-lite ELF user program' "$WORK/nanos-run.log"
grep -q 'HIT GOOD TRAP' "$WORK/nanos-run.log"

printf '\nPASS: RV64 software regression. 日志目录: %s\n' "$WORK"
