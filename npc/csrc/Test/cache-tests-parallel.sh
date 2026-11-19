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
#
# 失败传播:
#   - 任一镜像构建失败、仿真器未触发 GOOD trap、触发 BAD trap、输出 FAIL，
#     或仿真器退出码非 0，本脚本最终返回非 0。
set -uo pipefail

: "${AM_HOME:?请先 source 工作区的 init.sh 以设置 AM_HOME}"

HERE=$(cd "$(dirname "$0")" && pwd)
NPC_HOME=$(cd "$HERE/../.." && pwd)
TESTS_DIR=bench
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

# 镜像/ARCH 跟随 Kconfig 的 CONFIG_XLEN，与单测 Makefile 保持一致。
ARCH=$(make -s -C "$HERE/$TESTS_DIR/cache-scan-test" xlen-show 2>/dev/null |
  sed -n 's/^XLEN_AM_ARCH=//p')
: "${ARCH:=riscv32e-npc}"
echo "ARCH=$ARCH"

echo "[1/3] 并行构建测试镜像 ..."
build_pids=()
build_names=()
for t in "${TESTS[@]}"; do
  ( cd "$HERE/$TESTS_DIR/$t" && make ARCH="$ARCH" insert-arg ) \
    >"$OUT/$t.build.log" 2>&1 &
  build_pids+=("$!")
  build_names+=("$t")
done

build_failed=0
for i in "${!build_pids[@]}"; do
  if ! wait "${build_pids[$i]}"; then
    echo "FAIL: ${build_names[$i]} 镜像构建失败（日志: $OUT/${build_names[$i]}.build.log）" >&2
    tail -n 20 "$OUT/${build_names[$i]}.build.log" >&2 || true
    build_failed=1
  fi
done
if [ "$build_failed" -ne 0 ]; then
  echo "构建失败，停止。" >&2
  exit 1
fi

echo "[2/3] 构建仿真器 (ICACHE_STORAGE=${ICACHE_STORAGE:-reg} DCACHE_STORAGE=${DCACHE_STORAGE:-reg}) ..."
if ! make -C "$NPC_HOME" "$SIM_TARGET" >"$OUT/simulator.build.log" 2>&1; then
  echo "FAIL: 仿真器构建失败（日志: $OUT/simulator.build.log）" >&2
  tail -n 20 "$OUT/simulator.build.log" >&2 || true
  exit 1
fi
if [ ! -x "$SIM" ]; then
  echo "FAIL: 仿真器不存在或不可执行: $SIM" >&2
  exit 1
fi

echo "[3/3] 并行运行 ..."
# 非交互终端下加 --batch，避免 SDB 在 stdin EOF 处直接退出而不执行程序。
BATCH_FLAG=""
if [ ! -t 0 ]; then
  BATCH_FLAG="--batch"
fi
run_pids=()
for t in "${TESTS[@]}"; do
  (
    cd "$HERE/$TESTS_DIR/$t"
    "$SIM" "build/$t-$ARCH.bin" --elf "build/$t-$ARCH.elf" $BATCH_FLAG \
      >"$OUT/$t.log" 2>&1
    echo $? >"$OUT/$t.status"
  ) &
  run_pids+=("$!")
done
for i in "${!run_pids[@]}"; do
  wait "${run_pids[$i]}" || true
done

overall=0
: >"$OUT/summary.txt"
for t in "${TESTS[@]}"; do
  status=$(cat "$OUT/$t.status" 2>/dev/null || echo 127)
  log="$OUT/$t.log"
  reason=""

  if [ "$status" -ne 0 ]; then
    reason="exit=$status"
  fi
  if grep -q "HIT BAD TRAP" "$log" 2>/dev/null; then
    reason="${reason:+$reason, }BAD TRAP"
  elif ! grep -q "HIT GOOD TRAP" "$log" 2>/dev/null; then
    reason="${reason:+$reason, }无GOOD TRAP"
  fi
  if grep -Eq "$t: FAIL" "$log" 2>/dev/null; then
    reason="${reason:+$reason, }FAIL标记"
  fi
  if ! grep -q "$t: PASS" "$log" 2>/dev/null; then
    reason="${reason:+$reason, }无PASS标记"
  fi

  if [ -z "$reason" ]; then
    printf '  %-20s OK\n' "$t" | tee -a "$OUT/summary.txt"
  else
    printf '  %-20s FAIL (%s)\n' "$t" "$reason" | tee -a "$OUT/summary.txt"
    overall=1
  fi
done

echo "结果目录: $OUT"
if [ "$overall" -ne 0 ]; then
  echo "有测试失败。" >&2
else
  echo "全部通过。"
fi
exit "$overall"
