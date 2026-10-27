#!/usr/bin/env bash
#
# Compatibility entry point for the C++ profile merger.
#
# Profile resolution, fragment merging and the c_conf/kconfig_contract calls
# live in npc/tools/merge_config.cpp.
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NPC_HOME="${NPC_HOME:-$(cd -- "$HERE/.." && pwd)}"
WORKBENCH_HOME="${WORKBENCH_HOME:-$(cd -- "$NPC_HOME/.." && pwd)}"
export NPC_HOME WORKBENCH_HOME

TOOL="$NPC_HOME/tools/build/merge_config"
if [[ ! -x "$TOOL" ]]; then
	make -C "$NPC_HOME" merge-config-tool
fi

exec "$TOOL" "$@"
