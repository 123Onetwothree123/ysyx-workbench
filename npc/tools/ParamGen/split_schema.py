#!/usr/bin/env python3
"""Split the authoritative ParamGen schema into a manifest plus menu parts.

The authoritative schema is organised as a small ``schema.json`` manifest and
one JSON file per top-level menu under ``parts/``.  Each part owns exactly the
layout subtree, parameters, choices and flat menu records whose
``menu_path[1]`` (``path[1]`` for menus) is that top-level menu.

Usage::

    python3 npc/tools/ParamGen/split_schema.py
    python3 npc/tools/ParamGen/split_schema.py --check
    python3 npc/tools/ParamGen/split_schema.py --check \
        --compare /tmp/opencode/schema-before-split.json

The split is deterministic: JSON is written with sorted keys, two-space
indentation and a trailing newline, and no timestamp/host/absolute path is
recorded.  Re-running on an unchanged schema leaves every byte identical.

The default split backs up the original single-file schema to
``/tmp/opencode/schema-before-split.json`` before replacing it.  Re-running
from the manifest does not overwrite that baseline.
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import paramlib as p

DEFAULT_OUT_DIR = p.REPO_ROOT / "npc/params/schema"
DEFAULT_BACKUP = Path("/tmp/opencode/schema-before-split.json")

# Stable semantic filenames for the seven current top-level menus.  Order is
# defined only by the manifest's ``parts`` array, never by filename sorting.
# An unknown menu name is an error rather than an arbitrary numeric fallback,
# so a new top-level menu gets an explicit, reviewable part name.
PART_SLUGS = {
    "CPU 与平台": "cpu-platform",
    "运行模式": "mode",
    "FPGA 部署": "fpga",
    "SoC 与外设": "soc-peripheral",
    "运行时与调试": "runtime-debug",
    "固件与启动": "firmware-boot",
    "构建与仿真": "build-sim",
}
KNOWN_TOP_KEYS = {
    "parameters",
    "choices",
    "layout",
    "menus",
    "schema_version",
    "generated_snapshot",
    "generator",
    "source",
    "generation_issues",
}


class SplitError(p.ParamError):
    """The schema cannot be split without losing or misattributing data."""


def _canonical_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def _part_filename(prompt: str) -> str:
    try:
        slug = PART_SLUGS[prompt]
    except KeyError as error:
        raise SplitError(
            f"no stable part filename for top-level menu {prompt!r}; add it "
            "to PART_SLUGS instead of relying on filename order"
        ) from error
    return f"parts/{slug}.json"


def _top_menu_prompts(schema: Dict[str, Any]) -> List[str]:
    layout = schema.get("layout")
    if not isinstance(layout, dict):
        raise SplitError("schema.layout must be an object")
    extra_layout = set(layout) - {"mainmenu", "items"}
    if extra_layout:
        raise SplitError(
            "schema.layout has unsupported key(s): "
            + ", ".join(sorted(extra_layout))
        )
    mainmenu = layout.get("mainmenu")
    if not isinstance(mainmenu, str) or not mainmenu:
        raise SplitError("schema.layout.mainmenu must be a non-empty string")
    items = layout.get("items")
    if not isinstance(items, list) or not items:
        raise SplitError("schema.layout.items must be a non-empty list")
    prompts: List[str] = []
    for index, item in enumerate(items):
        if not isinstance(item, dict):
            raise SplitError(f"schema.layout.items[{index}] must be an object")
        if item.get("kind") != "menu":
            raise SplitError(
                f"schema.layout.items[{index}] must be a top-level menu, "
                f"got kind={item.get('kind')!r}"
            )
        prompt = item.get("prompt")
        if not isinstance(prompt, str) or not prompt:
            raise SplitError(
                f"schema.layout.items[{index}].prompt must be a non-empty string"
            )
        if prompt in prompts:
            raise SplitError(f"duplicate top-level menu prompt: {prompt!r}")
        prompts.append(prompt)
    return prompts


def _entry_menu_prompt(
    entry: Any,
    *,
    kind: str,
    where: str,
    mainmenu: str,
    top_prompts: set[str],
    path_field: str = "menu_path",
) -> str:
    if not isinstance(entry, dict):
        raise SplitError(f"{where} must be an object")
    path = entry.get(path_field)
    if (
        not isinstance(path, list)
        or len(path) < 2
        or not all(isinstance(part, str) for part in path)
    ):
        raise SplitError(
            f"{where}.{path_field} must be a menu path with at least two "
            f"components, got {path!r}"
        )
    if path[0] != mainmenu:
        raise SplitError(
            f"{where}.{path_field}[0] must be the mainmenu {mainmenu!r}, "
            f"got {path[0]!r}"
        )
    top = path[1]
    if top not in top_prompts:
        raise SplitError(
            f"{where} cannot be assigned to a top-level menu: "
            f"{path_field}[1]={top!r}"
        )
    return top


def split_schema(schema: Dict[str, Any]) -> Tuple[Dict[str, Any], List[Dict[str, Any]]]:
    """Return ``(manifest, parts)`` for a merged schema dict."""
    if not isinstance(schema, dict):
        raise SplitError("schema root must be an object")
    unknown = set(schema) - KNOWN_TOP_KEYS
    if unknown:
        raise SplitError(
            "schema has unsupported top-level key(s): "
            + ", ".join(sorted(unknown))
        )
    top_prompts = _top_menu_prompts(schema)
    top_set = set(top_prompts)
    layout = schema["layout"]
    mainmenu = layout["mainmenu"]
    layout_items = layout["items"]

    parameters = schema.get("parameters", [])
    choices = schema.get("choices", [])
    menus = schema.get("menus", [])
    if not isinstance(parameters, list):
        raise SplitError("schema.parameters must be a list")
    if not isinstance(choices, list):
        raise SplitError("schema.choices must be a list")
    if not isinstance(menus, list):
        raise SplitError("schema.menus must be a list")

    parameters_by_top: Dict[str, List[Dict[str, Any]]] = {
        prompt: [] for prompt in top_prompts
    }
    choices_by_top: Dict[str, List[Dict[str, Any]]] = {
        prompt: [] for prompt in top_prompts
    }
    menus_by_top: Dict[str, List[Dict[str, Any]]] = {
        prompt: [] for prompt in top_prompts
    }

    seen_parameters: Dict[str, str] = {}
    for index, entry in enumerate(parameters):
        where = f"schema.parameters[{index}]"
        top = _entry_menu_prompt(
            entry,
            kind="parameter",
            where=where,
            mainmenu=mainmenu,
            top_prompts=top_set,
        )
        name = entry.get("name")
        if not isinstance(name, str) or not name:
            raise SplitError(f"{where}.name must be a non-empty string")
        if name in seen_parameters:
            raise SplitError(
                f"duplicate parameter name {name!r} in {seen_parameters[name]} "
                f"and {where}"
            )
        seen_parameters[name] = where
        parameters_by_top[top].append(entry)

    seen_choices: Dict[str, str] = {}
    for index, entry in enumerate(choices):
        where = f"schema.choices[{index}]"
        top = _entry_menu_prompt(
            entry,
            kind="choice",
            where=where,
            mainmenu=mainmenu,
            top_prompts=top_set,
        )
        choice_id = entry.get("id")
        if not isinstance(choice_id, str) or not choice_id:
            raise SplitError(f"{where}.id must be a non-empty string")
        if choice_id in seen_choices:
            raise SplitError(
                f"duplicate choice id {choice_id!r} in {seen_choices[choice_id]} "
                f"and {where}"
            )
        seen_choices[choice_id] = where
        choices_by_top[top].append(entry)

    seen_menu_paths: Dict[Tuple[str, ...], str] = {}
    for index, entry in enumerate(menus):
        where = f"schema.menus[{index}]"
        top = _entry_menu_prompt(
            entry,
            kind="menu",
            where=where,
            mainmenu=mainmenu,
            top_prompts=top_set,
            path_field="path",
        )
        path = tuple(entry["path"])
        if path in seen_menu_paths:
            raise SplitError(
                "duplicate menu path "
                + " / ".join(path)
                + f" in {seen_menu_paths[path]} and {where}"
            )
        seen_menu_paths[path] = where
        order = entry.get("order")
        if not isinstance(order, int):
            raise SplitError(f"{where}.order must be an integer")
        menus_by_top[top].append(entry)

    parts: List[Dict[str, Any]] = []
    part_paths: List[str] = []
    for prompt, layout_item in zip(top_prompts, layout_items):
        relative = _part_filename(prompt)
        part_paths.append(relative)
        parts.append(
            {
                "menu_prompt": prompt,
                "layout": copy.deepcopy(layout_item),
                "parameters": sorted(
                    copy.deepcopy(parameters_by_top[prompt]),
                    key=lambda entry: entry["name"],
                ),
                "choices": sorted(
                    copy.deepcopy(choices_by_top[prompt]),
                    key=lambda entry: entry["id"],
                ),
                "menus": sorted(
                    copy.deepcopy(menus_by_top[prompt]),
                    key=lambda entry: entry["order"],
                ),
            }
        )

    manifest: Dict[str, Any] = {
        "schema_version": schema.get("schema_version"),
        "generated_snapshot": schema.get("generated_snapshot"),
        "mainmenu": mainmenu,
        "parts": part_paths,
    }
    for key in ("generator", "source", "generation_issues"):
        if key in schema:
            manifest[key] = copy.deepcopy(schema[key])
    return manifest, parts


def _summary_for(schema: Dict[str, Any]) -> Dict[str, Any]:
    # Reuse the generator's historical summary algorithm so the numbers keep
    # the same meaning after the split.
    import gen_schema

    return gen_schema.summarize(schema)


def _write_if_changed(path: Path, value: Any) -> bool:
    text = _canonical_text(value)
    data = text.encode("utf-8")
    if path.exists() and path.read_bytes() == data:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return True


def _read_bytes(path: Path) -> bytes:
    try:
        return path.read_bytes()
    except OSError as error:
        raise SplitError(f"cannot read {path}: {error}") from error


def _write_bundle(
    out_dir: Path,
    manifest: Dict[str, Any],
    parts: List[Dict[str, Any]],
    summary: Dict[str, Any],
) -> List[Path]:
    written: List[Path] = []
    manifest_path = out_dir / "schema.json"
    if _write_if_changed(manifest_path, manifest):
        written.append(manifest_path)
    for relative, part in zip(manifest["parts"], parts):
        path = out_dir / relative
        if _write_if_changed(path, part):
            written.append(path)
    summary_path = out_dir / "schema-summary.json"
    if _write_if_changed(summary_path, summary):
        written.append(summary_path)
    expected = {
        (out_dir / relative).resolve() for relative in manifest["parts"]
    }
    parts_dir = out_dir / "parts"
    if parts_dir.is_dir():
        for stale in sorted(parts_dir.glob("*.json")):
            if stale.resolve() not in expected:
                stale.unlink()
                written.append(stale)
    return written


def check_bundle(
    manifest_path: Path,
    *,
    compare: Optional[Path] = None,
) -> List[str]:
    """Return a list of problems; an empty list means the bundle is current."""
    problems: List[str] = []
    try:
        raw = p.read_json(manifest_path)
    except p.ParamError as error:
        return [str(error)]
    if not isinstance(raw, dict):
        return [f"{manifest_path}: schema root must be an object"]
    if "parts" not in raw:
        return [
            f"{manifest_path}: not a schema manifest (missing 'parts'); "
            "run split_schema.py first"
        ]
    if "parameters" in raw:
        return [
            f"{manifest_path}: manifest must not contain a merged "
            "'parameters' array"
        ]
    try:
        merged = p.load_schema(manifest_path)
    except p.ParamError as error:
        return [str(error)]
    try:
        expected_manifest, expected_parts = split_schema(merged)
    except p.ParamError as error:
        return [str(error)]

    expected_text = _canonical_text(expected_manifest)
    actual_text = _canonical_text(raw)
    if actual_text != expected_text:
        problems.append(f"manifest is stale or not canonical: {manifest_path}")
    out_dir = manifest_path.parent
    for relative, part in zip(expected_manifest["parts"], expected_parts):
        path = out_dir / relative
        if not path.is_file():
            problems.append(f"part file is missing: {path}")
            continue
        try:
            actual = path.read_text(encoding="utf-8")
        except OSError as error:
            problems.append(f"cannot read {path}: {error}")
            continue
        if actual != _canonical_text(part):
            problems.append(f"part is stale or not canonical: {path}")
    expected_part_paths = {
        (out_dir / relative).resolve() for relative in expected_manifest["parts"]
    }
    parts_dir = out_dir / "parts"
    if parts_dir.is_dir():
        for stale in sorted(parts_dir.glob("*.json")):
            if stale.resolve() not in expected_part_paths:
                problems.append(f"unexpected part file: {stale}")

    summary_path = out_dir / "schema-summary.json"
    try:
        expected_summary = _summary_for(merged)
    except Exception as error:  # pragma: no cover - defensive
        problems.append(f"cannot summarize merged schema: {error}")
    else:
        if not summary_path.is_file():
            problems.append(f"summary is missing: {summary_path}")
        elif summary_path.read_text(encoding="utf-8") != _canonical_text(
            expected_summary
        ):
            problems.append(f"summary is stale or not canonical: {summary_path}")

    if compare is not None:
        try:
            reference = p.read_json(compare)
        except p.ParamError as error:
            problems.append(str(error))
        else:
            if merged != reference:
                problems.append(
                    f"merged schema does not equal comparison schema: {compare}"
                )
    return problems


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--schema",
        default=str(p.DEFAULT_SCHEMA),
        help="authoritative schema or manifest (default: %(default)s)",
    )
    parser.add_argument(
        "--out-dir",
        default=str(DEFAULT_OUT_DIR),
        help="manifest/parts output directory (default: %(default)s)",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify manifest/parts/summary without writing anything",
    )
    parser.add_argument(
        "--compare",
        help="with --check, also require the merged schema to equal this JSON",
    )
    parser.add_argument(
        "--backup",
        default=str(DEFAULT_BACKUP),
        help="pre-split backup for a legacy single-file schema (default: %(default)s)",
    )
    args = parser.parse_args(argv)

    schema_path = Path(args.schema)
    if not schema_path.is_absolute():
        schema_path = p.REPO_ROOT / schema_path
    out_dir = Path(args.out_dir)
    if not out_dir.is_absolute():
        out_dir = p.REPO_ROOT / out_dir
    compare = Path(args.compare) if args.compare else None
    if compare is not None and not compare.is_absolute():
        compare = p.REPO_ROOT / compare

    try:
        if args.check:
            problems = check_bundle(schema_path, compare=compare)
            if problems:
                for problem in problems:
                    print(f"[schema-split] CHECK FAILED: {problem}", file=sys.stderr)
                return 1
            print(f"[schema-split] check passed: {schema_path}")
            return 0

        raw = _read_bytes(schema_path)
        try:
            schema = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise SplitError(f"invalid JSON in {schema_path}: {error}") from error
        if not isinstance(schema, dict):
            raise SplitError(f"{schema_path}: schema root must be an object")
        is_legacy = "parameters" in schema and "parts" not in schema
        if "parts" in schema and "parameters" in schema:
            raise SplitError(
                f"{schema_path}: schema must not contain both 'parts' and "
                "'parameters'"
            )
        if "parts" in schema:
            merged = p.load_schema(schema_path)
        else:
            merged = schema

        manifest, parts = split_schema(merged)
        summary = _summary_for(merged)

        if is_legacy:
            backup = Path(args.backup)
            if not backup.is_absolute():
                backup = p.REPO_ROOT / backup
            backup.parent.mkdir(parents=True, exist_ok=True)
            backup.write_bytes(raw)
            print(f"[schema-split] backed up legacy schema to {backup}")

        written = _write_bundle(out_dir, manifest, parts, summary)
        print(
            f"[schema-split] wrote {len(manifest['parts'])} part(s) and "
            f"{out_dir / 'schema.json'} ({len(written)} file(s) updated)"
        )
        for relative in manifest["parts"]:
            print(f"[schema-split]   {out_dir / relative}")
        return 0
    except p.ParamError as error:
        print(f"[schema-split] ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
