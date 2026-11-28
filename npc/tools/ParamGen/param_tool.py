#!/usr/bin/env python3
"""Command-line entry point for the NPC parameter/profile prototype.

Examples:
    python3 npc/tools/ParamGen/param_tool.py schema \
        --out /tmp/opencode/schema.json --summary /tmp/opencode/schema-summary.json
    python3 npc/tools/ParamGen/param_tool.py hash --typed /tmp/current.typed.json
    python3 npc/tools/ParamGen/param_tool.py check \
        --config /tmp/current.typed.json \
        --profile npc/params/profiles/rv64gc-linux@1.json
    python3 npc/tools/ParamGen/param_tool.py selftest
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, Optional, Sequence

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import paramlib as p


def cmd_schema(args: argparse.Namespace) -> int:
    import gen_schema

    child = [
        "--kconfig",
        args.kconfig,
        "--out",
        args.out,
        "--summary",
        args.summary,
    ]
    if args.allow_schema_overwrite:
        child.append("--allow-schema-overwrite")
    return gen_schema.main(child)


def cmd_hash(args: argparse.Namespace) -> int:
    typed = p.read_json(args.typed)
    schema = p.load_schema(args.schema) if args.schema else p.load_schema()
    document = p.canonical_document(typed, schema)
    if args.out:
        p.write_json(args.out, document)
    if args.json:
        print(json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True))
    else:
        print(document["effective_hash"])
        if args.out:
            print(f"canonical: {args.out}", file=sys.stderr)
    return 0


def default_manifest(profile: Dict[str, Any]) -> Optional[Path]:
    relative = profile.get("qualification", {}).get("manifest")
    if not relative:
        return None
    path = p.REPO_ROOT / relative
    return path if path.exists() else None


def cmd_check(args: argparse.Namespace) -> int:
    typed = p.read_json(args.config)
    schema = p.load_schema(args.schema) if args.schema else p.load_schema()
    profile = p.load_profile(args.profile)
    manifest_path = None if args.no_manifest else args.manifest
    if manifest_path is None and not args.no_manifest:
        manifest_path = default_manifest(profile)
    manifest = p.load_manifest(manifest_path) if manifest_path else None
    result = p.classify(profile, typed, manifest=manifest, schema=schema)
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    else:
        print(f"profile: {result['profile']['id']}@{result['profile']['version']}")
        print(f"effective_hash: {result['effective_hash']}")
        print(f"status: {result['status']}")
        if result["checks"]["base_allow"]["violations"]:
            print("base/allow violations:")
            for item in result["checks"]["base_allow"]["violations"][:20]:
                print(
                    f"  - {item['parameter']}: {item['reason']} "
                    f"(base={item['base']!r}, effective={item['effective']!r})"
                )
        if result["checks"]["predicate"]["failures"]:
            print("predicate failures:")
            for item in result["checks"]["predicate"]["failures"]:
                print(f"  - {item['id']}: {item.get('description', '')}")
        if result["errors"]:
            print("errors:")
            for error in result["errors"]:
                print(f"  - {error}")
    return 0


def cmd_selftest(args: argparse.Namespace) -> int:
    import selftest

    child = ["--typed", args.typed] if args.typed else []
    if args.profile:
        child += ["--profile", args.profile]
    return selftest.main(child)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    schema = sub.add_parser(
        "schema",
        help="parse npc/Kconfig into a migration snapshot (schema.json is authoritative)",
    )
    schema.add_argument("--kconfig", default=str(p.REPO_ROOT / "npc/Kconfig"))
    schema.add_argument(
        "--out", default=str(p.DEFAULT_SCHEMA)
    )
    schema.add_argument(
        "--summary", default=str(p.DEFAULT_SCHEMA_SUMMARY)
    )
    schema.add_argument(
        "--allow-schema-overwrite",
        action="store_true",
        help="allow an intentional re-migration over authoritative schema.json",
    )
    schema.set_defaults(func=cmd_schema)

    hash_cmd = sub.add_parser("hash", help="compute the canonical effective hash")
    hash_cmd.add_argument("--typed", required=True, help="npc_config_gen typed JSON")
    hash_cmd.add_argument("--schema", help="schema snapshot (default npc/params/schema/schema.json)")
    hash_cmd.add_argument("--out", help="write canonical JSON document")
    hash_cmd.add_argument("--json", action="store_true", help="print the canonical document")
    hash_cmd.set_defaults(func=cmd_hash)

    check = sub.add_parser("check", help="classify one config against one profile")
    check.add_argument("--config", required=True, help="typed or canonical effective JSON")
    check.add_argument("--profile", required=True)
    check.add_argument("--manifest", help="qualified manifest (default: profile qualification.manifest)")
    check.add_argument("--no-manifest", action="store_true")
    check.add_argument("--schema")
    check.add_argument("--json", action="store_true")
    check.set_defaults(func=cmd_check)

    selftest = sub.add_parser("selftest", help="run built-in hash/predicate/profile tests")
    selftest.add_argument("--typed")
    selftest.add_argument("--profile")
    selftest.set_defaults(func=cmd_selftest)
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except p.ParamError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
