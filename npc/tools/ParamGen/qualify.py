#!/usr/bin/env python3
"""Qualify defconfigs against support profiles with L0/L1/L2 evidence.

Stage definitions
-----------------
L0  Kconfig + contract
    * ``kconfig_contract --config <defconfig>`` (requested values)
    * ``c_conf --defconfig`` + ``c_conf --syncconfig`` in an isolated workdir
    * ``kconfig_contract --config .config --typed-json typed.json``
L1  ``npc_config_gen``
    * typed JSON, effective JSON, Scala defaults and the C++ module projection
L2  Chisel elaboration
    * ``mill -i runMain ysyx_26030103_Elaborate --target-dir <case>/elaborate``

L3/L4 (full Verilator build, simulation) are intentionally not run.

Every case uses its own workdir under ``/tmp/opencode/paramgen-qualify`` by
default, so the repository ``npc/.config``, ``npc/include/generated`` and
``npc/include/config`` are never used as scratch space.  L2 runs serially
because all mill invocations share ``npc/out``; L0/L1 can run with ``--jobs``.

A successful case writes an entry with its exact effective hash into
``npc/support/qualified/<profile>@<version>.json``.  A failed case keeps its
logs and does not add an entry.  Qualified means "this exact hash passed
L0/L1/L2 for this profile version", not "the profile name is qualified".
"""

from __future__ import annotations

import argparse
import concurrent.futures
import csv
import hashlib
import os
import shlex
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
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
DEFAULT_WORKDIR = Path("/tmp/opencode/paramgen-qualify")


# ---------------------------------------------------------------------------
# Small utilities
# ---------------------------------------------------------------------------

def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def repo_config_fingerprint(npc_dir: Path) -> Dict[str, str]:
    """Fingerprint files that must not change while qualifying."""
    result: Dict[str, str] = {}
    config = npc_dir / ".config"
    if config.is_file():
        result[".config"] = sha256_file(config)
    generated = npc_dir / "include/generated"
    if generated.is_dir():
        for path in sorted(generated.rglob("*")):
            if path.is_file():
                result[str(path.relative_to(npc_dir))] = sha256_file(path)
    include_config = npc_dir / "include/config"
    if include_config.is_dir():
        for path in sorted(include_config.rglob("*")):
            if path.is_file():
                result[str(path.relative_to(npc_dir))] = sha256_file(path)
    return result


def git_info(repo_root: Path) -> Dict[str, Any]:
    def git(*args: str) -> str:
        try:
            return subprocess.check_output(
                ["git", "-C", str(repo_root), *args],
                stderr=subprocess.DEVNULL,
                text=True,
            ).strip()
        except (OSError, subprocess.CalledProcessError):
            return ""

    dirty = git(
        "status",
        "--porcelain",
        "--",
        "npc/params",
        "npc/support",
        "npc/tools/ParamGen",
        "npc/Kconfig",
    )
    return {
        "git_head": git("rev-parse", "HEAD") or "unknown",
        "git_dirty": bool(dirty),
        "git_dirty_paths": dirty.splitlines(),
    }


def write_json_atomic(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    p.write_json(temporary, value)
    os.replace(temporary, path)


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


def profile_version(path: Path) -> int:
    stem = path.stem
    if "@" not in stem:
        return 0
    try:
        return int(stem.rsplit("@", 1)[1])
    except ValueError:
        return 0


def resolve_profile(value: str, profile_dir: Path) -> Path:
    candidate = Path(value)
    if candidate.is_file():
        return candidate.resolve()
    matches = sorted(
        profile_dir.glob(f"{value}@*.json"),
        key=profile_version,
    )
    if not matches:
        raise p.ParamError(f"profile not found: {value}")
    return matches[-1].resolve()


def load_profiles(args: argparse.Namespace) -> List[Tuple[Dict[str, Any], Path]]:
    profile_dir = Path(args.profile_dir)
    if args.profile:
        paths = [resolve_profile(item, profile_dir) for item in args.profile]
    else:
        paths = p.profile_paths(profile_dir)
    if not paths:
        raise p.ParamError(f"no profiles found in {profile_dir}")
    result = []
    for path in paths:
        profile = p.load_profile(path)
        result.append((profile, path))
    result.sort(key=lambda item: (item[0]["id"], item[0]["version"]))
    return result


def all_defconfigs(npc_dir: Path) -> List[Path]:
    paths = sorted((npc_dir / "configs").glob("*_defconfig"))
    if not paths:
        raise p.ParamError(f"no defconfigs found under {npc_dir / 'configs'}")
    return paths


# ---------------------------------------------------------------------------
# Command execution / stage logs
# ---------------------------------------------------------------------------

def command_text(command: Sequence[str]) -> str:
    return " ".join(shlex.quote(part) for part in command)


def run_stage(
    case_dir: Path,
    stage: str,
    command: Sequence[str],
    *,
    cwd: Path,
    timeout: float,
    env: Optional[Dict[str, str]] = None,
) -> Dict[str, Any]:
    log_path = case_dir / "logs" / f"{stage}.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    returncode: Optional[int] = None
    error = ""
    with log_path.open("w", encoding="utf-8") as log:
        log.write(f"$ {command_text(command)}\n")
        log.write(f"cwd: {cwd}\n\n")
        log.flush()
        try:
            completed = subprocess.run(
                list(command),
                cwd=str(cwd),
                env=env,
                stdout=log,
                stderr=subprocess.STDOUT,
                text=True,
                timeout=timeout,
            )
            returncode = completed.returncode
            if returncode != 0:
                error = f"command exited with status {returncode}"
        except subprocess.TimeoutExpired:
            returncode = 124
            error = f"timeout after {timeout:g}s"
            log.write(f"\n[TIMEOUT] {error}\n")
        except OSError as exc:
            returncode = 127
            error = f"cannot execute: {exc}"
            log.write(f"\n[ERROR] {error}\n")
    duration = round(time.monotonic() - started, 3)
    tail: List[str] = []
    if returncode != 0:
        try:
            lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
            tail = lines[-20:]
        except OSError:
            pass
    return {
        "stage": stage,
        "ok": returncode == 0,
        "returncode": returncode,
        "command": list(command),
        "cwd": str(cwd),
        "log": str(log_path),
        "duration_s": duration,
        "error": error,
        "log_tail": tail,
    }


def record_stage(result: Dict[str, Any], stage: Dict[str, Any]) -> bool:
    result["stages"][stage["stage"]] = stage
    if not stage["ok"]:
        result["failed"] = True
        result["failed_stage"] = stage["stage"]
        result["reason"] = stage["error"] or stage["stage"]
        return False
    return True


# ---------------------------------------------------------------------------
# L0 / L1
# ---------------------------------------------------------------------------

def run_case_l0_l1(
    npc_dir: Path,
    defconfig: Path,
    profiles: List[Tuple[Dict[str, Any], Path]],
    schema: Dict[str, Any],
    workdir: Path,
    args: argparse.Namespace,
) -> Dict[str, Any]:
    name = defconfig_name(defconfig)
    case_dir = workdir / name
    result: Dict[str, Any] = {
        "defconfig": name,
        "path": p.relative_to_repo(defconfig),
        "case_dir": str(case_dir),
        "log_dir": str(case_dir / "logs"),
        "stages": {},
        "classification": {},
        "compatible_profiles": [],
        "failed": False,
        "failed_stage": None,
        "reason": "",
    }

    contract = npc_dir / "tools/build/kconfig_contract"
    conf = npc_dir / "tools/build/c_conf"
    config_gen = npc_dir / "tools/build/npc_config_gen"
    for binary in (contract, conf, config_gen):
        if not binary.is_file():
            raise p.ParamError(f"required tool not found: {binary}")

    if args.dry_run:
        result["dry_run_commands"] = {
            "l0-defconfig-contract": command_text(
                [str(contract), "--config", str(defconfig)]
            ),
            "l0-kconfig-defconfig": command_text(
                [str(conf), "-s", f"--defconfig={defconfig}", "Kconfig"]
            ),
            "l1-npc-config-gen": command_text(
                [
                    str(config_gen),
                    "--config-json",
                    "<case>/include/generated/npc_config.json",
                    "--typed-json",
                    "<case>/typed.json",
                ]
            ),
        }
        return result

    p.prepare_kconfig_workdir(npc_dir, case_dir)
    typed_path = case_dir / "typed.json"
    env = os.environ.copy()

    stages = [
        (
            "l0-defconfig-contract",
            [str(contract), "--config", str(defconfig)],
            case_dir,
        ),
        (
            "l0-kconfig-defconfig",
            [str(conf), "-s", f"--defconfig={defconfig}", "Kconfig"],
            case_dir,
        ),
        (
            "l0-kconfig-syncconfig",
            [str(conf), "-s", "--syncconfig", "Kconfig"],
            case_dir,
        ),
        (
            "l1-npc-config-gen",
            [
                str(config_gen),
                "--config-json",
                str(case_dir / "include/generated/npc_config.json"),
                "--typed-json",
                str(typed_path),
                "--effective-json",
                str(case_dir / "effective.json"),
                "--scala-defaults",
                str(case_dir / "scala-defaults.scala"),
                "--output",
                str(case_dir / "npc.config.ixx"),
            ],
            case_dir,
        ),
        (
            "l0-generated-contract",
            [
                str(contract),
                "--config",
                str(case_dir / ".config"),
                "--typed-json",
                str(typed_path),
            ],
            case_dir,
        ),
    ]

    for stage_name, command, cwd in stages:
        stage = run_stage(
            case_dir,
            stage_name,
            command,
            cwd=cwd,
            timeout=args.timeout,
            env=env,
        )
        if not record_stage(result, stage):
            return result

    typed = p.read_json(typed_path)
    canonical = p.canonical_document(typed, schema)
    result["effective_hash"] = canonical["effective_hash"]
    result["npc_config_hash"] = typed.get("config_hash")
    write_json_atomic(case_dir / "effective.canonical.json", canonical)

    for profile, profile_path in profiles:
        classification = p.classify(profile, typed, manifest=None, schema=schema)
        result["classification"][profile["id"]] = {
            "profile_path": p.relative_to_repo(profile_path),
            "status": classification["status"],
            "base_allow_violations": classification["checks"]["base_allow"]["violations"],
            "predicate_failures": classification["checks"]["predicate"]["failures"],
            "errors": classification["errors"],
        }
    result["compatible_profiles"] = [
        profile_id
        for profile_id, item in result["classification"].items()
        if item["status"] in ("profile-compatible", "qualified")
    ]
    result["status"] = (
        "profile-compatible" if result["compatible_profiles"] else "experimental"
    )
    return result


# ---------------------------------------------------------------------------
# L2
# ---------------------------------------------------------------------------

def run_case_l2(
    npc_dir: Path,
    result: Dict[str, Any],
    args: argparse.Namespace,
) -> Dict[str, Any]:
    case_dir = Path(result["case_dir"])
    target = case_dir / "elaborate"
    command = [
        args.mill,
        "-i",
        "runMain",
        "ysyx_26030103_Elaborate",
        "--target-dir",
        str(target),
    ]
    env = os.environ.copy()
    env["NPC_CONFIG_JSON"] = str(case_dir / "typed.json")
    stage = run_stage(
        case_dir,
        "l2-elaboration",
        command,
        cwd=npc_dir,
        timeout=args.l2_timeout,
        env=env,
    )
    if target.is_dir():
        stage["artifacts"] = len(list(target.glob("*.sv")))
        stage["artifact_dir"] = str(target)
    else:
        stage["artifacts"] = 0
    result["l2"] = stage
    if not stage["ok"]:
        result["failed"] = True
        result["failed_stage"] = "l2-elaboration"
        result["reason"] = stage["error"] or "L2 elaboration failed"
    else:
        result["l2_pass"] = True
    return result


# ---------------------------------------------------------------------------
# Manifest update
# ---------------------------------------------------------------------------

def manifest_entry(
    result: Dict[str, Any],
    profile: Dict[str, Any],
    run_info: Dict[str, Any],
) -> Dict[str, Any]:
    return {
        "effective_hash": result["effective_hash"],
        "npc_config_hash": result.get("npc_config_hash"),
        "defconfig": result["defconfig"],
        "source": result["path"],
        "profile": profile["id"],
        "profile_version": profile["version"],
        "git_head": run_info["git_head"],
        "qualified_at": run_info["generated_at"],
        "evidence": {
            "l0_kconfig_contract": "pass",
            "l1_npc_config_gen": "pass",
            "l2_chisel_elaboration": "pass",
        },
    }


def update_manifest(
    profile: Dict[str, Any],
    profile_path: Path,
    passed_entries: List[Dict[str, Any]],
    selected_names: set[str],
    full_scope: bool,
    run_info: Dict[str, Any],
) -> Dict[str, Any]:
    manifest_path = p.REPO_ROOT / profile["qualification"]["manifest"]
    existing: Optional[Dict[str, Any]] = None
    if manifest_path.is_file():
        existing = p.load_manifest(manifest_path)

    entries: List[Any] = []
    seen: set[str] = set()
    for entry in passed_entries:
        hash_value = entry["effective_hash"]
        if hash_value in seen:
            continue
        seen.add(hash_value)
        entries.append(entry)

    if existing and not full_scope:
        for entry in existing.get("hashes", []):
            if isinstance(entry, dict):
                if entry.get("defconfig") in selected_names:
                    continue
                hash_value = entry.get("effective_hash")
            else:
                hash_value = entry
            if not hash_value or hash_value in seen:
                continue
            seen.add(hash_value)
            entries.append(entry)

    def sort_key(entry: Any) -> Tuple[str, str]:
        if isinstance(entry, dict):
            return (str(entry.get("defconfig", "")), str(entry.get("effective_hash", "")))
        return ("", str(entry))

    entries.sort(key=sort_key)
    manifest = {
        "schema": 1,
        "profile": profile["id"],
        "profile_version": profile["version"],
        "status": "qualified" if entries else "empty",
        "generated_by": "npc/tools/ParamGen/qualify.py",
        "generated_at": run_info["generated_at"],
        "run": {
            "git_head": run_info["git_head"],
            "git_dirty": run_info["git_dirty"],
            "selection": "all-defconfigs" if full_scope else "subset",
            "selected_defconfigs": sorted(selected_names),
            "stages": [
                "L0 Kconfig + contract",
                "L1 npc_config_gen",
                "L2 Chisel elaboration",
            ],
            "note": "L3 full Verilator build and L4 simulation are not part of this manifest.",
        },
        "notes": [
            "Each entry is an exact effective_hash, not a profile name.",
            "A hash is qualified only for this profile version and the recorded git head.",
            "Rerun qualify.py after changing Kconfig, defaults, profile policy or RTL.",
            "A failed case is not written; its logs remain in the isolated workdir.",
        ],
        "hashes": entries,
    }
    write_json_atomic(manifest_path, manifest)
    return {
        "path": p.relative_to_repo(manifest_path),
        "entries": len(entries),
        "status": manifest["status"],
    }


# ---------------------------------------------------------------------------
# Reports
# ---------------------------------------------------------------------------

def best_profile_for_report(
    result: Dict[str, Any],
    profiles: List[Tuple[Dict[str, Any], Path]],
) -> Tuple[str, str]:
    if result.get("compatible_profiles"):
        return result["compatible_profiles"][0], "profile-compatible"
    # For experimental cases, show the nearest profile: fewest total
    # predicate+base/allow failures, then fewest predicate failures.
    best: Optional[Tuple[int, int, int, str]] = None
    for profile, _path in profiles:
        item = result["classification"].get(profile["id"])
        if not item:
            continue
        violations = len(item["base_allow_violations"])
        predicates = len(item["predicate_failures"])
        key = (violations + predicates, predicates, violations, profile["id"])
        if best is None or key < best:
            best = key
    return (best[3], "experimental") if best else ("", "experimental")


def final_status(result: Dict[str, Any], skip_l2: bool) -> str:
    if result.get("failed"):
        return "failed"
    if not result.get("compatible_profiles"):
        return "experimental"
    if skip_l2:
        return "profile-compatible"
    if result.get("l2_pass"):
        return "qualified"
    return "profile-compatible"


def write_run_report(
    report_path: Path,
    csv_path: Path,
    results: List[Dict[str, Any]],
    profiles: List[Tuple[Dict[str, Any], Path]],
    run_info: Dict[str, Any],
    args: argparse.Namespace,
    manifest_updates: Dict[str, Any],
) -> Dict[str, Any]:
    cases = []
    summary = {
        "cases": len(results),
        "l0_pass": 0,
        "l1_pass": 0,
        "l2_pass": 0,
        "qualified": 0,
        "profile_compatible": 0,
        "experimental": 0,
        "failed": 0,
        "invalid": 0,
    }
    for result in results:
        status = final_status(result, args.skip_l2)
        result["final_status"] = status
        if result["stages"].get("l0-generated-contract", {}).get("ok"):
            summary["l0_pass"] += 1
        if result["stages"].get("l1-npc-config-gen", {}).get("ok"):
            summary["l1_pass"] += 1
        if result.get("l2_pass"):
            summary["l2_pass"] += 1
        if status == "qualified":
            summary["qualified"] += 1
        elif status == "profile-compatible":
            summary["profile_compatible"] += 1
        elif status == "experimental":
            summary["experimental"] += 1
        elif status == "failed":
            summary["failed"] += 1
        if any(
            item["status"] == "invalid"
            for item in result["classification"].values()
        ):
            summary["invalid"] += 1

        best_profile, _best_status = best_profile_for_report(result, profiles)
        incompatible = {
            profile_id: {
                "violations": len(item["base_allow_violations"]),
                "predicates": [clause["id"] for clause in item["predicate_failures"]],
                "errors": item["errors"],
            }
            for profile_id, item in result["classification"].items()
            if item["status"] not in ("profile-compatible", "qualified")
        }
        cases.append(
            {
                "defconfig": result["defconfig"],
                "path": result["path"],
                "effective_hash": result.get("effective_hash"),
                "npc_config_hash": result.get("npc_config_hash"),
                "best_profile": best_profile,
                "status": status,
                "compatible_profiles": result.get("compatible_profiles", []),
                "failed_stage": result.get("failed_stage"),
                "reason": result.get("reason", ""),
                "stages": {
                    key: {
                        "ok": value["ok"],
                        "returncode": value["returncode"],
                        "duration_s": value["duration_s"],
                        "log": value["log"],
                    }
                    for key, value in result["stages"].items()
                },
                "l2": (
                    {
                        "ok": result["l2"]["ok"],
                        "duration_s": result["l2"]["duration_s"],
                        "artifacts": result["l2"].get("artifacts", 0),
                        "log": result["l2"]["log"],
                    }
                    if result.get("l2")
                    else None
                ),
                "classification": {
                    profile_id: {
                        "status": item["status"],
                        "violations": len(item["base_allow_violations"]),
                        "predicates": [
                            clause["id"] for clause in item["predicate_failures"]
                        ],
                    }
                    for profile_id, item in result["classification"].items()
                },
                "incompatible": incompatible,
            }
        )

    report = {
        "schema": 1,
        "generated_at": run_info["generated_at"],
        "git_head": run_info["git_head"],
        "git_dirty": run_info["git_dirty"],
        "git_dirty_paths": run_info["git_dirty_paths"],
        "workdir": str(args.workdir),
        "profiles": [f"{profile['id']}@{profile['version']}" for profile, _ in profiles],
        "stages": {
            "L0": "Kconfig + kconfig_contract (defconfig and generated config)",
            "L1": "npc_config_gen (typed/effective/scala/module)",
            "L2": "Chisel elaboration via mill",
            "L3/L4": "not run (no full Verilator build, no simulation)",
        },
        "repo_config_untouched": run_info["repo_config_untouched"],
        "repo_config_before": run_info["repo_config_before"],
        "repo_config_after": run_info["repo_config_after"],
        "summary": summary,
        "manifest_updates": manifest_updates,
        "cases": cases,
    }
    write_json_atomic(report_path, report)

    fieldnames = [
        "defconfig",
        "effective_hash",
        "npc_config_hash",
        "profile",
        "status",
        "failed_stage",
        "l0",
        "l1",
        "l2",
        "reason",
        "log_dir",
    ]
    with csv_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for case in cases:
            writer.writerow(
                {
                    "defconfig": case["defconfig"],
                    "effective_hash": case["effective_hash"] or "",
                    "npc_config_hash": case["npc_config_hash"] or "",
                    "profile": ";".join(case["compatible_profiles"])
                    or case["best_profile"],
                    "status": case["status"],
                    "failed_stage": case["failed_stage"] or "",
                    "l0": (
                        "pass"
                        if case["stages"].get("l0-generated-contract", {}).get("ok")
                        else "fail"
                    ),
                    "l1": (
                        "pass"
                        if case["stages"].get("l1-npc-config-gen", {}).get("ok")
                        else "fail"
                    ),
                    "l2": (
                        "pass"
                        if case.get("l2") and case["l2"]["ok"]
                        else "skip"
                        if not case.get("l2")
                        else "fail"
                    ),
                    "reason": case["reason"],
                    "log_dir": str(
                        Path(args.workdir) / case["defconfig"] / "logs"
                    ),
                }
            )
    return report


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def write_support_policy_report(
    path: Path,
    results: List[Dict[str, Any]],
    profiles: List[Tuple[Dict[str, Any], Path]],
    run_info: Dict[str, Any],
) -> None:
    """Write a curated 20-defconfig policy table next to the raw report."""
    profile_by_id = {profile["id"]: profile for profile, _path in profiles}
    rows = []
    for result in results:
        status = result.get("final_status", "failed")
        compatible = result.get("compatible_profiles", [])
        if compatible:
            profile_id = compatible[0]
        else:
            profile_id = best_profile_for_report(result, profiles)[0]
            # The three custom-MMU configs are deliberately outside the
            # standard rv64-mmu family; report that family as the nearest one.
            item = result["classification"].get("rv64-mmu")
            if item and item["status"] == "experimental":
                failed = {clause["id"] for clause in item["predicate_failures"]}
                if failed & {
                    "mmu-standard-geometry",
                    "mmu-page-4k",
                    "mmu-pte-format",
                }:
                    profile_id = "rv64-mmu"
        profile = profile_by_id.get(profile_id, {})
        profile_label = (
            f"{profile_id}@{profile['version']}" if profile else profile_id
        )
        item = result["classification"].get(profile_id, {})
        predicate_ids = [
            clause["id"] for clause in item.get("predicate_failures", [])
        ]
        violation_names = [
            violation["parameter"]
            for violation in item.get("base_allow_violations", [])
        ]

        if status == "qualified":
            reason = (
                f"within {profile_label}; L0/L1/L2 pass and the exact hash is "
                "recorded in the qualified manifest"
            )
        elif status == "profile-compatible":
            reason = (
                f"within {profile_label}; L0/L1 pass but L2/manifest "
                "qualification is missing"
            )
        elif status == "failed":
            reason = (
                f"{result.get('failed_stage') or 'pipeline'}: "
                f"{result.get('reason') or 'failed'}"
            )
        elif profile_id == "rv64-mmu" and (
            "mmu-standard-geometry" in predicate_ids
            or "mmu-page-4k" in predicate_ids
            or "mmu-pte-format" in predicate_ids
        ):
            reason = (
                "custom MMU geometry/PTE/page size; deliberately experimental, "
                "not forced into the standard rv64-mmu family"
            )
        else:
            reason = "outside profile boundary"
            if predicate_ids:
                reason += "; predicate failures: " + ",".join(predicate_ids)
            if violation_names:
                reason += "; boundary: " + ",".join(violation_names[:8])

        rows.append(
            {
                "defconfig": result["defconfig"],
                "effective_hash": result.get("effective_hash"),
                "npc_config_hash": result.get("npc_config_hash"),
                "profile": profile_label,
                "profile_id": profile_id,
                "compatible": status in ("qualified", "profile-compatible"),
                "status": status,
                "reason": reason,
                "predicate_failures": predicate_ids,
                "boundary_violations": violation_names,
            }
        )

    rows.sort(key=lambda row: row["defconfig"])
    families = []
    for profile, _path in profiles:
        family_rows = [row for row in rows if row["profile_id"] == profile["id"]]
        families.append(
            {
                "profile": f"{profile['id']}@{profile['version']}",
                "base_source": profile["base"]["source"],
                "compatible": [
                    row["defconfig"] for row in family_rows if row["compatible"]
                ],
                "experimental_near_miss": [
                    row["defconfig"]
                    for row in family_rows
                    if not row["compatible"]
                ],
            }
        )

    counts: Dict[str, int] = {}
    for row in rows:
        counts[row["status"]] = counts.get(row["status"], 0) + 1
    for status in (
        "qualified",
        "profile-compatible",
        "experimental",
        "invalid",
        "failed",
    ):
        counts.setdefault(status, 0)
    report = {
        "schema": 1,
        "generated_at": run_info["generated_at"],
        "generated_by": "npc/tools/ParamGen/qualify.py",
        "git_head": run_info["git_head"],
        "semantics": {
            "qualified": "exact effective_hash passed L0/L1/L2 and is in the profile manifest",
            "profile-compatible": "inside profile policy, but not certified by this run",
            "experimental": "legal configuration outside every profile boundary; not a rejection",
            "failed": "L0/L1/L2 failed; logs kept, manifest not updated",
        },
        "counts": counts,
        "families": families,
        "defconfigs": rows,
    }
    write_json_atomic(path, report)


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "cases",
        nargs="*",
        help="defconfig names or paths (default: all npc/configs/*_defconfig)",
    )
    parser.add_argument(
        "--defconfig",
        action="append",
        default=[],
        help="additional defconfig name/path; may be repeated",
    )
    parser.add_argument(
        "--all",
        action="store_true",
        help="qualify every npc/configs/*_defconfig",
    )
    parser.add_argument(
        "--profile",
        action="append",
        default=[],
        help="profile id or path; may be repeated (default: all profiles)",
    )
    parser.add_argument(
        "--profile-dir",
        default=str(p.DEFAULT_PROFILE_DIR),
        help="profile directory",
    )
    parser.add_argument("--npc", default=str(p.REPO_ROOT / "npc"))
    parser.add_argument("--workdir", default=str(DEFAULT_WORKDIR))
    parser.add_argument(
        "--jobs",
        type=int,
        default=min(4, os.cpu_count() or 1),
        help="parallel L0/L1 workers (L2 always runs serially because mill shares npc/out)",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=600.0,
        help="timeout per L0/L1 command in seconds",
    )
    parser.add_argument(
        "--l2-timeout",
        type=float,
        default=1800.0,
        help="timeout for one L2 elaboration in seconds",
    )
    parser.add_argument(
        "--skip-l2",
        action="store_true",
        help="run L0/L1 only; never writes qualified manifests",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print the plan and do not execute or write anything",
    )
    parser.add_argument(
        "--report",
        default=str(p.REPO_ROOT / "npc/params/reports/qualification-run.json"),
    )
    parser.add_argument(
        "--csv",
        default=str(p.REPO_ROOT / "npc/params/reports/qualification-run.csv"),
    )
    parser.add_argument(
        "--policy-report",
        default=str(
            p.REPO_ROOT / "npc/params/reports/defconfig-support-policy.json"
        ),
        help="curated 20-defconfig policy table (written on a full-scope run)",
    )
    parser.add_argument("--mill", default="", help="mill executable (default: PATH)")
    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    npc_dir = Path(args.npc).resolve()
    args.workdir = Path(args.workdir)
    args.profile_dir = Path(args.profile_dir)
    schema = p.load_schema()
    if not schema:
        print(
            "[qualify] error: npc/params/schema/schema.json is missing; "
            "run gen_schema.py first",
            file=sys.stderr,
        )
        return 2

    if not args.mill:
        args.mill = shutil.which("mill") or "mill"

    try:
        profiles = load_profiles(args)
        if args.all:
            selected = all_defconfigs(npc_dir)
        else:
            values = list(args.cases) + list(args.defconfig)
            selected = (
                all_defconfigs(npc_dir)
                if not values
                else [resolve_defconfig(npc_dir, value) for value in values]
            )
    except p.ParamError as error:
        print(f"[qualify] error: {error}", file=sys.stderr)
        return 2

    # Deduplicate paths while preserving a deterministic order.
    selected = sorted({path.resolve() for path in selected})
    selected_names = {defconfig_name(path) for path in selected}
    all_names = {defconfig_name(path) for path in all_defconfigs(npc_dir)}
    full_scope = selected_names == all_names

    profile_text = ", ".join(
        f"{profile['id']}@{profile['version']}" for profile, _path in profiles
    )
    print(f"[qualify] profiles={profile_text}")
    print(
        f"[qualify] cases={len(selected)} full_scope={full_scope} "
        f"jobs={args.jobs} workdir={args.workdir}"
    )
    if args.dry_run:
        print("[qualify] dry-run: no commands will run and no files will be written")
        for path in selected:
            name = defconfig_name(path)
            print(f"  - {name}: L0/L1 in {args.workdir / name}")
            for profile, _profile_path in profiles:
                print(f"      candidate profile {profile['id']}@{profile['version']}")
        print(
            "[qualify] L2 would run serially only for cases classified "
            "profile-compatible (mill shares npc/out)"
        )
        return 0

    if args.skip_l2:
        print("[qualify] --skip-l2: manifests will not be written")
    elif not shutil.which(args.mill) and not Path(args.mill).is_file():
        print(
            f"[qualify] error: mill not found ({args.mill}); "
            "use --mill or install mill",
            file=sys.stderr,
        )
        return 2

    before = repo_config_fingerprint(npc_dir)
    run_info = {
        "generated_at": utc_now(),
        **git_info(p.REPO_ROOT),
    }

    args.workdir.mkdir(parents=True, exist_ok=True)
    results: List[Dict[str, Any]] = []
    with concurrent.futures.ThreadPoolExecutor(
        max_workers=max(1, args.jobs)
    ) as executor:
        future_map = {
            executor.submit(
                run_case_l0_l1,
                npc_dir,
                path,
                profiles,
                schema,
                args.workdir,
                args,
            ): path
            for path in selected
        }
        for future in concurrent.futures.as_completed(future_map):
            path = future_map[future]
            try:
                result = future.result()
            except Exception as error:  # keep other cases running
                result = {
                    "defconfig": defconfig_name(path),
                    "path": p.relative_to_repo(path),
                    "case_dir": str(args.workdir / defconfig_name(path)),
                    "log_dir": str(args.workdir / defconfig_name(path) / "logs"),
                    "stages": {},
                    "classification": {},
                    "compatible_profiles": [],
                    "failed": True,
                    "failed_stage": "internal",
                    "reason": f"{type(error).__name__}: {error}",
                }
            results.append(result)
            print(
                f"[qualify] {result['defconfig']}: "
                f"hash={str(result.get('effective_hash'))[:16]} "
                f"status={result.get('status', 'failed')} "
                f"profiles={','.join(result.get('compatible_profiles', [])) or '-'}"
                + (
                    f" failed={result.get('failed_stage')}"
                    if result.get("failed")
                    else ""
                )
            )
    results.sort(key=lambda item: item["defconfig"])

    if not args.skip_l2:
        candidates = [
            result
            for result in results
            if not result.get("failed") and result.get("compatible_profiles")
        ]
        print(f"[qualify] L2 candidates: {len(candidates)} (serial)")
        for index, result in enumerate(candidates, 1):
            print(
                f"[qualify] L2 [{index}/{len(candidates)}] {result['defconfig']} ...",
                end="",
                flush=True,
            )
            run_case_l2(npc_dir, result, args)
            if result.get("l2_pass"):
                print(f" pass ({result['l2']['duration_s']:g}s)")
            else:
                print(
                    f" FAIL ({result['l2']['duration_s']:g}s) "
                    f"log={result['l2']['log']}"
                )

    after = repo_config_fingerprint(npc_dir)
    run_info["repo_config_before"] = before
    run_info["repo_config_after"] = after
    run_info["repo_config_untouched"] = before == after
    if before != after:
        print(
            "[qualify] WARNING: repository npc/.config or include/ trees changed",
            file=sys.stderr,
        )

    manifest_updates: Dict[str, Any] = {}
    if not args.skip_l2:
        passed_by_profile: Dict[str, List[Dict[str, Any]]] = {}
        for result in results:
            if not result.get("l2_pass") or result.get("failed"):
                continue
            for profile_id in result.get("compatible_profiles", []):
                profile = next(
                    item for item, _path in profiles if item["id"] == profile_id
                )
                passed_by_profile.setdefault(profile_id, []).append(
                    manifest_entry(result, profile, run_info)
                )
        for profile, _profile_path in profiles:
            entries = passed_by_profile.get(profile["id"], [])
            manifest_updates[profile["id"]] = update_manifest(
                profile,
                _profile_path,
                entries,
                selected_names,
                full_scope,
                run_info,
            )
            print(
                f"[qualify] manifest {manifest_updates[profile['id']]['path']}: "
                f"{manifest_updates[profile['id']]['entries']} hash(es)"
            )

    report = write_run_report(
        Path(args.report),
        Path(args.csv),
        results,
        profiles,
        run_info,
        args,
        manifest_updates,
    )
    if full_scope and not args.skip_l2:
        all_profile_ids = {
            p.load_profile(path)["id"] for path in p.profile_paths(args.profile_dir)
        }
        selected_profile_ids = {profile["id"] for profile, _path in profiles}
        if selected_profile_ids == all_profile_ids:
            write_support_policy_report(
                Path(args.policy_report),
                results,
                profiles,
                run_info,
            )
            print(f"[qualify] policy report: {args.policy_report}")
    summary = report["summary"]
    print(
        "[qualify] summary: "
        + ", ".join(f"{key}={value}" for key, value in summary.items() if value)
    )
    print(f"[qualify] report: {args.report}")
    print(f"[qualify] csv: {args.csv}")
    return 1 if summary["failed"] or summary["invalid"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
