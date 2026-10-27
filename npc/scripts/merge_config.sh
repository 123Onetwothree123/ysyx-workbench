#!/usr/bin/env bash
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
