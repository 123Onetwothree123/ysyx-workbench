#!/usr/bin/env python3
"""Generate typed configs for all defconfigs in an isolated workdir and
classify them against the example support profiles.

This never runs from the repository's ``npc/`` directory: each defconfig gets
its own temporary c_conf workdir, so the repository's ``.config``,
``include/generated`` and ``include/config`` are left untouched.

    python3 npc/tools/ParamGen/classify_defconfigs.py
    python3 npc/tools/ParamGen/classify_defconfigs.py --skip-generate
    python3 npc/tools/ParamGen/classify_defconfigs.py --typed-only \
        --workdir /tmp/opencode/paramgen-typed-check

``--typed-only`` generates the typed JSON tree without loading profiles or
writing a classification report, which is useful before the profile policy
has been rebuilt.
"""

from __future__ import annotations

import argparse
import csv
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import paramlib as p

STATUS_RANK = {
    "qualified": 0,
    "profile-compatible": 1,
    "experimental": 2,
    "invalid": 3,
}


def defconfig_name(path: Path) -> str:
    name = path.name
    return name[: -len("_defconfig")] if name.endswith("_defconfig") else path.stem


def resolve_defconfig(npc_dir: Path, value: str) -> Path:
    candidate = Path(value)
    if candidate.is_file():
        return candidate.resolve()
    name = value if value.endswith("_defconfig") else value + "_defconfig"
    candidate = npc_dir / "configs" / name
    if candidate.is_file():
        return candidate.resolve()
    raise p.ParamError(f"defconfig not found: {value}")


def select_defconfigs(npc_dir: Path, values: Sequence[str]) -> List[Path]:
    if values:
        return sorted({resolve_defconfig(npc_dir, value) for value in values})
    defconfigs = sorted((npc_dir / "configs").glob("*defconfig"))
    if not defconfigs:
        raise p.ParamError(f"no defconfigs found under {npc_dir / 'configs'}")
    return defconfigs


def load_profiles(
    directory: Path,
) -> List[Tuple[Dict[str, Any], Optional[Dict[str, Any]], Path]]:
    result = []
    for profile_path in p.profile_paths(directory):
        profile = p.load_profile(profile_path)
        manifest_path = profile.get("qualification", {}).get("manifest")
        manifest = None
        if manifest_path:
            candidate = p.REPO_ROOT / manifest_path
            if candidate.exists():
                manifest = p.load_manifest(candidate)
        result.append((profile, manifest, profile_path))
    if not result:
        raise p.ParamError(f"no profiles found in {directory}")
    return result


def run_typed_only(
    npc_dir: Path,
    workdir: Path,
    defconfigs: Sequence[Path],
    schema: Dict[str, Any],
    *,
    skip_generate: bool,
    repeat: bool,
) -> int:
    """Generate (and optionally repeat-check) typed JSON only."""
    unstable: List[str] = []
    checked_repeats = 0
    for index, defconfig in enumerate(defconfigs, 1):
        name = defconfig_name(defconfig)
        typed = None
        repeated = None
        if skip_generate:
            typed_path = workdir / name / "typed.json"
            if typed_path.exists():
                typed = p.read_json(typed_path)
            repeated_path = workdir / f"{name}__repeat" / "typed.json"
            if repeated_path.exists():
                repeated = p.read_json(repeated_path)
        else:
            typed = p.generate_typed_config(npc_dir, workdir / name, defconfig)
            if repeat:
                repeated = p.generate_typed_config(
                    npc_dir, workdir / f"{name}__repeat", defconfig
                )
        if typed is None:
            raise p.ParamError(
                f"missing typed JSON for {name}: {workdir / name / 'typed.json'}; "
                "run without --skip-generate"
            )
        flat = typed.get("flat")
        if not isinstance(flat, dict) or not flat:
            raise p.ParamError(f"{name}: typed JSON has no non-empty 'flat' map")
        config_hash = typed.get("config_hash")
        if not isinstance(config_hash, str) or not config_hash:
            raise p.ParamError(f"{name}: typed JSON has no non-empty 'config_hash'")

        hash_value = p.canonical_document(typed, schema)["effective_hash"]
        repeated_hash = (
            p.canonical_document(repeated, schema)["effective_hash"]
            if repeated is not None
            else None
        )
        if repeated_hash is not None:
            checked_repeats += 1
        stable = repeated_hash is None or repeated_hash == hash_value
        if not stable:
            unstable.append(name)
        print(
            f"[{index}/{len(defconfigs)}] {name}: {hash_value[:16]} "
            f"npc_config_hash={config_hash[:16]} stable={stable}",
            file=sys.stderr,
        )

    if unstable:
        print(
            "[param-typed] ERROR repeat pass produced different effective hashes: "
            + ", ".join(unstable),
            file=sys.stderr,
        )
        return 1
    print(
        f"[param-typed] wrote {len(defconfigs)} typed.json under {workdir} "
        f"(repeat_checked={checked_repeats}/{len(defconfigs)})"
    )
    return 0


def choose_best(
    rows: List[Tuple[Dict[str, Any], Dict[str, Any]]],
) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    def key(item: Tuple[Dict[str, Any], Dict[str, Any]]) -> Tuple[int, int, int, int, str]:
        profile, result = item
        violations = len(result["checks"]["base_allow"]["violations"])
        predicates = len(result["checks"]["predicate"]["failures"])
        return (
            STATUS_RANK.get(result["status"], 99),
            violations + predicates,
            predicates,
            violations,
            profile["id"],
        )

    return min(rows, key=key)


def reason_text(result: Dict[str, Any], limit: int = 6) -> str:
    reasons = []
    for item in result["checks"]["base_allow"]["violations"][:limit]:
        reasons.append(f"base:{item['parameter']}")
    for item in result["checks"]["predicate"]["failures"][:limit]:
        reasons.append(f"predicate:{item['id']}")
    for error in result["errors"][:limit]:
        reasons.append(f"error:{error}")
    return ";".join(reasons)


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--npc", default=str(p.REPO_ROOT / "npc"))
    parser.add_argument(
        "--workdir",
        default="/tmp/opencode/paramgen-work/defconfigs",
        help="isolated workdir root (one child per defconfig)",
    )
    parser.add_argument(
        "--profiles", default=str(p.DEFAULT_PROFILE_DIR), help="profile directory"
    )
    parser.add_argument(
        "--out",
        default=str(p.REPO_ROOT / "npc/params/reports/defconfig-classification.csv"),
    )
    parser.add_argument(
        "--summary",
        default=str(
            p.REPO_ROOT / "npc/params/reports/defconfig-classification-summary.json"
        ),
    )
    parser.add_argument(
        "--skip-generate",
        action="store_true",
        help="reuse existing <workdir>/<name>/typed.json instead of running c_conf",
    )
    parser.add_argument(
        "--no-repeat",
        action="store_true",
        help="skip the second generation pass (hash stability not proven)",
    )
    parser.add_argument(
        "--defconfig",
        action="append",
        default=[],
        help="defconfig name or path; may be repeated (default: all defconfigs)",
    )
    parser.add_argument(
        "--typed-only",
        action="store_true",
        help=(
            "only generate <workdir>/<name>/typed.json; do not load profiles "
            "or write classification reports"
        ),
    )
    args = parser.parse_args(argv)

    npc_dir = Path(args.npc).resolve()
    workdir = Path(args.workdir)

    if args.typed_only:
        defconfigs = select_defconfigs(npc_dir, args.defconfig)
        return run_typed_only(
            npc_dir,
            workdir,
            defconfigs,
            p.load_schema(),
            skip_generate=args.skip_generate,
            repeat=not args.no_repeat,
        )

    profile_rows = load_profiles(Path(args.profiles))
    schema = p.load_schema()
    defconfigs = select_defconfigs(npc_dir, args.defconfig)

    rows = []
    details = []
    for index, defconfig in enumerate(defconfigs, 1):
        name = defconfig_name(defconfig)
        typed = None
        repeated = None
        if args.skip_generate:
            typed_path = workdir / name / "typed.json"
            if typed_path.exists():
                typed = p.read_json(typed_path)
            repeated_path = workdir / f"{name}__repeat" / "typed.json"
            if repeated_path.exists():
                repeated = p.read_json(repeated_path)
        else:
            typed = p.generate_typed_config(npc_dir, workdir / name, defconfig)
            if not args.no_repeat:
                repeated = p.generate_typed_config(
                    npc_dir, workdir / f"{name}__repeat", defconfig
                )
        if typed is None:
            raise p.ParamError(
                f"missing typed JSON for {name}; run without --skip-generate"
            )
        document = p.canonical_document(typed, schema)
        hash_value = document["effective_hash"]
        repeated_hash = (
            p.canonical_document(repeated, schema)["effective_hash"]
            if repeated is not None
            else None
        )
        stable = repeated_hash is None or repeated_hash == hash_value

        per_profile = []
        for profile, manifest, _path in profile_rows:
            result = p.classify(profile, typed, manifest=manifest, schema=schema)
            per_profile.append((profile, result))
        best_profile, best_result = choose_best(per_profile)

        profile_summary = ";".join(
            f"{profile['id']}:{result['status']}"
            for profile, result in per_profile
        )
        rows.append(
            {
                "defconfig": name,
                "effective_hash": hash_value,
                "hash_stable": "true" if stable else "false",
                "npc_config_hash": typed.get("config_hash", ""),
                "profile": best_profile["id"],
                "status": best_result["status"],
                "base_allow_violations": len(
                    best_result["checks"]["base_allow"]["violations"]
                ),
                "predicate_failures": len(
                    best_result["checks"]["predicate"]["failures"]
                ),
                "profiles": profile_summary,
                "reasons": reason_text(best_result),
            }
        )
        details.append(
            {
                "defconfig": name,
                "path": p.relative_to_repo(defconfig),
                "effective_hash": hash_value,
                "hash_stable": stable,
                "npc_config_hash": typed.get("config_hash"),
                "best_profile": best_profile["id"],
                "best_status": best_result["status"],
                "profiles": {
                    profile["id"]: {
                        "status": result["status"],
                        "base_allow_violations": result["checks"]["base_allow"]["violations"],
                        "predicate_failures": result["checks"]["predicate"]["failures"],
                        "errors": result["errors"],
                    }
                    for profile, result in per_profile
                },
            }
        )
        print(
            f"[{index}/{len(defconfigs)}] {name}: {hash_value[:16]} "
            f"status={best_result['status']} profile={best_profile['id']} "
            f"stable={stable}",
            file=sys.stderr,
        )

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "defconfig",
        "effective_hash",
        "hash_stable",
        "npc_config_hash",
        "profile",
        "status",
        "base_allow_violations",
        "predicate_failures",
        "profiles",
        "reasons",
    ]
    with out_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)

    status_counts = Counter(row["status"] for row in rows)
    profile_status_counts: Dict[str, Counter] = defaultdict(Counter)
    for detail in details:
        for profile_id, result in detail["profiles"].items():
            profile_status_counts[profile_id][result["status"]] += 1

    summary = {
        "generated_snapshot": True,
        "defconfig_count": len(rows),
        "profiles": [profile["id"] for profile, _manifest, _path in profile_rows],
        "hash_stable_count": sum(1 for row in rows if row["hash_stable"] == "true"),
        "hash_unstable": [
            row["defconfig"] for row in rows if row["hash_stable"] != "true"
        ],
        "status_counts": dict(sorted(status_counts.items())),
        "profile_status_counts": {
            profile: dict(sorted(counts.items()))
            for profile, counts in sorted(profile_status_counts.items())
        },
        "qualification_manifests": {
            profile["id"]: {
                "path": profile["qualification"].get("manifest"),
                "entries": len(p.manifest_hashes(manifest)),
                "state": (
                    "placeholder-empty" if manifest is not None and not p.manifest_hashes(manifest)
                    else "populated"
                    if manifest is not None
                    else "missing"
                ),
            }
            for profile, manifest, _path in profile_rows
        },
        "configs": details,
        "notes": [
            "Classification is support policy only; the existing Kconfig contract remains the legality authority.",
            "qualified means the exact effective_hash is present in the profile manifest after an L0/L1/L2 run; profile-compatible means policy boundary only.",
            "All typed configs were generated in isolated /tmp workdirs; repo .config/include/generated were not modified.",
        ],
    }
    p.write_json(args.summary, summary)

    print(f"[param-report] wrote {out_path}")
    print(f"[param-report] wrote {args.summary}")
    print(
        "[param-report] status counts: "
        + ", ".join(f"{key}={value}" for key, value in sorted(status_counts.items()))
    )
    if summary["hash_unstable"]:
        print(
            "[param-report] WARNING unstable hashes: "
            + ", ".join(summary["hash_unstable"]),
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
