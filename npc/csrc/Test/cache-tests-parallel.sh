#!/bin/bash
# 并行运行 npc/csrc/Test 下的全部缓存测试，节省时间。
#
# 用法:
#   ./cache-tests-parallel.sh [结果目录]
#
# 环境变量(可选):
#   ICACHE_STORAGE / DCACHE_STORAGE   存储实现(reg=默认, sram)；传给elaboration
#   其余环境变量(如 CACHE_INDEX_BITS)原样透传给elaboration
#
# 流程:
#   1) 并行构建各测试镜像(make insert-arg)
#   2) 按当前存储配置构建一次仿真器(make ./build/ysyx_26030103_npc)
#   3) 直接以并行方式运行仿真器(不再逐个走 make run, 避免重复elaborate/重编)
set -eu

: "${AM_HOME:?请先 source 工作区的 init.sh 以设置 AM_HOME}"

HERE=$(cd "$(dirname "$0")" && pwd)
NPC_HOME=$(cd "$HERE/../.." && pwd)
OUT=${1:-$(mktemp -d /tmp/opencode/cache-tests-XXXXXX)}
TESTS=(
  cache-scan-test
  cache-thrash-test
  cache-randacc-test
  cache-matmul-test
  cache-memcpy-test
  cache-pointer-test
  cache-icache-test
)
mkdir -p "$OUT"
SIM_TARGET=./build/ysyx_26030103_npc
SIM="$NPC_HOME/build/ysyx_26030103_npc"

echo "[1/3] 并行构建测试镜像 ..."
for t in "${TESTS[@]}"; do
  ( cd "$HERE/$t" && make ARCH=riscv32e-npc insert-arg ) >"$OUT/$t.build.log" 2>&1 &
done
wait

echo "[2/3] 构建仿真器 (ICACHE_STORAGE=${ICACHE_STORAGE:-reg} DCACHE_STORAGE=${DCACHE_STORAGE:-reg}) ..."
make -C "$NPC_HOME" "$SIM_TARGET" >"$OUT/simulator.build.log" 2>&1
test -x "$SIM"

echo "[3/3] 并行运行 ..."
: >"$OUT/summary.txt"
for t in "${TESTS[@]}"; do
  (
    cd "$HERE/$t"
    "$SIM" "build/$t-riscv32e-npc.bin" --elf "build/$t-riscv32e-npc.elf" \
      >"$OUT/$t.log" 2>&1
    echo "$t exit=$?" >>"$OUT/summary.txt"
  ) &
done
wait

sort -o "$OUT/summary.txt" "$OUT/summary.txt"
echo "结果目录: $OUT"
for t in "${TESTS[@]}"; do
  trap=$(grep -m1 -E "HIT (GOOD|BAD) TRAP" "$OUT/$t.log" || true)
  printf '  %-20s %s\n' "$t" "${trap:-未运行/无trap}"
done
