#!/usr/bin/env python3
"""Generate a deterministic JSON snapshot of the NPC Kconfig parameter system.

Usage (migration validation only after the schema switch):
    python3 npc/tools/ParamGen/gen_schema.py \
        --kconfig npc/Kconfig \
        --out /tmp/opencode/schema.json \
        --summary /tmp/opencode/schema-summary.json

``npc/params/schema/schema.json`` is now the authoritative parameter source.
``gen_schema.py`` is kept to prove that the switched ``npc/Kconfig`` wrapper
still parses and to compare semantic fields during migration.  Regenerating
into the authoritative path is blocked unless ``--allow-schema-overwrite`` is
passed, because doing so would replace the authority with a snapshot derived
from the generated files (``file``/``line``/``tier`` metadata would drift).

The snapshot is intentionally *generated* rather than hand-reviewed.  It
records what Kconfig currently says; it is not a whitelist and it does not
replace the existing Kconfig contract.

Schema version 2 additionally records everything ``gen_kconfig.py`` needs to
regenerate a Kconfig tree without reading the original files again:

* prompt/help text and prompt conditions,
* direct, local ("relative to the surrounding menu") and full dependencies,
* defaults/ranges/selects/implies with both full and local conditions,
* choice prompts/options/defaults/help/visibility,
* a deterministic menu/comment/symbol/choice layout tree and a flat menu list,
* parameter definition order and per-file order.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import tempfile
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

HERE = Path(__file__).resolve().parent
VENDOR = HERE / "vendor"
sys.path.insert(0, str(VENDOR))

try:
    import kconfiglib
except ImportError as error:  # pragma: no cover - vendored file should exist
    raise SystemExit(
        "kconfiglib is missing. Re-vendor it with:\n"
        "  cp vendor/opensbi/scripts/Kconfiglib/kconfiglib.py "
        "npc/tools/ParamGen/vendor/\n"
        "  cp vendor/opensbi/scripts/Kconfiglib/LICENSE.txt "
        "npc/tools/ParamGen/vendor/LICENSE.kconfiglib\n"
    ) from error

from paramlib import REPO_ROOT, write_json

TYPE_NAMES = kconfiglib.TYPE_TO_STR
SCHEMA_VERSION = 2

# Tier is a heuristic first guess, not an architecture decision.  The
# snapshot marks it as such so later human review can correct entries.
BUILD_PREFIXES = (
    "CC_",
    "CXX_",
    "KCONFIG_",
    "SIM_",
    "VERILATOR_",
    "GSIM_",
    "TRACE_",
    "LOG_",
    "PERF_",
    "SYNTH_",
    "PGO_",
    "MARCH_",
    "LD_",
    "LIB_",
    "STDLIB_",
    "USER_CXX_",
    "BUILD_",
    "PROF_",
    "X_INITIAL_",
    "X_ASSIGN_",
    "PDK",
)
BUILD_EXACT = {
    "ASSERT",
    "COVERAGE",
    "SDB",
    "DIFFTEST",
    "WATCHPOINT",
    "ITRACE",
    "MTRACE",
    "FTRACE",
    "IRINGBUF",
    "AUTOFLUSH",
    "SAVABLE",
    "OUTPUT_SPLIT",
}
# Historical Kconfig filenames.  After the switch, migration snapshots parse
# params/generated/kconfig/part-NN.Kconfig, so these file-based tier hints do
# not apply and the name-based heuristics below are used instead.  tier is not
# authoritative in either case.
BUILD_FILES = {
    "Kconfig.cpp",
    "Kconfig.debug",
    "Kconfig.log",
    "Kconfig.sim",
    "Kconfig.synth",
    "Kconfig.verilator",
}
PLATFORM_FILES = {
    "Kconfig.fpga",
    "Kconfig.linux",
    "Kconfig.rootfs",
    "Kconfig.sbi",
    "Kconfig.soc",
}

ARCH_EXACT = {
    "XLEN",
    "INTERNAL_BUS_WIDTH",
    "INTERNAL_BUS_WIDTH_FOLLOW_CPU",
    "ADDRESS_WIDTH",
}
ARCH_PREFIXES = (
    "RISCV_",
    "HAS_CSR_",
    "CSR_",
    "PMP_",
)
ARCH_MMU_PREFIXES = (
    "MMU_PROFILE_",
    "MMU_GEOMETRY_",
    "MMU_CUSTOM_",
    "MMU_PAGE_",
    "MMU_LEVELS",
    "MMU_PTE_",
    "MMU_SUPERPAGE",
    "MMU_AD_",
    "MMU_CACHE_",
)
MICROARCH_EXACT = {
    "ICACHE",
    "DCACHE",
}
MICROARCH_PREFIXES = (
    "FPU_",
    "MDU_",
    "ROCKET_",
    "MUL_",
    "DIV_",
    "ALU_",
    "IDU_",
    "CACHE_",
    "ICACHE_",
    "DCACHE_",
    "DMA_",
    "MISALIGNED_",
    "WBUF_",
    "BTB_",
    "JAL_BTB_",
    "RAS_",
    "LSU_",
    "ARBITER_",
    "AXI_ARB_",
    "AXI_XBAR_",
    "TLB_",
    "ITLB_",
    "DTLB_",
    "UTLB_",
    "PTW_",
    "MMU_PTW_",
    "MMU_TLB_",
    "MMU_ITLB_",
    "MMU_DTLB_",
    "MMU_UTLB_",
)
PLATFORM_PREFIXES = (
    "NPC_RAM_",
    "NPC_MODE_",
    "NPC_TARGET_",
    "SOC_",
    "AXI_",
    "APB_",
    "UART_",
    "PLIC_",
    "TIMER_",
    "PSRAM_",
    "SDRAM_",
    "SRAM_",
    "MROM_",
    "FLASH_",
    "GPIO_",
    "KEYBOARD_",
    "VGA_",
    "NVBOARD",
    "FPGA_",
    "SBI_",
    "LINUX_",
    "ROOTFS_",
    "BOOT_",
    "PMA_",
    "CHIPLINK",
)

REL_TO_OP = {
    kconfiglib.EQUAL: "eq",
    kconfiglib.UNEQUAL: "ne",
    kconfiglib.LESS: "lt",
    kconfiglib.LESS_EQUAL: "lte",
    kconfiglib.GREATER: "gt",
    kconfiglib.GREATER_EQUAL: "gte",
}


class GenerationIssueCollector:
    """Collect fields that cannot be regenerated faithfully.

    The list is deterministic: issues are appended in traversal order and
    sorted before writing, so the schema stays byte-stable.
    """

    def __init__(self) -> None:
        self._issues: List[Dict[str, str]] = []

    def add(self, kind: str, subject: str, detail: str) -> None:
        item = {"kind": kind, "subject": subject, "detail": detail}
        if item not in self._issues:
            self._issues.append(item)

    def items(self) -> List[Dict[str, str]]:
        return sorted(
            self._issues,
            key=lambda item: (item["kind"], item["subject"], item["detail"]),
        )


def repo_relative(path: str) -> str:
    candidate = Path(path)
    if not candidate.is_absolute():
        candidate = REPO_ROOT / "npc" / candidate
    try:
        return str(candidate.resolve().relative_to(REPO_ROOT))
    except ValueError:
        return str(candidate)


def guess_tier(name: str, filename: str) -> str:
    upper = name.upper()
    basename = Path(filename).name
    if basename in BUILD_FILES:
        return "build"
    if upper in BUILD_EXACT or upper.startswith(BUILD_PREFIXES):
        return "build"
    if basename == "Kconfig.mmu":
        if upper.startswith(MICROARCH_PREFIXES):
            return "microarch"
        return "arch"
    if upper in ARCH_EXACT or upper.startswith(ARCH_PREFIXES):
        return "arch"
    if upper.startswith("HAS_"):
        return "arch"
    if upper.startswith(ARCH_MMU_PREFIXES):
        return "arch"
    if upper in MICROARCH_EXACT or upper.startswith(MICROARCH_PREFIXES):
        return "microarch"
    if basename in PLATFORM_FILES:
        return "platform"
    if upper.startswith(PLATFORM_PREFIXES):
        return "platform"
    return "unknown"


def node_prompt(node: Any) -> Optional[str]:
    if node is None or not node.prompt:
        return None
    return node.prompt[0]


def node_help(node: Any) -> Optional[str]:
    return getattr(node, "help", None)


def value_json(value: Any) -> Dict[str, Any]:
    """Describe a Kconfig default/range bound without losing symbol refs."""
    if isinstance(value, kconfiglib.Symbol):
        if value.is_constant:
            return {"value": value.name, "kind": "constant"}
        return {"value": value.name, "kind": "symbol"}
    return {"value": str(value), "kind": "constant"}


def scalar_value(value: Any) -> Any:
    if isinstance(value, kconfiglib.Symbol):
        return value.name
    return str(value)


def expr_json(expr: Any, choice_names: Optional[Dict[int, str]] = None) -> Dict[str, Any]:
    if isinstance(expr, kconfiglib.Symbol):
        if expr.is_constant:
            return {"op": "const", "value": expr.name}
        return {"op": "symbol", "name": expr.name}
    if isinstance(expr, kconfiglib.Choice):
        group = None
        if choice_names is not None:
            group = choice_names.get(id(expr))
        return {"op": "choice", "name": group or expr.name or "<anonymous>"}
    if not isinstance(expr, tuple):
        return {"op": "raw", "text": str(expr)}
    op = expr[0]
    if op is kconfiglib.NOT and len(expr) == 2:
        return {"op": "not", "arg": expr_json(expr[1], choice_names)}
    if len(expr) != 3:
        return {"op": "raw", "text": kconfiglib.expr_str(expr)}
    if op in (kconfiglib.AND, kconfiglib.OR):
        return {
            "op": "and" if op is kconfiglib.AND else "or",
            "args": [
                expr_json(expr[1], choice_names),
                expr_json(expr[2], choice_names),
            ],
        }
    if op in REL_TO_OP:
        return {
            "op": REL_TO_OP[op],
            "left": expr_json(expr[1], choice_names),
            "right": expr_json(expr[2], choice_names),
        }
    return {"op": "raw", "text": kconfiglib.expr_str(expr)}


def expr_text(expr: Any, choice_names: Optional[Dict[int, str]] = None) -> str:
    if isinstance(expr, kconfiglib.Choice):
        group = None
        if choice_names is not None:
            group = choice_names.get(id(expr))
        return f"choice:{group or expr.name or '<anonymous>'}"
    if isinstance(expr, kconfiglib.Symbol) and expr.is_constant:
        return expr.name
    return kconfiglib.expr_str(expr)


def strip_dep(expr: Any, dep: Any, y: Any) -> Optional[Any]:
    """Remove a menu node's dependency from an expression.

    Mirrors ``MenuNode._strip_dep`` but returns ``None`` instead of silently
    returning an expression that still contains the dependency.  Kconfiglib
    reuses expression objects, so identity checks are exact.
    """
    if dep is y:
        return expr
    if expr is dep:
        return y
    if isinstance(expr, tuple) and expr[0] is kconfiglib.AND and expr[2] is dep:
        return expr[1]
    return None


def strip_choice(
    expr: Any, choice: Any, y: Any
) -> Optional[Any]:
    """Remove a choice reference from a choice option dependency."""
    if expr is choice:
        return y
    if isinstance(expr, tuple) and expr[0] is kconfiglib.AND:
        left = strip_choice(expr[1], choice, y)
        right = strip_choice(expr[2], choice, y)
        if left is None or right is None:
            return None
        if left is y:
            return right
        if right is y:
            return left
        return (kconfiglib.AND, left, right)
    if choice in kconfiglib.expr_items(expr):
        return None
    return expr


def local_dep(
    expr: Any,
    dep: Any,
    y: Any,
    issues: GenerationIssueCollector,
    subject: str,
    field: str,
) -> Any:
    stripped = strip_dep(expr, dep, y)
    if stripped is None:
        issues.add(
            "dependency-normalization",
            subject,
            f"{field}: could not isolate the surrounding dependency; "
            "using the full expression",
        )
        return expr
    return stripped


def choice_group_id(choice: Any, options: Sequence[Any]) -> str:
    if choice.name:
        return choice.name
    names = [option.name for option in options]
    if not names:
        return f"choice@{repo_relative(choice.nodes[0].filename)}:{choice.nodes[0].linenr}"
    prefix = os.path.commonprefix(names).rstrip("_")
    if prefix and re.fullmatch(r"[A-Z][A-Z0-9_]*", prefix):
        return prefix
    node = choice.nodes[0]
    return f"choice@{repo_relative(node.filename)}:{node.linenr}"


def choice_maps(kconf: Any) -> Tuple[Dict[int, str], Dict[str, List[str]]]:
    by_choice: Dict[int, str] = {}
    option_groups: Dict[str, List[str]] = {}
    used: Dict[str, int] = {}
    for choice in kconf.unique_choices:
        base = choice_group_id(choice, choice.syms)
        # Keep ids unique even if two choices share a common prefix.  Use an
        # ordinal rather than a source line number so the id is stable across
        # regeneration (generated Kconfig line numbers necessarily differ).
        used[base] = used.get(base, 0) + 1
        group = base if used[base] == 1 else f"{base}@{used[base]}"
        by_choice[id(choice)] = group
        for option in choice.syms:
            option_groups[option.name] = [group, group]
    return by_choice, option_groups


def build_layout(
    kconf: Any,
    choice_names: Dict[int, str],
    issues: GenerationIssueCollector,
) -> Dict[str, Any]:
    """Reconstruct the menu/comment/symbol/choice tree from MenuNodes.

    Ordinary Kconfig ``#`` comments are discarded by Kconfiglib and cannot be
    recovered.  ``comment`` statements are preserved as layout nodes.
    """
    mainmenu = node_prompt(kconf.top_node)
    meta: Dict[int, Dict[str, Any]] = {}
    menus: List[Dict[str, Any]] = []
    counter = {"order": 0}
    file_orders: Dict[str, int] = defaultdict(int)

    def next_order() -> int:
        counter["order"] += 1
        return counter["order"]

    def record(
        node: Any,
        menu_path: List[str],
        parent_dep: Any,
        local_override: Optional[Any] = None,
    ) -> Dict[str, Any]:
        filename = repo_relative(node.filename)
        file_orders[filename] += 1
        order = next_order()
        local = (
            local_override
            if local_override is not None
            else local_dep(
                node.dep,
                parent_dep,
                kconf.y,
                issues,
                _subject(node, choice_names),
                "depends on",
            )
        )
        info = {
            "order": order,
            "file": filename,
            "line": node.linenr,
            "menu_path": list(menu_path),
            "depends_on": expr_text(node.dep, choice_names),
            "depends_expr": expr_json(node.dep, choice_names),
            "depends_on_local": expr_text(local, choice_names),
            "depends_local_expr": expr_json(local, choice_names),
            "file_order": file_orders[filename],
        }
        meta[id(node)] = info
        return info

    def layout_node(
        node: Any, menu_path: List[str], parent_dep: Any, depth: int
    ) -> Dict[str, Any]:
        item = node.item
        if item is kconfiglib.MENU:
            prompt = node.prompt[0] if node.prompt else ""
            path = menu_path + [prompt]
            info = record(node, menu_path, parent_dep)
            visible_if = getattr(node, "visibility", kconf.y)
            entry = {
                "kind": "menu",
                "prompt": prompt,
                "depends_on": info["depends_on"],
                "depends_expr": info["depends_expr"],
                "depends_on_local": info["depends_on_local"],
                "depends_local_expr": info["depends_local_expr"],
                "visible_if": expr_text(visible_if, choice_names),
                "visible_if_expr": expr_json(visible_if, choice_names),
                "menu_path": path,
                "file": info["file"],
                "line": info["line"],
                "order": info["order"],
                "items": [],
            }
            menus.append(
                {
                    "prompt": prompt,
                    "path": path,
                    "depth": depth,
                    "depends_on": info["depends_on"],
                    "depends_expr": info["depends_expr"],
                    "depends_on_local": info["depends_on_local"],
                    "depends_local_expr": info["depends_local_expr"],
                    "visible_if": entry["visible_if"],
                    "visible_if_expr": entry["visible_if_expr"],
                    "file": info["file"],
                    "line": info["line"],
                    "order": info["order"],
                }
            )
            child = node.list
            while child:
                entry["items"].append(
                    layout_node(child, path, node.dep, depth + 1)
                )
                child = child.next
            return entry

        if item is kconfiglib.COMMENT:
            prompt = node.prompt[0] if node.prompt else ""
            info = record(node, menu_path, parent_dep)
            return {
                "kind": "comment",
                "prompt": prompt,
                "depends_on": info["depends_on"],
                "depends_expr": info["depends_expr"],
                "depends_on_local": info["depends_on_local"],
                "depends_local_expr": info["depends_local_expr"],
                "menu_path": info["menu_path"],
                "file": info["file"],
                "line": info["line"],
                "order": info["order"],
            }

        if isinstance(item, kconfiglib.Choice):
            group = choice_names[id(item)]
            info = record(node, menu_path, parent_dep)
            entry = {
                "kind": "choice",
                "id": group,
                "name": item.name,
                "options": [option.name for option in item.syms],
                "menu_path": info["menu_path"],
                "file": info["file"],
                "line": info["line"],
                "order": info["order"],
            }
            # Choice options are not emitted as separate layout children; the
            # choice entry owns them.  Record their order/path so parameter
            # entries remain complete.  The choice reference is implicit, so
            # the local dependency is the option's own extra condition.
            for option in item.syms:
                option_node = option.nodes[0]
                extra = strip_choice(option_node.dep, item, kconf.y)
                if extra is None:
                    issues.add(
                        "choice-dependency",
                        option.name,
                        "choice reference is nested in an unsupported expression",
                    )
                    extra = option_node.dep
                record(option_node, menu_path, node.dep, local_override=extra)
            return entry

        if isinstance(item, kconfiglib.Symbol):
            info = record(node, menu_path, parent_dep)
            entry = {
                "kind": "symbol",
                "name": item.name,
                "menu_path": info["menu_path"],
                "file": info["file"],
                "line": info["line"],
                "order": info["order"],
            }
            # Children of a symbol are implicit submenus created from
            # dependencies.  The generated Kconfig does not create a
            # syntactic container for them, so they keep the same parent
            # dependency as the symbol itself.
            child = node.list
            while child:
                entry.setdefault("items", []).append(
                    layout_node(child, menu_path, parent_dep, depth + 1)
                )
                child = child.next
            return entry

        issues.add(
            "layout-node",
            f"{repo_relative(node.filename)}:{node.linenr}",
            f"unsupported menu node item {item!r}",
        )
        return {
            "kind": "unknown",
            "file": repo_relative(node.filename),
            "line": node.linenr,
        }

    items: List[Dict[str, Any]] = []
    initial_path = [mainmenu] if mainmenu else []
    child = kconf.top_node.list
    while child:
        items.append(layout_node(child, initial_path, kconf.y, 1))
        child = child.next

    return {
        "mainmenu": mainmenu,
        "items": items,
        "menus": menus,
        "meta": meta,
    }


def _subject(node: Any, choice_names: Dict[int, str]) -> str:
    item = node.item
    if isinstance(item, kconfiglib.Symbol):
        return item.name
    if isinstance(item, kconfiglib.Choice):
        return choice_names.get(id(item), item.name or "<choice>")
    return f"{repo_relative(node.filename)}:{node.linenr}"


def symbol_entry(
    sym: Any,
    *,
    group: Optional[str] = None,
    choice_default: bool = False,
    choice_names: Optional[Dict[int, str]] = None,
    meta: Optional[Dict[str, Any]] = None,
    issues: Optional[GenerationIssueCollector] = None,
) -> Dict[str, Any]:
    issues = issues or GenerationIssueCollector()
    prompt_node = next((node for node in sym.nodes if node.prompt), sym.nodes[0])
    prompt = node_prompt(prompt_node)
    prompt_exists = prompt is not None
    kconfig_type = TYPE_NAMES.get(sym.orig_type, "unknown")
    public_type = "choice" if group else kconfig_type
    filename = repo_relative(prompt_node.filename)
    line = prompt_node.linenr
    visibility_value = getattr(sym, "visibility", 0)
    visible = bool(prompt_exists and visibility_value)
    if group and sym.choice is not None and not getattr(sym.choice, "visibility", 0):
        visible = False

    if len(sym.nodes) > 1:
        issues.add(
            "multiple-definition",
            sym.name,
            f"symbol is defined in {len(sym.nodes)} menu nodes; only the "
            "first prompt node's local conditions are recorded",
        )

    defaults = []
    for value, condition in sym.defaults:
        item = value_json(value)
        item["condition"] = expr_text(condition, choice_names)
        item["condition_expr"] = expr_json(condition, choice_names)
        local = local_dep(
            condition,
            prompt_node.dep,
            prompt_node.kconfig.y,
            issues,
            sym.name,
            "default condition",
        )
        item["condition_local"] = expr_text(local, choice_names)
        item["condition_local_expr"] = expr_json(local, choice_names)
        defaults.append(item)

    ranges = []
    for low, high, condition in sym.ranges:
        item = {
            "min": scalar_value(low),
            "min_kind": value_json(low)["kind"],
            "max": scalar_value(high),
            "max_kind": value_json(high)["kind"],
            "condition": expr_text(condition, choice_names),
            "condition_expr": expr_json(condition, choice_names),
        }
        local = local_dep(
            condition,
            prompt_node.dep,
            prompt_node.kconfig.y,
            issues,
            sym.name,
            "range condition",
        )
        item["condition_local"] = expr_text(local, choice_names)
        item["condition_local_expr"] = expr_json(local, choice_names)
        ranges.append(item)

    selects = []
    for target, condition in sym.selects:
        item = {"target": target.name}
        item["condition"] = expr_text(condition, choice_names)
        item["condition_expr"] = expr_json(condition, choice_names)
        local = local_dep(
            condition,
            prompt_node.dep,
            prompt_node.kconfig.y,
            issues,
            sym.name,
            "select condition",
        )
        item["condition_local"] = expr_text(local, choice_names)
        item["condition_local_expr"] = expr_json(local, choice_names)
        selects.append(item)

    implies = []
    for target, condition in sym.implies:
        item = {"target": target.name}
        item["condition"] = expr_text(condition, choice_names)
        item["condition_expr"] = expr_json(condition, choice_names)
        local = local_dep(
            condition,
            prompt_node.dep,
            prompt_node.kconfig.y,
            issues,
            sym.name,
            "imply condition",
        )
        item["condition_local"] = expr_text(local, choice_names)
        item["condition_local_expr"] = expr_json(local, choice_names)
        implies.append(item)

    prompt_condition = prompt_node.prompt[1] if prompt_node.prompt else None
    prompt_local = None
    if prompt_condition is not None:
        prompt_local = local_dep(
            prompt_condition,
            prompt_node.dep,
            prompt_node.kconfig.y,
            issues,
            sym.name,
            "prompt condition",
        )

    info = meta or {}
    first_default = defaults[0]["value"] if defaults else None
    range_min = ranges[0]["min"] if ranges else None
    range_max = ranges[0]["max"] if ranges else None
    dep_text = info.get("depends_on", expr_text(prompt_node.dep, choice_names))
    dep_local_text = info.get(
        "depends_on_local", expr_text(prompt_node.dep, choice_names)
    )
    dep_local_expr = info.get(
        "depends_local_expr", expr_json(prompt_node.dep, choice_names)
    )

    entry = {
        "name": sym.name,
        "type": public_type,
        "kconfig_type": kconfig_type,
        "prompt": prompt,
        "prompt_exists": prompt_exists,
        "help": node_help(prompt_node),
        "default": first_default,
        "defaults": defaults,
        "default_value": sym.str_value,
        "range": ranges,
        "range_min": range_min,
        "range_max": range_max,
        "depends_on": dep_text,
        "depends_expr": info.get(
            "depends_expr", expr_json(prompt_node.dep, choice_names)
        ),
        "depends_on_local": dep_local_text,
        "depends_local_expr": dep_local_expr,
        "prompt_condition": (
            expr_text(prompt_condition, choice_names)
            if prompt_condition is not None
            else None
        ),
        "prompt_condition_expr": (
            expr_json(prompt_condition, choice_names)
            if prompt_condition is not None
            else None
        ),
        "prompt_condition_local": (
            expr_text(prompt_local, choice_names)
            if prompt_local is not None
            else None
        ),
        "prompt_condition_local_expr": (
            expr_json(prompt_local, choice_names)
            if prompt_local is not None
            else None
        ),
        "selects": selects,
        "implies": implies,
        "file": filename,
        "line": line,
        "order": info.get("order"),
        "file_order": info.get("file_order"),
        "menu_path": info.get("menu_path", []),
        "visibility": "visible" if visible else "hidden",
        "tier": guess_tier(sym.name, filename),
        "derived": not prompt_exists,
        "derived_reason": "no_prompt" if not prompt_exists else None,
        "choice_group": group,
        "choice_option": bool(group),
        "choice_default": bool(choice_default),
        "is_menuconfig": bool(prompt_node.is_menuconfig),
        "node_count": len(sym.nodes),
    }
    return entry


def choice_entry(
    choice: Any,
    *,
    group: str,
    choice_names: Dict[int, str],
    meta: Optional[Dict[str, Any]] = None,
    issues: Optional[GenerationIssueCollector] = None,
) -> Dict[str, Any]:
    issues = issues or GenerationIssueCollector()
    prompt_node = next((node for node in choice.nodes if node.prompt), choice.nodes[0])
    prompt = node_prompt(prompt_node)
    filename = repo_relative(prompt_node.filename)
    options = sorted(option.name for option in choice.syms)
    selected = getattr(choice, "selection", None)
    defaults = []
    for value, condition in choice.defaults:
        item = value_json(value)
        item["condition"] = expr_text(condition, choice_names)
        item["condition_expr"] = expr_json(condition, choice_names)
        local = local_dep(
            condition,
            prompt_node.dep,
            prompt_node.kconfig.y,
            issues,
            group,
            "choice default condition",
        )
        item["condition_local"] = expr_text(local, choice_names)
        item["condition_local_expr"] = expr_json(local, choice_names)
        defaults.append(item)
    if len(choice.nodes) > 1:
        issues.add(
            "multiple-definition",
            group,
            f"choice is defined in {len(choice.nodes)} menu nodes; only the "
            "first prompt node's local conditions are recorded",
        )
    visible = bool(prompt and getattr(choice, "visibility", 0))
    tier = "unknown"
    if choice.syms:
        tier = guess_tier(choice.syms[0].name, filename)
    info = meta or {}
    dep_local_text = info.get(
        "depends_on_local", expr_text(prompt_node.dep, choice_names)
    )
    dep_local_expr = info.get(
        "depends_local_expr", expr_json(prompt_node.dep, choice_names)
    )
    prompt_condition = prompt_node.prompt[1] if prompt_node.prompt else None
    prompt_local = None
    if prompt_condition is not None:
        prompt_local = local_dep(
            prompt_condition,
            prompt_node.dep,
            prompt_node.kconfig.y,
            issues,
            group,
            "choice prompt condition",
        )
    visible_if = getattr(prompt_node, "visibility", prompt_node.kconfig.y)
    return {
        "id": group,
        "name": choice.name,
        "type": "choice",
        "prompt": prompt,
        "prompt_exists": prompt is not None,
        "help": node_help(prompt_node),
        "options": options,
        "options_in_order": [option.name for option in choice.syms],
        "default_option": selected.name if selected is not None else None,
        "defaults": defaults,
        "depends_on": info.get(
            "depends_on", expr_text(prompt_node.dep, choice_names)
        ),
        "depends_expr": info.get(
            "depends_expr", expr_json(prompt_node.dep, choice_names)
        ),
        "depends_on_local": dep_local_text,
        "depends_local_expr": dep_local_expr,
        "prompt_condition": (
            expr_text(prompt_condition, choice_names)
            if prompt_condition is not None
            else None
        ),
        "prompt_condition_expr": (
            expr_json(prompt_condition, choice_names)
            if prompt_condition is not None
            else None
        ),
        "prompt_condition_local": (
            expr_text(prompt_local, choice_names)
            if prompt_local is not None
            else None
        ),
        "prompt_condition_local_expr": (
            expr_json(prompt_local, choice_names)
            if prompt_local is not None
            else None
        ),
        "visible_if": expr_text(visible_if, choice_names),
        "visible_if_expr": expr_json(visible_if, choice_names),
        "file": filename,
        "line": prompt_node.linenr,
        "order": info.get("order"),
        "file_order": info.get("file_order"),
        "menu_path": info.get("menu_path", []),
        "visibility": "visible" if visible else "hidden",
        "tier": tier,
        "derived": prompt is None,
        "node_count": len(choice.nodes),
    }


def build_schema(kconfig_path: Path) -> Dict[str, Any]:
    npc_dir = kconfig_path.resolve().parent
    os.environ["srctree"] = str(npc_dir)
    # Loading .config would make the snapshot depend on the user's current
    # configuration.  Start from an empty config so defaults/visibility are
    # reproducible everywhere.
    kconf = kconfiglib.Kconfig(
        str(kconfig_path.relative_to(npc_dir)),
        warn=False,
        warn_to_stderr=False,
    )
    with tempfile.NamedTemporaryFile("w", delete=False) as handle:
        empty_config = handle.name
    try:
        kconf.load_config(empty_config)
    finally:
        try:
            os.unlink(empty_config)
        except OSError:
            pass

    issues = GenerationIssueCollector()
    choice_names, option_groups = choice_maps(kconf)
    layout_data = build_layout(kconf, choice_names, issues)
    meta = layout_data.pop("meta")
    menus = layout_data.pop("menus")
    parameters = []
    choices = []
    for sym in kconf.unique_defined_syms:
        if sym.name in ("y", "n", "m") or sym.is_constant:
            continue
        group = option_groups.get(sym.name, [None])[0]
        choice_default = False
        if group and sym.choice is not None:
            selected = getattr(sym.choice, "selection", None)
            choice_default = selected is sym
        prompt_node = next((node for node in sym.nodes if node.prompt), sym.nodes[0])
        parameters.append(
            symbol_entry(
                sym,
                group=group,
                choice_default=choice_default,
                choice_names=choice_names,
                meta=meta.get(id(prompt_node)),
                issues=issues,
            )
        )
    for choice in kconf.unique_choices:
        group = choice_names[id(choice)]
        prompt_node = next(
            (node for node in choice.nodes if node.prompt), choice.nodes[0]
        )
        choices.append(
            choice_entry(
                choice,
                group=group,
                choice_names=choice_names,
                meta=meta.get(id(prompt_node)),
                issues=issues,
            )
        )

    parameters.sort(key=lambda entry: entry["name"])
    choices.sort(key=lambda entry: entry["id"])

    kconfig_files = sorted({repo_relative(name) for name in kconf.kconfig_filenames})
    schema = {
        "generated_snapshot": True,
        "schema_version": SCHEMA_VERSION,
        "generator": {
            "name": "npc/tools/ParamGen/gen_schema.py",
            "version": 2,
            "kconfiglib_version": ".".join(str(part) for part in kconfiglib.VERSION),
            "kconfiglib_license": "ISC",
        },
        "source": {
            "kconfig": repo_relative(str(kconfig_path)),
            "source_root": "npc",
            "kconfig_files": kconfig_files,
            "visibility_model": (
                "prompt exists and symbol/choice visibility is non-zero under "
                "an empty default configuration"
            ),
            "tier_model": "heuristic name/file mapping, not human-reviewed",
            "layout_model": (
                "menu/comment/symbol/choice tree reconstructed from Kconfiglib "
                "MenuNode objects; ordinary '#' comments and raw source "
                "formatting are not recoverable"
            ),
            "generation_model": (
                "gen_kconfig.py consumes layout, parameters and choices only; "
                "local dependency/condition fields are relative to the "
                "surrounding menu or choice block"
            ),
        },
        "layout": layout_data,
        "menus": menus,
        "parameters": parameters,
        "choices": choices,
        "generation_issues": issues.items(),
    }
    schema["summary"] = summarize(schema)
    return schema


def summarize(schema: Dict[str, Any]) -> Dict[str, Any]:
    parameters = schema["parameters"]
    choices = schema["choices"]
    by_type = Counter(entry["type"] for entry in parameters)
    by_kconfig_type = Counter(entry["kconfig_type"] for entry in parameters)
    by_tier = Counter(entry["tier"] for entry in parameters)
    by_visibility = Counter(entry["visibility"] for entry in parameters)
    files = Counter(entry["file"] for entry in parameters)
    return {
        "generated_snapshot": True,
        "schema_version": schema["schema_version"],
        "parameter_count": len(parameters),
        "prompt_count": sum(1 for entry in parameters if entry["prompt_exists"]),
        "help_count": sum(1 for entry in parameters if entry["help"] is not None),
        "choice_help_count": sum(1 for choice in choices if choice["help"] is not None),
        "visible_count": by_visibility.get("visible", 0),
        "hidden_count": by_visibility.get("hidden", 0),
        "derived_count": sum(1 for entry in parameters if entry["derived"]),
        "hidden_with_prompt_count": sum(
            1
            for entry in parameters
            if entry["prompt_exists"] and entry["visibility"] == "hidden"
        ),
        "choice_group_count": len(choices),
        "choice_option_count": sum(len(choice["options"]) for choice in choices),
        "menu_count": len(schema["menus"]),
        "comment_count": sum(
            1 for item in _walk_layout(schema["layout"]["items"]) if item["kind"] == "comment"
        ),
        "select_count": sum(len(entry["selects"]) for entry in parameters),
        "imply_count": sum(len(entry["implies"]) for entry in parameters),
        "multi_node_symbol_count": sum(
            1 for entry in parameters if entry["node_count"] > 1
        ),
        "generation_issue_count": len(schema["generation_issues"]),
        "by_type": dict(sorted(by_type.items())),
        "by_kconfig_type": dict(sorted(by_kconfig_type.items())),
        "by_tier": dict(sorted(by_tier.items())),
        "by_visibility": dict(sorted(by_visibility.items())),
        "files": dict(sorted(files.items())),
    }


def _walk_layout(items: Iterable[Dict[str, Any]]) -> Iterable[Dict[str, Any]]:
    for item in items:
        yield item
        if "items" in item:
            yield from _walk_layout(item["items"])


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--kconfig", default=str(REPO_ROOT / "npc/Kconfig"), help="top-level Kconfig"
    )
    parser.add_argument(
        "--out",
        default=str(REPO_ROOT / "npc/params/schema/schema.json"),
        help="schema snapshot output",
    )
    parser.add_argument(
        "--summary",
        default=str(REPO_ROOT / "npc/params/schema/schema-summary.json"),
        help="summary snapshot output",
    )
    parser.add_argument(
        "--allow-schema-overwrite",
        action="store_true",
        help=(
            "allow writing the authoritative schema.json; schema is now the "
            "source of truth, so only use this for an explicit re-migration"
        ),
    )
    args = parser.parse_args(argv)
    authoritative = (REPO_ROOT / "npc/params/schema/schema.json").resolve()
    if Path(args.out).resolve() == authoritative and not args.allow_schema_overwrite:
        parser.error(
            "refusing to overwrite authoritative npc/params/schema/schema.json; "
            "write migration snapshots to a temporary --out path, or pass "
            "--allow-schema-overwrite for an intentional re-migration"
        )

    kconfig_path = Path(args.kconfig)
    if not kconfig_path.is_absolute():
        kconfig_path = (REPO_ROOT / kconfig_path).resolve()
    if not kconfig_path.exists():
        parser.error(f"Kconfig not found: {kconfig_path}")

    schema = build_schema(kconfig_path)
    summary = schema.pop("summary")
    write_json(args.out, schema)
    write_json(args.summary, summary)
    print(
        f"[param-schema] {summary['parameter_count']} parameters, "
        f"{summary['choice_group_count']} choice groups, "
        f"{summary['menu_count']} menus, "
        f"{summary['visible_count']} visible / {summary['hidden_count']} hidden"
    )
    if summary["generation_issue_count"]:
        print(
            f"[param-schema] WARNING {summary['generation_issue_count']} "
            "generation issue(s); see schema.json:generation_issues",
            file=sys.stderr,
        )
    print(f"[param-schema] wrote {args.out}")
    print(f"[param-schema] wrote {args.summary}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
