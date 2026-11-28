#!/usr/bin/env python3
"""Self-tests for the ParamGen prototype (stdlib only, no repo writes).

Run:
    python3 npc/tools/ParamGen/selftest.py
    python3 npc/tools/ParamGen/selftest.py --typed /tmp/current.typed.json
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
import tempfile
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import paramlib as p

PASS: List[str] = []
FAIL: List[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        PASS.append(name)
        print(f"PASS {name}")
    else:
        FAIL.append(name)
        print(f"FAIL {name}" + (f": {detail}" if detail else ""))


def load_current_typed(repo: Path, workdir: Path) -> Dict[str, Any]:
    config_json = repo / "npc/include/generated/npc_config.json"
    if not config_json.exists():
        raise p.ParamError(
            f"{config_json} is missing; run the existing Kconfig sync first "
            "or pass --typed"
        )
    gen = repo / "npc/tools/build/npc_config_gen"
    if not gen.exists():
        raise p.ParamError(
            f"{gen} is missing; build the existing NPCConfigGEN first "
            "or pass --typed"
        )
    output = workdir / "current.typed.json"
    p.run_checked(
        [
            str(gen),
            "--config-json",
            str(config_json),
            "--typed-json",
            str(output),
        ]
    )
    return p.read_json(output)


def hash_of(typed: Dict[str, Any], schema: Dict[str, Any]) -> str:
    return p.canonical_document(typed, schema)["effective_hash"]


def test_hash_invariance(typed: Dict[str, Any], schema: Dict[str, Any]) -> str:
    h1 = hash_of(typed, schema)
    h2 = hash_of(copy.deepcopy(typed), schema)
    check("hash: repeated computation is stable", h1 == h2, f"{h1} != {h2}")

    changed = copy.deepcopy(typed)
    changed["config_hash"] = "0" * 16
    changed["schema"] = 999
    changed.setdefault("build", {})["pdk"] = "other-pdk"
    changed.setdefault("build", {}).setdefault("trace", {})["file"] = "/tmp/other.vcd"
    changed.setdefault("build", {}).setdefault("log", {})["dir"] = "/tmp/logs"
    changed.setdefault("build", {}).setdefault("log", {})["timestamp"] = True
    changed.setdefault("boot", {})["sbi_fdt_path"] = "/tmp/other.dts"
    changed.setdefault("boot", {})["linux_image_path"] = "/tmp/Image"
    changed.setdefault("fpga", {})["toolchain_path"] = "/opt/toolchain"
    changed.setdefault("fpga", {})["build_dir"] = "/tmp/fpga-build"
    changed["metadata"] = {"generated_at": "2026-11-28T12:00:00Z", "host": "x"}
    changed.setdefault("flat", {})["BUILD_TIMESTAMP"] = "y"
    changed["flat"]["LOG_DIR"] = "/tmp/other-log"
    changed["flat"]["KCONFIG_CC"] = "clang++"
    changed["flat"]["LINUX_IMAGE_PATH"] = "/tmp/Image"
    changed["flat"]["SBI_FDT_PATH"] = "/tmp/other.dts"
    changed["flat"]["CONFIG_HASH"] = "nonsense"
    h3 = hash_of(changed, schema)
    check("hash: path/time/version/metadata changes are ignored", h1 == h3, f"{h1} != {h3}")

    rtl_changes = [
        ("isa.xlen", 64 if typed.get("isa", {}).get("xlen") != 64 else 32),
        ("platform.ram_size", int(typed.get("platform", {}).get("ram_size", 0)) + 4096),
        ("mmu.pte_format", "custom" if typed.get("mmu", {}).get("pte_format") != "custom" else "riscv64"),
    ]
    for path, value in rtl_changes:
        changed = copy.deepcopy(typed)
        target = changed
        parts = path.split(".")
        for part in parts[:-1]:
            target = target.setdefault(part, {})
        target[parts[-1]] = value
        check(
            f"hash: RTL parameter {path} changes the hash",
            hash_of(changed, schema) != h1,
        )

    changed = copy.deepcopy(typed)
    old = changed.setdefault("flat", {}).get("HAS_MMU", "n")
    changed["flat"]["HAS_MMU"] = "n" if old in ("y", "1", True) else "y"
    check("hash: flat boolean change is semantic", hash_of(changed, schema) != h1)

    changed = copy.deepcopy(typed)
    changed.setdefault("flat", {})["HAS_S"] = "1" if changed["flat"].get("HAS_S") in ("y", True, "1") else "y"
    changed["flat"]["HAS_S"] = "y"
    h4 = hash_of(changed, schema)
    changed["flat"]["HAS_S"] = "1"
    check("hash: y and 1 normalize to the same boolean", h4 == hash_of(changed, schema))
    return h1


def test_predicates() -> None:
    root = {
        "isa": {"xlen": 64, "f": True},
        "mmu": {"enabled": True, "pte_format": "riscv64"},
        "platform": {"ram_size": 67108864},
        "flat": {"HAS_MMU": True, "RISCV_C": False},
    }
    cases = [
        ("all", {"all": [
            {"eq": {"path": "isa.xlen", "value": 64}},
            {"eq": {"path": "mmu.enabled", "value": True}},
        ]}, True),
        ("any", {"any": [
            {"eq": {"path": "isa.xlen", "value": 32}},
            {"eq": {"path": "isa.xlen", "value": 64}},
        ]}, True),
        ("not", {"not": {"eq": {"path": "flat.RISCV_C", "value": True}}}, True),
        ("eq", {"eq": {"path": "mmu.pte_format", "value": "riscv64"}}, True),
        ("ne", {"ne": {"path": "isa.xlen", "value": 32}}, True),
        ("in", {"in": {"path": "isa.xlen", "values": [32, 64]}}, True),
        ("lt", {"lt": {"path": "platform.ram_size", "value": 67108865}}, True),
        ("lte", {"lte": {"path": "platform.ram_size", "value": 67108864}}, True),
        ("gt", {"gt": {"path": "platform.ram_size", "value": 67108863}}, True),
        ("gte", {"gte": {"path": "platform.ram_size", "value": 67108864}}, True),
        ("exists", {"exists": {"path": "mmu.pte_format"}}, True),
        ("exists-missing", {"exists": {"path": "mmu.missing"}}, False),
        ("missing-eq-false", {"eq": {"path": "flat.MISSING_FEATURE", "value": False}}, True),
        ("missing-eq-true", {"eq": {"path": "flat.MISSING_FEATURE", "value": True}}, False),
    ]
    for name, expr, expected in cases:
        try:
            actual = p.eval_expr(expr, root)
        except p.ProfileError as error:
            check(f"predicate: {name}", False, str(error))
            continue
        check(f"predicate: {name}", actual is expected, f"{actual} != {expected}")

    try:
        p.eval_expr({"bogus": {"path": "isa.xlen"}}, root)
        check("predicate: unknown operator is rejected", False)
    except p.ProfileError:
        check("predicate: unknown operator is rejected", True)


def test_allow_dimensions() -> None:
    profile = {
        "id": "allow-test",
        "version": 1,
        "status": "test",
        "description": "",
        "base": {
            "source": "test",
            "values": {"B": False, "E": "a", "X": 1, "L": "0,1"},
        },
        "allow": {
            "bool": ["B"],
            "enum": {"E": ["a", "b"]},
            "int_range": {"X": {"min": 0, "max": 10, "step": 1}},
            "list": {"L": {"allowed_items": ["0", "1", "2"], "max_items": 3}},
        },
        "predicate": [],
        "qualification": {"suites": ["test"], "hashes": []},
    }
    p.validate_profile(profile)
    ok = {"B": True, "E": "b", "X": 5, "L": "0,2"}
    check("allow: bool/enum/int_range/list accept deviations", not p.check_boundary(profile, ok))
    bad = {"B": 3, "E": "c", "X": 11, "L": "0,9"}
    violations = p.check_boundary(profile, bad)
    check(
        "allow: invalid bool/enum/range/list are violations",
        len(violations) == 4,
        str(violations),
    )
    missing = {"B": False, "E": "a", "X": 1}
    check(
        "allow: explicitly allowed list may disappear",
        not p.check_boundary(profile, missing),
    )
    bad_profile = copy.deepcopy(profile)
    bad_profile["allow"]["unknown"] = []
    try:
        p.validate_profile(bad_profile)
        check("allow: unknown dimension is rejected", False)
    except p.ProfileError:
        check("allow: unknown dimension is rejected", True)


def small_profile(profile_path: Path, schema: Dict[str, Any]) -> Dict[str, Any]:
    profile = p.load_profile(profile_path)
    return profile


def test_statuses(
    typed: Dict[str, Any],
    profile: Dict[str, Any],
    schema: Dict[str, Any],
    workdir: Path,
) -> None:
    base_result = p.classify(profile, typed, manifest=None, schema=schema)
    check(
        "status: base configuration is profile-compatible without a manifest",
        base_result["status"] == "profile-compatible",
        base_result["status"],
    )

    hash_value = base_result["effective_hash"]
    manifest = {
        "schema": 1,
        "profile": profile["id"],
        "profile_version": profile["version"],
        "status": "test",
        "hashes": [hash_value],
    }
    qualified = p.classify(profile, typed, manifest=manifest, schema=schema)
    check(
        "status: hash in manifest is qualified",
        qualified["status"] == "qualified",
        qualified["status"],
    )

    changed = copy.deepcopy(typed)
    changed.setdefault("isa", {})["xlen"] = 64 if typed.get("isa", {}).get("xlen") != 64 else 32
    experimental = p.classify(profile, changed, manifest=None, schema=schema)
    check(
        "status: predicate/base deviation is experimental",
        experimental["status"] == "experimental",
        experimental["status"],
    )

    changed_hash = p.effective_hash_of_document(changed, schema)
    conflict_manifest = dict(manifest)
    conflict_manifest["hashes"] = [changed_hash]
    conflict = p.classify(profile, changed, manifest=conflict_manifest, schema=schema)
    check(
        "status: manifest hash contradicting profile checks is invalid",
        conflict["status"] == "invalid",
        conflict["status"],
    )

    bad_profile = copy.deepcopy(profile)
    bad_profile["predicate"] = [{"id": "bad", "expr": {"unknown-op": {"path": "isa.xlen"}}}]
    invalid = p.classify(bad_profile, typed, manifest=None, schema=schema)
    check("status: malformed profile is invalid", invalid["status"] == "invalid", invalid["status"])

    missing = copy.deepcopy(profile)
    del missing["base"]
    invalid2 = p.classify(missing, typed, manifest=None, schema=schema)
    check("status: missing profile field is invalid", invalid2["status"] == "invalid", invalid2["status"])

    wrong_manifest = dict(manifest)
    wrong_manifest["profile"] = "other"
    invalid3 = p.classify(profile, typed, manifest=wrong_manifest, schema=schema)
    check(
        "status: manifest profile mismatch is invalid",
        invalid3["status"] == "invalid",
        invalid3["status"],
    )


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--typed", help="typed JSON to test; default: current repo .config output")
    parser.add_argument("--profile", help="profile used for status tests")
    args = parser.parse_args(argv)

    schema = p.load_schema()
    if not schema:
        print("FAIL schema: npc/params/schema/schema.json is missing", file=sys.stderr)
        return 1
    check("schema: generated_snapshot marker", schema.get("generated_snapshot") is True)

    workdir = Path(tempfile.mkdtemp(prefix="paramgen-selftest-", dir="/tmp/opencode"))
    if args.typed:
        typed = p.read_json(args.typed)
    else:
        typed = load_current_typed(p.REPO_ROOT, workdir)

    h1 = test_hash_invariance(typed, schema)
    check("hash: non-empty SHA256", len(h1) == 64 and all(c in "0123456789abcdef" for c in h1))

    test_predicates()
    test_allow_dimensions()

    profile_path = Path(args.profile) if args.profile else max(
        p.DEFAULT_PROFILE_DIR.glob("small-rv32@*.json"),
        key=lambda path: int(path.stem.rsplit("@", 1)[1]),
    )
    profile = small_profile(profile_path, schema)
    # The status tests need a config that passes the chosen profile.  Prefer
    # the defconfig named by profile.base.source when its typed output exists.
    status_typed = typed
    source = Path(profile["base"]["source"]).name
    name = source[: -len("_defconfig")] if source.endswith("_defconfig") else Path(source).stem
    candidate = Path("/tmp/opencode/paramgen-work/defconfigs") / name / "typed.json"
    if candidate.exists():
        status_typed = p.read_json(candidate)
    elif profile["id"] == "small-rv32" and typed.get("isa", {}).get("xlen") != 32:
        fallback = Path("/tmp/opencode/paramgen-work/defconfigs/rv32_c/typed.json")
        if fallback.exists():
            status_typed = p.read_json(fallback)
    test_statuses(status_typed, profile, schema, workdir)

    print()
    print(f"{len(PASS)} passed, {len(FAIL)} failed")
    return 0 if not FAIL else 1


if __name__ == "__main__":
    raise SystemExit(main())
