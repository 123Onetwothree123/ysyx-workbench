#!/usr/bin/env bash
# 用 CONFIG_* 命令行覆盖生成独立的 typed JSON，再用它跑 elaboration。
# 用法:
#   elaborate_config.sh <test_dir> <target_dir> [CONFIG_X=... ...]
set -euo pipefail

TEST_DIR=${1:?test_dir}
TARGET_DIR=${2:?target_dir}
shift 2

HERE=$(cd "$(dirname "$0")" && pwd)
NPC=$(cd "$HERE/../../.." && pwd)
BUILD=${TEST_DIR}/build/npc-config
mkdir -p "$BUILD" "$TARGET_DIR"

make -C "$NPC" BUILD_DIR="$BUILD" \
  NPC_CONFIG_MODULE="$BUILD/generated/npc_config.ixx" \
  NPC_CONFIG_SCALA_DEFAULTS="$BUILD/generated/ConfigDefaults.scala" \
  "$@" npc-config >/dev/null
JSON="$BUILD/generated/npc_config.typed.json"
test -f "$JSON" || { echo "missing $JSON" >&2; exit 1; }

cd "$NPC"
NPC_CONFIG_JSON="$JSON" mill -i runMain ysyx_26030103_Elaborate \
  --target-dir "$TARGET_DIR"
