#!/usr/bin/env python3
"""Cross-check Kconfig schema defaults against the hand-written make defaults.

``gen_kconfig.py`` generates both the Kconfig tree and the shared C++
``npc_config_defaults.h`` from ``npc/params/schema``.  A few defaults are also
spelled out in make because they must exist before a config has been loaded
(``npc/Makefile`` and ``npc/scripts/platform.mk``).  This checker keeps those
last copies honest:

* resolve each schema default under the conditions that make it applicable;
* find the matching variable assignment in the make file;
* require every expected literal to appear on that line.

It is invoked from the existing ``make kconfig-contract-check`` target, so it
does not add a new user-facing make option.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Set

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import paramlib as p

BASE_FLAGS: Dict[str, Any] = {
    "HAS_S_EXTERNAL_IRQ": True,
    "NPC_MODE_FPGA_ONLY": False,
    "SOC_MEMORY_MAP_LINUX": False,
    "SBI_FDT_ENABLE": True,
    "LINUX_INITRAMFS_ENABLE": True,
    "NPC_UBOOT_ENABLE": True,
}


def parse_int(text: Any) -> Optional[int]:
    try:
        return int(str(text), 0)
    except (TypeError, ValueError):
        return None


def eval_condition(expr: Any, symbols: Dict[str, Any]) -> bool:
    if not isinstance(expr, dict):
        raise ValueError(f"unsupported condition: {expr!r}")
    op = expr.get("op")
    if op == "const":
        value = str(expr.get("value"))
        if value in ("y", "true"):
            return True
        if value in ("n", "false"):
            return False
        literal = parse_int(value)
        if literal is not None:
            return literal != 0
        raise ValueError(f"unsupported const condition: {value!r}")
    if op == "symbol":
        name = str(expr.get("name"))
        literal = parse_int(name)
        if literal is not None:
            return literal != 0
        return bool(symbols.get(name, False))
    if op == "not":
        return not eval_condition(expr.get("arg"), symbols)
    if op == "and":
        return all(eval_condition(arg, symbols) for arg in expr.get("args", []))
    if op == "or":
        return any(eval_condition(arg, symbols) for arg in expr.get("args", []))
    if op == "eq":
        return operand_value(expr.get("left"), symbols) == operand_value(
            expr.get("right"), symbols
        )
    raise ValueError(f"unsupported condition op: {op!r}")


def operand_value(expr: Any, symbols: Dict[str, Any]) -> Any:
    if not isinstance(expr, dict):
        raise ValueError(f"unsupported operand: {expr!r}")
    op = expr.get("op")
    if op == "const":
        value = str(expr.get("value"))
        literal = parse_int(value)
        return literal if literal is not None else value
    if op == "symbol":
        name = str(expr.get("name"))
        literal = parse_int(name)
        return literal if literal is not None else symbols.get(name, 0)
    return eval_condition(expr, symbols)


def resolve_default(
    parameters: Dict[str, Dict[str, Any]], name: str, symbols: Dict[str, Any]
) -> Any:
    entry = parameters.get(name)
    if entry is None:
        raise ValueError(f"unknown schema parameter: {name}")
    for item in entry.get("defaults", []):
        if eval_condition(item.get("condition_expr"), symbols):
            return resolve_value(parameters, item, symbols)
    fallback = entry.get("default")
    if fallback is not None:
        return resolve_value(
            parameters, {"value": fallback, "kind": "symbol"}, symbols
        )
    raise ValueError(f"{name}: no default applies under {symbols}")


def resolve_value(
    parameters: Dict[str, Dict[str, Any]],
    item: Dict[str, Any],
    symbols: Dict[str, Any],
) -> Any:
    value = str(item.get("value"))
    kind = str(item.get("kind", "constant"))
    if kind == "symbol":
        literal = parse_int(value)
        if literal is not None:
            return literal
        if value in ("y", "n"):
            return value == "y"
        if value in parameters:
            return resolve_default(parameters, value, symbols)
        return symbols.get(value, 0)
    literal = parse_int(value)
    if literal is not None:
        return literal
    return value


def hex_literals(line: str) -> Set[int]:
    return {int(token, 16) for token in re.findall(r"0x[0-9A-Fa-f]+", line)}


def assignment_lines(path: Path, name: str) -> Optional[List[str]]:
    pattern = re.compile(
        rf"^\s*(?:override\s+)?{re.escape(name)}\s*(?:\?=|:=|=)"
    )
    lines = [
        line
        for line in path.read_text(encoding="utf-8").splitlines()
        if pattern.search(line)
    ]
    return lines or None


def find_assignment_literals(path: Path, name: str) -> Optional[Set[int]]:
    lines = assignment_lines(path, name)
    if lines is None:
        return None
    found: Set[int] = set()
    for line in lines:
        found |= hex_literals(line)
    return found


class Checker:
    def __init__(self, npc_dir: Path, schema: Dict[str, Any]) -> None:
        self.npc_dir = npc_dir
        self.parameters = {
            str(entry.get("name")): entry
            for entry in schema.get("parameters", [])
        }
        self.checks = 0
        self.errors: List[str] = []

    def expect(
        self, path: Path, variable: str, expected: Iterable[int], note: str = ""
    ) -> None:
        expected_values = {int(value) for value in expected}
        literals = find_assignment_literals(path, variable)
        self.checks += 1
        relative = path.relative_to(self.npc_dir.parent)
        if literals is None:
            self.errors.append(
                f"{relative}: 找不到 {variable} 的默认值赋值"
                + (f"（{note}）" if note else "")
            )
            return
        missing = sorted(
            value for value in expected_values if value not in literals
        )
        if missing:
            rendered = ", ".join(f"0x{value:x}" for value in missing)
            found = ", ".join(f"0x{value:x}" for value in sorted(literals))
            self.errors.append(
                f"{relative}: {variable} 默认值缺少 {rendered}；"
                f"当前行只包含 [{found}]"
                + (f"（{note}）" if note else "")
            )

    def schema_default(self, name: str, **flags: Any) -> int:
        symbols = dict(BASE_FLAGS)
        symbols.update(flags)
        value = resolve_default(self.parameters, name, symbols)
        if not isinstance(value, int):
            raise ValueError(f"{name}: schema default is not an integer: {value!r}")
        return value

    def expect_reference(
        self, path: Path, variable: str, token: str, note: str = ""
    ) -> None:
        lines = assignment_lines(path, variable)
        self.checks += 1
        relative = path.relative_to(self.npc_dir.parent)
        if lines is None:
            self.errors.append(
                f"{relative}: 找不到 {variable} 的默认值赋值"
                + (f"（{note}）" if note else "")
            )
            return
        if not any(token in line for line in lines):
            self.errors.append(
                f"{relative}: {variable} 默认值必须引用 {token}"
                + (f"（{note}）" if note else "")
            )


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--npc",
        default=str(p.REPO_ROOT / "npc"),
        help="npc directory (default: repository npc/)",
    )
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)

    npc_dir = Path(args.npc).resolve()
    schema = p.load_schema(npc_dir / "params/schema/schema.json")
    if not schema:
        print("[defaults-check] ERROR: schema is missing", file=sys.stderr)
        return 2

    checker = Checker(npc_dir, schema)
    makefile = npc_dir / "Makefile"
    platform = npc_dir / "scripts/platform.mk"

    checker.expect(
        makefile, "NPC_RAM_BASE", [checker.schema_default("NPC_RAM_BASE")]
    )
    checker.expect(
        makefile, "NPC_RAM_SIZE", [checker.schema_default("NPC_RAM_SIZE")]
    )
    checker.expect(
        platform,
        "SBI_FW_BASE",
        [checker.schema_default("SBI_FW_BASE")],
        "默认跟随 NPC_RAM_BASE",
    )
    checker.expect_reference(
        platform,
        "SBI_FW_BASE",
        "CONFIG_NPC_RAM_BASE",
        "schema 的默认值是符号 NPC_RAM_BASE",
    )
    checker.expect(
        platform, "SBI_FW_SIZE", [checker.schema_default("SBI_FW_SIZE")]
    )
    checker.expect(
        platform,
        "SBI_NEXT_ADDR",
        [
            checker.schema_default("SBI_NEXT_ADDR", XLEN=32),
            checker.schema_default("SBI_NEXT_ADDR", XLEN=64),
        ],
        "RV32/RV64 两个分支",
    )
    checker.expect(
        platform, "SBI_FDT_ADDR", [checker.schema_default("SBI_FDT_ADDR")]
    )
    checker.expect(
        platform,
        "LINUX_INITRAMFS_ADDR",
        [checker.schema_default("LINUX_INITRAMFS_ADDR")],
    )
    checker.expect(
        platform,
        "NPC_UBOOT_EFI_APP_ADDR",
        [checker.schema_default("NPC_UBOOT_EFI_APP_ADDR")],
    )
    checker.expect(
        platform,
        "NPC_UBOOT_INITRAMFS_ADDR",
        [checker.schema_default("NPC_UBOOT_INITRAMFS_ADDR")],
    )
    checker.expect(
        platform,
        "NPC_UBOOT_KERNEL_ADDR",
        [checker.schema_default("NPC_UBOOT_KERNEL_ADDR")],
    )
    checker.expect(
        platform, "PLIC_BASE", [checker.schema_default("PLIC_BASE")]
    )
    checker.expect(
        platform, "PLIC_SIZE", [checker.schema_default("PLIC_SIZE")]
    )
    checker.expect(
        platform, "UART_BASE", [checker.schema_default("PMA_UART_BASE")]
    )
    checker.expect(
        platform, "CLINT_BASE", [checker.schema_default("PMA_CLINT_BASE")]
    )

    header = npc_dir / "params/generated/npc_config_defaults.h"
    checker.checks += 1
    if not header.is_file():
        checker.errors.append(
            f"{header.relative_to(npc_dir.parent)}: 共享默认值头文件不存在；"
            "先运行 gen_kconfig.py"
        )

    if checker.errors:
        for error in checker.errors:
            print(f"[defaults-check] FAIL: {error}", file=sys.stderr)
        return 1
    if not args.quiet:
        print(f"[OK] 默认值一致性检查通过: {checker.checks} 项")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
