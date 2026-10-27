#!/usr/bin/env bash
#
# Compatibility entry point for the C++ DTB generator.
#
# The configuration/ISA/MMU derivation, address validation and metadata output
# live in npc/tools/build_dtb.cpp.  This wrapper exists because NEMU and the
# SBI/Linux scripts invoke scripts/build_dtb.sh directly.
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NPC_HOME="${NPC_HOME:-$(cd -- "$HERE/.." && pwd)}"
WORKBENCH_HOME="${WORKBENCH_HOME:-$(cd -- "$NPC_HOME/.." && pwd)}"
export NPC_HOME WORKBENCH_HOME

TOOL="$NPC_HOME/tools/build/build_dtb"
if [[ ! -x "$TOOL" ]]; then
	make -C "$NPC_HOME" build-dtb-tool
fi

exec "$TOOL" "$@"
