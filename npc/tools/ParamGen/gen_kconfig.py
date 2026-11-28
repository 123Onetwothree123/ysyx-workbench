#!/usr/bin/env python3
"""Generate a Kconfig tree from the ParamGen JSON schema.

Usage:
    python3 npc/tools/ParamGen/gen_kconfig.py \
        --schema npc/params/schema/schema.json \
        --out npc/params/generated/Kconfig

The generator reads *only* ``schema.json``.  It does not open the stable
``npc/Kconfig`` wrapper, any source under ``npc/kconfig/``, or the previous
generated files.  The output is written as an include-only top-level Kconfig
plus one ``kconfig/part-NN.Kconfig`` file per top-level menu, so the schema is
demonstrably the single source of truth.

``npc/Kconfig`` is the stable entry point.  It owns ``mainmenu`` because the
Kconfig grammar only accepts ``mainmenu`` in the top-level file, then sources
``params/generated/Kconfig``.  The generated top-level file only contains
``source`` statements using paths relative to ``npc/`` (the srctree), which is
also how ``c_conf`` resolves them.

Determinism: no timestamps, host names or absolute paths are written; files
are emitted in schema order and repeated runs produce byte-identical output.

Unsupported schema constructs are reported, never silently dropped:
``generation_issues`` from the schema are copied into the generated header and
printed to stderr, and any layout reference that cannot be resolved is a hard
error.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Set, Tuple

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from paramlib import REPO_ROOT, read_json

TYPE_KEYWORDS = {"bool", "tristate", "int", "hex", "string"}
Y = "y"
DEFAULT_SOURCE_PREFIX = "params/generated"


class GenerationError(Exception):
    """The schema cannot be represented as Kconfig without losing data."""


def quote_kconfig(text: str, *, where: str) -> str:
    """Quote a Kconfig string literal.

    Kconfig's lexer removes a backslash before the next character, so a
    literal backslash must be doubled.  Newlines inside prompts are not
    representable on one line and are reported instead of being flattened.
    """
    if "\n" in text:
        raise GenerationError(f"{where}: prompt contains a newline")
    if "$" in text:
        raise GenerationError(
            f"{where}: prompt contains '$' (Kconfig expands variables in "
            "quoted strings)"
        )
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def render_scalar(value: str, kind: str, kconfig_type: str, *, where: str) -> str:
    if kind == "symbol":
        return value
    if kconfig_type == "string":
        return quote_kconfig(value, where=where)
    if kconfig_type in ("bool", "tristate"):
        if value not in ("y", "n", "m"):
            raise GenerationError(f"{where}: invalid bool constant {value!r}")
        return value
    if kconfig_type in ("int", "hex"):
        return value
    raise GenerationError(f"{where}: unsupported Kconfig type {kconfig_type!r}")


def render_default(item: Dict[str, Any], kconfig_type: str, *, where: str) -> str:
    value = str(item["value"])
    kind = str(item.get("kind", "constant"))
    if kconfig_type == "string" and kind == "symbol":
        return value
    return render_scalar(value, kind, kconfig_type, where=where)


def help_lines(help_text: str, indent: int) -> List[str]:
    prefix = " " * (indent + 2)
    lines: List[str] = []
    for line in help_text.split("\n"):
        lines.append(prefix + line if line else "")
    return lines


class Emitter:
    def __init__(self, schema: Dict[str, Any]) -> None:
        self.schema = schema
        self.parameters: Dict[str, Dict[str, Any]] = {
            entry["name"]: entry for entry in schema.get("parameters", [])
        }
        self.choices: Dict[str, Dict[str, Any]] = {
            entry["id"]: entry for entry in schema.get("choices", [])
        }
        self.referenced_symbols: Set[str] = set()
        self.referenced_choices: Set[str] = set()
        self.issues: List[Dict[str, str]] = list(
            schema.get("generation_issues", [])
        )

    # -- lookup ---------------------------------------------------------

    def parameter(self, name: str) -> Dict[str, Any]:
        try:
            entry = self.parameters[name]
        except KeyError as error:
            raise GenerationError(f"layout references unknown symbol {name!r}") from error
        self.referenced_symbols.add(name)
        return entry

    def choice(self, choice_id: str) -> Dict[str, Any]:
        try:
            entry = self.choices[choice_id]
        except KeyError as error:
            raise GenerationError(
                f"layout references unknown choice {choice_id!r}"
            ) from error
        self.referenced_choices.add(choice_id)
        return entry

    # -- properties -----------------------------------------------------

    def emit_condition(self, lines: List[str], keyword: str, condition: Any, indent: int) -> None:
        if condition is None:
            return
        text = str(condition)
        if text == Y:
            return
        if "choice:" in text or "<choice" in text:
            raise GenerationError(
                f"{keyword}: choice reference leaked into a local condition: {text}"
            )
        lines.append(" " * indent + f"{keyword} {text}")

    def emit_help(self, lines: List[str], entry: Dict[str, Any], indent: int) -> None:
        help_text = entry.get("help")
        if help_text is None:
            return
        lines.append(" " * indent + "help")
        lines.extend(help_lines(help_text, indent))

    def emit_symbol(self, name: str, lines: List[str], indent: int) -> None:
        entry = self.parameter(name)
        kconfig_type = entry.get("kconfig_type") or entry.get("type")
        if kconfig_type not in TYPE_KEYWORDS:
            raise GenerationError(
                f"{name}: unsupported Kconfig type {kconfig_type!r}"
            )
        prompt = entry.get("prompt")
        prompt_condition = entry.get("prompt_condition_local")
        prefix = " " * indent
        keyword = "menuconfig" if entry.get("is_menuconfig") else "config"
        lines.append(f"{prefix}{keyword} {name}")
        if prompt is not None:
            quoted = quote_kconfig(prompt, where=f"{name} prompt")
            line = f"{prefix}  {kconfig_type} {quoted}"
            if prompt_condition and prompt_condition != Y:
                if "choice:" in prompt_condition or "<choice" in prompt_condition:
                    raise GenerationError(
                        f"{name}: choice reference in prompt condition"
                    )
                line += f" if {prompt_condition}"
            lines.append(line)
        else:
            lines.append(f"{prefix}  {kconfig_type}")
        self.emit_condition(
            lines, "depends on", entry.get("depends_on_local"), indent + 2
        )
        for item in entry.get("defaults", []):
            line = (
                f"{prefix}  default "
                f"{render_default(item, kconfig_type, where=f'{name} default')}"
            )
            condition = item.get("condition_local")
            if condition and condition != Y:
                if "choice:" in condition or "<choice" in condition:
                    raise GenerationError(
                        f"{name}: choice reference in default condition"
                    )
                line += f" if {condition}"
            lines.append(line)
        for item in entry.get("range", []):
            line = (
                f"{prefix}  range {item['min']} {item['max']}"
            )
            condition = item.get("condition_local")
            if condition and condition != Y:
                line += f" if {condition}"
            lines.append(line)
        for item in entry.get("selects", []):
            line = f"{prefix}  select {item['target']}"
            condition = item.get("condition_local")
            if condition and condition != Y:
                line += f" if {condition}"
            lines.append(line)
        for item in entry.get("implies", []):
            line = f"{prefix}  imply {item['target']}"
            condition = item.get("condition_local")
            if condition and condition != Y:
                line += f" if {condition}"
            lines.append(line)
        self.emit_help(lines, entry, indent + 2)

    def emit_choice(self, choice_id: str, lines: List[str], indent: int) -> None:
        entry = self.choice(choice_id)
        prefix = " " * indent
        name = entry.get("name")
        lines.append(f"{prefix}choice" + (f" {name}" if name else ""))
        prompt = entry.get("prompt")
        if prompt is not None:
            quoted = quote_kconfig(prompt, where=f"choice {choice_id} prompt")
            line = f"{prefix}  prompt {quoted}"
            condition = entry.get("prompt_condition_local")
            if condition and condition != Y:
                line += f" if {condition}"
            lines.append(line)
        self.emit_condition(
            lines, "depends on", entry.get("depends_on_local"), indent + 2
        )
        visible = entry.get("visible_if")
        if visible and visible != Y:
            self.emit_condition(lines, "visible if", visible, indent + 2)
        for item in entry.get("defaults", []):
            line = f"{prefix}  default {item['value']}"
            condition = item.get("condition_local")
            if condition and condition != Y:
                line += f" if {condition}"
            lines.append(line)
        self.emit_help(lines, entry, indent + 2)
        for option in entry.get("options_in_order", entry.get("options", [])):
            self.emit_symbol(option, lines, indent + 2)
        lines.append(f"{prefix}endchoice")

    def emit_menu(self, item: Dict[str, Any], lines: List[str], indent: int) -> None:
        prefix = " " * indent
        prompt = item.get("prompt", "")
        lines.append(
            f"{prefix}menu {quote_kconfig(prompt, where='menu prompt')}"
        )
        self.emit_condition(
            lines, "depends on", item.get("depends_on_local"), indent + 2
        )
        visible = item.get("visible_if")
        if visible and visible != Y:
            self.emit_condition(lines, "visible if", visible, indent + 2)
        for child in item.get("items", []):
            self.emit_node(child, lines, indent + 2)
        lines.append(f"{prefix}endmenu")

    def emit_comment(self, item: Dict[str, Any], lines: List[str], indent: int) -> None:
        prefix = " " * indent
        lines.append(
            f"{prefix}comment "
            f"{quote_kconfig(item.get('prompt', ''), where='comment prompt')}"
        )
        self.emit_condition(
            lines, "depends on", item.get("depends_on_local"), indent + 2
        )

    def emit_node(self, item: Dict[str, Any], lines: List[str], indent: int) -> None:
        kind = item.get("kind")
        if kind == "menu":
            self.emit_menu(item, lines, indent)
        elif kind == "comment":
            self.emit_comment(item, lines, indent)
        elif kind == "symbol":
            self.emit_symbol(item["name"], lines, indent)
            for child in item.get("items", []):
                self.emit_node(child, lines, indent)
        elif kind == "choice":
            self.emit_choice(item["id"], lines, indent)
        else:
            raise GenerationError(
                f"unsupported layout item {kind!r} at "
                f"{item.get('file', '?')}:{item.get('line', '?')}"
            )

    def check_complete(self) -> None:
        unused_symbols = sorted(set(self.parameters) - self.referenced_symbols)
        unused_choices = sorted(set(self.choices) - self.referenced_choices)
        if unused_symbols:
            raise GenerationError(
                "schema parameters are not referenced by the layout: "
                + ", ".join(unused_symbols[:20])
            )
        if unused_choices:
            raise GenerationError(
                "schema choices are not referenced by the layout: "
                + ", ".join(unused_choices[:20])
            )


def build_outputs(
    schema: Dict[str, Any], *, source_prefix: str = DEFAULT_SOURCE_PREFIX
) -> Dict[str, str]:
    layout = schema.get("layout")
    if not isinstance(layout, dict):
        raise GenerationError("schema has no layout object; regenerate with gen_schema.py")
    emitter = Emitter(schema)
    top_items = layout.get("items", [])
    if not isinstance(top_items, list):
        raise GenerationError("schema layout.items must be a list")

    part_files: List[Tuple[str, List[str]]] = []
    if not top_items:
        top_items = []
        body: List[str] = []
        part_files.append(("kconfig/part-00.Kconfig", body))
    else:
        for index, item in enumerate(top_items):
            body = []
            emitter.emit_node(item, body, 0)
            part_files.append((f"kconfig/part-{index:02d}.Kconfig", body))

    emitter.check_complete()

    header = [
        "# Generated by npc/tools/ParamGen/gen_kconfig.py",
        "# Source of truth: npc/params/schema/schema.json",
        "# Do not edit by hand; regenerate from schema.json instead.",
        "# This file is included by npc/Kconfig and has no mainmenu of its own.",
        (
            f"# schema_version={schema.get('schema_version')} "
            f"parameters={len(schema.get('parameters', []))} "
            f"choices={len(schema.get('choices', []))} "
            f"menus={len(schema.get('menus', []))}"
        ),
    ]
    if emitter.issues:
        header.append(f"# generation_issues={len(emitter.issues)}")
        for issue in emitter.issues:
            header.append(
                "# ISSUE "
                f"{issue.get('kind')}: {issue.get('subject')}: "
                f"{issue.get('detail')}"
            )
    header.append("")

    root: List[str] = list(header)
    mainmenu = layout.get("mainmenu")
    if mainmenu:
        # mainmenu is legal only in the top-level Kconfig file.  npc/Kconfig
        # carries it; keep the generated tree include-only so c_conf accepts
        # the same files when they are sourced.
        root.append(
            "# mainmenu is declared by npc/Kconfig: "
            + quote_kconfig(mainmenu, where="mainmenu")
        )
    prefix = source_prefix.strip("/")
    if not prefix:
        raise GenerationError("source prefix must not be empty")
    for path, _body in part_files:
        root.append(f'source "{prefix}/{path}"')
    root.append("")

    outputs: Dict[str, str] = {"Kconfig": "\n".join(root)}
    for path, body in part_files:
        part_header = [
            "# Generated by npc/tools/ParamGen/gen_kconfig.py",
            f"# Schema part: {path}"
            + (
                f" (menu {body[0].strip()[5:]})"
                if body and body[0].lstrip().startswith("menu ")
                else ""
            ),
            "# Do not edit by hand; regenerate from npc/params/schema/schema.json.",
            "",
        ]
        outputs[path] = "\n".join(part_header + body) + "\n"
    outputs["Kconfig"] = outputs["Kconfig"] + "\n"
    return outputs


def write_outputs(outputs: Dict[str, str], out: Path) -> List[Path]:
    """Write generated files without touching unchanged content.

    Refreshing mtimes on every invocation would make ``c_conf`` re-run for
    unrelated builds.  Keep existing files byte-for-byte when the schema did
    not change, and remove stale part files after a menu layout change.
    """
    out = out.resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    kconfig_dir = out.parent / "kconfig"
    kconfig_dir.mkdir(parents=True, exist_ok=True)
    written: List[Path] = []
    for relative, text in sorted(outputs.items()):
        path = out.parent / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        data = text.encode("utf-8")
        if path.exists() and path.read_bytes() == data:
            written.append(path)
            continue
        path.write_bytes(data)
        written.append(path)
    expected = {
        (out.parent / relative).resolve()
        for relative in outputs
        if relative.startswith("kconfig/")
    }
    for stale in sorted(kconfig_dir.glob("*.Kconfig")):
        if stale.resolve() not in expected:
            stale.unlink()
    return written


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--schema",
        default=str(REPO_ROOT / "npc/params/schema/schema.json"),
        help="ParamGen schema snapshot",
    )
    parser.add_argument(
        "--out",
        default=str(REPO_ROOT / "npc/params/generated/Kconfig"),
        help="generated top-level Kconfig path",
    )
    parser.add_argument(
        "--source-prefix",
        default=DEFAULT_SOURCE_PREFIX,
        help="source path prefix relative to npc/ (default: %(default)s)",
    )
    args = parser.parse_args(argv)

    schema_path = Path(args.schema)
    if not schema_path.is_absolute():
        schema_path = REPO_ROOT / schema_path
    schema = read_json(schema_path)
    try:
        outputs = build_outputs(schema, source_prefix=args.source_prefix)
    except GenerationError as error:
        print(f"[param-kconfig] ERROR: {error}", file=sys.stderr)
        return 1
    written = write_outputs(outputs, Path(args.out))
    issues = schema.get("generation_issues", [])
    if issues:
        print(
            f"[param-kconfig] WARNING {len(issues)} schema generation issue(s); "
            "see the generated Kconfig header",
            file=sys.stderr,
        )
    print(
        f"[param-kconfig] wrote {written[0]} "
        f"({len(written) - 1} part file(s))"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
