#!/usr/bin/env python3
"""Generate a Kconfig tree from the ParamGen JSON schema.

Usage:
    python3 npc/tools/ParamGen/gen_kconfig.py \
        --schema npc/params/schema/schema.json \
        --out npc/params/generated/Kconfig

The generator reads the authoritative schema manifest
(``npc/params/schema/schema.json``) plus its menu parts through
``paramlib.load_schema()``.  It does not open the stable ``npc/Kconfig``
wrapper, any source under ``npc/kconfig/``, or the previous generated files.
The output is written as an include-only top-level Kconfig plus one
``kconfig/part-NN.Kconfig`` file per top-level menu, so the schema is
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

from paramlib import ParamError, REPO_ROOT, load_schema

TYPE_KEYWORDS = {"bool", "tristate", "int", "hex", "string"}
Y = "y"
DEFAULT_SOURCE_PREFIX = "params/generated"
SCHEMA_AUTHORITY = "npc/params/schema/schema.json"


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


def validate_schema_source(schema: Dict[str, Any]) -> List[str]:
    """Check that provenance metadata cannot point build tools at stale files.

    The pre-switch Kconfig tree is intentionally deleted.  ``source`` records
    that fact explicitly so a later edit does not accidentally reintroduce a
    second, hand-maintained Kconfig source tree or make a build tool open a
    path that no longer exists.
    """
    problems: List[str] = []
    source = schema.get("source")
    if not isinstance(source, dict):
        return ["schema.source is missing; cannot verify Kconfig provenance"]
    authority = source.get("authority")
    if authority != SCHEMA_AUTHORITY:
        problems.append(
            f"schema.source.authority must be {SCHEMA_AUTHORITY!r}, "
            f"got {authority!r}"
        )
    status = source.get("kconfig_files_status")
    if status not in ("legacy-deleted", "present"):
        problems.append(
            "schema.source.kconfig_files_status must be 'legacy-deleted' "
            f"or 'present', got {status!r}"
        )
    kconfig_files = source.get("kconfig_files")
    if not isinstance(kconfig_files, list) or any(
        not isinstance(relative, str) for relative in kconfig_files
    ):
        problems.append("schema.source.kconfig_files must be a string list")
        kconfig_files = []
    legacy_files = source.get("legacy_kconfig_files")
    if not isinstance(legacy_files, list) or any(
        not isinstance(relative, str) for relative in legacy_files
    ):
        problems.append(
            "schema.source.legacy_kconfig_files must be a string list"
        )
        legacy_files = []
    for relative in legacy_files:
        if (REPO_ROOT / relative).exists():
            problems.append(
                f"legacy Kconfig file still exists: {relative}; remove it or "
                "update schema.source.legacy_kconfig_files"
            )
    for relative in kconfig_files:
        if relative in legacy_files:
            continue
        if not (REPO_ROOT / relative).exists():
            problems.append(
                f"schema.source.kconfig_files entry is missing and not marked "
                f"legacy: {relative}"
            )
    allowed_provenance = set(kconfig_files)
    for entry in schema.get("parameters", []):
        if not isinstance(entry, dict):
            continue
        relative = entry.get("file")
        if not isinstance(relative, str) or not relative:
            continue
        if relative in allowed_provenance:
            continue
        if (REPO_ROOT / relative).exists():
            continue
        problems.append(
            f"parameter {entry.get('name', '?')}: provenance file is neither "
            f"present nor listed in schema.source.kconfig_files: {relative}"
        )
    generated = source.get("generated_kconfig_files", [])
    if not isinstance(generated, list) or any(
        not isinstance(relative, str) for relative in generated
    ):
        problems.append("schema.source.generated_kconfig_files must be a string list")
    return problems


def validate_stable_wrapper() -> List[str]:
    """Ensure npc/Kconfig remains a thin schema-generated entry point."""
    path = REPO_ROOT / "npc/Kconfig"
    if not path.is_file():
        return [f"stable Kconfig wrapper is missing: {path}"]
    text = path.read_text(encoding="utf-8")
    problems: List[str] = []
    if not any(
        line.strip().startswith("mainmenu ") for line in text.splitlines()
    ):
        problems.append("npc/Kconfig must declare mainmenu")
    if 'source "params/generated/Kconfig"' not in text:
        problems.append(
            'npc/Kconfig must source "params/generated/Kconfig"; it must not '
            "source the deleted npc/kconfig tree"
        )
    return problems


def validate_generated_metadata(
    schema: Dict[str, Any], out: Path, *, source_prefix: str
) -> List[str]:
    """Compare source.generated_kconfig_files with the real generated set."""
    source = schema.get("source") or {}
    declared = source.get("generated_kconfig_files")
    if not isinstance(declared, list):
        return ["schema.source.generated_kconfig_files must be a list"]
    out = out.resolve()
    try:
        outputs = build_outputs(schema, source_prefix=source_prefix)
    except GenerationError:
        return []
    actual: List[str] = []
    for relative in outputs:
        path = (out.parent / relative).resolve()
        try:
            actual.append(str(path.relative_to(REPO_ROOT)))
        except ValueError:
            # A migration snapshot written outside the repository does not
            # describe the repository's generated tree.
            return []
    if sorted(declared) != sorted(actual):
        missing = sorted(set(actual) - set(declared))
        extra = sorted(set(declared) - set(actual))
        problems = []
        if missing:
            problems.append(
                "schema.source.generated_kconfig_files is missing: "
                + ", ".join(missing)
            )
        if extra:
            problems.append(
                "schema.source.generated_kconfig_files has extra entries: "
                + ", ".join(extra)
            )
        return problems
    for relative in declared:
        if not (REPO_ROOT / relative).is_file():
            return [f"generated Kconfig file is missing: {relative}"]
    return []


def check_outputs(
    schema: Dict[str, Any], out: Path, *, source_prefix: str
) -> List[str]:
    """Return differences between schema and the generated Kconfig tree."""
    problems: List[str] = []
    try:
        outputs = build_outputs(schema, source_prefix=source_prefix)
    except GenerationError as error:
        return [f"schema cannot be generated: {error}"]
    out = out.resolve()
    for relative, text in sorted(outputs.items()):
        path = out.parent / relative
        if not path.is_file():
            problems.append(f"generated Kconfig file is missing: {path}")
        elif path.read_bytes() != text.encode("utf-8"):
            problems.append(f"generated Kconfig file is stale: {path}")
    expected = {(out.parent / relative).resolve() for relative in outputs}
    kconfig_dir = out.parent / "kconfig"
    if kconfig_dir.is_dir():
        for stale in sorted(kconfig_dir.glob("*.Kconfig")):
            if stale.resolve() not in expected:
                problems.append(f"unexpected generated Kconfig file: {stale}")
    return problems


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
    parser.add_argument(
        "--check",
        action="store_true",
        help=(
            "verify generated files match schema and provenance metadata; "
            "exit non-zero without writing anything on drift"
        ),
    )
    args = parser.parse_args(argv)

    schema_path = Path(args.schema)
    if not schema_path.is_absolute():
        schema_path = REPO_ROOT / schema_path
    try:
        schema = load_schema(schema_path)
    except ParamError as error:
        print(f"[param-kconfig] ERROR: {error}", file=sys.stderr)
        return 2
    problems = validate_schema_source(schema) + validate_stable_wrapper()
    if problems:
        for problem in problems:
            print(f"[param-kconfig] ERROR: {problem}", file=sys.stderr)
        return 1
    if args.check:
        problems = validate_generated_metadata(
            schema, Path(args.out), source_prefix=args.source_prefix
        )
        problems += check_outputs(
            schema, Path(args.out), source_prefix=args.source_prefix
        )
        for problem in problems:
            print(f"[param-kconfig] CHECK FAILED: {problem}", file=sys.stderr)
        return 1 if problems else 0
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
