#!/usr/bin/env bash
# direct-NPC Nanos-lite 冒烟验证。
#
# 前置条件：
#   * 当前目录是 ysyx-workbench 根目录；
#   * navy-apps/libs/libc 已存在（Navy 的 newlib 子仓库）；
#   * 已安装 riscv64-linux-gnu 工具链、mill、verilator。
#
# 说明：本脚本不会伪造结果。任何一步失败都会直接退出，并把日志留在
# build/nanos-verify/ 下。
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK="$ROOT/build/nanos-verify"
NPC="$ROOT/npc"
AM="$ROOT/abstract-machine"
NAVY="$ROOT/navy-apps"
NANOS="$ROOT/nanos-lite"

mkdir -p "$WORK"

# 1. NPC 配置：MMU/TLB + RV32M + ICache/DCache（与可启动的 direct-NPC 一致）。
cd "$NPC"
make nanos_defconfig >"$WORK/npc-config.log" 2>&1
make -j"$(nproc)" build/ysyx_26030103_npc >"$WORK/npc-build.log" 2>&1

# 2. 裸机 hello 冒烟。
rm -rf "$WORK/hello"
mkdir -p "$WORK/hello"
cp "$ROOT/am-kernels/kernels/hello/Makefile" "$ROOT/am-kernels/kernels/hello/hello.c" "$WORK/hello/"
cd "$WORK/hello"
AM_HOME="$AM" make ARCH=riscv32e-npc mainargs=nanos-smoke -j"$(nproc)" >"$WORK/hello-build.log" 2>&1
timeout 60 "$NPC/build/ysyx_26030103_npc" build/hello-riscv32e-npc.bin \
  --elf build/hello-riscv32e-npc.elf >"$WORK/hello-run.log" 2>&1
grep -q 'HIT GOOD TRAP' "$WORK/hello-run.log"

# 3. 两个会 exit 的最小 Navy 用户程序，分别装入 /bin/pal 和 /bin/hello。
#    Nanos-lite 固定加载这两个文件；两者都退出后调度器会 halt(0)。
mkdir -p "$WORK/user"
cat >"$WORK/user/Makefile" <<'EOF'
NAME = nanos-user
SRCS = nanos-user.c
include $(NAVY_HOME)/Makefile
EOF
cat >"$WORK/user/nanos-user.c" <<'EOF'
#include <stdio.h>
#include <unistd.h>
int main(void) {
  int pid = (int)getpid();
  printf("nanos-user: pid=%d hello from Navy-apps\n", pid);
  fflush(stdout);
  printf("nanos-user: pid=%d exiting\n", pid);
  fflush(stdout);
  return 0;
}
EOF
cd "$WORK/user"
NAVY_HOME="$NAVY" ISA=riscv32e VME=1 make -j"$(nproc)" >"$WORK/user-build.log" 2>&1

mkdir -p "$NANOS/build"
APP="$WORK/user/build/nanos-user-riscv32e"
cp "$APP" "$NANOS/build/user.elf"
cat "$APP" "$APP" >"$NANOS/build/ramdisk.img"
SZ=$(stat -c %s "$APP")
printf '// file path, file size, offset in disk\n{"/bin/pal", %s, 0},\n{"/bin/hello", %s, %s},\n' \
  "$SZ" "$SZ" "$SZ" >"$NANOS/build/ramdisk.h"
ln -sf "$NANOS/build/ramdisk.h" "$NANOS/src/files.h"
ln -sf "$NAVY/libs/libos/src/syscall.h" "$NANOS/src/syscall.h"

# 4. 构建并运行 Nanos-lite。
cd "$NANOS"
AM_HOME="$AM" NAVY_HOME="$NAVY" make ARCH=riscv32e-npc -j"$(nproc)" >"$WORK/nanos-build.log" 2>&1
timeout 180 "$NPC/build/ysyx_26030103_npc" \
  build/nanos-lite-riscv32e-npc.bin \
  --elf build/nanos-lite-riscv32e-npc.elf >"$WORK/nanos-run.log" 2>&1

grep -q 'Finish initialization' "$WORK/nanos-run.log"
grep -q 'nanos-user: pid=1' "$WORK/nanos-run.log"
grep -q 'nanos-user: pid=2' "$WORK/nanos-run.log"
grep -q 'HIT GOOD TRAP' "$WORK/nanos-run.log"

echo "PASS: see $WORK/nanos-run.log"
