#!/usr/bin/env bash
#
# Compatibility entry point for the C++ Linux-image packer.
#
# The Image header checks, initramfs format detection, DTB hash verification,
# RAM layout checks and binary assembly live in
# npc/tools/pack_linux_image.cpp.
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NPC_HOME="${NPC_HOME:-$(cd -- "$HERE/.." && pwd)}"
WORKBENCH_HOME="${WORKBENCH_HOME:-$(cd -- "$NPC_HOME/.." && pwd)}"
export NPC_HOME WORKBENCH_HOME

TOOL="$NPC_HOME/tools/build/pack_linux_image"
if [[ ! -x "$TOOL" ]]; then
	make -C "$NPC_HOME" pack-linux-tool
fi

exec "$TOOL" "$@"
